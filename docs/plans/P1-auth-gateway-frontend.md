# P1 登录、网关与前端骨架

**目标：** 打通"注册 → 登录 → 经网关调用后端 → 页面显示结果"的完整链路，建立前端骨架和后端服务的标准结构。

**前置条件：** P0 完成。

**阶段结束时能看到：**
- 浏览器打开 http://localhost:5173，点击登录跳转 Keycloak，自助注册后回到首页，右上角显示用户名；
- 设置页显示个人信息，可切换语言（中 / 英）、主题（亮 / 暗）、涨跌颜色（默认红涨绿跌），刷新后保留；
- http://localhost:8080/swagger-ui.html 用 Keycloak 登录后可直接调用 account 接口；
- Grafana 用 Keycloak 的管理员账号单点登录，普通用户被拒绝。

**需要的 Compose profile：** `core`、`obs`

---

## P1-T01 Keycloak 与 realm 配置

**目标：** 本地 Keycloak 可用，realm 配置以文件形式进入仓库。

**实现要点：**
- Compose `core` 加入 Keycloak 26.x（开发模式，使用 PostgreSQL 的 `keycloak` schema，堆 384MB），端口 8180。
- `deploy/keycloak/realm-crypto-trading.json`，启动时自动导入：
  - 开放自助注册，**不验证邮箱**；登录失败锁定策略开启。
  - Realm 角色 `user`（注册默认赋予）、`admin`。
  - Client `web`：public client，授权码 + PKCE，回调地址 `http://localhost:5173/*`、`http://localhost:8080/swagger-ui/*`、IntelliJ HTTP Client 回调地址；Web Origins 对应设置。
  - Client `grafana`：confidential client，回调 `http://localhost:3000/login/generic_oauth`，令牌中包含 realm 角色。
  - 访问令牌 5 分钟，刷新令牌 30 分钟。
  - 开启 realm 国际化：支持 `en`、`zh-CN`，默认 `en`；登录 / 注册页可切换语言，并根据前端传入的 `ui_locales` 参数显示对应语言（design.md 12.4）。使用 Keycloak 自带的翻译，不在仓库中写中文。
  - 预置两个**仅本地开发用**的账号：`admin`（角色 admin + user）、`alice`（角色 user）；初始密码从 `.env.local` 读取，导入脚本替换占位符。
- `scripts/keycloak-export.sh`：在控制台修改 realm 后导出为 JSON，方便回写仓库。

**产出：** Compose 更新、`deploy/keycloak/`、`scripts/keycloak-export.sh`

**验证：**
1. `scripts/dev.sh up core`，打开 http://localhost:8180，用 Keycloak 管理员登录，看到 `crypto-trading` realm。
2. 打开 `http://localhost:8180/realms/crypto-trading/account`，点击注册，用新邮箱注册成功并登录（无需验证邮件）。
3. 获取 OIDC 配置：`curl -s localhost:8180/realms/crypto-trading/.well-known/openid-configuration | jq .issuer`。

**Checklist：**
- [x] realm 从文件自动导入，删除卷重建后仍正确
- [x] 自助注册可用且不要求验证邮箱
- [x] 新注册用户自动拥有 `user` 角色
- [x] 开发账号密码不在仓库中
- [x] 内存占用更新到 `docs/verification/memory.md`

