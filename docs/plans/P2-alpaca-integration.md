# P2 Alpaca 接入

**目标：** 完成与 Alpaca 的全部底层对接（REST、两类 WebSocket、限流、容错、live 保护），做出可替代 Alpaca 的 alpaca-mock，并在真实模拟盘上验证 design.md 的关键假设。

**前置条件：** P1 完成；用户已有 Alpaca 模拟账户和 API Key，并写入 `.env.local`（`ALPACA_KEY_ID`、`ALPACA_SECRET_KEY`）。

**阶段结束时能看到：**
- 命令行工具 `alpaca-probe` 对真实模拟盘查询账户、下一笔小额限价单再撤单，并实时打印成交回报；
- 同样的命令切到 alpaca-mock 也能完成，且可以注入 429、断线、乱序回报等故障；
- `docs/verification/alpaca-findings.md` 记录了全部关键假设的验证结论。

**需要的 Compose profile：** `core`（Vault、Redis），`mock`（本阶段新增）

---

## P2-T01 Vault 存放平台 Alpaca Key

**目标：** 平台唯一的 Alpaca Key 只存在于 Vault，代码和配置文件中都没有。

**实现要点：**
- `scripts/vault-init.sh`：从 `.env.local` 读取 Key，写入 Vault KV `secret/crypto-trading/alpaca`（字段 `key-id`、`secret-key`）；创建三个策略 `order`、`market-data`、`ledger`（只读该路径），以及供它们使用的 AppRole（dev 模式下为每个服务生成 role-id / secret-id 写入 `.env.local`）。
- `scripts/dev.sh up core` 后自动执行 `vault-init.sh`（Vault dev 模式重启会丢数据）。

**产出：** `scripts/vault-init.sh`、`dev.sh` 更新

**验证：**
```bash
scripts/dev.sh up core
vault kv get -field=key-id secret/crypto-trading/alpaca   # 能读到
# 使用 account 服务的身份（无策略）读取同一路径   # 被拒绝
```

**Checklist：**
- [ ] Key 只在 `.env.local` 与 Vault 中
- [ ] 三个策略只读、其他身份无权访问
- [ ] 重启 Vault 后重新执行可恢复

---

## P2-T02 `alpaca-spring-boot-starter`：配置、live 保护、交易对编码

**目标：** 建立 Alpaca 接入库的基础部分。

**实现要点：**
- `libs/alpaca-spring-boot-starter`，自动配置类按 Boot 4 方式注册。
- `AlpacaProperties`：交易 REST 地址、行情 REST 地址、行情 WebSocket 地址（`v1beta3/crypto/us`）、成交回报 WebSocket 地址、超时、令牌桶参数（P2-T04 使用）。默认值全部指向模拟盘 / 行情域名。
- **live 保护：** 启动时校验所有交易相关地址必须是模拟盘域名；否则启动失败并给出明确错误。
- 凭据通过 Spring Cloud Vault 从 `secret/crypto-trading/alpaca` 注入；测试中可用属性覆盖。
- `Symbol` 值对象：`BTC/USD` ↔ 路径格式 `BTC-USD` ↔ URL 编码；拆分基础货币与报价货币。
- 指标：`alpaca_http_requests_seconds`（P2-T03 使用）。

**验证：**
```bash
./gradlew :libs:alpaca-spring-boot-starter:test
```
测试覆盖：配置为 live 地址时上下文启动失败；`Symbol` 三种格式互转。

**Checklist：**
- [ ] live 地址会导致启动失败
- [ ] 凭据不出现在日志中（测试断言）
- [ ] `Symbol` 测试覆盖 5 个默认交易对

---

## P2-T03 REST 客户端

**目标：** 用 Spring HTTP Interface 封装本项目用到的全部 Alpaca REST 接口。

