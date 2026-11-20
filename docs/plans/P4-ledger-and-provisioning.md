# P4 账本与开户

**目标：** 实现复式记账账本、PLATFORM 资金池、新用户开户划拨和平台总额对账。

**前置条件：** P3 完成。

**阶段结束时能看到：**
- 新用户首次登录后，资产页显示 1,000 USD；
- 管理员页面看到 PLATFORM 资金池余额、已分配总额、可再开户人数和最近一次对账结果；
- 资金池不足时新用户余额为 0 并出现提示，管理员补发后余额实时更新；
- Grafana"平台资金"大盘 v0 有数据。

**需要的 Compose profile：** `core`、`mq`、`obs`、`mock`

---

## P4-T01 Outbox 公共组件

**目标：** 提供"业务数据与待发消息同一事务写入、再异步投递"的通用组件。

**实现要点：**
- 放在 `libs/common-mybatis`（表与写入）和 `libs/common-kafka`（投递）：
  - `outbox` 表 SQL 片段（id、topic、key、payload、headers、状态、重试次数、下次尝试时间、创建时间）；
  - `OutboxWriter`：在当前事务中写入一条待发消息；
  - 投递线程：`SELECT ... FOR UPDATE SKIP LOCKED` 批量取出，发送到 Kafka，成功标记完成；失败按指数退避重试；超过上限告警；多实例可同时运行。
- 投递时保留 trace 上下文到消息头。
- 已发送记录按天清理（保留 3 天）。

**验证：** 集成测试：业务事务回滚时消息不发送；投递中断后重启能继续；两个实例并发投递不重复（每条消息只发送一次，消费端去重兜底）。

**Checklist：**
- [ ] 回滚不发送
- [ ] 并发投递不重复
- [ ] 失败重试与告警指标可见

---

## P4-T02 ledger 服务骨架与 PLATFORM 初始化

**目标：** 建立 ledger 服务和账本表，以 Alpaca 账户实际资产初始化资金池。

**实现要点：**
- `services/ledger`，标准目录结构，另有服务特有目录 `projection/`（P7 使用）。端口 8085。
- Flyway：`account_balance`、`freeze`、`ledger_entry`、`reconcile_diff`、`outbox`、`audit_outbox`（P6 使用，表先建好）、`consumed_event`、`shedlock`；字段按 design.md 5.4.1，金额 `NUMERIC(38,18)`，`CHECK (available >= 0 AND frozen >= 0)`。
- **PLATFORM 初始化：** 启动时如果 `user_id = 0` 没有任何余额行，则从 Alpaca 拉取账户现金与全部持仓，记 `BALANCE_INIT` 分录（对方账户为 `EXTERNAL`）。只执行一次（ShedLock + 幂等检查）。在代码中标注 `// AUDIT: BALANCE_INIT`。
- 接入 starter（读 Vault 中的 Key）、Nacos、observability。

**验证：** 连 mock 启动：`account_balance` 中出现 `user_id = 0` 的 USD 及各币余额，与 mock 账户一致；重启不重复初始化。

**Checklist：**
- [ ] 表结构与 design.md 一致
- [ ] 初始化只执行一次
- [ ] 余额与 Alpaca（mock）一致

---

## P4-T03 记账核心与不变式

**目标：** 实现全部记账操作，保证四条不变式。

**实现要点：**
- `PostingService`：一次过账 = 一个 `tx_id` + 若干分录（借贷和为 0，否则拒绝）+ 对应余额的条件更新（`UPDATE ... SET available = available - ? WHERE user_id=? AND asset=? AND available >= ? AND version = ?`，影响行数为 0 则失败回滚），全部在一个本地事务中。
- 操作（service 方法，各自写分录类型）：`allocate`（PLATFORM → 用户）、`reclaim`、`dust`、`freeze`、`unfreeze`、`settleTrade`（买 / 卖）、`chargeFee`、`adjustFee`、`reconcileAdjust`。本阶段对外只用到 `allocate`、`freeze`、`unfreeze`，其余在 P5 / P6 接入，但现在实现并测试。
- 每次余额变化写 Outbox 事件 `BalanceChanged`（Avro，Topic `ledger.events`，key = userId）。
- 重要修改处标注 `// AUDIT: <编码>`。
- 测试：
  - jqwik 属性测试：随机生成一串划拨 / 冻结 / 解冻 / 成交 / 手续费操作，断言不变式 1–3 始终成立；
  - 并发测试：同一用户 50 个线程同时冻结，余额不为负、冻结总额不超过可用余额。

