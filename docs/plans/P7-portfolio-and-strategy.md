# P7 读模型与策略

**目标：** 提供持仓、盈亏与净值曲线；打开依赖持仓的风控规则；实现三种内置策略及其分片、快照与自动下单。

**前置条件：** P6 完成。

**阶段结束时能看到：**
- 资产页显示净值曲线、资产分布环形图、各持仓的成本与浮动盈亏；
- 策略页可创建均线交叉、网格、定投策略，启动后自动下单，信号记录可查；
- 运行两个 strategy 实例，杀掉其中一个，策略由另一个接管且不重复下单。

**需要的 Compose profile：** `core`、`mq`、`obs`、`mock`

---

## P7-T01 持仓与盈亏读模型

**目标：** 在 ledger 中按 CQRS 方式维护持仓、盈亏和净值。

**实现要点：**
- `projection/` 目录；Flyway：`position`（用户、资产、数量、平均成本）、`pnl_daily`（用户、日期、已实现盈亏）、`equity_snapshot`（用户、时间、总资产 USD）。
- 消费 `ledger.events`（独立消费组，幂等），只依赖事件内容，不调用账本写模型的 service（ArchUnit 规则）。
- 成本：买入按移动加权平均更新；卖出按平均成本计算已实现盈亏；手续费计入成本 / 盈亏。
- 估值使用 Redis `md:last:{symbol}`；每小时记录净值快照（ShedLock）。
- Redis 缓存 `pf:summary:{userId}`（10 秒）：总资产、各币占比、当日已实现盈亏。
- 接口：`GET /api/portfolio`、`GET /api/portfolio/equity?range=1d|7d|30d`。

**验证：** 单元测试覆盖多次买卖后的平均成本与已实现盈亏；连 mock 下几笔单后接口数据与手工计算一致。

**Checklist：**
- [ ] 成本与盈亏计算有测试
- [ ] 读模型不调用写模型（ArchUnit）
- [ ] 重放事件可重建读模型（提供重建命令）

---

## P7-T02 打开风控 R06 / R07

**实现要点：** risk 读取 `pf:summary:{userId}`：R06 当日已实现亏损超过初始资金 50% 拒绝；R07 买入后单币占总资产超过 80% 拒绝（阈值来自 Nacos）。缓存缺失时调用 ledger 查询。

**验证：** 构造亏损与持仓集中的数据，下单被对应规则拒绝。

**Checklist：**
- [ ] 两条规则各有通过与拒绝测试
- [ ] 配置开关打开

---

## P7-T03 前端：资产页完整版

**实现要点：** 净值曲线（Lightweight Charts 面积图，1 天 / 7 天 / 30 天切换）；资产分布环形图（ECharts）；持仓列表（数量、平均成本、现价、浮动盈亏金额与比例，颜色按涨跌偏好）；实时刷新。

**Checklist：**
- [ ] 数据与接口一致
- [ ] 亮暗主题、中英文正确

---

## P7-T04 strategy 服务骨架与策略管理

**目标：** 策略定义与实例的增删改查。

**实现要点：**
- `services/strategy`，端口 8086，标准目录结构，服务特有目录 `strategy/`（策略实现）、`scheduler/`（分片与调度）。
- Flyway：`strategy_def`（预置三种类型及其参数 JSON Schema）、`strategy_instance`（用户、交易对、类型、参数、状态 RUNNING / PAUSED、下次执行时间等）、`signal`、`outbox`、`audit_outbox`、`consumed_event`。
- 接口：`GET /api/strategies/types`、`GET/POST/PUT/DELETE /api/strategies`、`POST /api/strategies/{id}/start|pause`、`GET /api/strategies/{id}/signals`。参数按 JSON Schema 校验；每用户最多 5 个运行中实例（Nacos `strategy.yaml`）。
- 审计：`STRATEGY_CREATE`、`STRATEGY_UPDATE`、`STRATEGY_START`、`STRATEGY_PAUSE`、`STRATEGY_DELETE`。
- gateway 路由 `/api/strategies/**` → strategy。

**验证：** `http/strategy.http` 完成增删改查；第 6 个启动请求被拒绝；审计中有对应记录。

**Checklist：**
- [ ] 参数校验覆盖三种类型
- [ ] 5 个上限生效
- [ ] 审计完整

---

## P7-T05 分片调度与状态快照

**目标：** 策略实例分布在多个 strategy 服务实例上运行，可故障转移。

