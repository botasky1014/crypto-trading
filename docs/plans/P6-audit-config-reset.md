# P6 审计、配置管理与重置

**目标：** 为所有重要数据修改加上审计（与业务同事务、防篡改、可查询），实现业务配置的受控修改入口，以及用户自助重置账户。

**前置条件：** P5 完成。

**阶段结束时能看到：**
- 管理员审计页能查到每一次下单、撤单、开户、资金划拨、配置修改等操作，包括修改前后内容、操作人、IP、结果；
- 用户在设置页看到"我的操作记录"，并能一键重置账户（每 24 小时一次）；
- 管理员在配置页修改风控阈值，填写原因后立即生效，并留下审计记录；
- 人为篡改审计表中任一记录，校验任务立即告警。

**需要的 Compose profile：** `core`、`mq`、`obs`、`mock`

---

## P6-T01 审计事件契约

**实现要点：**
- `events-schema` 增加 `AuditEvent`，字段按 design.md 5.9.2；`before` / `after` 为 JSON 字符串。
- `audit.events` 的 key 为 `resourceType:resourceId`，6 个分区（P3-T01 已创建）。

**验证：** Schema 注册成功，兼容规则为 `BACKWARD`。

**Checklist：**
- [ ] 字段与 design.md 一致

---

## P6-T02 `common-audit` 公共库（关键场景 8）

**目标：** 一个注解完成审计记录，且与业务变更同生同死。

**实现要点：**
- `@Audited(action, resourceType, resourceId = SpEL)`，只允许用在 `service.impl`（ArchUnit 规则）。
- 切面在业务方法所在事务内执行：方法执行前通过 `AuditSnapshotProvider` 取修改前快照，执行后取修改后快照，写入本服务的 `audit_outbox`（同一本地事务）。
- 执行者：有 JWT 时取 `sub`、角色决定 `USER` / `ADMIN`；`AuditContext.runAsSystem("system:xxx", ...)` 声明系统任务；`onBehalfOf` 由调用方设置（策略下单、重置卖单）。
- 上下文：`clientIp`（`X-Forwarded-For` 第一个值）、`userAgent`、`traceId`。
- 失败（业务异常）与拒绝（权限不足）在 `REQUIRES_NEW` 事务中记录，结果为 `FAILURE` / `DENIED`；权限不足通过安全层的拒绝处理器捕获。
- 脱敏：密钥、令牌、密文字段不记录（按字段名与注解 `@AuditMasked` 判断）。
- `audit_outbox` 投递复用 P4-T01 的 Outbox 组件，发送到 `audit.events`。

**验证（场景 8）：** 集成测试：
- 业务成功 → 审计记录存在；
- 业务回滚 → 审计记录不存在；
- 审计写入失败（模拟）→ 业务回滚；
- 权限不足 → 有 `DENIED` 记录；
- 快照中不含敏感字段。

**Checklist：**
- [ ] 场景 8 全部断言通过
- [ ] ArchUnit 规则限制注解位置
- [ ] 系统任务执行者正确

---

## P6-T03 audit-service（关键场景 9）

**目标：** 审计集中存储，只追加、防篡改、可扩容、可查询。

**实现要点：**
- `services/audit-service`，端口 8087，标准目录结构。
- Flyway：`audit_log` 按月分区（Flyway 创建当前与下月分区，定时任务每月提前创建下一个）；`audit_chain_head(partition, last_seq, last_hash)`；`audit_chain_check`。
- 数据库权限：服务账号对 `audit_log` 只有 `INSERT`、`SELECT`；对链头表另有 `UPDATE`；`audit_log` 上的触发器拒绝 `UPDATE` / `DELETE`。
- 写入：每条消息在同一事务中 `SELECT ... FOR UPDATE` 锁住该分区链头 → `hash = SHA-256(last_hash + 规范化后的记录内容)` → 插入（`(partition, seq)` 唯一）→ 更新链头；`eventId` 去重。
- 每日校验任务：逐分区重算哈希链，断裂时 `audit_chain_broken{partition}=1` 并记录位置。
- 查询：`GET /api/admin/audit-logs`（admin；按操作人、动作、资源、时间筛选，分页；查询本身记 `AUDIT_QUERY`）；`GET /api/me/activity`（`actorId` 或 `onBehalfOf` 为本人）。
- 保留期 1 年：过期分区的归档删除写成运维脚本 `scripts/audit-archive.sh`（不自动执行）。
- gateway 路由 `/api/admin/audit-logs/**`、`/api/me/activity` → audit-service。

