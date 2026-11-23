# P5 交易主链路

**目标：** 实现下单前风控、订单状态机、下单 Saga、成交回报处理、成交入账、UNKNOWN 恢复和撤单，并在前端完成下单。

**前置条件：** P4 完成。

**阶段结束时能看到：**
- 浏览器交易页下市价单或限价单，几秒内看到订单状态变化、成交明细和余额变化；
- 当前委托可撤单；订单历史页可筛选查询；
- mock 注入超时、重复、乱序回报后，订单状态与余额仍然正确；
- Grafana"交易"大盘 v0 显示下单数、成交笔数、成交额、拒单原因。

**需要的 Compose profile：** `core`、`mq`、`obs`、`mock`

---

## P5-T01 risk 服务（gRPC）

**目标：** 下单前风控校验服务。

**实现要点：**
- `libs/risk-proto`：按 design.md 5.5 定义 `RiskService.Check`；`com.google.protobuf` 插件生成代码。
- `services/risk`：Spring gRPC 服务端（9095），HTTP 8084 仅用于 Actuator。
- 规则 R01–R05 按 design.md 5.5 实现，阈值来自 Nacos `risk.yaml`（R04 ±10%、R05 每分钟 30 次）。R04 读 Redis `md:last:{symbol}`；R01 / R02 读资产元数据（Caffeine + Redis）；R05 用 Redis 滑动窗口计数。
- R06、R07 依赖持仓读模型，本阶段**实现为关闭状态**（配置开关），P7-T02 打开。
- `source = RESET` 只执行 R01–R03。
- 截止时间（deadline）从调用方传播。

**验证：**
```bash
./gradlew :services:risk:test
grpcurl -plaintext -d '{"user_id":1,"symbol":"BTC/USD","side":"BUY","type":"LIMIT","qty":"0.00001","limit_price":"1","tif":"GTC","source":"MANUAL"}' localhost:9095 crypto.risk.RiskService/Check
```
预期：数量低于最小下单量 → R02 拒绝；价格偏离超过 10% → R04 拒绝。

**Checklist：**
- [ ] 每条规则有通过与拒绝两个测试
- [ ] 规则参数修改后无需重启即生效
- [ ] R06 / R07 开关默认关闭

---

## P5-T02 order 服务骨架与状态机

**目标：** 建立 order 服务和订单状态机。

**实现要点：**
- `services/order`，标准目录结构，服务特有目录 `statemachine/`。端口 8083。
- Flyway：`orders`（含 `client_order_id` 唯一、`source`、`type`、`side`、`tif`、`qty`、`notional`、`limit_price`、`status`、`filled_qty`、`avg_fill_price`、`version`）、`order_fill`、`order_idempotency`、`INT_LOCK`、`leader_epoch`、`outbox`、`audit_outbox`、`consumed_event`、`shedlock`。
- 状态机按 design.md 5.3.1：状态枚举带 `rank`；转换表集中定义；`transition(from, event)` 返回新状态或拒绝。只允许市价、限价；有效期只允许 GTC、IOC。
- 状态更新语句：`WHERE id=? AND version=? AND rank(status) < ?`。

**验证：** 参数化测试覆盖转换表中每一个合法转换和全部非法转换。

**Checklist：**
- [ ] 转换表与 design.md 图一致
- [ ] 非法转换全部被拒绝
- [ ] 只能向前推进（测试）

---

## P5-T03 下单接口与 Saga 前半段

**目标：** 下单请求经过幂等、风控、冻结后进入待提交状态。

**实现要点：**
- `POST /api/orders`（需 `Idempotency-Key`）：
  1. 幂等检查（`order_idempotency`），重复请求返回首次结果；
  2. 交易限制检查接口 `UserTradingGuard`（本阶段默认放行，P6 实现"重置中拒单"）；
  3. 生成 `client_order_id = "ct-" + orderId`，落库 `NEW`；
  4. gRPC 调用 risk；拒绝 → `REJECTED_LOCAL`；
  5. 按 design.md 5.4.3 计算冻结金额（市价按数量买入时取订单簿卖一价），调用 ledger 冻结（`svc-order` 客户端凭证）；失败 → `REJECTED_LOCAL`；
  6. 同一事务：状态 `FUNDS_FROZEN` + Outbox（`SUBMIT`）；返回 202 和订单 ID。
- 标注 `// AUDIT: ORDER_CREATE`。
- gateway 路由 `/api/orders/**` → order。