**验证：**
```bash
./gradlew :services:ledger:test :services:ledger:integrationTest
```

**Checklist：**
- [ ] 不平衡的过账被拒绝
- [ ] 属性测试覆盖全部操作类型
- [ ] 并发测试通过
- [ ] 每次余额变化都产生 `BalanceChanged`

---

## P4-T04 服务间认证与内部接口

**目标：** 服务之间通过 Keycloak 客户端凭证调用 `/internal/**` 接口。

**实现要点：**
- Keycloak realm 增加 confidential client `svc-account`、`svc-order`（P5 用）、`svc-strategy`（P7 用），client_credentials；client scope `internal.ledger`、`internal.order`、`internal.strategy`，按 design.md 8.1 授予。client secret 写入 `.env.local` 并由 Vault 提供给服务。
- `libs/common-web`：`/internal/**` 要求对应 scope；提供带 client_credentials 令牌的 HTTP Interface 客户端构建工具（自动获取与缓存令牌）。
- ledger 内部接口：
  - `POST /internal/accounts/{userId}/provision`（幂等：已开户直接返回当前余额）；
  - `POST /internal/freezes`、`DELETE /internal/freezes/{orderId}`（P5 使用）。
- gateway 不路由 `/internal/**`（测试断言）。

**验证：** 用 `svc-account` 的令牌调用 provision 成功；用用户令牌调用返回 403；经 gateway 访问 `/internal/...` 返回 404。

**Checklist：**
- [ ] scope 校验生效
- [ ] 令牌自动获取与缓存
- [ ] gateway 不暴露内部接口
- [ ] realm 文件已更新，客户端密钥不在仓库中

---

## P4-T05 开户流程

**目标：** 用户首次访问 `/api/me` 时自动获得 1,000 USD。

**实现要点：**
- account 的 `GET /api/me`：首次创建档案后调用 ledger provision（金额来自 Nacos `ledger.yaml`，默认 1,000 USD）。档案创建与开户调用分离：开户失败不影响档案，下次访问 `/api/me` 重试，直到成功或确认资金不足。
- `PLATFORM` 的 USD 不足时：用户余额为 0，`/api/me` 返回 `fundingStatus = INSUFFICIENT_PLATFORM_FUNDS`，计数指标增加。
- account 中标注 `// AUDIT: ACCOUNT_PROVISION`。
- market-data 推送模块消费 `ledger.events`，向 `user:balances` 频道推送。

**验证：** 浏览器注册新用户 → 登录 → `/api/me` 显示已开户；数据库中 PLATFORM USD 减少 1,000、用户 USD 增加 1,000、两条分录和为 0。

**Checklist：**
- [ ] 开户幂等（并发与重试都只划拨一次）
- [ ] 资金不足时的状态与提示正确
- [ ] 余额变化实时推送

---

## P4-T06 资金池不足与管理员补发（关键场景 6 的开户部分）

**目标：** 覆盖资金池不足的完整处理。

**实现要点：**
- `POST /api/admin/users/{id}/funds`（admin，`amount`、`reason` 必填）：从 PLATFORM 划拨；资金不足返回 409。标注 `// AUDIT: FUNDS_ALLOCATE`。
- 集成测试：把 PLATFORM USD 调到 1,500 → 第一个用户开户成功、第二个用户余额为 0 且状态为资金不足 → 管理员补发失败（409）→ 通过 mock 增加账户现金并执行对账调整后补发成功。

