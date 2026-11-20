# P3 行情链路

**目标：** 接入实时行情，经 Kafka 分发、落库、聚合，并推送到浏览器；行情连接具备 Leader 选举与故障切换能力。

**前置条件：** P2 完成。

**阶段结束时能看到：**
- 浏览器行情列表页实时跳动 5 个交易对的价格、24 小时涨跌和走势小图；
- 交易页显示可切换周期的 K 线、实时订单簿和最新成交；
- 同时运行两个 market-data 实例，杀掉 Leader 后 30 秒内另一个接管，K 线没有缺口；
- Kafka UI 中看到各行情 Topic 的消息；Grafana"行情"大盘 v0 有数据。

**需要的 Compose profile：** `core`、`mq`（本阶段新增）、`obs`、`mock`

---

## P3-T01 Compose `mq`：Kafka、Apicurio、Kafka UI

**目标：** 本地消息基础设施可用。

**实现要点：**
- Kafka 4.x 单节点 KRaft（堆 512MB），Apicurio Registry 3.x（存储使用 PostgreSQL `apicurio` schema，堆 256MB），Kafka UI（kafbat，连 Apicurio 解码 Avro）。镜像固定版本、arm64。
- `scripts/kafka-topics.sh`：按 design.md 6.2 创建全部 Topic（分区 6、保留时间按表），以及对应的 `.DLT`；可重复执行。

**验证：**
```bash
scripts/dev.sh up core mq
scripts/kafka-topics.sh
```
浏览器打开 Kafka UI（http://localhost:8089）看到全部 Topic 及分区、保留配置；打开 Apicurio（http://localhost:8088）UI。

**停止条件：** 找不到 arm64 镜像。

**Checklist：**
- [ ] 镜像固定版本且为 arm64
- [ ] Topic 配置与 design.md 6.2 一致
- [ ] 内存占用更新到 `docs/verification/memory.md`

---

## P3-T02 事件契约：Avro、Apicurio、Kafka 公共库

**目标：** 建立全项目共用的事件定义和 Kafka 配置。

**实现要点：**
- `libs/events-schema`：Avro Gradle 插件生成 Java 类；本阶段定义行情事件 `MarketTrade`、`MarketQuote`、`OrderBookSnapshot`、`Bar`；公共字段 `eventId`、`eventType`、`occurredAt`、`producer`；金额字段使用 `decimal` 逻辑类型（precision 38，scale 18）。
- `libs/common-kafka`：
  - 生产者 / 消费者默认配置（Apicurio `AvroKafkaSerializer` / `AvroKafkaDeserializer`、按 Topic 命名策略、`acks=all`、幂等生产者）；
  - 消息头工具：trace 上下文、`x-leader-epoch`、`x-backfill`；
  - 失败重试 + 死信主题的默认配置；
  - 消费幂等支持：`consumed_event` 表的 MyBatis 实现放在 `libs/common-mybatis`，`common-kafka` 提供"在业务事务内登记事件 ID"的工具方法。
- `scripts/schema-register.sh`：把 `.avsc` 注册到 Apicurio，并为每个 Topic 设置 `BACKWARD` 兼容规则；CI 中加入兼容性检查步骤（对比 main 分支）。

**验证：**
```bash
./gradlew :libs:events-schema:build :libs:common-kafka:integrationTest
scripts/schema-register.sh
```
集成测试（Testcontainers Kafka + Apicurio）：生产一条 `MarketTrade`，消费后字段与金额精度一致；故意提交一个不兼容的 Schema 修改，注册被拒绝。

**Checklist：**
- [ ] 金额精度端到端保持不变
- [ ] 不兼容 Schema 被拒绝
- [ ] 消费幂等工具有测试
- [ ] 死信主题配置生效（处理失败的消息进入 `.DLT`）

---

## P3-T03 market-data 服务骨架与资产元数据

**目标：** 建立 market-data 服务，提供可交易资产与精度规则。

