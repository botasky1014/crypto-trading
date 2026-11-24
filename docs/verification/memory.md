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

## P0-T07：Compose `core` + `obs`（Prometheus、Grafana）+ hello（2026-11-24）

环境同上。`scripts/dev.sh up core obs`，hello 用 `bootRun` 在宿主机运行（`-Xmx256m -XX:+UseSerialGC`），发出 100 次 `/hello` 请求，Grafana 重建后稳定约 2 分钟读取。

**读数说明：** `docker stats` 的 MEM USAGE 包含容器内仍活跃的页缓存（读过的镜像文件、数据文件等）。这部分内存在不够时可以被内核回收，不会导致 OOM。所以本表同时列出 cgroup `memory.stat` 中的 `anon`（进程自己申请的内存），它才是真正的占用。P0-T04 的表只有 `docker stats` 读数。

| 组件 | `docker stats` | 其中 anon（实际占用） | 容器上限 | design.md 10.1 估算 | 说明 |
| --- | --- | --- | --- | --- | --- |
| postgres | 88 MiB | 14 MiB | 512 MiB | 0.4 GB | 空库 |
| redis | 32 MiB | 15 MiB | 256 MiB | 0.15 GB | 空库 |
| nacos | 819 MiB | 687 MiB | 1 GiB | 0.6 GB | 和 P0-T04 的 663–691 MiB 一致；多出的是页缓存 |
| vault | 193 MiB | 12 MiB | 256 MiB | 0.1 GB | 几乎全是页缓存（P0-T04 读到 46 MiB 是因为刚启动） |
| prometheus | 47 MiB | 54 MiB | 384 MiB | 可观测性合计 1.3 GB | 只有 hello 一个目标；保留 2 天 |
| grafana | 362 MiB | 353 MiB | 512 MiB | （同上） | 刚启动时约 240 MiB，打开大盘、执行查询后升到约 350 MiB |
| **容器合计** | **约 1.54 GiB** | **约 1.11 GiB** | | | |
| hello（宿主机进程） | — | RSS 112 MiB（JVM 已用堆 + 非堆 64 MiB） | 堆上限 256 MiB | 每个服务约 0.4 GB（8 个服务约 3.2 GB） | 低于估算 |

**和估算的差异：**
- **Grafana 13 比预想的重。** 一开始给了 384 MiB 上限，刚启动 anon 就到 237 MiB、`docker stats` 到 350 MiB，余量不够，所以把上限提高到 **512 MiB**。Prometheus + Grafana 实际约 0.4 GB，P8 还要加入 OTel Collector、Tempo、Loki、Alloy，可观测性合计 1.3 GB 的估算目前看够用，P8 再复测。
- Nacos 仍比估算高约 15%，原因和调优见上一节，没有变化。
- 总体：容器实际占用约 1.1 GiB，加上 hello 约 1.2 GiB，没有超过估算，也没有触发 P0-T10 的停止条件（高出 30% 以上）。P0-T10 再做一次完整记录。