**实现要点：**
- 基于 `RestClient`（JDK HttpClient），拦截器注入认证头。
- 接口范围：
  - 交易：账户、资产列表与单个资产（精度、最小下单量）、下单（market / limit；qty 或 notional；`gtc` / `ioc`；`client_order_id`）、按 ID 查询、按 `client_order_id` 查询、撤单、未完结订单列表、持仓、Activities（手续费流水）。
  - 行情：历史 K 线、最新订单簿、最新报价。
- 请求和响应用 record；金额、数量一律 `BigDecimal`（JSON 中可能是字符串）。
- 错误映射：4xx → `AlpacaRejectedException`（含 Alpaca 错误码与消息），404 单独区分，429 → `AlpacaRateLimitedException`，5xx / 超时 → `AlpacaUnavailableException`。
- 每次调用记录 `alpaca_http_requests_seconds`（标签：endpoint、status）。
- 测试：WireMock，响应样例取自 Alpaca 官方文档，存放在 `src/test/resources/alpaca-samples/`，注明来源链接。

**验证：**
```bash
./gradlew :libs:alpaca-spring-boot-starter:test
```
预期：每个接口至少一个成功用例和一个错误用例。

**停止条件：** 官方文档的字段与 design.md 的假设不一致（例如加密货币下单参数不同），记录差异并报告。

**Checklist：**
- [ ] 全部接口实现并有测试
- [ ] 金额字段全部为 `BigDecimal`
- [ ] 错误映射完整
- [ ] 样例文件注明来源

---

## P2-T04 两级令牌桶

**目标：** 实现 design.md 5.6 的全局 + 每用户令牌桶，所有实例共享。

**实现要点：**
- Redis + Lua 原子扣减，桶键 `alpaca:rl:global`、`alpaca:rl:user:{userId}`；容量与速率来自配置（默认全局每分钟 180、用户每分钟 20）。
- 调用方声明优先级：`ORDER`（下单 / 撤单）、`SYSTEM_QUERY`（剩余 < 30% 时拒绝）、`BACKGROUND`（对账、回填，剩余 < 50% 时拒绝）。
- 用户订单先扣用户桶再扣全局桶；任一失败则整体不扣（Lua 内保证）。
- 收到 429 时：清空全局桶并冻结 10 秒。
- 指标 `alpaca_ratelimit_rejected_total{bucket,priority}`。

**验证：**
```bash
./gradlew :libs:alpaca-spring-boot-starter:integrationTest --tests '*RateLimiter*'
```
测试（Testcontainers Redis）：
- 并发 200 次扣减，成功数等于容量；
- 用户 A 耗尽自己的桶后，用户 B 不受影响（对应关键场景 12 的底层）；
- 低优先级请求在阈值以下被拒绝，下单仍可通过；
- 模拟 429 后 10 秒内全部拒绝。

**Checklist：**
- [ ] Lua 脚本原子性有并发测试
- [ ] 三种优先级行为正确
- [ ] 429 冻结行为正确
- [ ] 指标可在 `/actuator/prometheus` 中看到

---

## P2-T05 容错：重试、熔断、超时、隔离

**目标：** 对 Alpaca 调用加上 Resilience4j 保护。

**实现要点：**
- 重试：指数退避（初始 200ms、倍数 2、最多 3 次、带抖动）。**只对查询类请求和带 `client_order_id` 的下单请求重试**；撤单按幂等处理也可重试。
- 熔断：失败率 50%（最少 20 次调用）打开，30 秒后半开。
- 超时：查询 3 秒、下单 5 秒。
- 隔离：行情类与交易类调用使用不同的 Bulkhead。
- 熔断打开时抛出 `AlpacaUnavailableException`，调用方（P5 的 Outbox）负责延迟重试。

**验证：** WireMock 注入 5xx、延迟、429，测试重试次数、熔断打开与恢复、超时生效。

**Checklist：**
- [ ] 不带 `client_order_id` 的下单不会被重试（测试断言）
- [ ] 熔断状态通过 Actuator 可见
- [ ] 行情调用失败不影响交易调用

