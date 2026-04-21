# XXL-JOB 项目 SKILL 生成建议

> 本文件列出建议为本项目创建的 SKILL.md 文件清单。不要现在生成，只做规划。
> 重要等级：高 = 高频任务或高风险改动；中 = 偶发但有复杂度；低 = 备用。

---

## 通用类（Java Web 项目常见场景）

- [ ] `add-api-endpoint.skill.md` — 在 Admin 新增 Controller 端点（含 Service/Mapper 层联动）｜重要等级：中
- [ ] `add-mybatis-mapper.skill.md` — 新增 MyBatis Mapper 接口 + XML SQL（项目全手写，无 Generator）｜重要等级：中
- [ ] `write-unit-test.skill.md` — 为 Service / Helper 编写单元测试（项目当前无测试模块，补测场景）｜重要等级：中
- [ ] `add-spring-bean.skill.md` — 在 Admin 注册新 Spring Bean 并接入 XxlJobAdminBootstrap 生命周期｜重要等级：低
- [ ] `update-i18n.skill.md` — 在三份 i18n properties 文件中同步新增/修改翻译词条｜重要等级：低
- [ ] `add-freemarker-page.skill.md` — 新增 Freemarker 模板页面及对应 Controller 路由｜重要等级：低

---

## 扩展点类（本项目特有的 Seam 扩展点）

基于 [docs/architecture.md](../docs/architecture.md) Seam 清单，以下扩展点有明确接口，值得单独建 SKILL：

- [ ] `add-job-alarm.skill.md` — 新增告警渠道：实现 `JobAlarm` 接口注册为 Bean（邮件之外，如钉钉/飞书/Webhook）｜重要等级：**高**
- [ ] `add-executor-route-strategy.skill.md` — 新增路由策略：继承 `ExecutorRouter`，在 `ExecutorRouteStrategyEnum` 加枚举值｜重要等级：**高**
- [ ] `add-schedule-type.skill.md` — 新增调度类型：实现 `ScheduleType` 接口，在 `ScheduleTypeEnum` 加枚举值（注意 FIX_DELAY 禁用原因）｜重要等级：中
- [ ] `add-misfire-strategy.skill.md` — 新增过期策略：实现 `MisfireHandler`，在 `MisfireStrategyEnum` 加枚举值｜重要等级：中
- [ ] `add-job-handler.skill.md` — 在执行器中新增 `@XxlJob` Handler（含分片广播、GLUE 两种模式说明）｜重要等级：**高**
- [ ] `implement-adminbiz-method.skill.md` — 实现 `AdminBiz` 中 7 个注释掉的方法（jobAdd/jobUpdate/jobDelete 等），包含安全确认步骤｜重要等级：**高**
- [ ] `add-glue-factory-impl.skill.md` — 新增 `GlueFactory` 实现（非 Spring 环境或自定义类加载器场景）｜重要等级：低
- [ ] `configure-trigger-pool.skill.md` — 调整 `fastPool`/`slowPool` 线程数：含公式计算步骤和配置验证方法｜重要等级：中

---

## 改造类（老项目改造常见场景）

基于 [docs/architecture.md](../docs/architecture.md) 债务地图（红区/黄区）：

- [ ] `fix-triggerpool-rejection.skill.md` — 修复 `JobTriggerPoolHelper` 静默丢任务问题（R-1，当前 `null` 拒绝策略）｜重要等级：**高**
- [ ] `implement-fix-delay.skill.md` — 补全 FIX_DELAY 调度类型（R-3，`ScheduleTypeEnum:28` 已注释，`JobCompleter:50` 有占位）｜重要等级：**高**
- [ ] `implement-adminbiz-placeholders.skill.md` — 系统性实现 AdminBiz 7 个占位方法（R-4，需先确认外部调用方）｜重要等级：**高**
- [ ] `migrate-embed-server-netty.skill.md` — 升级 EmbedServer Netty 版本（Y-2，需全量回归，不能只改 pom）｜重要等级：中
- [ ] `cleanup-deprecated-dir.skill.md` — 清理 `deprecated/` 和 `old/` 目录（Y-1，需先确认无外部 jar 依赖）｜重要等级：中
- [ ] `replace-schedule-lock.skill.md` — 将 `SELECT FOR UPDATE` 调度锁替换为 Redis 锁（R-5，架构决策，需问架构团队）｜重要等级：中
- [ ] `extract-admin-biz-http-client.skill.md` — 将 AdminBiz / ExecutorBiz 的 HTTP 代理客户端从 deprecated/ 迁移到正式包｜重要等级：低
- [ ] `add-jobschedulehelper-test.skill.md` — 为 `JobScheduleHelper`（22 次改动热点，R-2）补充集成测试覆盖｜重要等级：中
