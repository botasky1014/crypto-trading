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

3. 后续的构建、中间件和服务启动命令随各阶段逐步加入，见 [docs/plan.md](docs/plan.md) 第 5 节。

## 目录

| 目录 | 用途 |
| --- | --- |
| `docs/` | 设计、计划、环境准备、验证记录 |
| `scripts/` | 开发脚本（`doctor.sh`，以及后续的 `dev.sh`） |
| `deploy/compose/` | 本地 Docker Compose 中间件 |
| `http/` | IntelliJ HTTP Client 请求文件（`*.http`） |
| `.run/` | 共享的 IntelliJ 运行配置 |

## 密钥

密钥只放在 `.env.local`（已被 git 忽略）或 Vault，不要提交到仓库。