**验证（场景 9）：**
- 用数据库超级用户篡改一条记录 → 手动触发校验任务 → 指标为 1，日志指出分区与序号；
- 服务账号执行 `UPDATE` / `DELETE` 被拒绝；
- 两个 audit-service 实例同时运行并触发再平衡，链不分叉（`(partition, seq)` 无冲突、校验通过）。

**Checklist：**
- [ ] 场景 9 全部断言通过
- [ ] 分区自动创建
- [ ] 查询接口权限正确，查询本身被审计

---

## P6-T04 补齐各服务审计

**目标：** 把 P1–P5 中标注的 `// AUDIT:` 全部换成 `@Audited`。

**实现要点：** 按下表逐项实现快照提供者与注解；完成后加一个测试：源码中不再出现 `// AUDIT:` 标注。

| 服务 | 动作编码 | 位置 |
| --- | --- | --- |
| account | `ACCOUNT_PROVISION`、`PROFILE_UPDATE` | 开户、修改偏好 |
| order | `ORDER_CREATE`、`ORDER_CANCEL`、`ORDER_RECOVER` | 下单、撤单、UNKNOWN 恢复 |
| ledger | `FUNDS_ALLOCATE`、`FUNDS_FREEZE`、`FUNDS_RELEASE` | 开户划拨、管理员补发、冻结、解冻 |
| ledger | `BALANCE_INIT`、`BALANCE_ADJUST`、`FEE_ADJUST` | PLATFORM 初始化、对账确认、手续费调整 |

**验证：** 浏览器中下单、撤单、修改偏好后，`/api/admin/audit-logs` 中都能查到对应记录，修改前后内容正确。

**Checklist：**
- [ ] 表中每个动作都有集成测试断言审计记录
- [ ] 源码中不再有 `// AUDIT:`

---

## P6-T05 业务配置管理接口

**目标：** 业务配置只能通过管理接口修改，并留下审计。

**实现要点：**
- account：`GET /api/admin/configs`（可管理的 Data ID 白名单：`risk.yaml`、`order.yaml`、`ledger.yaml`、`account.yaml`、`strategy.yaml`、`market-data.yaml`）、`GET /api/admin/configs/{dataId}`、`PUT /api/admin/configs/{dataId}`（`content`、`reason` 必填；先做 YAML 语法校验；通过 Nacos OpenAPI 写入；审计 `CONFIG_UPDATE`，`before` / `after` 为修改前后内容）。
- Nacos 鉴权：创建 `config-admin` 账号（仅 account 使用，可写上述 Data ID）、`ops` 账号（运维应急）；各服务使用只读账号。账号密码在 `.env.local` / Vault。
- risk 收到配置刷新事件后，以系统身份记录 `RISK_RULE_APPLIED`（含新旧阈值）。

**验证：** 管理员通过接口把 R05 改为每分钟 5 次 → 立即生效（第 6 次下单被拒）→ 审计中有 `CONFIG_UPDATE` 和 `RISK_RULE_APPLIED`；用服务的只读账号写 Nacos 被拒绝。

**Checklist：**
- [ ] 白名单外的 Data ID 拒绝修改
- [ ] YAML 语法错误拒绝写入
- [ ] 两条审计记录都存在

---

## P6-T06 交易限制：重置期间拒单

**目标：** 实现 P5 预留的 `UserTradingGuard`。

**实现要点：**
- account 在重置开始时写 Redis `account:status:{userId} = RESETTING`，结束时删除；order 的 `UserTradingGuard` 检查该键，存在则拒绝（`REJECTED_LOCAL`，原因 `ACCOUNT_RESETTING`）。`source = RESET` 的系统订单不受限制。
- 该键已列在 design.md 6.3。

**验证：** 手动设置该键后下单被拒，删除后恢复。

