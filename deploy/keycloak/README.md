# Keycloak realm

`realm-crypto-trading.json` 是 realm `crypto-trading` 的完整配置（design.md 8.1），Compose `core` 启动 Keycloak 时自动导入（`start-dev --import-realm`）。

- **只在 realm 不存在时导入。** 已存在的 realm 不会被文件覆盖（`IGNORE_EXISTING`）。要让文件里的修改生效，可以在管理控制台删除 realm 后重启 Keycloak，或删除 PostgreSQL 数据卷（Keycloak 的数据在 `keycloak` schema 中）。
- **文件里没有密钥。** `${KEYCLOAK_DEV_ADMIN_PASSWORD}`、`${KEYCLOAK_DEV_ALICE_PASSWORD}`、`${GRAFANA_OAUTH_CLIENT_SECRET}` 是占位符，导入时 Keycloak 从容器环境变量（来自 `.env.local`）替换。签名密钥不放在文件里，导入时自动生成。
- **在控制台改了配置后**，用 `scripts/keycloak-export.sh` 导出回这个文件，再看 `git diff`。脚本会删除签名密钥、把客户端密钥和开发账号密码换回占位符、去掉自助注册的用户。导出文件包含 Keycloak 的全部默认值，比手写的版本长很多，这是正常的。

| 账号 | Realm | 角色 | 密码（`.env.local`） | 用途 |
| --- | --- | --- | --- | --- |
| `kc-admin` | `master` | Keycloak 管理员 | `KEYCLOAK_ADMIN_PASSWORD` | 管理控制台 http://localhost:8180 |
| `admin` | `crypto-trading` | `admin`、`user` | `KEYCLOAK_DEV_ADMIN_PASSWORD` | 仅本地开发：平台管理员 |
| `alice` | `crypto-trading` | `user` | `KEYCLOAK_DEV_ALICE_PASSWORD` | 仅本地开发：普通用户 |

| Client | 类型 | 回调地址 |
| --- | --- | --- |
| `web` | public，授权码 + PKCE（S256） | `http://localhost:5173/*`（前端）、`http://localhost:8080/swagger-ui/*`（Swagger UI）、`http://localhost:12345/http-client/callback`（IntelliJ HTTP Client，P1-T03 的 `http-client.env.json` 使用同一地址） |
| `grafana` | confidential | `http://localhost:3000/login/generic_oauth`；令牌（ID、访问、userinfo）带 `roles` 声明，供 P1-T08 判断 `admin` |
