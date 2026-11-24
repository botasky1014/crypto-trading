# 版本兼容性验证

本文件记录版本组合的实测结论，对应 design.md 2.2 的 A4 和第 15 节的 Q1。

## P0-T06：Boot 4.1 + Spring Cloud 2025.1 + Spring Cloud Alibaba 2025.1（2026-11-23）

**结论：兼容，A4 成立。** 示例服务 `hello` 启动正常、注册到 Nacos、配置可以动态刷新，启动日志里没有 Spring 或 Nacos 的 WARN / ERROR，Spring Cloud 的兼容性校验通过。不需要退回 Boot 4.0.x。

### 实际版本组合

| 组件 | 版本 | 来源 |
| --- | --- | --- |
| JDK | Temurin 25.0.5.1 | Gradle Toolchain |
| Spring Boot | 4.1.3 | `libs.versions.toml` |
| Spring Framework | 7.0.11 | Boot BOM |
| Spring Cloud | 2025.1.5（`spring-cloud-commons` / `spring-cloud-context` 5.0.5） | Spring Cloud BOM |
| Spring Cloud Alibaba | 2025.1.0.0（`spring-cloud-starter-alibaba-nacos-discovery` / `-config`） | SCA BOM |
| Nacos 客户端 | 3.1.1 | SCA BOM |
| Nacos 服务端 | 3.3.0（`nacos/nacos-server:v3.3.0`，单机，开启鉴权） | Compose `core` |
| Tomcat | 11.0.27 | Boot BOM |
| Jackson | 3.1.8 | Boot BOM |

SCA 2025.1.0.0 的 starter 声明依赖 `spring-cloud-commons` / `spring-cloud-context` 5.0.0，Gradle 按"取最高版本"解析为 Spring Cloud 2025.1.5 对应的 5.0.5（`./gradlew :services:hello:dependencies --configuration runtimeClasspath` 中显示 `5.0.0 -> 5.0.5`）。没有被降级。

Nacos 客户端 3.1.1 比服务端 3.3.0 旧，属于官方支持的"旧客户端连新服务端"，通过 gRPC（9848）连接正常。

### 验证过程

前提：`scripts/dev.sh up core`。它会创建命名空间 `dev`，并发布初始配置 `deploy/nacos/dev/hello.yaml`（`hello.greeting: Hello from Nacos`）。

| 检查项 | 方法 | 结果 |
| --- | --- | --- |
| 启动 | `./gradlew :services:hello:bootRun` | `Started HelloApplication in 1.22 seconds`；没有 Spring 或 Nacos 的 WARN / ERROR（JDK 的 `Unsafe` 提示见下文） |
| 兼容性校验确实生效 | 用 `--spring.cloud.compatibility-verifier.compatible-boot-versions=3.5.x` 启动 | 启动失败：`Spring Boot [4.1.3] is not compatible with this Spring Cloud release train`。说明校验在运行，正常启动时是通过了校验，而不是没有执行 |
| 读取 Nacos 配置 | `curl -s localhost:8090/hello` | `{"greeting":"Hello from Nacos"}` |
| 服务注册 | 日志 `nacos registry, DEFAULT_GROUP hello 192.168.188.153:8090 register finished`；Nacos API `GET /nacos/v3/admin/ns/instance/list?namespaceId=dev&serviceName=hello` | 实例 `healthy: true`、`ephemeral: true`。控制台 http://localhost:8849 的"服务列表"（命名空间 `dev`）中同样可以看到 |
| 动态刷新 | 不重启服务，通过 Nacos API（与控制台"编辑配置"相同）把 `hello.greeting` 改为 `Changed without restart`，再 `curl` | 约 1 秒内返回新值；日志 `Refresh keys changed: [hello.greeting]` |
| 虚拟线程 | `spring.threads.virtual.enabled: true` | 请求由 `tomcat-handler-*` 虚拟线程处理 |

验证结束后已把 `hello.greeting` 恢复为初始值。

### 实现上需要注意的地方

- **动态刷新用可变的 `@ConfigurationProperties` 类。** Spring Cloud 收到配置变化后，会对 `@ConfigurationProperties` bean 重新绑定。重新绑定靠 setter，用构造器绑定的 record 不会更新，要么改用普通类，要么加 `@RefreshScope`。`hello` 用的是普通类（`HelloProperties`）。
- **单元测试里关掉 Nacos。** `spring.config.import: nacos:hello.yaml` 是必选导入，Nacos 不可用时启动直接失败（这是想要的效果）。不连 Nacos 的切片测试（`@WebMvcTest`）要设置 `spring.cloud.nacos.config.enabled=false`、`spring.cloud.nacos.discovery.enabled=false`、`spring.config.on-not-found=ignore`。
- **Nacos 鉴权。** 服务端开启了鉴权。服务使用最小权限账号 `svc-dev`（配置只读、注册读写），密码是 `.env.local` 的 `NACOS_SERVICE_PASSWORD`，由 `bootRun` 自动读取。账号划分和权限实测见 P0-T06 的说明。改用 `svc-dev` 后重新验证过：启动、注册（`healthy: true`）、动态刷新都正常。
- **JDK 的 `sun.misc.Unsafe` 提示。** 启动时 stderr 有 4 行 `WARNING: A terminally deprecated method in sun.misc.Unsafe has been called ...`，来自 nacos-client 内部打包的 Guava（`com.alibaba.nacos.shaded.com.google.common...`）。这是 JDK 25 对已弃用 API 的提示，不影响运行，也不是 Spring Cloud 兼容性问题。将来某个 JDK 版本移除这些方法时，需要 nacos-client 升级；P10 升级时留意。