**Checklist：**
- [ ] 场景 6 的开户部分自动化测试通过
- [ ] `reason` 缺失返回 400

---

## P4-T07 查询接口

**目标：** 用户查看余额与流水，管理员查看资金池。

**实现要点：**
- `GET /api/balances`：各资产可用 / 冻结。
- `GET /api/ledger/entries?asset&page`：本人流水，分页。
- `GET /api/admin/platform`：PLATFORM 各资产余额、已分配给用户的总额、按当前开户金额可再开户人数、最近一次对账时间与结果。
- gateway 路由 `/api/balances/**`、`/api/ledger/**`、`/api/portfolio/**`（P7）、`/api/admin/platform/**`、`/api/admin/users/**`、`/api/admin/reconcile-diffs/**` → ledger。

**验证：** `http/ledger.http` 中各请求返回正确；用户只能看到自己的流水。

**Checklist：**
- [ ] 用户隔离有测试
- [ ] 管理员接口需要 admin 角色

---

## P4-T08 平台总额对账（关键场景 6 的对账部分）

**目标：** 每小时检查对账恒等式，差异不自动修正。

**实现要点：**
- 定时任务（ShedLock，每小时，`BACKGROUND` 优先级令牌）：拉取 Alpaca 现金与持仓，对每种资产比较 `Σ(所有账户 available + frozen)` 与 Alpaca 余额；超出容差（Nacos 配置）写 `reconcile_diff`。
- `POST /api/admin/reconcile/run`：管理员手动触发。
- `POST /api/admin/reconcile-diffs/{id}/confirm`（admin，`reason` 必填）：按差异调整 PLATFORM 余额，记 `RECONCILE` 分录。标注 `// AUDIT: BALANCE_ADJUST`。
- 指标：`ledger_reconcile_diff_current`、`platform_funds_available{asset}`。
- 每日不变式巡检任务：检查不变式 1–3，失败计数并记录错误日志。

**验证：** 集成测试：正常情况无差异；通过 mock 改变账户现金后产生差异；确认后再次对账无差异。

**Checklist：**
- [ ] 无差异时不写记录
- [ ] 差异确认后恒等式恢复
- [ ] 巡检任务可发现人为写坏的数据（测试）

---

## P4-T09 前端：资产页 v0 与管理员资金池页

**目标：** 用户看到余额，管理员看到资金池。

**实现要点：**
- `modules/portfolio`：余额列表（资产、可用、冻结），订阅 `user:balances` 实时刷新；资金不足时显示提示。净值曲线等在 P7 加入。
- `modules/admin`：`/admin/platform`（资金池、已分配、可开户人数、对账差异列表、"确认差异"和"补发资金"操作，均需填写原因）；导航中只对 admin 显示"管理"入口。

**验证：** 浏览器中新用户看到 1,000 USD；管理员页面数据与数据库一致；补发后用户页面实时更新。

**Checklist：**
- [ ] 非 admin 看不到也打不开管理页
- [ ] 原因必填的前端校验
- [ ] E2E：新用户登录后看到 1,000 USD

---

## P4-T10 Grafana"平台资金"大盘 v0

**实现要点：** 面板：PLATFORM 各资产余额、已分配总额、可再开户人数、开户成功 / 资金不足次数、对账差异数、不变式巡检结果。

**验证：** 执行几次开户与补发，大盘数据随之变化。

**Checklist：**
- [ ] 大盘以文件形式提交

---

## P4 阶段验收

- [ ] 新用户自动获得 1,000 USD，分录平衡
- [ ] 资金池不足与补发流程可用
- [ ] 对账恒等式成立，差异可人工确认
- [ ] 账本不变式属性测试与并发测试通过
- [ ] 资产页、管理员资金池页、Grafana 平台资金大盘可用
- [ ] 所有重要修改处都有 `// AUDIT:` 标注
