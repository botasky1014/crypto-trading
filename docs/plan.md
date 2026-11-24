# crypto-trading 实施计划（总览）

本计划把 `design.md` 拆成 11 个阶段（P0–P10），按从易到难排列。每个阶段一个文件，放在 `plans/` 下；每个阶段由若干任务组成，每个任务都能单独运行、测试并看到结果。

计划的执行者是 AI 编码助手（Claude）。用户负责审查每个任务的结果、在需要决策时做决定、推送代码。

---

## 1. 阶段一览

| 阶段 | 文件 | 主要内容 | 阶段结束时能看到什么 |
| --- | --- | --- | --- |
| P0 | `plans/P0-environment-and-skeleton.md` | 本机环境、Gradle 骨架、Compose `core`、最小 Prometheus + Grafana、CI、版本兼容性验证 | `./gradlew build` 通过；Nacos 控制台中看到示例服务；Grafana 中看到它的 JVM 指标 |
| P1 | `plans/P1-auth-gateway-frontend.md` | Keycloak、gateway、account（`/api/me`）、Vue 前端骨架与登录、Grafana 单点登录 | 浏览器中注册、登录、看到个人信息；Swagger UI 调用接口 |
| P2 | `plans/P2-alpaca-integration.md` | Vault、`alpaca-spring-boot-starter`、两级令牌桶、两类 WebSocket 客户端、alpaca-mock、真实模拟盘验证 | 命令行工具对真实模拟盘下单并收到回报；alpaca-mock 可替代 Alpaca；关键假设验证报告 |
| P3 | `plans/P3-market-data.md` | Kafka + Apicurio、Avro、market-data（Leader、订单簿、K 线、推送）、前端行情页 | 浏览器实时看到行情列表、K 线和订单簿；杀掉 Leader 自动恢复 |
| P4 | `plans/P4-ledger-and-provisioning.md` | Outbox、ledger（PLATFORM、划拨、冻结、分录、对账）、开户、资产页 v0、资金池管理页 | 新用户登录即有 1,000 USD；管理员页看到资金池与对账结果 |
| P5 | `plans/P5-trading.md` | risk（gRPC）、order（状态机、Saga、UNKNOWN、回报 Leader）、成交入账、交易页 | 浏览器下市价 / 限价单，看到成交和余额变化 |
| P6 | `plans/P6-audit-config-reset.md` | `common-audit`、audit-service、补齐审计、配置管理接口、重置 Saga、审计与设置页 | 管理员页查到每一次重要修改；用户一键重置账户 |
| P7 | `plans/P7-portfolio-and-strategy.md` | ledger 读模型、风控 R06/R07、strategy（三种策略、分片、快照）、资产与策略页 | 净值曲线、持仓盈亏；策略自动下单 |
| P8 | `plans/P8-observability.md` | 链路、日志、业务指标、9 个大盘、告警 | 从告警跳到 Trace 再跳到日志；9 个大盘全部有数据 |
| P9 | `plans/P9-cloud-native.md` | Jib + GHCR、k3d、Helm、Argo CD、Envoy Gateway、HPA + KEDA、Argo Rollouts、NetworkPolicy、mTLS、Chaos Mesh、备用 EC2 | 压测时看到服务自动扩容、结束后缩容；滚动升级不中断 |
| P10 | `plans/P10-upgrade.md` | 升级到 Boot 4.2 + Spring Cloud 2026.0 | 全部测试在新版本上通过 |

阶段之间严格按顺序进行；阶段内的任务也按编号顺序进行，除非任务说明中注明可并行。

---

## 2. 执行规则（给执行者）

1. **以 `design.md` 为准。** 计划只描述"怎么一步步做出来"，设计决策以 design.md 为准。两者冲突时，停止并向用户报告。
2. **不猜测。** 遇到 design.md 和本计划都没有规定、且会影响功能或结构的问题，停止并向用户提问；只有纯实现细节（变量名、私有方法拆分等）可以自行决定。
3. **遇到"停止条件"必须停止。** 每个任务列出了停止条件（例如版本不兼容、外部服务行为与假设不符）。触发时整理现象、可选方案和推荐方案，交给用户决策。
4. **一次只做一个任务。** 完成任务的全部 checklist 后再开始下一个。
5. **每个任务结束时：**
   - `./gradlew build` 通过（含单元测试、ArchUnit、Spotless 检查）；涉及前端时 `pnpm -C web build` 和 `pnpm -C web test` 通过；
   - 任务"验证"一节的命令全部执行过，结果符合预期；
   - 把验证结果（命令输出摘要、截图位置或 URL）写进回复中；
   - 在回复中给出建议的提交信息，使用 Conventional Commits，例如 `feat(order): place order saga (P5-T03)`，每个任务对应一个提交。
   - **所有 git 提交操作都由用户手动完成。** 执行者绝对不要执行 `git add`、`git commit`、`git push`，也不要执行其他会改动暂存区或提交历史的命令。
