# P9 云原生与自动扩缩容

**目标：** 把全部组件部署到本地 k3d，用 GitOps 管理发布，实现按流量自动扩缩容、金丝雀发布、网络隔离与 mTLS，并验证零停机；准备好备用 EC2 环境的脚本。

**前置条件：** P8 完成。**运行 k3d 前先停止 Compose**（`scripts/dev.sh down`），本机内存不足以同时运行两者。

**阶段结束时能看到：**
- `kubectl get pods -A` 中全部组件运行；浏览器通过 `https://localhost:8443` 访问前端（自签名证书）；
- Argo CD 界面中看到全部应用由 GitOps 仓库同步；
- 运行小规模压测时，Grafana"弹性伸缩"大盘中服务副本数自动增加，结束后逐步缩回；
- 滚动升级全部服务期间，浏览器行情不中断、订单不丢失。

---

## P9-T01 镜像构建：Jib 与 GHCR

**实现要点：**
- `crypto-trading.spring-service` 插件加入 Jib：基础镜像 Temurin 25 JRE；平台 `linux/arm64` 与 `linux/amd64`（EC2 与 GitHub 运行器可能是 amd64）；JVM 参数 `-XX:MaxRAMPercentage=75`；镜像标签为提交 SHA。
- 评估 JDK 25 AOT 缓存（JEP 514/515）能否在 Jib 构建中生成；**如果需要额外的训练运行步骤且 Jib 无法支持，记录结论并询问用户是否放弃或改用其他构建方式**。
- 前端镜像：Nginx 托管 `web/dist`（多阶段 Dockerfile，或 Jib 之外的单独构建步骤）。
- CI：main 分支构建通过后推送镜像到 `ghcr.io/<github-owner>/crypto-trading/<服务名>`；本地可用 `./gradlew jibDockerBuild` 构建后导入 k3d。

**验证：** 本地 `jibDockerBuild` 生成 arm64 镜像并能运行；用户推送后 GHCR 中出现镜像（由用户确认）。

**Checklist：**
- [ ] 多平台镜像
- [ ] AOT 缓存结论已记录
- [ ] CI 推送步骤只在 main 分支执行

---

## P9-T02 k3d 集群

**实现要点：** `deploy/k3d/create-cluster.sh`：单节点集群，映射 443 → 8443；配置从 GHCR 拉取的只读令牌（来自 `.env.local`）；`values-local.yaml`（本地副本范围）与 `values-ec2.yaml`（设计副本范围）。

**验证：** `kubectl get nodes` 正常；能拉取 GHCR 镜像。

**Checklist：**
- [ ] 一条命令创建与删除集群
- [ ] 令牌不在仓库中

---

## P9-T03 基础设施进入 k3d

**实现要点：** 命名空间 `ct-infra`：CloudNativePG（单实例）、Strimzi Kafka（单节点 KRaft）、Redis、Nacos、Keycloak、Vault（dev 模式）、Apicurio。把 Compose 阶段的初始化（schema 与账号、Topic、Avro 注册、realm 导入、Nacos 配置、Vault 写入 Key）改为 Kubernetes Job 或 Operator 资源。内存限制沿用 design.md 10.1。

**验证：** 全部 Pod 就绪；初始化 Job 成功；记录集群总内存占用到 `docs/verification/memory.md`。

**停止条件：** 本机内存不足以运行基础设施加最小副本的全部服务，报告实测数据并询问是否转到备用 EC2（P9-T13）。

**Checklist：**
- [ ] 初始化全部自动化
- [ ] 内存实测已记录

---

## P9-T04 Helm Chart

**实现要点：**
- `deploy/helm/library`：Deployment（探针、`preStop` 等待、优雅停机、资源 requests / limits、单副本时 `maxSurge: 1, maxUnavailable: 0`）、Service、ServiceMonitor、PodDisruptionBudget（副本数 ≥ 2 时生成）、KEDA ScaledObject、NetworkPolicy 模板。
- 每个服务一个 Chart，只写差异；环境差异放在 `values-local.yaml` / `values-ec2.yaml`。
- 日志：Alloy 改为采集 Pod 标准输出（P8-T03 的 k3d 方式）。

**验证：** `helm template` 输出通过 `kubeconform` 校验；手动 `helm install` 一个服务能运行。

**Checklist：**
- [ ] 全部服务都有 Chart
- [ ] 模板校验通过

---

## P9-T05 GitOps：Argo CD

**实现要点：** 创建 GitOps 仓库 `<github-owner>/crypto-trading-gitops`（由用户在 GitHub 创建，计划提供初始内容）；Argo CD 采用 App of Apps 同步基础设施与全部服务；CI 在推送镜像后更新 GitOps 仓库中的镜像标签（需要用户配置一个有权限的令牌作为 GitHub Actions Secret）。

**验证：** 修改 GitOps 仓库中某服务的副本数，Argo CD 自动同步；CI 推送新镜像后自动部署（由用户推送后确认）。

**Checklist：**
- [ ] App of Apps 结构
- [ ] 漂移检测可见（手动改动被还原）

---

## P9-T06 入口：Envoy Gateway 与证书

**实现要点：** 安装 Envoy Gateway 与 cert-manager（自签名 Issuer）；Gateway API 路由：`/` → web，`/api`、`/ws`、`/swagger-ui` → gateway，Keycloak、Grafana、Argo CD 使用各自路径或子域（`*.localhost`）；Keycloak realm 回调地址加入 k3d 地址。

**验证：** 浏览器 `https://localhost:8443` 完成登录、行情、下单。

