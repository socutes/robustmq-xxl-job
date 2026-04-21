# 核心链路护栏清单

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：哪些链路需要护栏、为什么、选什么护法。
**不讲什么**：护栏的具体代码实现（那是 SKILL 的事）。

---

## 链路总览

| # | 链路名称 | 风险 | 护法 |
|---|---------|------|------|
| L-1 | Cron 触发主链路（scheduleThread → 执行器 /run） | **高** | 集成测试 |
| L-2 | 执行结果回调（TriggerCallbackThread → /api/callback） | **高** | 集成测试 + Characterization Test |
| L-3 | 分布式调度锁（SELECT FOR UPDATE） | **高** | 纸面 RISK + smoke 脚本 |
| L-4 | 执行器注册与心跳（/api/registry → address_list 刷新） | 中 | Characterization Test |
| L-5 | 失败重试与告警（JobFailAlarmMonitorHelper） | 中 | 集成测试（受控场景） |
| L-6 | 执行器失联兜底（monitorThread 标记 FAIL） | 中 | Characterization Test |
| L-7 | TriggerPool 拒绝（R-1 当前静默丢任务） | **高** | 纸面 RISK（修复前）→ 集成测试（修复后） |
| L-8 | 回调文件重试（TriggerRetryCallbackThread） | 中 | Characterization Test |

---

## L-1：Cron 触发主链路

**链路描述**：
`JobScheduleHelper.scheduleThread`（每秒扫 DB，SELECT FOR UPDATE）→ 写 `ringData` →
`ringThread`（每秒取桶 ±2 格）→ `JobTriggerPoolHelper.trigger()` →
`JobTrigger.processTrigger()`（INSERT log，路由选址，HTTP POST `/run`）→
执行器 `EmbedServer`（入队，返回 200）→ `JobThread`（执行 Handler）

**为什么是核心**：
这是所有 Cron 任务的唯一触发路径。这条链路中断，所有定时任务静默不触发，无报错，
第一个知道的人是业务方（发现数据没跑），不是运维（没有告警）。
`JobScheduleHelper` 是项目 2 年内改动最频繁的文件（R-2，22 次），是 bug 高发区。

**风险等级**：高

**建议护法**：集成测试（Spring Boot Test + 内存/本地 MySQL）

**选这个护法的理由**：

链路横跨 `scheduleThread → ringThread → TriggerPool → HTTP → EmbedServer → JobThread` 六个节点，单元测试无法覆盖节点间的异步交互——两个独立线程通过 `ringData` Map 解耦，这个 happens-before 关系没有比端到端跑更便宜的验证方式。

项目是 Spring Boot + MySQL 单体，可以在测试中启 `XxlJobAdminBootstrap`，插一条 `trigger_next_time = 当前时间 - 1s` 的任务，等待 ≤3s，断言 `xxl_job_log.trigger_code = 200`。执行器侧可以用 mock HTTP server（固定返回 200），把链路拆成 Admin 侧和 Executor 侧两段独立测，Admin 侧不需要真实执行器就能断言触发是否完成。

**测试要覆盖的边界**：

1. 正常触发：任务到期 → log 记录 trigger_code=200
2. 任务过期超过 5s（misfire）：FIX_RATE 用 FIRE_ONCE_NOW / DO_NOTHING 两种策略分别验证
3. `trigger_next_time` 在 scheduleThread 扫描后被正确更新（防重复触发）
4. 手动触发（跳过 scheduleThread/ringThread，直接进 TriggerPool）

---

## L-2：执行结果回调

**链路描述**：
`JobThread.pushCallBack(CallbackRequest)` → `TriggerCallbackThread`（批量 drainTo，
HTTP POST `/api/callback`）→ Admin `OpenApiController`（校验 accessToken）→
`JobCompleteHelper.callbackThreadPool`（异步）→ `JobCompleter.complete()`（UPDATE xxl_job_log，触发子任务）

**为什么是核心**：
这是执行结果唯一的上报通道。通道中断 → `xxl_job_log.handle_code` 永远是 0（进行中）→
告警线程认为任务还在跑 → 不重试不告警 → 业务方发现"任务一直在执行中"。
这条链路的文件重试分支（`TriggerRetryCallbackThread`）与主流程并列，是本项目最复杂的异步结构。

**风险等级**：高

**建议护法**：集成测试（主流程）+ Characterization Test（文件重试分支）

**选这个护法的理由**：

主流程和文件重试分支的测试成本差别极大，应该分开处理：

**主流程**（`TriggerCallbackThread → /api/callback → JobCompleter`）适合集成测试：执行器侧和 Admin 侧在同一 JVM 内可以启动，直接注入 `CallbackRequest` 进队列，等待 Admin 处理完，断言 `xxl_job_log.handle_code = 200/500`。能确认 accessToken 校验、UPDATE 语句、子任务触发三步联动。

