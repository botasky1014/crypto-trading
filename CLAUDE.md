# CLAUDE.md

学习用的加密货币交易微服务项目（Spring Boot / Spring Cloud + Vue），接入 Alpaca **加密货币现货模拟盘**。

## 文档

- `docs/design.md`：设计的唯一依据。
- `docs/plan.md`：实施总览，含执行规则、端口表（§4）、关键场景对应表（§7）、进度。
- `docs/plans/P<n>-*.md`：各阶段任务，每个任务都有 checklist、验证步骤和停止条件。
- `docs/setup/`：本机环境准备；`docs/verification/`：验证记录（兼容性、内存实测等）。

## 工作规则

- **以 design.md 为准**。计划和设计冲突时，停下来向用户报告。
- **不猜测**。会影响功能或结构、但文档里没有规定的问题，先问用户。纯实现细节（命名、私有方法拆分）可以自己定。
- 碰到任务的**停止条件**必须停下，整理现象、可选方案和推荐方案，交给用户决定。
- **一次只做一个任务**，按编号顺序做，做完整个 checklist 再开始下一个。
- 每个任务结束时：
  - `./gradlew build` 通过（含单元测试、ArchUnit、Spotless；ArchUnit 在 P0-T05 接入，Spotless 在 P0-T08 接入）；改到前端时还要通过 `pnpm -C web build` 和 `pnpm -C web test`；
  - 任务"验证"一节的命令全部执行过，结果写进回复或提交说明；
  - 在回复中给出建议的提交信息（Conventional Commits，例如 `feat(order): place order saga (P5-T03)`），每个任务对应一个提交。
- **所有 git 提交操作都由用户手动完成**。绝对不要执行 `git add`、`git commit`、`git push`，也不要执行其他会改动暂存区或提交历史的命令。
- **不提交密钥**。密钥只放在 `.env.local`（已加入 gitignore）或 Vault。新增密钥时在 `.env.example` 里加一个空值的键，`scripts/dev.sh up` 会为缺少的键生成随机值。
- 测试和 CI 一律连 **alpaca-mock**。只有任务里明确写了"连真实模拟盘"才连 Alpaca，同一时间只能有一个环境连着真实 Alpaca。系统禁止连接 live 环境。
- P6 之前遇到重要数据修改，用 `// AUDIT: <动作编码>` 注释标出，P6-T04 时统一换成 `@Audited`。

## 环境限制

- 本机是 Apple 芯片 Mac，16GB 内存。只启动当前任务需要的 Compose profile，所有镜像都要支持 `linux/arm64`。
- 内存实测记在 `docs/verification/memory.md`，以 cgroup `memory.stat` 的 `anon` 为准（`docker stats` 含页缓存，偏高），两种读数都记录；Java 服务在宿主机运行时看 RSS。和 design.md 10.1 对比。
- JDK 25（Temurin，由 Gradle Toolchain 管理；Gradle Daemon 也固定为 Temurin 25，见 `gradle/gradle-daemon-jvm.properties`）；Gradle 9.x，Groovy DSL；Node LTS + pnpm。
- 版本只在 `gradle/libs.versions.toml` 里定义，每个版本都要注明来源；子项目依赖不写版本，统一由 `platform` 管理。模块通过 `build-logic` 的约定插件构建：服务用 `crypto-trading.spring-service`，库用 `crypto-trading.java-conventions`（需要测试时再加 `crypto-trading.test-conventions`）。

## 常用命令

| 用途 | 命令 |
| --- | --- |
| 启动 / 停止中间件 | `scripts/dev.sh up core [mq] [obs] [mock]` / `scripts/dev.sh down` |
| 查看容器状态 / 内存 | `scripts/dev.sh ps` / `scripts/dev.sh stats` |
| 环境自检 | `scripts/doctor.sh` |
| 构建与全部检查 | `./gradlew build` |
| 自动格式化 | `./gradlew spotlessApply` |
| 覆盖率报告 | `./gradlew test integrationTest jacocoTestReport`（`build/reports/jacoco/`） |
| 集成测试 | `./gradlew integrationTest` |
| 启动服务 | `./gradlew :services:<name>:bootRun` |
| 前端开发 | `pnpm -C web dev` |

新增服务时，在 `deploy/compose/prometheus/targets/*.json` 里加一条抓取目标（`host.docker.internal:<端口>`），并依赖 `libs:common-observability`（Actuator、Prometheus 指标、公共标签、ECS JSON 日志，零配置）。Grafana 大盘只改 `deploy/grafana/dashboards/*.json`，标题和面板名用英文。

