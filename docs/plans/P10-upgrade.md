# P10 升级演练

**目标：** 把全部服务升级到 Spring Boot 4.2 + Spring Cloud 2026.0，验证升级流程与回归测试的有效性。

**前置条件：** P9 完成；Spring Boot 4.2 与 Spring Cloud 2026.0 已正式发布（GA）；Spring Cloud Alibaba 已发布兼容版本或经验证可用。

**阶段结束时能看到：** 全部测试在新版本上通过，k3d 中以金丝雀方式完成升级，期间无订单丢失。

---

## P10-T01 升级前检查

**实现要点：**
- 查阅 Boot 4.2、Spring Cloud 2026.0 的发布说明与迁移指南，以及 Spring Cloud Alibaba 的兼容声明，把影响本项目的变化列入 `docs/upgrade/boot-4.2.md`（例如 Spring Cloud Bus 已并入 Spring Cloud Stream）。
- 检查第三方依赖（MyBatis-Plus、Redisson、ShedLock、springdoc、Springwolf、Apicurio serde 等）对新版本的支持。

**停止条件：** Spring Cloud Alibaba 或任一关键依赖尚不支持新版本，报告并询问用户是等待还是先在分支中验证。

**Checklist：**
- [ ] 影响清单完成
- [ ] 依赖兼容性逐项确认

---

## P10-T02 升级与修复

**实现要点：**
- 新建分支；可先用 OpenRewrite 的 Spring Boot / Spring Cloud 升级配方做机械性修改，再人工处理剩余问题。
- 更新 `libs.versions.toml` 与 `platform`；处理废弃 API；运行 `./gradlew build integrationTest contractTest`。

**Checklist：**
- [ ] 无废弃 API 警告
- [ ] 全部测试通过

---

## P10-T03 回归与发布

**实现要点：**
- Compose 全量环境运行全部 E2E；运行 P8 的可观测性检查脚本。
- k3d 中通过 Argo CD 逐个服务升级（order 走金丝雀），升级期间持续压测；完成后对账恒等式成立。
- 更新 design.md 第 4 节与技术栈清单中的版本基线。

**Checklist：**
- [ ] E2E 全部通过
- [ ] 升级期间无订单丢失
- [ ] 文档版本基线已更新

---

## P10 阶段验收

- [ ] 全部服务运行在 Boot 4.2 + Spring Cloud 2026.0
- [ ] 全部测试与 E2E 通过
- [ ] 升级记录与文档已更新
