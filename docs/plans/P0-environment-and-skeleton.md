# P0 环境与工程骨架

**目标：** 准备本机环境，搭好 Gradle 多项目骨架和本地中间件，验证 Spring Cloud Alibaba 与 Boot 4.1 的兼容性，并让第一个服务的指标出现在 Grafana 中。

**前置条件：** 无。

**阶段结束时能看到：**
- `./gradlew build` 全部通过；
- Nacos 控制台中看到示例服务 `hello` 已注册，修改 Nacos 配置后服务实时生效；
- Grafana（http://localhost:3000）"服务实例"大盘（英文标题 `Service Instances`）中看到 `hello` 的 JVM 内存、GC、线程数。

**需要的 Compose profile：** `core`、`obs`

---

## P0-T01 本机环境准备

**目标：** 确认本机工具齐全，Docker Desktop 资源设置合理。

**实现要点：**
- 编写 `scripts/doctor.sh`，逐项检查并打印 OK / 缺失：git、jq、curl、Docker（引擎可连接、架构为 arm64）、Docker Desktop 分配内存 ≥ 10GB、Node.js ≥ 22.12、pnpm（corepack）、可用磁盘 ≥ 30GB。
- 不检查 JDK：JDK 由 Gradle Toolchain 自动下载。
- 在 `docs/setup/macos.md` 中写明：Docker Desktop 设置（内存 10GB、Swap 2GB、CPU 6）；IntelliJ 设置（Project SDK 选 Temurin 25、Gradle JVM 选 Temurin 25、启用 Annotation Processing）；corepack 启用命令。

**产出：** `scripts/doctor.sh`、`docs/setup/macos.md`

**验证：**
```bash
scripts/doctor.sh
```
预期：每项均为 OK；缺失项给出安装提示。

**Checklist：**
- [x] `doctor.sh` 可执行并覆盖上述全部检查项
- [x] 在本机运行全部 OK（如有缺失，提示用户安装后重跑）
- [x] `docs/setup/macos.md` 写清 Docker Desktop 与 IntelliJ 设置

---

## P0-T02 仓库初始化

**目标：** 建立仓库基本结构和文档位置。

**实现要点：**
- `git init`，默认分支 `main`。
- `.gitignore`：Gradle、IntelliJ（保留 `.run/`）、macOS、Node、`.env.local`、`*.local.yaml`。
- `.editorconfig`：UTF-8、LF、Java 4 空格、前端 2 空格。
- 目录：`docs/`（放入 design.md、plan.md、plans/）、`docs/verification/`、`scripts/`、`deploy/compose/`、`http/`、`.run/`。
- `README.md`：项目一句话介绍、文档索引、快速开始（指向 `docs/setup/macos.md`）。

**产出：** 上述文件和目录

**验证：**
```bash
git status && tree -L 2 -a -I '.git'
```
预期：目录结构与上述一致；`.env.local` 被忽略（创建一个空文件后 `git status` 不显示）。

**Checklist：**
- [x] `.gitignore` 忽略 `.env.local`
- [x] design.md、plan.md、plans/ 已放入 `docs/`
- [x] 已给出首个提交的建议提交信息（由用户手动提交）

**说明：** Git 不跟踪空目录，所以 `docs/verification/`、`deploy/compose/`、`http/`、`.run/` 里放了 `.gitkeep`，等目录里有真实文件后再删。本机没有装 `tree`，验证时可以 `brew install tree`，或者用 `find . -maxdepth 2 -not -path './.git*' | sort` 代替。

---

## P0-T03 Gradle 多项目骨架

**目标：** 建好构建骨架：Wrapper、版本目录、约定插件、版本平台。