**验证：** `http/order.http` 下限价单返回 202；数据库订单为 `FUNDS_FROZEN`；ledger 中该用户 USD 冻结金额正确；同一 `Idempotency-Key` 再发返回同一订单。

**Checklist：**
- [ ] 幂等（含并发重复请求）
- [ ] 风控拒绝与余额不足都进入 `REJECTED_LOCAL` 且无冻结残留
- [ ] 冻结金额按规则计算（单元测试覆盖四种订单）

---

## P5-T04 提交 Alpaca 与订单事件

**目标：** Outbox 投递把订单提交到 Alpaca，并发出订单事件。

**实现要点：**
- `SUBMIT` 类 Outbox 消息由专门的投递器处理（不发 Kafka，而是调用 Alpaca）：先扣用户桶再扣全局桶（`ORDER` 优先级），不足则延迟投递；状态置 `SUBMITTING` 后调用下单接口。
- 结果：成功 → `ACCEPTED`；4xx → `REJECTED`；超时 / 网络错误 → `UNKNOWN`（不重试，交给 P5-T07）。
- `events-schema` 增加订单事件（`OrderAccepted`、`OrderRejected`、`OrderFilled`（含本次成交量、价格、累计量）、`OrderCanceled`、`OrderExpired`），经 Outbox 发到 `order.events`（key = userId）。
- 指标：`trade_orders_total`、`trade_order_rejected_total`、`order_submit_latency_seconds`。

**验证：** 连 mock：订单变为 `ACCEPTED`；Kafka UI 中看到 `OrderAccepted`；mock 设为 100% 拒单时订单变为 `REJECTED`。

**Checklist：**
- [ ] 令牌不足时延迟而不丢失
- [ ] 三种结果状态正确
- [ ] 事件经 Outbox 发送

---

## P5-T05 成交入账与解冻（关键场景 4）

**目标：** ledger 根据订单事件完成入账和解冻。

**实现要点：**
- ledger 消费 `order.events`（幂等）：
  - `OrderFilled`：按 design.md 5.4.4 结算（买：冻结报价货币减少、基础货币增加；卖：相反）；按估算费率记 `FEE`（从收到的资产中扣除，计入 PLATFORM 的 FEE 账户）；
  - 终态（`FILLED` / `CANCELED` / `EXPIRED` / `REJECTED`）：剩余冻结全部解冻。
- 余额变化经 `ledger.events` 推送到浏览器。
- 场景 4 集成测试：冻结成功后 mock 拒单 → 冻结全部释放，余额恢复。

**Checklist：**
- [ ] 部分成交、全部成交、撤单后解冻都正确
- [ ] 手续费从收到的资产中扣除
- [ ] 场景 4 通过

---

## P5-T06 成交回报处理（关键场景 2）

**目标：** order 的 Leader 持有唯一的成交回报连接，按回报推进订单状态。

**实现要点：**
- 使用 `common-leader`（角色 `order-trade-updates`）：成为 Leader 时启动 `TradeUpdatesStream`。
- 回报按 `client_order_id`（前缀 `ct-`）找到本地订单；非本平台前缀记录告警日志后丢弃。
- 事件映射到状态转换；`filled_qty` 取回报中的累计成交量与当前值的较大者；每次成交写 `order_fill`（按 Alpaca 成交 ID 去重），并发出 `OrderFilled`。
- 场景 2 集成测试：mock 注入"重复回报"和"乱序回报（fill 先于 partial_fill）"，最终状态、累计成交量、`order_fill` 条数、ledger 余额都正确。

**Checklist：**
- [ ] 重复回报无副作用
- [ ] 乱序回报最终正确
- [ ] 只有 Leader 持有连接

---

## P5-T07 UNKNOWN 恢复与未完结订单轮询（关键场景 1）

**目标：** 处理"不知道 Alpaca 有没有收到"的订单，以及漏掉的回报。

**实现要点：**
- UNKNOWN 恢复任务（ShedLock，每 10 秒，`SYSTEM_QUERY` 优先级）：扫描 `UNKNOWN` 超过 5 秒的订单，按 `client_order_id` 查询：查到 → 按状态推进；404 → 回到 `SUBMITTING` 用同一 ID 重新提交；超时 → 下次再查。标注 `// AUDIT: ORDER_RECOVER`。
- 未完结订单轮询（ShedLock，每 30 秒）：最近 1 分钟内未收到回报的 `ACCEPTED` / `PARTIALLY_FILLED` / `CANCEL_REQUESTED` 订单，查询最新状态并推进。
- 指标 `order_unknown_current`。
- 场景 1 集成测试：mock 注入下单接口延迟超过超时时间（但 mock 实际收下订单）→ 订单进入 `UNKNOWN` → 恢复任务查到并推进；断言 mock 中该 `client_order_id` 只有一笔订单。