**说明（2026-11-26 完成时）：**
- 镜像 `quay.io/keycloak/keycloak:26.8.2`（26.x 最新，有 `linux/arm64`）。`start-dev --import-realm`，数据库 PostgreSQL `crypto` 库的 `keycloak` schema（用户 `keycloak`，P0-T04 已建好）。容器内也监听 8180（`KC_HTTP_PORT`），端口只绑定 `127.0.0.1`。健康检查：镜像里没有 curl / wget，用 bash 的 `/dev/tcp` 请求管理端口 9000 的 `/health/ready`。
- **issuer 固定。** `KC_HOSTNAME=http://localhost:8180` + `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`：令牌的 issuer 永远是 `http://localhost:8180/realms/crypto-trading`（浏览器和宿主机上的服务用这个地址），容器里的组件（P1-T08 的 Grafana）可以通过 `http://keycloak:8180` 做后端请求，issuer 不变。
- **密钥用占位符。** 计划写的是"导入脚本替换占位符"，实际用的是 Keycloak 导入时自带的环境变量替换：realm 文件里写 `${KEYCLOAK_DEV_ALICE_PASSWORD}` 这样的占位符，值由 Compose 从 `.env.local` 传给容器。实测：开发账号能用 `.env.local` 中的密码登录，用占位符字面量登录被拒绝；`grafana` 的 client secret 和 `.env.local` 一致。新增密钥键：`KEYCLOAK_ADMIN_PASSWORD`（master realm 管理员 `kc-admin`）、`KEYCLOAK_DEV_ADMIN_PASSWORD`、`KEYCLOAK_DEV_ALICE_PASSWORD`、`GRAFANA_OAUTH_CLIENT_SECRET`。master realm 管理员叫 `kc-admin`，避免和业务 realm 的开发账号 `admin` 混淆。
- realm 设置（`deploy/keycloak/realm-crypto-trading.json`，说明见同目录 README）：
  - 自助注册开启，不验证邮箱，可用用户名或邮箱登录；注册表单是 Keycloak 默认的（用户名、邮箱、名、姓、密码）。
  - 登录失败锁定：5 次失败后临时锁定，等待时间从 60 秒递增，最长 15 分钟；不永久锁定。具体数值计划没有规定，是我定的。
  - 默认角色 `default-roles-crypto-trading` 包含 `user`（以及 `account` 的 `view-profile`、`manage-account`，用于个人账户页）。Keycloak 导入时还会自动给默认角色加上 `offline_access`、`uma_authorization`，属于 Keycloak 默认行为，保留。
  - 访问令牌 300 秒；刷新令牌 30 分钟（SSO 会话空闲超时 1800 秒），会话最长 10 小时。
  - 国际化：`en`、`zh-CN`，默认 `en`；`ui_locales=zh-CN` 时登录页和注册页显示 Keycloak 自带的中文（"登录到 crypto-trading"），页面上有语言切换。仓库里没有写中文。
  - 开启了用户事件和管理事件（保留 7 天），供 design.md 5.9.1 的 Keycloak 审计使用（P8 经 Alloy 送入 Loki）。计划没有要求，属于提前打开开关。
  - `web`：public client，只开授权码流程，PKCE `S256`，回调地址 `http://localhost:5173/*`、`http://localhost:8080/swagger-ui/*`、`http://localhost:12345/http-client/callback`。最后一个是 IntelliJ HTTP Client 用的：JetBrains 文档没有写默认回调地址，所以在仓库里固定这个值，P1-T03 的 `http-client.env.json` 用同一个地址（`"Redirect URL"`，并设置 `"PKCE": true`）。
  - `grafana`：confidential client，回调 `http://localhost:3000/login/generic_oauth`；加了 realm 角色映射器，ID 令牌、访问令牌、userinfo 中都有 `roles` 声明。
  - 开发账号 `admin`（`admin` + `user`）、`alice`（`user`），邮箱 `@example.com`。
- **令牌里的角色。** 用 Keycloak 的"生成示例令牌"接口确认：`web` 的访问令牌有 `realm_access.roles`（P1-T02 的角色映射用它），`grafana` 的 ID 令牌和访问令牌有 `roles`。注意：Keycloak 26 的 `admin-cli` 默认签发"轻量访问令牌"，不带角色等声明，所以不能用 `admin-cli` 的令牌检查角色。
- `scripts/keycloak-export.sh`：在运行中的容器里执行 `kc.sh export`。必须加 `--health-enabled=false --metrics-enabled=false`，否则导出进程要占用管理端口 9000（已被运行中的服务占用），导出完成后报错退出。导出后用 jq 清理：删除签名 / 加密密钥（含私钥、HMAC / AES 密钥，导入时重新生成），客户端密钥换回占位符（`grafana` → `${GRAFANA_OAUTH_CLIENT_SECRET}`，其他客户端 → `${KEYCLOAK_CLIENT_SECRET_<ID>}` 并提示补到 `.env.example`），只保留 `admin`、`alice` 且密码换回占位符，删除自助注册的用户；清理后如果仍检测到密码哈希或私钥就不写文件。实测：导出结果中查不到 `.env.local` 里的任何实际值；把导出结果导入一个临时的 Keycloak 容器，导入成功。仓库里的 realm 文件仍是手写的精简版，导出版本包含 Keycloak 的全部默认值，会长很多。
- 验证：
  1. `scripts/dev.sh up core` 后 Keycloak 约 16 秒 healthy，日志 `Realm 'crypto-trading' imported`；`kc-admin` 登录后能看到 `master`、`crypto-trading` 两个 realm（通过管理 API 验证，和控制台相同）。
  2. 用 curl 走完真实的注册表单：授权码 + PKCE 请求 → 提交注册表单 → 直接重定向回 `http://localhost:5173/callback` 并带授权码（没有验证邮件步骤）→ 用 `code_verifier` 换到令牌，`email_verified: false`，`realm_access.roles` 含 `user`，有效期 300 秒。中文注册页（`ui_locales=zh-CN`）也注册成功。
  3. `curl -s localhost:8180/realms/crypto-trading/.well-known/openid-configuration | jq .issuer` → `"http://localhost:8180/realms/crypto-trading"`。
  - 登录失败锁定：对测试用户连续输错密码后，`attack-detection` 显示该用户 `disabled: true`（临时锁定）。
  - 删除卷重建：确认 PostgreSQL 中只有 `keycloak` schema 有表（其他 schema 为空）后，删除 PostgreSQL 数据卷重新启动：realm 重新导入，只剩 `admin`、`alice` 两个用户，设置全部正确，开发账号能登录，自助注册仍可用。
  - 验证 1、2 的控制台 / 个人账户页面没有在浏览器里手动点过，用的是相同的接口和表单。