**实现要点：**
- Gradle Wrapper 使用 9.x 最新稳定版（≥ 9.1）。
- `settings.gradle`：`includeBuild('build-logic')`；启用 foojay-resolver 插件；`include` 暂时只有 `platform`。
- `gradle.properties`：开启构建缓存、配置缓存、并行构建；JVM 内存参数适配 16GB 机器（`-Xmx2g`）。
- `gradle/libs.versions.toml`：Spring Boot 4.1.x、Spring Cloud 2025.1.x、Spring Cloud Alibaba 2025.1.0.0、MyBatis-Plus（Boot 4 starter）、以及本阶段用到的插件版本。全部取各自**当前最新稳定版**，并在文件中注释版本来源（Maven Central 或官方发布页）。
- `platform/build.gradle`（`java-platform`）：导入 Boot、Spring Cloud、SCA、MyBatis-Plus 四个 BOM，确保 Spring Cloud 使用 2025.1.x 最新版。注意：Gradle 的 `platform(...)` 与 Maven 不同，**BOM 的书写顺序不影响结果**，同一个库有多个版本时默认选最高的。所以要检查的是实际解析出的版本（用 `dependencyInsight`），而不是 BOM 的顺序；需要强制版本时用 `enforcedPlatform(...)` 或 `strictly`。
- `build-logic`（Groovy 预编译脚本插件）：
  - `crypto-trading.java-conventions`：Java Toolchain 25（Temurin）、`-parameters`、UTF-8、依赖 `platform`。
  - `crypto-trading.spring-service`：在 java-conventions 基础上应用 Spring Boot 插件。
  - `crypto-trading.test-conventions`：JUnit Platform；JVM Test Suite 定义 `test`、`integrationTest` 两个套件（`contractTest` 在 P5 加入）。

**产出：** `settings.gradle`、`build.gradle`、`gradle.properties`、`gradle/`、`build-logic/`、`platform/`

**验证：**
```bash
./gradlew build
./gradlew :platform:dependencies --configuration classpath | head -40
./gradlew javaToolchains
```
预期：构建成功；依赖输出中 Spring Boot 为 4.1.x、Spring Cloud 为 2025.1.x；Toolchain 列表中有 Temurin 25。

**停止条件：** 任一 BOM 的目标版本在 Maven Central 不存在，或 SCA BOM 强制把 Spring Cloud 降级。

**Checklist：**
- [x] Wrapper 版本 ≥ 9.1
- [x] 版本目录中每个版本都注明来源
- [x] 三个约定插件可被子项目应用
- [x] `platform` 中 Spring Cloud 版本为 2025.1.x 最新
- [x] `./gradlew build` 通过，配置缓存无警告

**说明（2026-11-21 完成时）：**
- 选定版本：Gradle 9.9.2（Wrapper 带 `distributionSha256Sum` 校验）、Spring Boot 4.1.3、Spring Cloud 2025.1.5、Spring Cloud Alibaba 2025.1.0.0、MyBatis-Plus 3.5.18.1、foojay-resolver 1.0.0。start.spring.io 显示 Spring Cloud 2025.1.x 兼容 Boot `>=4.0.0 and <4.2.0-M1`。
- 验证命令原来写的是 `--configuration apiElements`。`java-platform` 的 `apiElements` 是只供消费的配置，报告里显示 "No dependencies"，所以改用可解析的 `classpath`。
- 子项目里实际解析出的 `spring-cloud-commons` 是 5.0.5，和 Spring Cloud 2025.1.5 BOM 声明的一致，说明 SCA BOM 没有把 Spring Cloud 降级。（SCA 2025.1.0.0 的 BOM 本身也没有导入 Spring Cloud BOM；而且按 Gradle 的规则，它就算写了旧版本也会输给更高的版本。）JUnit 6.0.3 由 Boot BOM 管理。
- 本阶段 `settings.gradle` 只 include 了 `platform`。约定插件是用一个临时子项目验证的：同时应用三个插件，引入 Nacos Discovery 和 MyBatis-Plus starter，`build integrationTest` 通过，测试运行在 Temurin 25 上。临时子项目验证完已删除。
- `spring-service` 同时应用了 `java-conventions` 和 `test-conventions`，服务模块只写 `id 'crypto-trading.spring-service'` 就行。库模块按需应用 `java-conventions`，要测试的话再加 `test-conventions`。
- Gradle Daemon 用本机默认的 JDK 运行（本机是 JDK 27，Gradle 9.8 起支持）。编译和测试一律走 Toolchain（Temurin 25），首次构建时由 foojay 自动下载。

---

## P0-T04 Compose `core` 中间件

**目标：** 一条命令启动 PostgreSQL（含 TimescaleDB）、Redis、Nacos、Vault。