**Checklist：**
- [ ] 全部页面经 HTTPS 可用
- [ ] WebSocket 经 Envoy 正常

---

## P9-T07 可观测性进入 k3d

**实现要点：** 命名空间 `ct-obs`：kube-prometheus-stack（启用 Prometheus、Grafana、kube-state-metrics、node-exporter，关闭不需要的组件以省内存）、Loki、Alloy、OTel Collector；Tempo 默认关闭、按需开启。复用 P8 的大盘与告警文件。

**验证：** 9 个大盘有数据，其中"服务器 / 集群"大盘首次有完整数据；ServiceMonitor 自动发现全部服务（不再需要目标文件）。

**Checklist：**
- [ ] 服务自动发现
- [ ] 大盘与告警复用 P8 文件

---

## P9-T08 对照实验：Spring Cloud Kubernetes

**实现要点：** 在单独的 Spring profile `k8s-native` 中：服务发现改用 Kubernetes Service；配置改用 ConfigMap；market-data 的 Leader 选举改用 Lease 对象。默认部署仍使用 Nacos 与 JDBC 锁。把两种方式在配置、故障切换耗时、运维复杂度上的对比写入 `docs/experiments/k8s-native.md`。

**Checklist：**
- [ ] `k8s-native` profile 可完整运行
- [ ] 对比文档完成

---

## P9-T09 自动扩缩容（关键场景 10、12）

**实现要点：**
- 安装 metrics-server（k3s 自带则确认可用）与 KEDA。
- 按 design.md 10.4 为每个服务配置 ScaledObject：CPU / 内存兜底 + 流量指标（Prometheus 触发器：请求速率、WebSocket 连接数、运行中策略实例数；Kafka 触发器：消费积压）；扩缩容行为（扩容 30 秒翻倍、缩容稳定窗口 5 分钟、每分钟减 1）。本地使用"本地副本"范围。
- `perf/`（Gatling Java DSL）：下单压测、WebSocket 连接压测、批量创建策略；规模适配本机。
- 场景 10：压测期间各服务扩容，结束后缩容，过程中订单不丢失（压测前后对账恒等式成立）。
- 场景 12：压测中一个用户耗尽配额，其他用户下单成功率不受影响。

**验证：** Grafana"弹性伸缩"大盘截图存档；压测报告存档到 `docs/verification/perf/`。

**Checklist：**
- [ ] 每个服务都有 ScaledObject
- [ ] 场景 10、12 通过
- [ ] 缩容平稳、无抖动

---

## P9-T10 金丝雀发布：Argo Rollouts

**实现要点：** order 改用 Rollout：50% → 100%；AnalysisTemplate 用 Prometheus 查询 `order_unknown_current` 与 5xx 比例，超阈值自动回滚。

**验证：** 发布一个正常版本 → 自动推进；发布一个故意返回 500 的版本 → 自动回滚。

**Checklist：**
- [ ] 正常版本自动推进
- [ ] 异常版本自动回滚

---

## P9-T11 网络隔离与 mTLS

**实现要点：**
- NetworkPolicy：默认拒绝；只放行必要的服务间访问；只有 order、market-data、ledger 允许访问外网（Alpaca）。确认 k3d 中 NetworkPolicy 生效（k3s 内置控制器）。
- 服务间 mTLS：cert-manager 为每个服务签发证书，Spring Boot SSL Bundles 加载，证书更新后热重载。

**验证：** 从 account Pod 访问 Alpaca 被拒绝；从 order Pod 访问成功；不带客户端证书访问内部接口失败。

**Checklist：**
- [ ] 默认拒绝生效
- [ ] 外网访问只限三个服务
- [ ] mTLS 与证书热重载可用

---

## P9-T12 混沌实验与零停机（关键场景 3 复验）

**实现要点：**
- 安装 Chaos Mesh；实验：杀掉 market-data / order 的 Leader Pod；Kafka Pod 故障 1 分钟；网络延迟注入。
- 零停机：持续压测的同时对全部服务执行滚动升级，验证行情推送不中断、订单不丢失、对账恒等式成立。

**验证：** 实验报告存档到 `docs/verification/chaos/`。

**Checklist：**
- [ ] 每个实验都有结果记录
- [ ] 滚动升级期间无订单丢失

---

## P9-T13 备用 EC2 环境

**实现要点：**
- `deploy/ec2/bootstrap.sh`：在全新 EC2 上安装 Docker、k3d、kubectl、Helm 并克隆仓库。
- `values-ec2.yaml`：副本取"设计副本"，可观测性组件全开、保留期更长。
- `scripts/ec2-tunnel.sh`：按 design.md 10.6 建立 SSH 端口转发。
- `docs/operations/ec2.md`：用户在 AWS 控制台手动创建实例的要求（64GB 内存、安全组只开放 SSH 给用户 IP、磁盘大小）；**环境切换步骤：先停掉原环境的 market-data 与 order，确保同一时间只有一个环境连接真实 Alpaca**。
- 本任务只准备脚本与文档；是否实际启用 EC2 由用户决定，启用时再按文档执行并验证。

**Checklist：**
- [ ] 脚本与文档完成
- [ ] 文档中强调单一 Alpaca 连接
- [ ] （用户启用时）EC2 上按设计副本完成一次扩缩容验证

---

## P9 阶段验收

- [ ] 全部组件在 k3d 中运行，Argo CD 同步
- [ ] HTTPS 访问前端完成全流程
- [ ] 场景 10、12 在 k3d 中通过
- [ ] 金丝雀、网络隔离、mTLS、混沌实验、零停机验证完成
- [ ] 备用 EC2 脚本与文档完成