**实现要点：**
- 每个服务实例在 Redis 注册租约 `strategy:instances:{id}`（TTL 15 秒，每 5 秒续约）；每 5 秒读取存活实例列表，用 Rendezvous Hashing（HRW）计算自己负责的策略实例集合，差量启动 / 停止。
- 每个运行中的策略实例一个虚拟线程；事件（K 线、成交、定时）投递到该实例的队列。
- 每处理一个事件后把状态写入 Redis `strategy:state:{id}`；接管时加载快照，再通过 market-data 的历史 K 线接口补读快照之后的 K 线（strategy 不直接访问 market schema），然后切换到实时流。
- 消费 `md.bars.1m`、`md.bars.agg`、`order.events`（独立消费组，每个服务实例都收到全部消息，只分发给自己负责的策略实例）。

**验证：** 单元测试 HRW 分配的稳定性（增加一个实例时迁移比例接近 1/N）；集成测试两个服务实例的分配无重叠、无遗漏。

**Checklist：**
- [ ] 分配无重叠、无遗漏
- [ ] 快照与补读后状态正确

---

## P7-T06 均线交叉策略

**实现要点：** 参数：交易对、K 线周期、快线 / 慢线周期、每次金额。快线上穿慢线 → 市价买入（按金额）；下穿 → 市价卖出全部持有该币（以策略自身记录的持仓为准）。只在 K 线收盘时判断。

**验证：** 用固定 K 线序列的单元测试断言信号时点；连 mock 回放运行，看到信号与订单。

**Checklist：**
- [ ] 无未来数据（只用已收盘 K 线）
- [ ] 重复 K 线不产生重复信号

---

## P7-T07 定投策略

**实现要点：** 参数：交易对、间隔（1 小时 / 1 天 / 1 周）、每次金额（不低于交易对最小下单金额）。到点市价按金额买入；错过执行时间（例如实例迁移期间）只补执行一次。

**验证：** 单元测试（可控时钟）覆盖正常执行、错过多次只补一次；连 mock 设为 1 小时间隔，用可调时钟或测试配置验证。

**Checklist：**
- [ ] 补执行只一次
- [ ] 金额下限校验

---

## P7-T08 网格策略

**实现要点：** 参数：交易对、价格上下限、格数、每格数量。启动时在区间内等距挂买卖限价单（GTC）；某格成交后挂反向单；暂停时撤销全部挂单；价格超出区间时不再补单。挂单状态以 `order.events` 为准。

**验证：** 单元测试覆盖启动挂单、成交补反向单、暂停撤单；连 mock 运行，看到成交后自动挂反向单。

**Checklist：**
- [ ] 订单与网格格位一一对应
- [ ] 暂停后无残留挂单
- [ ] 每用户配额消耗可控（启动时挂单数受令牌桶约束，分批提交）

---

## P7-T09 信号与下单执行器、重置联动

**实现要点：**
- 信号 ID 确定性生成（`实例ID + 触发时间 + 动作`），写入 `signal` 表并经 Outbox 发到 `strategy.signals`。
- 下单执行器消费 `strategy.signals`，调用 order `POST /internal/orders`（`svc-strategy` 客户端凭证，`source = STRATEGY`，`onBehalfOf` 为策略所属用户，`Idempotency-Key` = 信号 ID）。
- strategy 提供 `POST /internal/users/{userId}/pause-all`（审计 `STRATEGY_PAUSE`，执行者 `system:account-reset`）；去掉 P6-T07 中"strategy 未部署时跳过"的逻辑。
- 指标 `strategy_signals_total`。

**验证：** 重复投递同一信号只产生一笔订单；重置账户时该用户策略全部暂停。

**Checklist：**
- [ ] 信号幂等
- [ ] 重置联动生效
- [ ] 审计中策略下单的 `onBehalfOf` 正确

---

## P7-T10 前端：策略页

**实现要点：** `/strategies`：实例列表（类型、交易对、状态、累计信号数）；创建表单按类型的 JSON Schema 动态生成；启动、暂停、删除；信号记录与对应订单链接。

**Checklist：**
- [ ] 三种策略都可从界面创建并运行
- [ ] 达到上限时界面提示
- [ ] E2E：创建定投策略并看到首笔订单

---

## P7-T11 策略迁移测试与大盘

**实现要点：**
- 集成测试：两个 strategy 实例运行 10 个策略实例，杀掉其中一个，另一个在 30 秒内接管全部；期间信号不重复下单、不遗漏（定投补一次）。
- Grafana"交易"大盘增加：策略信号数（按类型、动作）、按来源统计的订单。

**Checklist：**
- [ ] 迁移测试通过
- [ ] 大盘更新已提交

---

## P7 阶段验收

- [ ] 资产页净值曲线、分布、持仓盈亏正确
- [ ] 风控 R06 / R07 生效
- [ ] 三种策略可创建、运行、暂停，自动下单
- [ ] 策略实例迁移不重复下单
- [ ] 重置时策略自动暂停