**实现要点：**
- `deploy/compose/docker-compose.yml`，按 profile 组织；本任务只实现 `core`：PostgreSQL 18 + TimescaleDB、Redis 8、Nacos 3.x（单机模式，开启鉴权）、Vault（dev 模式）。Keycloak 在 P1 加入。
- 所有镜像固定到具体版本标签，并确认支持 `linux/arm64`。
- 内存限制按 design.md 10.1：PostgreSQL `shared_buffers=128MB`、Redis `maxmemory 128mb`、Nacos 堆 384MB。
- PostgreSQL 初始化脚本 `deploy/compose/postgres/init/`：创建各服务的数据库用户和 schema（account、market、order、ledger、strategy、audit、keycloak、apicurio），并启用 `timescaledb` 扩展（仅 market schema 使用）。
- Nacos 使用 PostgreSQL 还是内置存储：使用 Nacos 单机模式内置存储（减少依赖）；数据目录挂载卷。
- 所有服务都配置健康检查。
- `scripts/dev.sh`：`up <profiles...>`、`down`、`stats`（`docker stats --no-stream`）、`logs <service>`、`reset`（删除卷，执行前要求确认）。

**产出：** `deploy/compose/docker-compose.yml`、`deploy/compose/postgres/init/*.sql`、`scripts/dev.sh`

**验证：**
```bash
scripts/dev.sh up core
docker compose -f deploy/compose/docker-compose.yml ps        # 全部 healthy
psql -h localhost -U postgres -c '\dn'                         # 看到各服务 schema
psql -h localhost -U postgres -d crypto -c "select extversion from pg_extension where extname='timescaledb'"
scripts/dev.sh stats
```
浏览器打开 http://localhost:8848/nacos 登录控制台。

预期：4 个容器全部 healthy；schema 齐全；TimescaleDB 扩展可用；总内存占用约 1.3GB 以内。

**停止条件：** 找不到同时支持 PostgreSQL 18、TimescaleDB 和 arm64 的镜像；或 Nacos 3.x 没有 arm64 镜像。报告可选的替代版本。

**Checklist：**
- [ ] 镜像全部固定版本且为 arm64
- [ ] 健康检查全部通过
- [ ] 各服务数据库用户只能访问自己的 schema（用 `account` 用户访问 `order` schema 应被拒绝）
- [ ] Nacos 控制台可登录
- [ ] `dev.sh` 各子命令可用
- [ ] 内存占用记录到 `docs/verification/memory.md`

---

## P0-T05 公共库：common-core、common-web、架构规则

**目标：** 建立后续所有服务共用的基础库和架构约束。

**实现要点：**
- `libs/common-core`：
  - 金额工具 `Money` / `Decimals`：基于 `BigDecimal`，提供按"最小单位"舍入（向下）、比较、校验非负；单元测试覆盖舍入边界。
  - 统一错误码枚举基类和业务异常基类。错误码是稳定的英文大写下划线编码，异常信息只用英文；后端不做国际化，由前端按错误码翻译（design.md 12.4）。
- `libs/common-web`：
  - 全局异常处理，统一返回 RFC 9457 Problem Details（含 `code`、`traceId` 字段；`title`、`detail` 只用英文）。
  - Jackson 3 配置：`BigDecimal` 序列化为字符串、不使用科学计数法；时间为 ISO-8601 UTC。
  - API 版本化配置（请求头 `API-Version`）。
- `libs/architecture-rules`：用 ArchUnit 实现 design.md 11.2 的通用规则（调用方向、`@Transactional` 只在 `service.impl`、`model` 纯净、禁止 `double`/`float` 表示金额字段——按字段名 `amount|qty|price|notional|balance|fee` 判断），提供给各服务的测试直接引用。
- `test-conventions` 插件中让所有服务的 `test` 套件自动依赖 `architecture-rules`。

**产出：** `libs/common-core`、`libs/common-web`、`libs/architecture-rules`

**验证：**
```bash
./gradlew :libs:common-core:test :libs:common-web:test :libs:architecture-rules:test
```
预期：全部通过。`architecture-rules` 自带一组反例测试（故意违反规则的样例类），证明规则能拦住违规代码。