- 内存：Keycloak 约 4 分钟后稳定在 537 MiB（anon），比估算 0.6 GB 低 13%，见 `docs/verification/memory.md`。

---

## P1-T02 公共库：安全与 MyBatis-Plus

**目标：** 准备所有业务服务共用的认证与数据访问配置。

**实现要点：**
- `libs/common-web` 增加安全配置：
  - OAuth2 Resource Server（JWT），`issuer-uri` 指向 Keycloak realm。
  - 自定义 `JwtAuthenticationConverter`：把 `realm_access.roles` 映射为 `ROLE_USER`、`ROLE_ADMIN`。
  - 工具类 `CurrentUser`：从 JWT 取 `sub` 作为用户 ID、取 `preferred_username`、`email`。
  - 默认规则：`/actuator/health/**`、`/v3/api-docs/**` 匿名可访问；`/api/admin/**` 需要 `ADMIN`；`/internal/**` 暂时拒绝所有（P4 开放给服务账号）；其余需要登录。
- `libs/common-mybatis`：
  - MyBatis-Plus 配置：分页、乐观锁、防全表更新删除插件。
  - 字段自动填充：`created_at`、`created_by`、`updated_at`、`updated_by`；执行者取当前用户 ID，无用户上下文时取 `system:<应用名>`。
  - 基础实体类 `BaseEntity`（四个审计字段 + `version`）。
  - **确认 MyBatis-Plus 在 Boot 4 下的测试切片注解**（design.md Q7），在 `docs/verification/compatibility.md` 记录；如果不存在专用注解，记录替代做法（例如 `@SpringBootTest` + Testcontainers）。

**产出：** `libs/common-web`、`libs/common-mybatis` 更新

**验证：**
```bash
./gradlew :libs:common-web:test :libs:common-mybatis:test
```
预期：JWT 角色映射、`CurrentUser`、自动填充均有测试并通过。

**Checklist：**
- [x] 角色映射测试覆盖 user、admin、无角色
- [x] 自动填充测试覆盖"有用户"和"系统任务"两种情况
- [x] Q7 结论已记录

