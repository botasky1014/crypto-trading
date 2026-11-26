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

## P0-T10：阶段收尾实测（2026-11-24）

**条件：** `scripts/dev.sh up core obs` 的 6 个容器已连续运行约 3 小时（稳态）；hello 用 `bootRun` 在宿主机运行（`-Xmx256m -XX:+UseSerialGC`），先发 1,000 次请求预热，之后每分钟读一次、读之前再发 200 次请求，共读 4 次。4 次读数几乎一样，下表取最后一次（Vault 的页缓存在 34–137 MiB 之间波动，取最大值）。Docker Desktop 分配内存 10 GiB。

**读数说明：** `docker stats` 包含容器内活跃的页缓存，内存紧张时可被内核回收；`anon` 是进程自己申请的内存（cgroup `memory.stat`），代表真实占用。两列都列出，和估算的差异两种口径都算。

### 实测与估算对比

| 组件 | 配置 | `docker stats` | anon（实际） | design.md 10.1 估算 | 差异（stats / anon） |
| --- | --- | --- | --- | --- | --- |
| postgres | `shared_buffers=128MB` | 89 MiB | 16 MiB | 0.4 GB | −78% / −96% |
| redis | `maxmemory 128mb` | 32 MiB | 14 MiB | 0.15 GB | −79% / −91% |
| nacos | 堆 384MB，见 P0-T04 调优 | 845 MiB | 712 MiB | 0.6 GB | **+38%** / +16% |
| vault | dev 模式 | 135 MiB | 8 MiB | 0.1 GB | **+32%** / −92% |
| hello（宿主机进程） | 堆上限 256 MiB | RSS 234 MiB | 同左 | 约 0.4 GB（3.2 GB / 8） | −43% |
| **以上合计** | | **1,335 MiB** | **984 MiB** | **约 1.65 GB** | **−21% / −42%** |
| prometheus | 保留 2 天，1 个服务目标 | 59 MiB | 54 MiB | 可观测性 6 个组件合计 1.3 GB | — |
| grafana | 13.3.1 | 387 MiB | 380 MiB | （同上） | — |
| **可观测性小计（6 个中的 2 个）** | | **446 MiB** | **434 MiB** | 1.3 GB | 已用约 1/3 |

hello 的 JVM：堆已用 81 MiB、已提交 249 MiB（SerialGC 的初始堆按物理内存的 1/64 算，16GB 机器正好约 256 MiB，所以一启动就提交到上限），非堆 65 MiB，线程 103 个（大部分是 Nacos 客户端的线程）。

宿主机（`vm_stat` / `memory_pressure`）：系统空闲内存比例 66%，压缩内存约 3.2 GB，没有内存压力。

### 差异分析

- **总量低于估算。** 按任何一种口径，P0 实际运行的组件都比估算少 20% 以上：PostgreSQL、Redis 目前是空库，hello 也比估算的每服务 0.4 GB 小。
- **Nacos 是唯一真实偏高的组件。** anon 从 P0-T04 的 663–691 MiB 缓慢升到 712 MiB（运行 3 小时），比估算高 16%；加上页缓存后 `docker stats` 为 845 MiB，高 38%。1 GiB 的容器上限仍有余量。
- **Vault 的 +32% 只是页缓存。** 它自己只用 8 MiB；页缓存在几次读数间在 34–137 MiB 之间变化。
- **Grafana 13 比较重。** 一个组件就用了约 380 MiB；P8 再加 OTel Collector、Tempo、Loki、Alloy 时，1.3 GB 的可观测性预算会比较紧，P8 需要复测。
- **后续风险：** 估算里最大的一块是 8 个 Java 服务（3.2 GB），按 hello 的情况看，每个服务的 RSS 约 230 MiB 加上业务代码，估算偏保守。真正需要关注的是 P1 起加入的 Keycloak 和 P3 的 Kafka。

### 关于 P0-T10 停止条件（实测值比估算高出 30% 以上）

- 按合计：不触发（−21% / −42%）。
- 按单个组件、`docker stats` 口径：Nacos +38%、Vault +32% 超过 30%；按 anon（真实占用）口径：只有 Nacos 高出 16%，不超过 30%。
- **用户决定（2026-11-24）：不算触发，按实测修正 design.md 10.1。** 理由：停止条件是为了发现"内存不够用"，而合计明显低于估算；超出部分要么是可回收的页缓存（Vault），要么只高 16%（Nacos 的真实占用）。
- design.md 10.1 的修改：Nacos 估算 0.6 GB → **0.75 GB**，合计约 7.8 GB → 约 8 GB；表格加了"P0 实测"列；写明测量口径以 cgroup `anon` 为准。PostgreSQL、Redis 目前是空库，hello 没有业务代码，这几项不按 P0 读数下调，保留原估算，后续阶段再复测。

## P1-T01：Compose `core` 加入 Keycloak（2026-11-26）

`scripts/dev.sh up core`（本次没有启动 `obs`）。Keycloak 26.8.2 开发模式，存储用 PostgreSQL 的 `keycloak` schema，堆固定 384 MiB（`JAVA_OPTS_KC_HEAP=-Xms384m -Xmx384m`），容器上限 1 GiB。删除 PostgreSQL 数据卷重建、realm 导入、自助注册 3 个用户、若干次登录之后，每分钟读一次。

| 组件 | `docker stats` | anon（实际） | design.md 10.1 估算 | 差异（stats / anon） |
| --- | --- | --- | --- | --- |
| keycloak（启动后约 2 分钟） | 522 MiB | 475 MiB | | |
| keycloak（约 4 分钟，读数稳定） | 589 MiB | 537 MiB | 0.6 GB | −4% / −13% |
| postgres（Keycloak 已建 101 张表） | 96 MiB | 31 MiB | 0.4 GB | 低于估算 |
| nacos | 810 MiB | 680 MiB | 0.75 GB | −11%（anon） |

- Keycloak 进程有 53 个线程。启动后几分钟内 anon 还在上涨（475 → 537 MiB），之后稳定；在估算之内，但余量不大（约 80 MiB）。P1 后续任务有 gateway、account 一起运行时再复测。
- PostgreSQL 因为有了 Keycloak 的表和连接，anon 从 16 MiB 升到 31 MiB。
- 不需要调整 design.md 10.1。