- `bootRun` 会把根目录 `.env.local` 读成环境变量（`crypto-trading.spring-service` 插件），服务配置里用 `${KEY}` 引用密钥；IntelliJ 运行配置（`.run/*.run.xml`）用 Gradle 类型执行 `bootRun`。
- Nacos 初始配置放在 `deploy/nacos/<命名空间>/<Data ID>`（分组 `DEFAULT_GROUP`），`scripts/dev.sh up core` 只发布 Nacos 中还不存在的配置，不覆盖已有修改。服务用 `spring.config.import: nacos:<服务名>.yaml`（命名空间 `dev`）。
- Nacos 账号：服务一律用 `svc-dev`（配置只读、注册读写，密码 `NACOS_SERVICE_PASSWORD`）；只有 account 写配置时用 `config-admin`；管理员 `nacos` 不给服务用。账号由 `scripts/dev.sh up core` 创建。
- Keycloak（`core`，http://localhost:8180）：realm 文件 `deploy/keycloak/realm-crypto-trading.json` 只在 realm 不存在时导入，文件里的密钥一律写成 `${ENV}` 占位符（值在 `.env.local`，经 Compose 传给容器）；控制台改了 realm 后用 `scripts/keycloak-export.sh` 导出回仓库。令牌 issuer 固定为 `http://localhost:8180/realms/crypto-trading`。
- 动态刷新的配置用可变的 `@ConfigurationProperties` 类（不用 record）。不连 Nacos 的切片测试要关闭 `spring.cloud.nacos.config.enabled` / `discovery.enabled`，并设置 `spring.config.on-not-found=ignore`。

## 代码约定

- 目录结构：`build-logic/`（约定插件）、`platform/`（BOM）、`libs/`、`services/<name>/`、`tools/alpaca-mock/`、`web/`、`deploy/`。
- 根包 `com.cryptotrading.<服务名>`。所有服务用同一套目录：`controller/`、`service/`（只放接口）+ `service/impl/`、`model/{entity,dto,enums,event}`、`mapper/`、`converter/`、`client/`、`listener/`、`job/`、`config/`。用不到的目录不建。
- 以下规则由 ArchUnit 强制检查：
  - 调用方向只能是 `controller → service → mapper/client`，不能反向，controller 也不能跳过 service 直接调 mapper 或 client；
  - `@Transactional` 和 `@Audited` 只能写在 `service.impl` 中；
  - `model` 不依赖 service、controller、mapper；
  - 金额、数量、价格**禁止用 `double`/`float`**，全程用 `BigDecimal`；
  - 服务之间不共享 entity，只通过 `libs` 里的契约（Avro、Protobuf、DTO）交互。
  - 规则在 `libs/architecture-rules`。每个服务都要在测试根包放一个 `class ArchitectureTest extends AbstractArchitectureTest {}`。
- 空值：NullAway 检查 `com.cryptotrading` 的生产代码，没标注的类型都是非空；可能为空的参数、返回值、字段用 `org.jspecify.annotations.Nullable` 标注。改完代码先 `./gradlew spotlessApply` 再 `build`。不要靠 `@SuppressWarnings` 绕过 NullAway 或 Error Prone，确有必要时注明原因。
- 用到 MapStruct 的模块自己加 `implementation 'org.mapstruct:mapstruct'`，并在该模块加 `-Amapstruct.unmappedTargetPolicy=ERROR`（注解处理器已由约定插件配置）。
- 金额舍入用 `common-core` 的 `Decimals.roundDown`（按最小单位向零截断），比较用 `Decimals.eq/gt/...`，不要用 `BigDecimal.equals`。
- 业务错误：各服务定义 `enum XxxErrorCode implements ErrorCode`，抛出 `BusinessException`，由 `common-web` 统一转成 Problem Details。
- 安全：依赖 `common-web` 就自动成为 Resource Server（Keycloak JWT，默认规则见 design.md 8.2），用户 ID 一律取 `CurrentUser.get().id()`（JWT `sub`）。控制器测试用 `@WebMvcTest` + spring-security-test 的 `jwt()`（依赖 `spring-boot-starter-security-oauth2-resource-server-test`），切片里同样有安全规则和 Problem Details。
- 数据访问：依赖 `common-mybatis`，实体继承 `BaseEntity`（审计字段自动填充 + `@Version`）；只用 wrapper 的更新不会自动填充。Mapper 测试用 `@MybatisPlusTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers PostgreSQL，放在 `integrationTest`（写法见 `docs/verification/compatibility.md`）。
- **多语言与代码语言**（design.md 12.4）：
  - 前端所有页面必须支持中英文切换，文案全部走 vue-i18n，不硬编码。
  - 代码里只能用英文，不能出现任何中文：注释、日志、异常信息、配置、脚本、SQL、测试名和测试数据都算代码。唯一例外是 `web/src/locales/zh-CN.*` 语言包；Markdown 文档不算代码，继续用中文。
  - 后端不做国际化，只返回英文错误码（Problem Details 的 `code`、风控 `rule` 等），由前端翻译。
  - P0-T08 起 `./gradlew build` 会用 `scripts/check-no-chinese.sh` 检查。
- CI：`.github/workflows/ci.yml`，执行 `./gradlew build integrationTest jacocoTestReport`。Action 固定到 commit SHA 并在注释写版本；改工作流后本地跑 `actionlint`。
- 服务间通信：order 调 risk 用 gRPC；同步调用用 REST（HTTP Interface + LoadBalancer）；事件走 Kafka（Avro）。内部接口的路径前缀是 `/internal`，gateway 不路由这些接口。