**说明（2026-11-26 完成时）：**
- **`libs/common-web` 的安全配置**（`security/` 包，自动配置，服务只需依赖 common-web）：
  - common-web 以 `api` 依赖引入 `spring-boot-starter-security-oauth2-resource-server`。这样业务服务不会因为漏加依赖而变成没有安全保护；P1-T04 的 WebFlux gateway 本来也要用 Resource Server，它的响应式安全链自己配置，servlet 规则不会生效（按 servlet 应用条件启用）。
  - `issuer-uri` 默认是 `http://localhost:8180/realms/crypto-trading`（`SecurityDefaults`，最低优先级，服务配置或环境变量可以覆盖，P9 要覆盖）。公钥在收到第一个令牌时才去取，Keycloak 没起来时服务照样能启动。
  - `KeycloakJwtAuthenticationConverter`：`realm_access.roles` 里的每个角色都变成 `ROLE_<大写>`（`-` 换成 `_`），所以 `user` → `ROLE_USER`、`admin` → `ROLE_ADMIN`。Keycloak 自带的 `offline_access` 等角色也会映射，但没有规则用到它们。`scope` 照常映射成 `SCOPE_*`，留给 P4 的服务账号（`internal.ledger` 等）。principal 名称取 `sub`。另外 Spring Security 7 自己还会加一个 `FACTOR_BEARER` 权限（新的多因素认证模型），不影响角色判断。
  - `CurrentUser`（record）：`id`（`sub`）、`username`（`preferred_username`）、`email`（可能为空）。`CurrentUser.get()` 在没有登录用户时抛 `UNAUTHORIZED`；`find()` 返回 `Optional`；`from(Jwt)` 配合 `@AuthenticationPrincipal Jwt` 使用。
  - 默认规则按顺序是：
    - `OPTIONS` 预检请求放行；
    - `/actuator/health/**`、`/actuator/info`、`/actuator/prometheus`、`/v3/api-docs/**`、`/error` 匿名访问；
    - `/internal/**` 全部拒绝；
    - `/api/admin/**` 需要 `ADMIN`；
    - 其余请求需要登录。
    
    无状态，不建会话，关闭 CSRF（只用 Bearer 令牌）。`/actuator/prometheus` 和 `/actuator/info` 是计划之外新加的匿名路径：不放行的话，Prometheus 抓指标会拿到 401。这是用户的决定（方案"匿名放行"），已写进 design.md 8.2。服务如果需要不同的规则，自己定义一个 `SecurityFilterChain` bean，默认的就不再生效。
  - 401 / 403 也返回 Problem Details（`ProblemDetailsSecurityHandler`，`code` 是 `UNAUTHORIZED` / `FORBIDDEN`，带 `traceId`）。安全失败发生在过滤器链里，到不了 `@RestControllerAdvice`，所以单独实现。RFC 6750 规定的 `WWW-Authenticate` 头仍由 Spring 设置；Spring Security 7 会在头里加 `resource_metadata="…/.well-known/oauth-protected-resource"`（RFC 9728），这个元数据端点本身也匿名可访问，是框架的默认行为。
  - **控制器切片测试同样生效：** common-web 把自己注册到了 `@WebMvcTest` 的自动配置列表（`META-INF/spring/org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest.imports`）。所以 `@WebMvcTest` 里也有安全规则、Problem Details 和 JSON 约定，P1-T03 直接用 spring-security-test 的 `jwt()` 即可（见 `WebMvcSliceTest`）。依赖是 `testImplementation 'org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test'`。
  - 原有的 `ProblemDetailsTest` 和 `ApiVersioningTest` 加了 `@WithMockUser`，因为示例接口现在需要登录。`hello` 的控制器测试加了 `jwt()`；`hello` 的 `/hello` 现在需要令牌。
- **`libs/common-mybatis`**（新模块，自动配置）：
  - 依赖 `mybatis-plus-spring-boot4-starter` 和 `mybatis-plus-jsqlparser`。从 3.5.9 起，分页插件和防全表更新删除插件都在后者里，不加就找不到这两个类。
  - 插件按 MyBatis-Plus 推荐的顺序注册：分页（`POSTGRE_SQL`）→ 乐观锁 → 防全表更新删除。
  - 自动填充 `AuditFieldsMetaObjectHandler`：
    - 插入时写 `createdAt`、`createdBy`、`updatedAt`、`updatedBy`，`version` 为空时设为 0；
    - 更新时写 `updatedAt`、`updatedBy`；
    - 总是覆盖已有值，调用方伪造不了审计字段。另一个原因是 MyBatis-Plus 的 `strictUpdateFill` 遇到已有值会跳过，查出来再更新的实体就不会刷新 `updatedAt`；
    - 时间截断到微秒（PostgreSQL `TIMESTAMPTZ` 的精度），使用 `Clock` bean（没有就用 UTC 系统时钟）。
  - 执行者：`ActorProvider`。默认实现读取 Spring Security 上下文的 principal 名称（也就是 `sub`）；没有登录用户或者是匿名用户时，返回 `system:<spring.application.name>`。P6 的 `AuditContext.runAsSystem("system:xxx")` 替换这个 bean 即可。common-mybatis 对 spring-security 是 `compileOnly`：classpath 上没有 Security 时，一律返回系统执行者。
  - `BaseEntity`：四个审计字段（`Instant` / `String`）加 `@Version Long version`，对应的 DDL 写在类注释里。`created_by` / `updated_by` 用 `VARCHAR(64)`，因为 `sub` 是 UUID 字符串。**限制：** 只有传实体的写操作会自动填充（`insert`、`updateById`、`update(entity, wrapper)`）；只用 wrapper 的更新和手写 SQL 要自己设置 `updated_*`。