**实现要点：**
- `services/market-data`，标准目录结构，另有服务特有目录 `stream/`、`orderbook/`、`push/`（本任务只建 controller / service / model / mapper）。端口 8082。
- Flyway：`asset_meta`（交易对、状态、最小下单量、数量步长、价格步长、最小下单金额、更新时间）。
- 同步任务：启动时与每日一次（ShedLock）从 Alpaca 拉取资产，写库并刷新缓存；缓存为 Caffeine（本地）+ Redis，变更时通过 Redis Pub/Sub 广播失效。
- 接口 `GET /api/assets`：返回默认订阅交易对的元数据。
- gateway 增加路由 `/api/assets/**`、`/api/market/**` → market-data。
- Prometheus 目标加入 market-data。

**验证：**
```bash
./gradlew :services:market-data:bootRun        # 连 mock
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/assets | jq
```
预期：返回 5 个交易对及精度规则（值与 P2-T09 记录一致）。

**Checklist：**
- [ ] 两级缓存与失效广播有测试
- [ ] 同步任务只在一个实例执行（ShedLock）
- [ ] 路由经网关可访问

---

## P3-T04 公共库：Leader 选举与 Fencing

**目标：** 提供 market-data 和 order 共用的 Leader 选举能力。

**实现要点：**
- `libs/common-leader`：基于 Spring Integration `LockRegistryLeaderInitiator` + `JdbcLockRegistry`；锁表 `INT_LOCK` 与 `leader_epoch` 表由使用方服务的 Flyway 创建（库中提供 SQL 片段）。
- 当选时：从 `leader_epoch` 取递增 epoch；发布 `LeaderGrantedEvent(role, epoch)`；失去时发布 `LeaderRevokedEvent`。
- 提供 `LeaderOnly` 注解或开关，供"只在 Leader 上运行"的组件使用。
- 优雅停机时主动释放锁。
- 指标 `leader_change_total{role}`、当前是否为 Leader 的 Gauge。

**验证：** 集成测试启动两个应用上下文共享同一 PostgreSQL：只有一个成为 Leader；关闭它后另一个在锁超时内当选，epoch 加 1；优雅停机时立即交接。

**Checklist：**
- [ ] 同一时刻只有一个 Leader（测试断言）
- [ ] epoch 单调递增
- [ ] 优雅停机立即交接

---

## P3-T05 行情接入与发布

**目标：** Leader 连接行情 WebSocket，把行情发到 Kafka 和 Redis。

**实现要点：**
- `stream/`：成为 Leader 时启动 `MarketDataStream`，失去 Leader 时关闭。
- 订阅列表来自 Nacos `market-data.yaml` 的 `subscriptions`（默认 5 个交易对），变更时发送差量订阅。
- 成交、报价、分钟 K 线分别发到 `md.trades`、`md.quotes`、`md.bars.1m`（key = symbol），消息头带 `x-leader-epoch`。
- 最新价写 Redis `md:last:{symbol}`（Hash：价格、时间；24 小时涨跌在 P3-T07 补齐）。
- 下游统一工具：丢弃 epoch 小于已见最大值的消息。

**验证：** 连 mock：Kafka UI 中三个 Topic 持续有消息；`redis-cli HGETALL md:last:BTC/USD` 持续变化；在 Nacos 删掉一个交易对，该交易对消息停止且连接未断开。

**Checklist：**
- [ ] 只有 Leader 发送
- [ ] 订阅差量更新不断线
- [ ] epoch 过滤工具有测试

---

## P3-T06 订单簿重建

**目标：** 在 Leader 上维护每个交易对的本地订单簿。

