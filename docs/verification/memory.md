# 内存实测

本文件记录各阶段实测的内存占用，和 design.md 10.1 的估算对比（对应 design.md Q10）。数据来自 `scripts/dev.sh stats`（`docker stats --no-stream`），容器启动并稳定约 1 分钟后读取。

## P0-T04：Compose `core`（2026-11-23）

环境：Apple 芯片 Mac 16GB，Docker Desktop 29.9.3（内存 10GB），镜像均为 `linux/arm64`。

| 容器 | 镜像 | 主要配置 | 实测 | 容器上限 | design.md 10.1 估算 | 差异 |
| --- | --- | --- | --- | --- | --- | --- |
| postgres | `timescale/timescaledb:2.32.1-pg18` | `shared_buffers=128MB`，`max_connections=200` | 47 MiB | 512 MiB | 0.4 GB | 远低于估算（空库，没有连接） |
| redis | `redis:8.8.4-alpine` | `maxmemory 128mb`，`noeviction`，AOF | 10 MiB | 256 MiB | 0.15 GB | 远低于估算（空库） |
| nacos | `nacos/nacos-server:v3.3.0` | 堆 384MB，见下方说明 | 663 MiB | 1 GiB | 0.6 GB | **高约 10%** |
| vault | `hashicorp/vault:2.2.2` | dev 模式，内存存储 | 46 MiB | 256 MiB | 0.1 GB | 低于估算 |
| **合计** | | | **约 766 MiB** | | **约 1.25 GB** | 低于估算；计划目标"约 1.3GB 以内"满足 |

PostgreSQL 和 Redis 目前是空库，接入服务后会上涨，P0-T10 和后续阶段再复测。

### Nacos 的内存调优

Nacos 3.3 在一个 JVM 里同时运行服务端（8848）和控制台（8849），线程约 270 个。只设置堆 384MB 时，进程 RSS 稳定在约 **760 MiB**，几乎顶满当时 768 MiB 的容器上限。原因和处理：

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 非堆内存不受限 | 镜像启动脚本在单机模式下只设置堆参数；`JAVA_OPT_EXT` 被放在 `-jar` 之后，成了程序参数 | 改用 `JAVA_TOOL_OPTIONS`：`-XX:+UseSerialGC -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m -Xss512k` |
| glibc malloc 区域约 150 MiB | 线程多，每个线程可能分到一个 malloc arena | `MALLOC_ARENA_MAX=2` |
| 加载了 AI 注册中心模块 | 默认 `FUNCTION_MODE=all` | `FUNCTION_MODE=microservice`（只要注册与配置） |

调整后稳定在约 **663–691 MiB**，容器上限放宽到 1 GiB 留余量。比估算高约 10–15%，没有超过 P0-T10 停止条件的 30%。