**文件重试分支**（callback 失败写文件，`TriggerRetryCallbackThread` 30s 重试）不适合集成测试——需要制造 Admin 不可达的受控网络中断，测试环境过于复杂。改用 Characterization Test：直接构造格式正确的 callback 文件，启动 `TriggerRetryCallbackThread`，断言 30s 内文件被删除且 DB 已更新。这个测试记录的是"当前行为"而不是"期望行为"，任何重构导致的行为漂移都会被捕获。文件格式（Gson 序列化的 `List<CallbackRequest>`）是隐式契约，`CallbackRequest` 字段改名时测试失败，强制改动者意识到会破坏存量 callback 文件。

**测试要覆盖的边界**：

1. 正常回调：handle_code=200，DB 更新，子任务触发（若有 childJobId）
2. 回调时 accessToken 错误：Admin 拒绝，执行器写失败文件
3. 文件重试：构造合法 callback 文件，验证 `TriggerRetryCallbackThread` 读取并清理
4. 批量回调：多条 CallbackRequest 一次 POST，每条对应的 log 都更新

---

## L-3：分布式调度锁

**链路描述**：
`JobScheduleHelper.scheduleThread` → 开启 DB 事务 →
`XxlJobLockMapper.lock("schedule_lock")`（`SELECT * FROM xxl_job_lock FOR UPDATE`）→
扫描并写入 `ringData` → 事务提交释放锁

Admin 集群所有节点竞争同一把锁，同时只有一个节点执行调度扫描。

**为什么是核心**：
这是整个调度平台的分布式仲裁点。锁失效（如 MySQL 连接超时导致事务未提交自动提交）→
多个 Admin 节点同时触发同一 jobId → 同一任务被重复执行 → 业务数据错乱。
锁范围扩大（如误将非调度逻辑放入锁内）→ 持锁时间增加 → 其他节点等待时间增加 → 触发延迟。

**风险等级**：高

**建议护法**：纸面 RISK + smoke 脚本

**选这个护法的理由**：

多节点竞争场景无法在本地单进程集成测试中真实复现——需要启多个 Admin 进程共享同一 MySQL。能做的集成测试只能验证单节点事务边界，反而制造"测了等于没测"的虚假安全感。

真正有价值的护栏是在变更锁相关代码时要求改动者填写 RISK 确认单（见下方模板）。smoke 脚本作为辅助：Admin 重启后，对一个每分钟触发一次的定时任务观察 5 分钟，确认 `xxl_job_log` 中无重复触发记录（同一 trigger_time 出现两条）。

**纸面 RISK 模板**（改动 `JobScheduleHelper` 的调度锁逻辑时填写）：

```text
改动的行范围：
事务开始位置：
事务提交/回滚位置：
锁范围是否改变（对比改前）：
持锁期间是否有新的 DB 写操作：
持锁期间是否有 HTTP 调用或阻塞 I/O：
是否在多节点环境验证过：
```

---

## L-4：执行器注册与心跳

**链路描述**：
执行器 `ExecutorRegistryThread`（每 30s）→ HTTP POST `/api/registry` →
Admin `OpenApiController` → `XxlJobRegistryMapper.registryUpdate/registrySave`（upsert） →
`JobRegistryHelper`（每 30s 扫描）→ 删除超时实例 → `UPDATE xxl_job_group.address_list`

**为什么是核心**：
`address_list` 是路由选址的数据来源。注册异常 → `address_list` 为空或包含死实例 →
FAILOVER/BUSYOVER 策略探活失败 → 任务无法触发，触发日志写"No online executor"。
挂了之后业务方触发任务时才会感知，运维无主动告警。

**风险等级**：中

**建议护法**：Characterization Test

**选这个护法的理由**：

注册流程的核心逻辑在 `JobRegistryHelper` 的扫描线程，是定时任务，真实测试需要等 30s 心跳 + 90s 超时窗口。集成测试跑完需要 2 分钟以上，且需要同时启 Admin 和 Executor，成本超过收益。

验证点其实很集中：upsert 的幂等性、90s 超时清理的边界判断、address_list 的聚合格式（逗号分隔）。用 Characterization Test 直接操作 DB：插入构造好的 `xxl_job_registry` 数据，手动调用 `JobRegistryHelper` 的扫描逻辑，断言 `xxl_job_group.address_list` 的结果。这样能精确覆盖 90s 边界的 off-by-one，而集成测试等真实时钟无法做到。

**测试要覆盖的边界**：