**Checklist：**
- [ ] UNKNOWN 不会直接重发
- [ ] 404 时用同一 ID 重提
- [ ] 场景 1 通过，Alpaca 侧只有一笔

---

## P5-T08 撤单与查询接口

**实现要点：**
- `DELETE /api/orders/{id}`：只能撤自己的、未完结的订单；状态置 `CANCEL_REQUESTED`，调用 Alpaca 撤单；最终状态以回报为准。标注 `// AUDIT: ORDER_CANCEL`。
- `GET /api/orders?status&symbol&page`、`GET /api/orders/{id}`（含成交明细）。

**验证：** 下一笔远离市价的限价单 → 撤单 → 状态变为 `CANCELED`，冻结释放；撤别人的订单返回 404。

**Checklist：**
- [ ] 撤单与成交竞争时以回报为准（测试）
- [ ] 用户隔离

---

## P5-T09 前端：下单面板、当前委托、订单历史

**目标：** 浏览器中完成下单、撤单、查看订单。

**实现要点：**
- 交易页下单面板：买 / 卖；市价 / 限价；限价按数量，市价买入可选按数量或按金额；有效期 GTC / IOC；按资产精度校验和舍入；显示可用余额、预估金额与预估手续费；提交时生成 `Idempotency-Key`。
- 当前委托列表（可撤单）；订阅 `user:orders` 实时更新并弹出提示（成交、拒单）。拒单原因按后端返回的英文原因码（风控 `rule`、Alpaca 拒单等）在语言包中取中英文文案（design.md 12.4）。
- `/orders` 订单历史页：筛选、分页、详情抽屉（成交明细）。
- E2E：登录 → 下市价单 → 看到成交与余额变化（连 mock）。

**验证：** 浏览器中完成下单、成交、撤单全流程；中英文与涨跌颜色正确。

**Checklist：**
- [ ] 前端校验与后端规则一致
- [ ] 重复点击提交不会产生两笔订单
- [ ] E2E 通过

---

## P5-T10 并发、隔离、故障切换与契约测试（关键场景 3、5、12）

**实现要点：**
- 场景 5：同一用户 50 个并发下单，余额不为负、冻结不超额、每笔订单状态最终一致。
- 场景 12：用户 A 快速下单耗尽配额，用户 B 下单不受影响。
- 场景 3（order 部分）：两个 order 实例，杀掉持有成交回报连接的 Leader，另一个接管；切换期间成交的订单最终状态正确（依靠轮询兜底）。
- 契约测试：在 `test-conventions` 中加入 `contractTest` 套件；Spring Cloud Contract 定义 order → ledger（冻结 / 解冻）和 account → ledger（开户）的 REST 契约，以及 `order.events` 的消息契约。

**Checklist：**
- [ ] 场景 3、5、12 通过
- [ ] 契约测试在双方构建中都运行

---

## P5-T11 Grafana"交易"大盘 v0

**实现要点：** 面板：下单数 / 成交笔数 / 成交额（按交易对、类型、来源）、订单状态分布、拒单原因 Top、下单延迟、UNKNOWN 订单数、Alpaca 调用耗时、令牌桶拒绝次数。

**Checklist：**
- [ ] 大盘以文件形式提交，下单后数据可见

---

## P5-T12 真实模拟盘联调

**目标：** 用真实 Alpaca 模拟盘跑通交易。

**实现要点：** 停止 mock；**下单前告知用户将执行的操作**；在浏览器中下小额市价单和限价单、撤单；手动触发对账，确认对账恒等式成立。

**Checklist：**
- [ ] 下单、成交、撤单结果与 Alpaca 网页一致
- [ ] 对账无差异（或差异已解释）

---

## P5 阶段验收

- [ ] 浏览器下市价 / 限价单、撤单、查看历史
- [ ] 场景 1、2、3（order 部分）、4、5、12 自动化测试通过
- [ ] 契约测试通过
- [ ] 真实模拟盘联调通过，对账恒等式成立
- [ ] Grafana 交易大盘有数据