**实现要点：**
- `orderbook/`：每个交易对 bids 降序、asks 升序两棵 `TreeMap<BigDecimal, BigDecimal>`；快照整体替换，增量逐价位更新，数量为 0 删除。
- 每 200ms 把前 20 档写入 Redis（`md:book:{symbol}:bids/asks`）并发布 `md.orderbook`（合并发布）。
- 重连后全量重建。
- 校验任务（ShedLock，每 5 分钟）：REST 拉取最新订单簿与本地前 10 档比较，超过阈值计数 `md_orderbook_check_failed_total` 并强制重建。

**验证：** 单元测试覆盖快照、增量、删除价位；连 mock 后 Redis 中订单簿持续更新；mock 注入"断开 WebSocket"后订单簿被重建。

**Checklist：**
- [ ] 增量处理正确（单元测试）
- [ ] 合并发布频率符合 200ms
- [ ] 校验任务能发现并修复偏差（测试中人为篡改本地订单簿）

---

## P3-T07 K 线落库、聚合与历史接口

**目标：** 分钟 K 线落库，聚合出多周期 K 线，提供历史查询和缺口回填。

**实现要点：**
- Flyway：`bar_1m` 为 TimescaleDB 超表；`bar_agg`（5m / 15m / 1h / 1d）。
- 消费 `md.bars.1m` 写入 `bar_1m`（幂等：主键 symbol + 时间）。
- Kafka Streams：按 symbol 窗口聚合出 5m / 15m / 1h，发到 `md.bars.agg` 并落库；1d 由 TimescaleDB 连续聚合生成。
- `md:last:{symbol}` 补充 24 小时涨跌（取 24 小时前的收盘价）。
- 接口 `GET /api/market/{symbol}/bars?tf=1m|5m|15m|1h|1d&from&to`（`symbol` 为 `BTC-USD` 格式），`GET /api/market/{symbol}/snapshot`。
- **缺口回填：** 当选 Leader 时读取 `md:bars:last-ts:{symbol}`，用 REST 补拉缺失分钟 K 线并发送（`x-backfill=true`）。

**验证：** `curl` 历史接口返回连续 K 线；手动停止 market-data 2 分钟后再启动，查询这段时间的 1m K 线没有缺口。

**Checklist：**
- [ ] 超表与连续聚合创建成功
- [ ] 聚合结果与 1m 数据一致（测试）
- [ ] 回填后无缺口
- [ ] 历史接口经网关可访问

---

## P3-T08 浏览器推送

**目标：** market-data 所有实例向浏览器推送行情。

**实现要点：**
- `push/`（WebFlux WebSocket，与 Servlet 部分分开端口或使用同一 Reactor Netty 端口，按 Boot 4 支持方式实现；如两种栈不能共存于同一服务，**停止并报告**）。
- `POST /api/ws-ticket`：生成 30 秒有效的一次性票据（Redis），绑定用户 ID。
- WebSocket 连接：`/ws?ticket=...`，经 gateway 转发；协议按 design.md 7.3（`subscribe` / `unsubscribe` / `ping`；频道 `ticker:`、`book:`、`bars:<symbol>:<tf>`、`user:*`）。
- 每个实例以独立消费组消费 `md.*`，按 symbol 保存最新值；合并推送：ticker 和 bars 每连接每交易对每 250ms 最多一次，订单簿每 500ms 一次；慢客户端只保留最新值。
- 用户私有频道的投递通道（Redis Pub/Sub `push:user:{userId}`）先搭好，P4 / P5 发送真实数据。
- 指标：`push_ws_connections_current`、`md_e2e_latency_seconds`（Alpaca 时间戳 → 推送发出）。

**验证：** 用 `websocat` 连接（先取票据）并订阅 `ticker:BTC/USD`，看到每秒约 4 条；同一票据第二次使用被拒绝；30 秒后票据失效。

**停止条件：** Boot 4 下 Servlet 与 WebFlux WebSocket 无法在同一服务内按设计共存。

**Checklist：**
- [ ] 票据一次性且 30 秒过期
- [ ] 合并推送频率正确
- [ ] 慢客户端不拖慢其他连接（测试）
- [ ] 经 gateway 可连接