1. 新执行器注册：`xxl_job_registry` 新增，`address_list` 更新包含新地址
2. 心跳续约：update_time 刷新，地址不重复
3. 死亡超时清理：update_time > 90s 的实例被删除，address_list 不再包含该地址
4. 所有实例死亡：address_list 更新为空字符串

---

## L-5：失败重试与告警

**链路描述**：
`JobFailAlarmMonitorHelper`（每 10s）→ SELECT xxl_job_log WHERE handle_code=500 AND alarm_status=0 →
UPDATE alarm_status=-1（加锁防并发）→ 若 retryCount>0 → `JobTriggerPoolHelper.trigger(RETRY)` →
`JobAlarmer.alarm()` → 遍历所有 `JobAlarm` Bean →
UPDATE alarm_status=2（成功）/ 3（失败）

**为什么是核心**：
失败重试是任务可靠性的最后保障，告警是人工干预的入口。这条链路中断 → 任务失败无告警 → 业务方不知道 → 数据没处理 → 故障升级。`alarm_status=-1` 的加锁设计是关键不变式：保证多节点 Admin 不重复处理同一条失败日志，破坏这个不变式会导致同一任务被重复重试。

**风险等级**：中

**建议护法**：集成测试（受控场景）

**选这个护法的理由**：

这条链路的核心不变式（同一失败日志只重试一次、只告警一次）依赖 `alarm_status=-1` 的 CAS 语义，必须在真实 DB 事务中验证——Mock DB 无法验证 `UPDATE ... WHERE alarm_status=0` 的原子性。

但链路不需要真实执行器：在测试中直接插一条 alarm_status=0、handle_code=500 的 xxl_job_log 记录，手动调用 `JobFailAlarmMonitorHelper` 的扫描逻辑，告警渠道（EmailJobAlarm）在测试中替换为 mock Bean，验证 `doAlarm()` 被调用次数和 alarm_status 变化。扫描周期 10s 可以在测试中手动触发，不需要等真实时钟。

**测试要覆盖的边界**：

1. retryCount=0：alarm_status 0 → -1 → 告警 → 2 或 3，不触发重试
2. retryCount=1：alarm_status 0 → -1 → 触发重试 → 告警 → 更新 retryCount-1
3. 并发场景：两个线程同时扫到同一记录，只有一个能 UPDATE alarm_status=-1 成功

---

## L-6：执行器失联兜底

**链路描述**：
`JobCompleteHelper.monitorThread`（每 60s）→
SELECT xxl_job_log WHERE handle_code=0 AND trigger_time < now-10min →
检查 address_list 是否还包含该执行器地址 →
若已离线：UPDATE xxl_job_log handle_code=500, alarm_status=0

**为什么是核心**：
这是唯一能将"永远进行中"的日志清理掉的机制。执行器进程崩溃未发回调时，没有这个兜底，`xxl_job_log.handle_code=0` 永远挂着，失败重试和告警都不会触发（L-5 扫的是 500，不是 0）。挂了之后的现象是：调度日志页面大量"进行中"永不结束，业务方和运维都感知不到失败。

**风险等级**：中

**建议护法**：Characterization Test

**选这个护法的理由**：

兜底逻辑的边界条件集中在两点：10 分钟超时窗口的判断、address_list 字符串包含判断。集成测试需要制造一个超过 10 分钟前的 log 记录并等监控线程扫到，要么等真实时钟（测试超慢），要么 mock 当前时间（污染主类设计）。

Characterization Test 更直接：插入 `trigger_time = now - 11 minutes` 且 `handle_code=0` 的 log，将该执行器地址从 address_list 移除，手动调用 monitorThread 的扫描方法，断言 log 被更新为 `handle_code=500`。覆盖"在线但超时"和"已离线"两个分支的行为差异，精确且快速。

**测试要覆盖的边界**：

1. 执行器离线且超时（>10min）：handle_code=0 → 500，alarm_status=0
2. 执行器仍在线（如慢任务）：handle_code=0 不变（即使超时也不标失败）
3. 未超过 10 分钟：不处理

---

## L-7：TriggerPool 拒绝（R-1 当前静默丢任务）

**链路描述**：
`JobScheduleHelper.ringThread` → `JobTriggerPoolHelper.trigger()` →
fastTriggerPool / slowTriggerPool（队列满时）→
当前 RejectedExecutionHandler：只打 error 日志，任务丢失

**为什么是核心**：
这不是一条正常链路，而是一个已知缺陷的断裂点（R-1，债务地图红区）。高峰期任务堆积时，任务静默丢失，不执行，不重试，无告警。`xxl_job_log` 中甚至没有这条任务的触发记录，因为丢在进入 `JobTrigger.processTrigger()` 之前。任务丢失对业务的影响完全不可感知，直到业务数据出现空洞才会被发现。

**风险等级**：高（当前状态是已知缺陷，无护栏）