- **Q7 结论：** MyBatis-Plus 3.5.18.1 提供 `mybatis-plus-spring-boot4-starter-test`，切片注解是 `@MybatisPlusTest`（`com.baomidou.mybatisplus.test.autoconfigure`）。配合 `@AutoConfigureTestDatabase(replace = NONE)`、Testcontainers PostgreSQL（`@ServiceConnection`，镜像和 Compose 相同）和 Flyway 建表，放在 `integrationTest` 里。common-mybatis 把自己注册到了 `@MybatisPlusTest` 的自动配置列表，所以切片测试里的插件和自动填充和服务里一样。详见 `docs/verification/compatibility.md`。
- 验证：`./gradlew :libs:common-web:test :libs:common-mybatis:test` 通过：common-web 47 个测试（角色映射 5、`CurrentUser` 6、安全规则 15、切片 3 等），common-mybatis 11 个（自动填充 7，覆盖有用户、系统任务、匿名用户、伪造字段等情况）。`./gradlew :libs:common-mybatis:integrationTest` 7 个测试通过（真实 PostgreSQL 上的填充、乐观锁、分页、防全表操作）。`./gradlew build` 通过。另外用真实 Keycloak 令牌（curl 走 alice / admin 的登录表单）调用 `hello`，结果如下：
  - 不带令牌：401 Problem Details；
  - alice 的令牌：200；篡改过的令牌：401；
  - alice 访问 `/api/admin/**`：403；admin 能通过安全检查；
  - admin 访问 `/internal/**`：403；
  - `/actuator/health`、`/prometheus` 匿名可访问：200；
  - 启动日志里没有 WARN / ERROR。

---

## P1-T03 account 服务：`/api/me` 与偏好设置

**目标：** 第一个正式业务服务，按标准结构实现。

**实现要点：**
- `services/account`，目录结构按 design.md 11.1（controller / service / service/impl / model/{entity,dto,enums} / mapper / converter / config / exception）。
- Flyway：`user_profile`（user_id 为 Keycloak `sub`，用户名、邮箱、状态 `ACTIVE`、创建时间等）、`user_preference`（语言、主题、涨跌颜色，默认 `RED_UP`）。
- 接口：
  - `GET /api/me`：用户首次访问时**幂等地**创建档案（并发两次请求只创建一条），返回档案和偏好。开户划拨资金在 P4 加入，本阶段在代码中留 `// P4: provision funds` 注释。
  - `PUT /api/me/preferences`：修改语言、主题、涨跌颜色；校验枚举值。
- 接入 Nacos Discovery / Config、`common-observability`、springdoc（声明 OAuth2 授权码 + PKCE 安全方案）。
- 测试：
  - 控制器切片测试（使用 spring-security-test 的 `jwt()` 模拟令牌）；
  - Mapper 测试（P1-T02 确定的方式）；
  - 集成测试：Testcontainers PostgreSQL，验证并发首次访问只创建一条档案；
  - ArchUnit 规则全部通过。
- `.run/account.run.xml`、`http/account.http`（使用 IntelliJ HTTP Client 的 OAuth2 授权码 + PKCE 配置，`http-client.env.json` 中只放非敏感配置）。
- Prometheus 目标文件加入 account（8081）。

**产出：** `services/account`、运行配置、HTTP 文件

**验证：**
```bash
./gradlew :services:account:build :services:account:integrationTest
./gradlew :services:account:bootRun
```
1. IntelliJ 打开 `http/account.http`，执行 `GET /api/me`，弹出 Keycloak 登录，用 alice 登录后返回档案；再执行一次，返回同一档案。
2. 执行 `PUT /api/me/preferences` 改为英文和暗色，再 `GET /api/me` 看到变化。
3. 不带令牌请求返回 401，Problem Details 格式。
4. Grafana"服务实例"大盘中出现 account。

**Checklist：**
- [ ] 目录结构符合 design.md 11.1
- [ ] 首次访问幂等创建（并发测试通过）
- [ ] 默认涨跌颜色为红涨绿跌
- [ ] 401 / 400 返回 Problem Details
- [ ] ArchUnit 通过
- [ ] Grafana 可见

---

## P1-T04 gateway 服务