---

## P2-T06 WebSocket 客户端

**目标：** 实现可靠的行情流和成交回报流客户端。

**实现要点：**
- 公共基类：连接 → 认证 → 订阅；30 秒无消息视为假死并重连；指数退避 1s、2s、4s…上限 60s，±20% 抖动；重连后自动重新认证和订阅；订阅列表变更时只发送差量。
- `MarketDataStream`：订阅成交、报价、订单簿、分钟 K 线；消息解码为 record，回调交给使用者。
- `TradeUpdatesStream`：订阅成交回报；把事件类型映射为枚举（以官方文档为准，未知类型记录告警日志并保留原始内容）。
- 两个流都提供连接状态（`CONNECTING` / `CONNECTED` / `DISCONNECTED`）和 `HealthIndicator`。
- 指标：`md_ws_reconnect_total{reason}`、消息速率。
- 原始消息录制开关（`alpaca.recording.enabled`）：把收到的原始行情消息连同接收时间写入 JSONL 文件（gzip），供 alpaca-mock 回放。

**验证：**
```bash
./gradlew :libs:alpaca-spring-boot-starter:test --tests '*Stream*'
```
测试使用本地 WebSocket 测试服务器：正常订阅收消息；服务端断开后按退避重连并重新订阅；30 秒无消息触发重连（测试中缩短为 1 秒）；订阅差量正确。

**Checklist：**
- [ ] 重连与重新订阅有测试
- [ ] 假死检测有测试
- [ ] 未知事件类型不会导致崩溃
- [ ] 录制文件格式有说明（`docs/alpaca-mock/replay-format.md`）

---

## P2-T07 alpaca-mock

**目标：** 做出一个可以替代 Alpaca 的模拟服务，供开发、集成测试和 CI 使用。

**实现要点：**
- `tools/alpaca-mock`，Spring Boot 应用，REST 8099、WebSocket 8098，Compose profile `mock`，堆 128MB。
- design.md 5.8 的能力：
  - REST：账户、持仓、资产、下单、撤单、查询、Activities；
  - 行情 WebSocket：从录制文件回放（原速 / 倍速），协议与 Alpaca 一致；
  - 成交回报 WebSocket：订单经撮合后推送 new / partial_fill / fill / canceled 等事件；
  - 撮合：用回放出的订单簿判断限价单能否成交，市价单吃对手盘，支持部分成交；模拟账户有初始现金和持仓，按成交更新；
  - 故障注入管理接口 `/admin/faults`：返回 429 的比例、固定延迟、断开 WebSocket、成交回报丢弃 / 重复 / 乱序；
  - 管理接口 `/admin/reset`：恢复初始状态（测试之间隔离）。
- **实现前先评估：** design.md 写的是 REST 部分用 WireMock 实现。由于 REST 的下单、查询需要和撮合引擎共享订单状态，先评估 WireMock（含扩展）能否做到。**如果不能，停止并请用户在两种方案中选择：** ① WireMock 加自定义扩展；② REST 也在 alpaca-mock 中直接用 Spring MVC 编写（WireMock 只用于 starter 的单元测试）。
- 内置一个小的默认回放文件（几分钟、5 个交易对），由 P2-T09 录制。

**验证：**
```bash
scripts/dev.sh up core mock
curl -s localhost:8099/v2/account | jq .cash
# 用 P2-T08 的 alpaca-probe 连接 mock：下限价单、看到 new，价格穿越后看到 fill
curl -X POST localhost:8099/admin/faults -d '{"rate429":1.0}'   # 之后请求全部 429
```

**Checklist：**
- [ ] 评估结论已记录；如需选择已由用户决定
- [ ] 下单、撤单、部分成交、全部成交可复现
- [ ] 故障注入每一项都可用
- [ ] `/admin/reset` 可在测试间重置状态
- [ ] 镜像为 arm64

---

## P2-T08 命令行工具 alpaca-probe

