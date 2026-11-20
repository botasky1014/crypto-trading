# P8 可观测性完整

**目标：** 补齐链路追踪、集中日志、全部业务指标、9 个 Grafana 大盘和告警，做到"从告警追到链路，再追到日志"。

**前置条件：** P7 完成。

**阶段结束时能看到：**
- 下一笔单后，在 Grafana 中打开一条 Trace，看到"信号 → 风控 → 冻结 → 下单 → 回报 → 入账"的完整调用链，并能跳到对应日志；
- design.md 9.1 的 9 个大盘全部存在并有数据（"服务器 / 集群"与"弹性伸缩"大盘在 P9 才有完整数据）；
- 人为制造故障（例如停止 order）后，对应告警触发。

**需要的 Compose profile：** `core`、`mq`、`obs`、`mock`

---

## P8-T01 补齐可观测性组件

**实现要点：**
- Compose `obs` 增加 Loki、Tempo、Alloy、OTel Collector（均单实例、本地存储、短保留期，内存按 design.md 10.1）。
- Grafana 数据源关联：Prometheus Exemplar → Tempo；Tempo → Loki（按 traceId 查日志）；Loki → Tempo（日志中的 traceId 可点击）。
- 更新 `docs/verification/memory.md`；如总内存接近上限，记录哪些组件可按需关闭。

**Checklist：**
- [ ] 四个组件 healthy
- [ ] 数据源之间可互相跳转
- [ ] 内存记录已更新

---

## P8-T02 链路追踪

**实现要点：**
- `common-observability`：Micrometer Tracing + OpenTelemetry，OTLP 发到 Collector；HTTP、gRPC、Kafka（生产与消费的 Observation）、JDBC 都产生 span；Outbox 投递时还原原始 trace 上下文。
- Collector 尾部采样：错误与慢请求（> 1 秒）全部保留，其余 10%（本地开发可临时调为 100%）。
- 指标开启 Exemplar。

**验证：** 在浏览器下单 → Grafana"交易"大盘的下单延迟面板点 Exemplar → 打开 Trace，看到跨 gateway、order、risk、ledger、Kafka、Alpaca（mock）的 span。

**Checklist：**
- [ ] 经过 Kafka 与 Outbox 后链路不断
- [ ] gRPC span 存在
- [ ] 采样策略生效

---

## P8-T03 集中日志

**实现要点：**
- 本地开发时服务在 IDE 中运行，标准输出无法被 Alloy 采集，因此：服务在 `dev` profile 下额外把 ECS JSON 日志写到 `./logs/<服务名>.json`，Alloy 采集该目录；k3d 阶段改为采集 Pod 标准输出（P9 配置）。
- 日志字段：`trace.id`、`span.id`、`service.name`、`user.id`、`order.id`（有则带）。
- Keycloak 开启用户事件与管理事件日志（jboss-logging 监听器），Alloy 采集 Keycloak 容器日志进 Loki。
- 敏感信息检查：日志中不出现令牌、密钥（加一个测试扫描日志样本）。

**验证：** Grafana Explore 中按 traceId 查到该请求在各服务的日志；查到 Keycloak 的登录事件。

**Checklist：**
- [ ] 日志与 Trace 互相跳转
- [ ] Keycloak 事件可查
- [ ] 敏感信息扫描测试通过

---

## P8-T04 业务指标补齐

**实现要点：**
- 在 `common-observability` 中集中定义 design.md 9.3 的全部指标名与标签（`TradeMetrics`、`LedgerMetrics`、`MarketMetrics`、`AuditMetrics` 等门面），各服务改为通过门面上报。
- 测试：启动各服务后抓取 `/actuator/prometheus`，断言 9.3 表中每个指标都存在、标签集合正确、没有 userId / orderId 等高基数标签。

**Checklist：**
- [ ] 9.3 表中指标全部存在
- [ ] 无高基数标签（测试）

---

## P8-T05 中间件指标

**实现要点：** Compose 增加子 profile `obs-extra`：Kafka、PostgreSQL、Redis 的 exporter；Nacos 与 Keycloak 自带的指标端点加入 Prometheus 目标文件。内存紧张时可不启动 `obs-extra`。

**Checklist：**
- [ ] 中间件指标在 Prometheus 中可见

---

## P8-T06 全部大盘

**实现要点：** 按 design.md 9.1 完成 9 个大盘（总览、交易、平台资金、行情、服务实例、弹性伸缩、服务器 / 集群、中间件、审计），全部以 JSON 文件提交；所有大盘用 `application` 变量过滤服务，不写死服务名。审计大盘加入 Keycloak 注册与登录失败趋势（Loki 查询）。"弹性伸缩"和"服务器 / 集群"大盘的面板先建好，数据在 P9 出现。

**验证：** 逐个打开大盘截图存档到 `docs/verification/dashboards/`。

**Checklist：**
- [ ] 9 个大盘齐全
- [ ] 没有写死服务名
- [ ] 截图已存档

---

## P8-T07 新服务自动接入（关键场景 11）

**实现要点：** 在 `tools/observability-probe` 建一个只依赖 `common-observability` 的空服务；把它加入 Prometheus 目标文件并启动；脚本 `scripts/check-observability.sh` 验证：Prometheus 中 `up{application="observability-probe"} == 1`，"服务实例"大盘的服务变量中出现它，Tempo 中有它的 span，Loki 中有它的日志。

**Checklist：**
- [ ] 场景 11 脚本通过
- [ ] k3d 阶段的自动发现方式已在 P9 计划中对应

---

## P8-T08 告警

**实现要点：**
- **开始前询问用户告警通知发到哪里**（design.md 未规定，例如只在 Grafana 中查看、邮件、Slack 或其他）；按用户的选择配置联系点。
- 按 design.md 9.4 用文件定义全部告警规则（Grafana Alerting provisioning）。
- 逐条演练：停止 market-data（行情中断）、mock 注入下单超时（订单卡住）、篡改审计记录（审计链断裂）、把 PLATFORM 余额调低（资金池不足）等，确认告警触发与恢复。

**Checklist：**
- [ ] 通知渠道已由用户决定
- [ ] 每条告警都演练过并记录结果

---

## P8-T09 诊断工具文档

**实现要点：** `docs/operations/diagnostics.md`：Arthas 的常用命令（`watch`、`trace`、`thread`）与在本地 / k3d Pod 中的使用方法；JFR 录制与分析虚拟线程 pinning 的方法；以"订单卡在 SUBMITTING"为例演示一次排查。

**Checklist：**
- [ ] 文档中的示例实际执行过

---

## P8-T10 每日 E2E 工作流

**实现要点：** `.github/workflows/e2e.yml`：每日定时 + 手动触发；在 GitHub 运行器上用 Compose 启动全部中间件、mock 与服务，运行 Playwright 全部用例；失败时上传截图、录像和服务日志。

**Checklist：**
- [ ] 工作流文件通过 `actionlint`
- [ ] 用户推送后手动触发一次并通过（由用户确认）

---

## P8 阶段验收

- [ ] 一次下单能从指标追到 Trace 再追到日志
- [ ] 9 个大盘齐全
- [ ] 场景 11 通过
- [ ] 告警全部演练过
- [ ] 每日 E2E 工作流已提交
