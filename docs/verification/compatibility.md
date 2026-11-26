# 版本兼容性验证

本文件记录版本组合的实测结论，对应 design.md 2.2 的 A4 和第 15 节的 Q1、Q7。

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

## P1-T02：MyBatis-Plus 测试切片（Q7）与 Spring Security 7（2026-11-26）

**结论：MyBatis-Plus 3.5.18.1 有 Boot 4 专用的测试切片，注解是 `@MybatisPlusTest`**（包 `com.baomidou.mybatisplus.test.autoconfigure`），在 `mybatis-plus-spring-boot4-starter-test` 里。用不着 `@SpringBootTest` 这种替代做法。

### MyBatis-Plus 的 Boot 4 模块

| 用途 | artifact（版本由 `mybatis-plus-bom` 管理） |
| --- | --- |
| 运行时 | `com.baomidou:mybatis-plus-spring-boot4-starter`（依赖 MyBatis 3.5.19、mybatis-spring 4.1.0） |
| 分页、防全表更新删除插件 | `com.baomidou:mybatis-plus-jsqlparser`（JSqlParser 5.4）。3.5.9 起这两个插件从核心包移到这里，不加这个依赖就找不到类 |
| 测试切片 | `com.baomidou:mybatis-plus-spring-boot4-starter-test`（`@MybatisPlusTest`、`@AutoConfigureMybatisPlus`） |

`mybatis-plus-boot-starter`、`mybatis-plus-spring-boot3-starter` 是 Boot 2 / 3 用的，不要引错。

### Mapper 测试的写法（各服务照此编写）

```java
@MybatisPlusTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class XxxMapperTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:2.32.1-pg18").asCompatibleSubstituteFor("postgres"));
    ...
}
```

- 放在 `integrationTest` 测试套件里（需要 Docker）。依赖有 `mybatis-plus-spring-boot4-starter-test`、`spring-boot-testcontainers`、`testcontainers-junit-jupiter`、`testcontainers-postgresql`（Testcontainers 2.0.5 的类是 `org.testcontainers.postgresql.PostgreSQLContainer`），运行时还要 `postgresql` 和 `flyway-database-postgresql`。
- 切片会导入 DataSource、事务、MyBatis-Plus 和 Flyway（可选）的自动配置。表结构由服务自己的 Flyway 脚本创建，和生产一致。每个测试在一个事务里执行，结束后回滚。
- 必须写 `@AutoConfigureTestDatabase(replace = NONE)`，否则切片会去找嵌入式数据库。
- 测试源码里要有一个 `@SpringBootConfiguration`（服务的启动类就行），切片从它所在的包开始扫描 `@Mapper`。
- common-mybatis 把自己的自动配置注册给了 `@MybatisPlusTest`（`META-INF/spring/com.baomidou.mybatisplus.test.autoconfigure.AutoConfigureMybatisPlus.imports`），所以切片里的插件和审计字段自动填充和服务里一样。
- 实测（`libs/common-mybatis` 的 `SampleItemMapperTest`，7 个测试）：
  - 插入、更新时自动填充；
  - `TIMESTAMPTZ` 和 `Instant` 互相转换不丢失精度（微秒）；
  - `NUMERIC(38,18)` 和 `BigDecimal` 互相转换正确；
  - 乐观锁冲突时返回 0 行；
  - 分页的总数和偏移正确；
  - 全表 `UPDATE` / `DELETE` 被拒绝。
  
  日志里没有 MyBatis-Plus、Flyway、Testcontainers 的警告（只有 JDK 提示 Mockito 动态加载了 agent）。
- **注意一级缓存：** 同一个事务（同一个 SqlSession）里对同一行执行两次 `selectById`，返回的是同一个对象。所以测试乐观锁冲突时，要自己构造一个持有旧 `version` 的实体，模拟另一个写入者；再查一次不能得到"旧数据"。

### Spring Security 7（Boot 4.1.3 自带）的行为变化

这些变化在写测试和排查问题时会碰到：

- **`FACTOR_BEARER` 权限。** 用 Bearer 令牌认证后，除了 `ROLE_*` / `SCOPE_*`，Spring Security 7 还会自动加一个 `FACTOR_BEARER` 权限，标记这次认证用的是哪种因素（7.0 的多因素认证模型）。`hasRole` 判断不受影响；断言权限列表时要把它过滤掉。
- **`WWW-Authenticate` 带资源元数据地址。** 401 响应头是 `Bearer resource_metadata="http://<host>/.well-known/oauth-protected-resource"`（RFC 9728）。框架会匿名提供这个元数据端点，内容是 `resource`、`bearer_methods_supported` 等，不含密钥。
- **`AuthenticationEntryPoint` / `AccessDeniedHandler` 会抛 `ServletException`。** 委托给 `BearerTokenAuthenticationEntryPoint` 的实现需要声明这个异常。
- Boot 4 的 starter 名称：运行时用 `spring-boot-starter-security-oauth2-resource-server`，测试用 `spring-boot-starter-security-oauth2-resource-server-test`（里面带 spring-security-test，提供 `jwt()`、`@WithMockUser`）。`@WebMvcTest` 会导入 `OAuth2ResourceServerAutoConfiguration`。
- 用真实 Keycloak 26.8.2 的令牌测过（`hello`，`issuer-uri` 取默认值）：签名校验、issuer 校验、角色映射都正常，启动日志里没有 WARN（也没有 "generated security password"）。