**建议护法**：纸面 RISK（修复前）→ 集成测试（修复后）

**选这个护法的理由**：

修复前，为错误行为写测试是在测试并固化缺陷本身，意义反而是负的——会让"静默丢任务"这个行为被"测试通过"背书。正确时序是：RISK 标注（当前已在 CLAUDE.md 和 `fix-triggerpool-rejection.skill.md` 中）→ 修复 → 补集成测试。

修复后的集成测试：将 fastTriggerPool 线程数设为 1、队列深度设为 1，同时提交 10 个触发请求，断言 10 条都有对应的 xxl_job_log 记录（无静默丢失）。这个测试同时验证了修复方案（CallerRunsPolicy）的反压行为是否正确。

---

## L-8：回调文件重试

**链路描述**：
`TriggerCallbackThread`（正常回调失败）→ 写 `{logPath}/xxl-job-callback-{md5}.log` →
`TriggerRetryCallbackThread`（每 30s）→ 读文件 → HTTP POST `/api/callback` →
成功后删文件

**为什么是核心**：
回调失败的兜底机制。Admin 短暂不可达（如滚动重启）时，执行结果不丢失。这条链路在生产中不常走，但一旦走到必须保证结果最终一致。文件格式（Gson 序列化的 `List<CallbackRequest>`）是一个隐式契约：任何对 `CallbackRequest` 的字段改动都可能导致旧文件反序列化失败，且没有任何显式的格式版本号。

**风险等级**：中

**建议护法**：Characterization Test

**选这个护法的理由**：

制造"Admin 不可达"的网络中断在本地测试中很难受控，且测试意图模糊。Characterization Test 更直接：构造合法格式的 callback 文件（按现有 Gson 序列化格式），放入 logPath，mock 掉 HTTP 调用返回成功，断言文件被删除。

这个测试的核心价值是**将文件格式固化为契约**：`CallbackRequest` 任何字段改名时测试失败，强制改动者意识到会破坏存量 callback 文件。这是一种隐式的序列化契约测试，比在 CLAUDE.md 里写"注意文件格式"更可靠。

**测试要覆盖的边界**：

1. 合法文件：被读取，HTTP 成功，文件删除
2. 文件格式损坏（非法 JSON）：不崩溃，记录 error 日志，文件保留
3. HTTP 仍然失败：文件保留，下次 30s 继续重试

---

## 护法决策矩阵

```text
                        本地集成测试是否能真实复现
                        能 ◄──────────────────────► 不能
                        │                            │
风   高  L-1 集成测试        L-3 纸面RISK + smoke
险       L-2 集成测试        L-7 纸面RISK（修复前）
         L-5 集成测试

     中  L-4 Char.Test       L-3 smoke辅助
         L-6 Char.Test
         L-8 Char.Test
```

**护法选择的三条判断规则**：

1. **跨多个异步节点、需要真实时序的** → 集成测试。节点间的 happens-before 关系没有比端到端跑更便宜的验证方式。

2. **逻辑单一、边界条件集中、需要"冻结当前行为防漂移"的** → Characterization Test。尤其适合定时扫描线程（需要控制时间输入）和隐式序列化契约（文件格式、DB 状态格式）。

3. **涉及多进程竞争、本地单 JVM 无法复现的** → 纸面 RISK + smoke 脚本。强迫改动者在 PR 中明确回答关键假设，比一个测不到真实并发的集成测试更诚实。

---

## 执行优先级

按"投入产出比"排序，不是按"风险高低"：

| 优先级 | 链路 | 理由 |
|--------|------|------|
| 1 | **L-2 集成测试（主流程）** | 回调链路代码稳定，DB 操作直接，mock 执行器 HTTP server 成本低，测试写起来最快 |
| 2 | **L-5 集成测试** | alarm_status 状态机有 5 个值且散落在注释里，边界容易出 bug；测试可以共用 L-2 的测试基础设施 |
| 3 | **L-8 Characterization Test** | 和 L-2 一起做，文件格式契约与主流程的 CallbackRequest 模型共享，边际成本低 |
| 4 | **L-1 集成测试** | 覆盖最多改动热点（R-2），但需要 mock 执行器 HTTP server，稍复杂；有了 L-2 的基础后会更顺 |
| 5 | **L-3 纸面 RISK 模板** | 贴到 `JobScheduleHelper` 顶部注释区一次即可，不需要写代码，但要形成 PR checklist 习惯 |
| 6 | **L-4/L-6 Characterization Test** | 逻辑稳定，不紧急，在有余力时补 |
| 7 | **L-7 集成测试** | 等 R-1 修复 PR 合并后配套补，与 `fix-triggerpool-rejection.skill.md` 联动 |