**目标：** 所有浏览器请求经过网关：校验令牌、限流、路由。

**实现要点：**
- `services/gateway`（Spring Cloud Gateway Server WebFlux 版），端口 8080。
- 路由：`/api/me/**` → `lb://account`（通过 Nacos 发现）；路由表放 Nacos `gateway.yaml`，新增服务时只改配置。
- 作为 Resource Server 校验 JWT；校验通过后**原样转发 `Authorization` 头**（不使用 TokenRelay）。
- 按用户限流：RequestRateLimiter + Redis，`KeyResolver` 取 JWT 的 `sub`；默认每秒 20 个请求、突发 40（配置在 Nacos）。超限返回 429 Problem Details。
- CORS：允许 `http://localhost:5173`。
- 转发 `X-Forwarded-For`，供后续审计取客户端 IP。
- springdoc 聚合：Swagger UI 列出各服务的 OpenAPI 文档；Swagger UI 中可用 `web` client 以授权码 + PKCE 登录。
- Prometheus 目标加入 gateway。

**产出：** `services/gateway`、Nacos 初始配置 `deploy/nacos/`（启动时由脚本导入）

**验证：**
```bash
./gradlew :services:gateway:bootRun   # account 同时运行
curl -i localhost:8080/api/me                       # 401
# 用 http/gateway.http 带令牌请求              # 200
# http/gateway.http 中的压测请求连续发送 100 次   # 出现 429
```
浏览器打开 http://localhost:8080/swagger-ui.html，点 Authorize 登录，调用 `GET /api/me` 成功。

**Checklist：**
- [ ] 无令牌 401，有令牌 200
- [ ] 令牌原样转发到 account（account 日志中能看到同一用户）
- [ ] 按用户限流生效，且只影响该用户
- [ ] Swagger UI 聚合并可登录调用
- [ ] Nacos 配置脚本可重复执行

---

## P1-T05 前端骨架

**目标：** Vue 前端可登录，有统一的布局、主题和多语言。

**实现要点：**
- `web/`：Vite 8 + Vue 3.5 + TypeScript + pnpm。目录按 design.md 12.1（`modules/`、`shared/`、`locales/`、`router/`、`styles/`）。
- Tailwind CSS 4 + shadcn-vue（Reka UI）；`styles/tokens.css` 定义设计令牌：主色蓝、中性灰阶、涨跌色（按偏好切换红涨绿跌 / 绿涨红跌）、圆角（卡片 16px、按钮全圆角）、间距 4 的倍数；亮 / 暗两套主题；字体 Inter，数字使用 `tabular-nums`。视觉参考 Coinbase 风格，**不使用 Coinbase 的名称、Logo、字体**。
- vue-i18n：`zh-CN`、`en-US`，默认跟随浏览器语言；登录后以服务端偏好为准。规则见 design.md 12.4：
  - 所有页面的文案（菜单、按钮、表单校验、提示、空状态、错误信息、图表标题）都走语言包，组件里不写任何文案；数字、金额、日期用 `Intl` 按当前语言格式化。
  - 中文只能出现在 `web/src/locales/zh-CN.*`；`.vue`、`.ts`、测试和注释里只用英文。
  - 语言包的 key 用英文点号分层（如 `nav.market`、`errors.INSUFFICIENT_PLATFORM_FUNDS`）；加一个测试校验 `zh-CN` 与 `en-US` 的 key 完全一致。
  - 跳转 Keycloak 登录时带上 `ui_locales`，让登录页与当前语言一致。
- 认证：oidc-client-ts，授权码 + PKCE，静默续期；路由守卫；未登录访问受保护页面时跳转登录。
- HTTP 客户端：自动带令牌，401 时尝试续期一次；错误按 Problem Details 的 `code` 到语言包中取文案（`errors.<code>`），找不到时显示通用错误提示并附 `traceId`。
- Pinia（用户、偏好）+ TanStack Query（服务端数据）。
- 页面：顶部导航（Logo 用项目自己的文字标识、行情、交易、资产、订单、策略、设置；未实现的页面显示"即将推出"）、首页占位、设置页（个人信息、语言、主题、涨跌颜色，修改后调用 `PUT /api/me/preferences`）。
- 工具：ESLint + Prettier；Vitest + Vue Test Utils + MSW（模拟 `/api/me`）。

**产出：** `web/`