**Checklist：**
- [ ] 金额舍入测试覆盖 0、最小单位、超出精度、负数
- [ ] Problem Details 格式有测试
- [ ] `BigDecimal` JSON 序列化为字符串有测试
- [ ] 每条 ArchUnit 规则都有一个反例测试

---

## P0-T06 示例服务 hello 与版本兼容性验证（design.md Q1 / A4）

**目标：** 用一个最小服务验证 Boot 4.1 + Spring Cloud 2025.1.x + Spring Cloud Alibaba 2025.1.0.0 能一起工作。

**实现要点：**
- `services/hello`（临时服务，P1-T09 删除），按 design.md 11.1 的目录结构：`controller/`、`service/`、`service/impl/`、`model/dto/`、`config/`。
- 接口：`GET /hello` 返回 Nacos 配置项 `hello.greeting` 的当前值。
- 接入 Nacos Discovery 与 Nacos Config（`spring.config.import=nacos:hello.yaml`，命名空间 `dev`）。
- 虚拟线程开启。
- IntelliJ 运行配置 `.run/hello.run.xml`。
- `http/hello.http`。

**产出：** `services/hello`、`.run/hello.run.xml`、`http/hello.http`

**验证：**
```bash
./gradlew :services:hello:bootRun
curl -s localhost:8090/hello
```
1. Nacos 控制台"服务列表"中看到 `hello`，状态健康。
2. 在 Nacos 控制台把 `hello.yaml` 中的 `hello.greeting` 改为新值，不重启服务，再次 `curl` 看到新值。
3. 启动日志中没有 Spring Cloud 兼容性校验失败。

**停止条件（重要）：** 出现以下任一情况，**停止 P0 后续任务**，向用户报告错误信息、尝试过的办法，以及可选方案（例如退回 Boot 4.0.x、等待 SCA 新版、关闭兼容性校验后手工验证）：
- 应用启动失败或兼容性校验报错；
- 服务无法注册到 Nacos；
- 配置无法动态刷新。

**Checklist：**
- [ ] 目录结构符合 design.md 11.1
- [ ] 注册成功
- [ ] 配置动态刷新成功
- [ ] 在 `docs/verification/compatibility.md` 记录实际版本组合与结论（对应 design.md Q1）

---

## P0-T07 最小可观测性：指标进入 Grafana

**目标：** 让服务指标从第一天起就能在 Grafana 里看到。

**实现要点：**
- `libs/common-observability` v0：引入 Actuator、Micrometer Prometheus；统一公共标签 `application`、`instance`；暴露 `health`、`info`、`prometheus` 端点；健康检查分组 `liveness`、`readiness`；结构化 JSON 日志（ECS 格式）。
- `hello` 依赖该库。
- Compose `obs` profile 本阶段只包含 **Prometheus 和 Grafana**（其余组件在 P8 加入），内存限制按 design.md 10.1。
- Prometheus 使用 `file_sd`：`deploy/compose/prometheus/targets/services.json` 列出 `host.docker.internal:<端口>`；本阶段只有 hello。
- Grafana 预置：Prometheus 数据源；"服务实例"大盘 v0（标题 `Service Instances`，面板名全部英文；按服务选择：JVM 堆 / 非堆、GC 次数与耗时、线程数、CPU、HTTP 请求量与延迟）。大盘 JSON 放在 `deploy/grafana/dashboards/`。
- Grafana 本阶段使用本地管理员账号（P1-T08 改为 Keycloak 单点登录）。

**产出：** `libs/common-observability`、Compose `obs`、`deploy/compose/prometheus/`、`deploy/grafana/`

**验证：**
```bash
scripts/dev.sh up core obs
./gradlew :services:hello:bootRun
curl -s localhost:8090/actuator/prometheus | head
for i in $(seq 1 50); do curl -s localhost:8090/hello >/dev/null; done
```
浏览器：http://localhost:9090/targets 中 hello 为 UP；http://localhost:3000 打开"服务实例"大盘（`Service Instances`），看到 hello 的 JVM 指标和刚才 50 次请求。

**Checklist：**
- [ ] Prometheus 目标为 UP
- [ ] 大盘以文件形式预置，重建容器后仍在
- [ ] 日志为 JSON 格式
- [ ] 内存占用更新到 `docs/verification/memory.md`