---

## P3-T09 前端：行情列表页

**目标：** 首页显示实时行情列表。

**实现要点：**
- `shared/composables/useMarketStream`：全局唯一连接；按频道引用计数订阅 / 退订；断线自动重连并重新订阅（重新取票据）；推送数据写入非响应式缓冲区，`requestAnimationFrame` 每帧合并一次再写入 `shallowRef`。
- `modules/market`：行情列表（交易对、最新价、24 小时涨跌、24 小时走势小图）；涨跌颜色按用户偏好；价格按资产精度格式化（decimal.js + `Intl.NumberFormat`）。
- 测试：MSW 模拟 WebSocket 消息，验证合并渲染和颜色切换。

**验证：** 浏览器首页看到 5 个交易对实时跳动；切换涨跌颜色立即生效；断开网络 10 秒再恢复，数据继续更新。

**Checklist：**
- [ ] 引用计数订阅正确（离开页面后退订）
- [ ] 断线重连可用
- [ ] 数字等宽、不抖动

---

## P3-T10 前端：交易页（行情部分）

**目标：** 交易页的行情区域可用，下单面板留到 P5。

**实现要点：**
- `modules/trade`：路由 `/trade/:symbol`。
- K 线：TradingView Lightweight Charts（保留其要求的署名）；周期切换（1m / 5m / 15m / 1h / 1d）；先加载历史，再拼接实时 K 线。
- 订单簿：买卖各 20 档表格 + ECharts 深度图。
- 最新成交列表。
- 下单面板区域显示"即将推出"。

**验证：** 浏览器打开 `/trade/BTC-USD`，K 线实时更新、切换周期正常；订单簿和成交实时刷新。

**Checklist：**
- [ ] 历史与实时 K 线无缝衔接
- [ ] 深度图与订单簿一致
- [ ] 中英文、亮暗主题正常

---

## P3-T11 Leader 故障切换与行情大盘（关键场景 3 的行情部分）

**目标：** 验证多实例下的 Leader 切换，并在 Grafana 中能看到。

**实现要点：**
- IntelliJ 运行配置 `.run/market-data-2.run.xml`（端口 8092）；Prometheus 目标加入第二个实例。
- 集成测试：两个实例 + mock，杀掉 Leader（非优雅），断言 30 秒内另一个当选、`md.bars.1m` 在切换期间的数据经回填后连续。
- Grafana"行情"大盘 v0：各交易对消息速率、端到端延迟、Leader 所在实例与切换次数、WebSocket 重连次数、订单簿校验失败、推送连接数。

**验证：** 手动运行两个实例，在终端 `kill -9` Leader 进程；浏览器行情不中断（短暂停顿可接受）；Grafana 中看到 Leader 切换。

**Checklist：**
- [ ] 集成测试通过
- [ ] 手动演练通过
- [ ] 大盘以文件形式提交

---

## P3-T12 切换到真实行情

**目标：** 用真实 Alpaca 模拟盘行情运行完整链路。

**实现要点：** 停止 `mock` profile，market-data 使用 Vault 中的真实 Key；按 design.md 10.6 的约定，同一时间只有一个环境连真实 Alpaca。

**验证：** 浏览器中行情与 Alpaca 网页显示的价格一致；运行 30 分钟无异常断线（或断线后自动恢复）；记录 Q6 额度相关的观察到 `alpaca-findings.md`。

**Checklist：**
- [ ] 真实行情显示正确
- [ ] 30 分钟稳定性观察已记录

---

## P3 阶段验收

- [ ] 浏览器行情列表与交易页行情区实时更新
- [ ] K 线历史与实时衔接、无缺口
- [ ] Leader 故障切换自动恢复（自动化测试 + 手动演练）
- [ ] Kafka UI 与 Grafana 行情大盘有数据
- [ ] `./gradlew build integrationTest` 通过