**验证：**
```bash
pnpm -C web install
pnpm -C web test
pnpm -C web build
pnpm -C web dev
```
浏览器打开 http://localhost:5173：
1. 点击登录 → Keycloak 注册新用户 → 回到首页，右上角显示用户名。
2. 设置页切换英文、暗色、绿涨红跌，刷新页面后保持；切换语言后所有页面（含导航、"即将推出"占位、错误提示）都变成对应语言，登录页也跟着变。
3. 退出登录后访问设置页，自动跳到登录。

**Checklist：**
- [ ] 目录结构符合 design.md 12.1
- [ ] 登录、退出、静默续期可用
- [ ] 中英文切换完整（无硬编码文案），`zh-CN` 与 `en-US` 的 key 一致性测试通过
- [ ] 除 `web/src/locales/zh-CN.*` 外，`web/` 中没有中文（`scripts/check-no-chinese.sh` 通过）
- [ ] 亮 / 暗主题、涨跌颜色切换生效并持久化到服务端
- [ ] 单元测试通过，构建无警告

---

## P1-T06 前端接入 Gradle 与 API 类型生成

**目标：** 一条 Gradle 命令同时构建前后端；前端类型从后端 OpenAPI 自动生成。

**实现要点：**
- `web/build.gradle` 使用 `com.github.node-gradle.node` 插件：`./gradlew :web:build` 执行 `pnpm install --frozen-lockfile`、`pnpm test`、`pnpm build`。
- `pnpm gen:api`：用 openapi-typescript 从 gateway 聚合的 OpenAPI 文档生成 `shared/api/types.ts`（需要服务在运行），生成结果提交到仓库。
- `settings.gradle` 加入 `web`。

**验证：**
```bash
./gradlew build          # 包含 web 的测试与构建
pnpm -C web gen:api      # 服务运行时
git diff --stat web/src/shared/api/types.ts
```

**Checklist：**
- [ ] `./gradlew build` 包含前端
- [ ] 类型生成可用，前端调用 `/api/me` 使用生成的类型

---

## P1-T07 E2E 冒烟测试

**目标：** 用一个端到端测试覆盖登录主流程，之后每个阶段往里加场景。

**实现要点：**
- `web/e2e/`，Playwright；测试前提：Compose `core` 与 gateway、account、web 已启动。
- 场景：注册新用户 → 登录 → 设置页显示该用户邮箱 → 切换语言 → 退出。
- `pnpm -C web e2e` 运行；CI 中暂不运行（P8 后加入每日任务）。

**验证：** `pnpm -C web e2e` 通过，失败时生成截图和录像。

**Checklist：**
- [ ] 冒烟测试本地通过
- [ ] 失败时有截图

---

## P1-T08 Grafana 单点登录

**目标：** Grafana 改为 Keycloak 登录，只有管理员能进入。

**实现要点：**
- Grafana Generic OAuth 指向 Keycloak `grafana` client；`role_attribute_path` 把 `admin` 角色映射为 Editor；**严格模式**：没有 `admin` 角色的用户拒绝登录。
- 关闭 Grafana 本地登录表单（保留 Grafana 内置管理员仅作应急，密码在 `.env.local`）。

**验证：**
1. 用 `admin` 账号登录 Grafana 成功，可编辑大盘。
2. 用 `alice` 登录被拒绝。

**Checklist：**
- [ ] 管理员可登录
- [ ] 普通用户被拒绝
- [ ] 本地登录表单已隐藏

---

## P1-T09 清理示例服务

**目标：** 删除 P0 的 hello 服务。

**实现要点：** 删除 `services/hello`（以及 `settings.gradle` 中的 include）、运行配置 `.run/hello.run.xml`、HTTP 文件 `http/hello.http`、Prometheus 目标与 Nacos 配置（初始配置 `deploy/nacos/dev/hello.yaml`，以及 Nacos 命名空间 `dev` 中已发布的 `hello.yaml`）。

**验证：** `./gradlew build` 通过；Prometheus 目标中不再有 hello。

**Checklist：**
- [ ] 所有 hello 相关文件已删除

---

## P1 阶段验收

- [ ] 浏览器可注册、登录、退出
- [ ] 设置页修改语言、主题、涨跌颜色并持久化
- [ ] 所有请求经过 gateway，未登录 401，超频 429
- [ ] Swagger UI 可登录调用
- [ ] Grafana 只允许管理员单点登录
- [ ] E2E 冒烟测试通过
- [ ] `./gradlew build` 通过（含前端）