---

## P0-T08 代码质量工具

**目标：** 把格式、静态检查、覆盖率接入构建，从第一行代码开始执行。

**实现要点：**
- 在 `crypto-trading.java-conventions` 中加入：Spotless（Palantir Java Format）、Error Prone + NullAway（以 JSpecify 注解为准，只检查 `com.cryptotrading` 包）、JaCoCo（`test` 和 `integrationTest` 合并报告）。
- Lombok 与 MapStruct 注解处理器及 `lombok-mapstruct-binding` 的配置也放在这里（本阶段还没有使用，配置好即可）。
- `./gradlew build` 包含 `spotlessCheck`。
- 中文检查（design.md 12.4）：`scripts/check-no-chinese.sh` 扫描 `git ls-files` 列出的全部文件，发现中文字符（CJK 统一表意文字和中文标点）就打印"文件:行号"并以非 0 退出。例外只有 `*.md` 和 `web/src/locales/zh-CN.*`。根项目注册 `checkNoChinese` 任务调用该脚本，并挂到 `check` 上，使 `./gradlew build` 包含此检查；CI 随 `build` 一起执行。

**产出：** 更新后的 `build-logic`、`scripts/check-no-chinese.sh`、根 `build.gradle`

**验证：**
1. 故意在 hello 中写一段格式错误的代码，`./gradlew build` 失败并提示运行 `spotlessApply`；运行后通过。
2. 故意写一个可能返回 null 却未标注的方法，NullAway 报错；修复后通过。
3. `./gradlew jacocoTestReport` 生成报告，打开 `build/reports/jacoco/` 查看。
4. 故意在 hello 的注释里写一个中文字符，`./gradlew build` 失败并指出文件和行号；删除后通过。

**Checklist：**
- [ ] 格式错误会导致构建失败
- [ ] NullAway 生效
- [ ] 覆盖率报告可生成
- [ ] 中文检查生效（`zh-CN` 语言包和 Markdown 除外）
- [ ] 验证用的错误代码已删除

---

## P0-T09 持续集成

**目标：** 推送后由 GitHub Actions 自动构建和测试。

**实现要点：**
- `.github/workflows/ci.yml`：触发于 push 和 pull_request；Temurin 25；Gradle 缓存（`gradle/actions/setup-gradle`）；运行 `./gradlew build integrationTest`；上传测试和覆盖率报告。
- 集成测试使用 Testcontainers，在 GitHub 托管的 Linux 运行器上可直接运行 Docker。
- 只做构建与测试；镜像构建和推送在 P9 加入。

**产出：** `.github/workflows/ci.yml`

**验证：**
1. 本地用 `actionlint` 检查工作流文件无错误。
2. 告诉用户：推送到 `<github-owner>/crypto-trading` 后，在 GitHub Actions 页面确认工作流通过。

**Checklist：**
- [ ] `actionlint` 无错误
- [ ] 用户推送后工作流通过（由用户确认）

---

## P0-T10 阶段收尾：内存实测

**目标：** 记录本阶段的实际内存占用，为后续阶段提供依据（design.md Q10）。

**实现要点：**
- 启动 `core` + `obs` + hello，记录 `scripts/dev.sh stats` 与 hello 进程内存。
- 与 design.md 10.1 的估算值对比，写入 `docs/verification/memory.md`。

**验证：** `docs/verification/memory.md` 中有实测表格。

**停止条件：** 实测值比估算高出 30% 以上，向用户报告并给出调整建议（降低堆大小、减少组件或提前使用备用 EC2）。

**Checklist：**
- [ ] 实测表格完成
- [ ] 与估算的差异已说明

---

## P0 阶段验收

- [ ] `./gradlew build` 通过
- [ ] `scripts/dev.sh up core obs` 后全部容器 healthy
- [ ] hello 注册到 Nacos，配置动态刷新生效
- [ ] Grafana"服务实例"大盘显示 hello 的指标
- [ ] 兼容性结论已记录（Q1），若不兼容已由用户决定方案
- [ ] 内存实测已记录（Q10）
- [ ] CI 工作流已提交
