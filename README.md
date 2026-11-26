# crypto-trading

学习用的加密货币交易微服务项目：Spring Boot / Spring Cloud 后端 + Vue 前端，接入 Alpaca **加密货币现货模拟盘**（禁止连接 live 环境）。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/design.md](docs/design.md) | 系统设计（唯一依据） |
| [docs/plan.md](docs/plan.md) | 实施总览：执行规则、端口约定、关键场景、进度 |
| [docs/plans/](docs/plans/) | 各阶段（P0–P10）的任务、验证步骤和停止条件 |
| [docs/setup/macos.md](docs/setup/macos.md) | macOS 本机环境准备 |
| [docs/verification/](docs/verification/) | 验证记录（版本兼容性、内存实测等） |
| [CLAUDE.md](CLAUDE.md) | 给 AI 编码助手的工作规则 |

## 快速开始

1. 按 [docs/setup/macos.md](docs/setup/macos.md) 准备本机环境。
2. 运行环境自检，所有必需项应为 OK：

   ```bash
   scripts/doctor.sh
   ```

3. 构建，并启动本地中间件（PostgreSQL、Redis、Nacos、Vault、Keycloak）：

   ```bash
   ./gradlew build
   scripts/dev.sh up core
   ```

   首次运行会在 `.env.local` 中生成本地密码。Nacos 控制台：http://localhost:8849（用户 `nacos`，密码见 `.env.local` 的 `NACOS_ADMIN_PASSWORD`）。

   Keycloak：http://localhost:8180（管理员 `kc-admin`，密码见 `.env.local` 的 `KEYCLOAK_ADMIN_PASSWORD`），realm `crypto-trading` 自动导入，开发账号见 [deploy/keycloak/README.md](deploy/keycloak/README.md)。`up core` 同时会创建 Nacos 命名空间 `dev`，发布 `deploy/nacos/` 中的初始配置（已存在的不覆盖），并创建服务用的 Nacos 账号 `svc-dev`、`config-admin`（密码同样在 `.env.local`）。

4. 启动示例服务 `hello`（临时服务，P1 删除），确认能读到 Nacos 配置：

   ```bash
   ./gradlew :services:hello:bootRun
   curl -s localhost:8090/hello
   ```

   `bootRun` 会读取 `.env.local` 中的密钥。IntelliJ 里可以直接用运行配置 `hello`（`.run/hello.run.xml`）。

5. 查看指标：

   ```bash
   scripts/dev.sh up core obs
   ```

   Prometheus：http://localhost:9090/targets；Grafana：http://localhost:3000（用户 `admin`，密码见 `.env.local` 的 `GRAFANA_ADMIN_PASSWORD`），首页就是 `Service Instances` 大盘。

6. 其他命令见 [docs/plan.md](docs/plan.md) 第 5 节。

## 目录

| 目录 | 用途 |
| --- | --- |
| `docs/` | 设计、计划、环境准备、验证记录 |
| `scripts/` | 开发脚本（`doctor.sh`、`dev.sh`） |
| `build-logic/`、`platform/` | Gradle 约定插件、版本平台 |
| `libs/` | 公共库（`common-core`、`common-web`、`common-mybatis`、`common-observability`、`architecture-rules`） |
| `services/` | 微服务（目前只有临时示例服务 `hello`） |
| `deploy/compose/` | 本地 Docker Compose 中间件 |
| `deploy/keycloak/` | Keycloak realm 配置（启动时自动导入） |
| `deploy/nacos/` | Nacos 初始配置（`<命名空间>/<Data ID>`） |
| `deploy/grafana/` | Grafana 数据源与大盘（启动时自动导入） |
| `http/` | IntelliJ HTTP Client 请求文件（`*.http`） |
| `.run/` | 共享的 IntelliJ 运行配置 |

## 密钥

密钥只放在 `.env.local`（已被 git 忽略）或 Vault，不要提交到仓库。