**Checklist：**
- [ ] 系统卖单不受限制

---

## P6-T07 自助重置 Saga

**目标：** 实现 design.md 5.1.2 的重置流程。

**实现要点：**
- account：Flyway `account_reset`（状态、当前步骤、开始时间、各步骤结果）；服务特有目录 `reset/`。
- `POST /api/me/reset`：检查 24 小时限制（Redis `account:reset-lock:{userId}`）→ 创建重置记录 → 异步执行；接口立即返回 202。
- 步骤（每步完成即持久化，可从断点继续）：
  1. 设置 `RESETTING`；
  2. 调用 order `POST /internal/users/{userId}/cancel-all`，等待全部订单终态；
  3. 查询 ledger 该用户非 USD 持仓；对每种资产，数量达到最小下单量的部分调用 order `POST /internal/orders` 提交市价卖单（`source = RESET`，`onBehalfOf` 为该用户，审计执行者为 `system:account-reset`），等待成交；
  4. 调用 ledger `POST /internal/accounts/{userId}/reset`：零头转给 PLATFORM（`DUST`）、全部 USD 归还 PLATFORM（`RECLAIM`）、重新划拨开户金额（`ALLOCATE`）；
  5. 调用 strategy `POST /internal/users/{userId}/pause-all`（strategy 在 P7 实现；本阶段该步骤为"服务未部署时跳过并记录"，P7-T09 去掉跳过逻辑）；
  6. 清除 `RESETTING`，推送 `user:notice` `RESET_DONE`。
- 恢复任务（ShedLock，每分钟）：继续执行卡住超过 5 分钟的重置。
- 审计 `ACCOUNT_RESET`（`before` 为重置前各资产余额）；ledger 中 `FUNDS_RECLAIM`、`FUNDS_DUST` 审计。
- order、ledger 新增的内部接口使用 `svc-account` 客户端凭证。

**验证：** 用户持有 BTC 与挂单 → 发起重置 → 挂单被撤、BTC 卖出、余额恢复 1,000 USD；24 小时内再次重置返回 429 并提示下次可用时间。

**Checklist：**
- [ ] 每一步可重入
- [ ] 零头处理正确
- [ ] 24 小时限制正确

---

## P6-T08 重置场景测试（关键场景 7）

**实现要点：** 集成测试（mock）：
- 在每一步之后模拟进程崩溃，恢复任务从断点继续并最终完成；
- 重置期间用户下单被拒；
- 持仓低于最小下单量时转为零头，PLATFORM 余额相应增加；
- 全过程后对账恒等式成立。

**Checklist：**
- [ ] 场景 7 全部断言通过

---

## P6-T09 前端：设置页、审计页、配置页

**实现要点：**
- 设置页：
  - "重置账户"按钮：说明后果，二次确认；显示下次可重置时间；重置中显示进度；完成后提示。
  - "我的操作记录"列表（分页）。
- `/admin/audit`：筛选（操作人、动作、资源、时间范围）、列表、详情（修改前后内容对比）。
- `/admin/configs`：选择 Data ID、编辑 YAML、修改前后差异预览、原因必填。
- E2E：用户重置流程；管理员修改配置后在审计页查到记录。

**Checklist：**
- [ ] 重置需要二次确认
- [ ] 审计详情显示前后对比
- [ ] 配置修改有差异预览
- [ ] E2E 通过

---

## P6-T10 Grafana"审计"大盘 v0

**实现要点：** Grafana 增加 PostgreSQL 数据源（只读账号，只能读 `audit` schema）；面板：审计事件数（按动作、结果）、最近失败 / 拒绝操作、管理员操作列表、各分区哈希链校验状态。Keycloak 登录事件在 P8 加入。

**Checklist：**
- [ ] 数据源账号只读
- [ ] 大盘以文件形式提交

---

## P6 阶段验收

- [ ] 场景 7、8、9 自动化测试通过
- [ ] design.md 5.9.1 表中所有已实现服务的动作都有审计
- [ ] 业务配置只能通过管理接口修改
- [ ] 用户可自助重置并查看操作记录
- [ ] 管理员审计页、配置页、Grafana 审计大盘可用