**目标：** 提供一个直接操作 Alpaca（或 mock）的命令行工具，用于验证和排查。

**实现要点：**
- `tools/alpaca-probe`，Spring Shell 应用，依赖 starter；`--target=paper|mock` 选择目标。
- 命令：`account`、`assets <symbol...>`、`quote <symbol>`、`book <symbol>`、`stream-market <symbols> <秒数>`、`stream-trades <秒数>`、`order-limit <symbol> <side> <qty> <price> <tif>`、`order-market <symbol> <side> --qty|--notional`、`order-get <clientOrderId>`、`order-cancel <id>`、`orders-open`、`positions`、`activities --type FEE`、`record <symbols> <分钟> <文件>`。
- 下单前打印订单摘要并要求输入 `yes` 确认。

**验证：** 对 mock 执行每个命令一次，输出合理。

**Checklist：**
- [ ] 全部命令可用
- [ ] 下单需要确认
- [ ] `--target=paper` 时同样受 live 保护

---

## P2-T09 真实模拟盘验证与回放录制

**目标：** 在真实模拟盘上验证 design.md 的关键假设，并录制回放数据。

**实现要点：** 使用 alpaca-probe `--target=paper`。**执行任何下单操作前，先把将要执行的操作清单发给用户确认。** 每项结果写入 `docs/verification/alpaca-findings.md`（现象、证据、结论、对设计的影响）：

| 编号 | 验证内容 | 方法 |
| --- | --- | --- |
| A1 / Q2 | 同一 Key 能否同时开两条行情 WebSocket | 同时运行两个 `stream-market`，观察第二条是否被拒或第一条被断开 |
| A2 / Q2 | 同一账户能否同时开两条成交回报 WebSocket | 同上，用 `stream-trades` |
| A3 / Q3 | REST 限流数值与计算方式 | 查看响应头中的限流信息；必要时小规模连续请求观察 429 |
| A5 / Q4 | 账户初始权益；能否调整 | `account` 查看现金与权益；在 Alpaca 网页控制台确认模拟账户能否重置、可选金额 |
| Q6 | 免费行情额度 | 同时订阅 5 个交易对的成交、报价、订单簿、K 线 10 分钟，观察是否被限制 |
| Q8 | 成交回报事件类型 | 对照官方文档列出全部类型；下单、部分成交、撤单时记录实际收到的事件 |
| Q11 | 同一账户的买卖单能否互相成交 | 先市价买入少量 BTC；再挂一个高于市价的卖单和一个同价的买单，观察是否被拒、是否成交 |
| Q5 | 模拟盘是否收取手续费 | 成交后次日查 `activities --type FEE` |
| — | 5 个默认交易对是否都可交易，各自的最小下单量与精度 | `assets` |

完成后录制回放：`record BTC/USD,ETH/USD,SOL/USD,LTC/USD,DOGE/USD 10 replay-default.jsonl.gz`，放入 alpaca-mock 资源目录；文件超过 10MB 时停止并询问存放方式。

**停止条件：** 任何一项结果与 design.md 的假设不一致（例如允许多条连接、自成交被拒、某个交易对不可交易），整理影响和可选方案交给用户决策。

**Checklist：**
- [ ] 下单操作已事先获得用户确认
- [ ] 表中每一项都有结论和证据
- [ ] 与设计不一致的项已报告并有用户决定
- [ ] 回放文件已录制并可被 mock 使用
- [ ] 验证中下的单已全部撤销或平仓，账户恢复原状（或记录残留持仓）

---

## P2 阶段验收

- [ ] starter 全部单元测试与集成测试通过
- [ ] alpaca-probe 对真实模拟盘和 mock 都能完成"查账户 → 下单 → 收到回报 → 撤单"
- [ ] mock 故障注入可用
- [ ] `alpaca-findings.md` 完成，不一致项已由用户决定
- [ ] Key 只存在于 `.env.local` 与 Vault