6. **不提交任何密钥。** Alpaca Key、Keycloak 客户端密钥等只放在 `.env.local`（已在 `.gitignore` 中）或 Vault。
7. **测试连 alpaca-mock。** 自动化测试和 CI 一律连 alpaca-mock；只有任务中明确写"连真实模拟盘"的步骤才连 Alpaca。同一时间只能有一个环境连接真实 Alpaca。
8. **内存。** 本机为 16GB 内存的 Apple 芯片 Mac。只启动任务需要的容器和服务；任务说明中列出了需要的 Compose profile。所有镜像必须支持 `linux/arm64`。
9. **审计补齐。** P6 之前实现的"重要数据修改"在代码中用 `// AUDIT: <动作编码>` 注释标出位置，P6-T04 统一替换为 `@Audited`。
10. **多语言与代码语言（design.md 12.4）。** 前端所有页面必须支持中英文切换，文案全部走 vue-i18n。代码里只能用英文，不能出现任何中文（含注释、日志、异常信息、配置、脚本、测试）；唯一例外是 `web/src/locales/zh-CN.*` 语言包，Markdown 文档不算代码。后端只返回英文错误码，由前端翻译。P0-T08 起由构建检查强制。

---

## 3. 本机环境

| 项 | 要求 |
| --- | --- |
| 机器 | macOS，Apple 芯片，16GB 内存 |
| Docker | Docker Desktop；资源设置：内存 10GB、Swap 2GB、CPU 6 核（P0-T01 设置） |
| IDE | IntelliJ IDEA；手工验证使用 IntelliJ HTTP Client（`http/*.http` 文件） |
| JDK | 由 Gradle Toolchain 自动下载 Temurin 25；IDE 中也配置 Temurin 25 |
| Node.js | 当前 LTS（≥ 22.12），pnpm（通过 corepack 启用） |
| 其他 CLI | git、jq、httpie 或 curl、actionlint（检查 CI 工作流）、k3d / kubectl / helm（P9 才需要） |

---

## 4. 端口约定（本地 Compose 阶段）

| 组件 | 端口 |
| --- | --- |
| web（Vite 开发服务器） | 5173 |
| hello（临时示例服务，P0–P1） | 8090 |
| gateway | 8080 |
| account | 8081 |
| market-data | 8082 |
| order | 8083 |
| risk | 8084（HTTP，Actuator）/ 9095（gRPC） |
| ledger | 8085 |
| strategy | 8086 |
| audit-service | 8087 |
| alpaca-mock | 8099（REST）/ 8098（WebSocket） |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Nacos | 8848（API，路径 `/nacos`）/ 8849（控制台）/ 9848（gRPC） |
| Keycloak | 8180 |
| Vault | 8200 |
| Kafka | 9092 |
| Apicurio Registry | 8088 |
| Kafka UI | 8089 |
| Prometheus | 9090 |
| Grafana | 3000 |
| Loki | 3100 |
| Tempo | 3200 |
| OTel Collector | 4317（gRPC）/ 4318（HTTP） |

Prometheus 的抓取目标写在 `deploy/compose/prometheus/targets/*.json`（`file_sd`），新增服务时加一行。

---

## 5. 常用命令约定

| 用途 | 命令 |
| --- | --- |
| 启动中间件 | `scripts/dev.sh up core [mq] [obs] [mock]` |
| 停止中间件 | `scripts/dev.sh down` |
| 查看容器内存 | `scripts/dev.sh stats` |
| 环境自检 | `scripts/doctor.sh` |
| 构建与全部检查 | `./gradlew build` |
| 自动格式化 | `./gradlew spotlessApply` |
| 覆盖率报告 | `./gradlew test integrationTest jacocoTestReport`（`build/reports/jacoco/`） |
| 只跑集成测试 | `./gradlew integrationTest` |
| 启动某个服务 | `./gradlew :services:<name>:bootRun`，或 IntelliJ 中对应的运行配置（`.run/*.run.xml`，随代码提交） |
| 前端开发 | `pnpm -C web dev` |

---

## 6. 占位符

| 占位符 | 含义 | 何时替换 |
| --- | --- | --- |
| `<github-owner>` | GitHub 用户名或组织名 | 首次推送前由用户提供 |
| `<github-owner>/crypto-trading` | 代码仓库 | 同上 |
| `<github-owner>/crypto-trading-gitops` | GitOps 仓库（P9 创建） | 同上 |
| `<EC2 地址>` | 备用 EC2 地址 | 使用备用环境时 |

---

## 7. 关键场景与阶段对应

design.md 第 13 节的 12 个关键场景在以下任务中实现自动化测试：

| 场景 | 内容 | 任务 |
| --- | --- | --- |
| 1 | 下单超时进入 UNKNOWN 后正确恢复 | P5-T07 |
| 2 | 成交回报重复、乱序 | P5-T06 |
| 3 | market-data、order 的 Leader 故障切换 | P3-T11、P5-T10 |
| 4 | 冻结后 Alpaca 拒单，冻结全部释放 | P5-T05 |
| 5 | 同一用户 50 并发下单 | P5-T10 |
| 6 | 资金池不足开户、对账恒等式 | P4-T06、P4-T08 |
| 7 | 重置断点续做、重置期间拒单、零头转移 | P6-T08 |
| 8 | 审计与业务同事务 | P6-T02 |
| 9 | 审计哈希链篡改检测、扩容不分叉 | P6-T03 |
| 10 | 压测期间自动扩缩容 | P9-T09 |
| 11 | 新服务自动出现在 Grafana | P8-T07 |
| 12 | 用户配额耗尽只影响本人 | P5-T10 |

---

## 8. 进度总表

- [x] P0 环境与工程骨架（2026-11-24 完成）
- [ ] P1 登录、网关与前端骨架
- [ ] P2 Alpaca 接入
- [ ] P3 行情链路
- [ ] P4 账本与开户
- [ ] P5 交易主链路
- [ ] P6 审计、配置管理与重置
- [ ] P7 读模型与策略
- [ ] P8 可观测性完整
- [ ] P9 云原生与自动扩缩容
- [ ] P10 升级演练
