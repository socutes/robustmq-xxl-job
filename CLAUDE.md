# CLAUDE.md — XXL-JOB 项目上下文

> 本文件由 AI 生成，供 AI 读取。源材料：docs/architecture.md（基于 v3.4.1-SNAPSHOT 代码实测）。

---

## 项目身份

这是 **XXL-JOB**：一个分布式任务调度平台。调度中心（Admin）负责触发决策，执行器（Executor）集群负责执行，两者通过 HTTP 双向通信解耦，调度中心自身不执行任何业务逻辑。

---

## 模块骨架

### 模块列表

| 模块 | 职责（一句话） |
|------|--------------|
| `xxl-job-core` | Admin 与 Executor 共用的协议模型、接口定义、Netty 内嵌服务、注册线程、回调线程 |
| `xxl-job-admin` | 唯一的调度决策节点：时间轮触发、路由选址、日志落库、告警、Web UI、OpenAPI |
| `xxl-job-executor-sample-springboot` | Spring Boot 执行器接入示例，不是生产模块 |
| `xxl-job-executor-sample-frameless` | 无框架执行器接入示例，不是生产模块 |
| `xxl-job-executor-sample-springboot-ai` | AI 执行器示例（集成 spring-ai + Dify），不是生产模块 |

三个 `sample` 模块仅供参考，不要把它们当生产代码改动。

### 核心数据流

主链路（Cron 触发）按顺序：

```text
scheduleThread（每秒扫 DB，SELECT FOR UPDATE，预读 5s 内任务）
  → ringData Map（时间轮缓冲，按秒分桶）
  → ringThread（每秒取当前桶 ±2 格）
  → TriggerPool 线程池（异步①，快慢双池）
  → JobTrigger.processTrigger()（路由选址 + HTTP POST /run）
  → EmbedServer/ExecutorBizImpl（接收 /run，入队，立即返回 200，异步②）
  → JobThread（每 jobId 一个专属线程，执行 handler）
  → TriggerCallbackThread（批量回调 Admin /api/callback，失败写文件重试，异步③）
  → JobCompleter（更新 xxl_job_log，触发子任务）
```

关键异步节点：

- **异步①**：scheduleThread 与 TriggerPool 通过 ringData 解耦，互不阻塞
- **异步②**：/run 返回 200 时任务尚未执行，真正执行在 JobThread
- **异步③**：回调失败不丢失，写 `xxl-job-callback-{md5}.log`，每 30s 重试

手动触发 / API 触发从 TriggerPool 直接进入，跳过 scheduleThread 和 ringThread。

---

## 禁区

以下位置不要在没有完整上下文的情况下动。每条说明约束，不解释原因。

### 历史代码有明显警告

| 位置 | 约束 |
|------|------|
| [CronExpression.java](xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/cron/CronExpression.java) | `getFinalFireTime()` 永远返回 null（FUTURE_TODO），不要依赖它的返回值；修改此文件前先通读 Quartz 原版语义 |
| [JobThread.java:170-190](xxl-job-core/src/main/java/com/xxl/job/core/thread/JobThread.java#L170) | 空闲销毁逻辑有竞态窗口，修改时保持 `toStop` flag 的 happens-before 顺序不变 |
| [EmbedServer.java:65](xxl-job-core/src/main/java/com/xxl/job/core/server/EmbedServer.java#L65) | 从 xxl-rpc 复制而来，升级 Netty 版本不能只改 pom，要全量回归 |

### 核心数据流上的节点

| 位置 | 约束 |
|------|------|
| [JobScheduleHelper.java:77-148](xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobScheduleHelper.java#L77) | scheduleThread + ringData 是所有任务触发的入口，改这里会影响全部任务的触发时机 |
| [JobTrigger.java:134](xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/trigger/JobTrigger.java#L134) | 路由寻址、HTTP 触发、日志落库三步耦合在一个方法里，改任何一步都要同时验证其余两步 |
| [TriggerCallbackThread.java:67-196](xxl-job-core/src/main/java/com/xxl/job/core/thread/TriggerCallbackThread.java#L67) | 执行结果回报的唯一通道；改主流程时同步验证文件重试路径，两者行为要保持一致 |
| [JobScheduleHelper.java:75](xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobScheduleHelper.java#L75) + [XxlJobLockMapper.xml:6](xxl-job-admin/src/main/resources/mapper/XxlJobLockMapper.xml#L6) | 分布式调度锁是所有 Admin 节点的唯一仲裁点，改锁范围或换锁机制必须全集群灰度 |

### 需要先问人再动

| 位置 | 先确认什么 |
|------|-----------|
| `xxl-job-executor-sample-springboot-ai/` | 是否有外部业务方依赖这个 sample 的包结构；有的话重命名或重构会破坏其构建 |
| 分布式调度锁（`xxl_job_lock`）替换为 Redis | 架构团队是否已评审；不是单人可以推进的决策 |
| `XxlJobLogGlueMapper.xml` GLUE 版本清理逻辑 | 先读 mapper XML 确认实际版本上限值，再决定容量规划 |

---

## 协作规则

### 改动前

1. **触碰以下文件前，先列出你要改哪几行、为什么**，等我确认再动：
   - `JobScheduleHelper.java`（调度主循环 + 时间轮，全局触发热路径）
   - `JobTrigger.java`（路由 + HTTP 触发 + 日志落库耦合在一起）
   - `TriggerCallbackThread.java`（结果回报唯一通道，含文件重试分支）
   - `JobTriggerPoolHelper.java`（拒绝策略当前静默丢任务，改动有任务丢失风险）
   - `CronExpression.java`（借自 Quartz，含未实现方法，语义复杂）

2. **触碰以下位置前，先问我**，不要自行推进：
   - 分布式调度锁机制变更（`xxl_job_lock` + `XxlJobLockMapper.xml`）——需要架构团队决策
   - AI 示例模块（`xxl-job-executor-sample-springboot-ai/`）的包结构或命名——可能有外部依赖方
   - 任何涉及 docs/architecture.md"代码之外的未决问题"一节中标注"问人"的事项

3. **跨多个文件的改动**，先给我列改动清单（文件 + 改哪段 + 改动原因），确认后再执行。

### 改动中

4. **每次实际写代码前给我 commit 预告**：说明改什么文件、改什么行为、预期影响范围，我回复"可以"再动。

5. **改核心数据流上的任意节点时**，同步检查上下游：
   - 改 `scheduleThread` → 验证 `ringThread` 的取桶逻辑不受影响
   - 改 `processTrigger()` → 验证日志落库（`xxl_job_log`）的字段仍完整
   - 改 `TriggerCallbackThread` 主流程 → 验证文件重试路径（`TriggerRetryCallbackThread`）行为一致

6. **改动 `xxl_job_log` 相关代码前，先阅读 [docs/log-table-refactor-plan.md](docs/log-table-refactor-plan.md)**，确认改动范围与在制目标（G-1 到 G-10）的一致性，避免与进行中的治理工作冲突。涉及文件：`XxlJobLogMapper.java`（及 XML）、`JobTrigger.java`（trigger_msg 截断）、`JobLogReportHelper.java`（清理逻辑）、`JobFailAlarmMonitorHelper.java`（alarm_status 状态机）、`JobCompleter.java`（handle_msg 截断）。

7. **触碰以下核心链路文件前，先跑集成测试确认基线绿**，失败则先排查再改：
   - 触碰文件：`JobTrigger.java`、`JobTriggerPoolHelper.java`、`JobCompleteHelper.java`、`XxlJobLogMapper.java`（及对应 XML）、`AdminBizImpl.java`
   - 基线命令：`mvn test -Dtest="TriggerChainTest,CallbackChainTest,LogQueryChainTest" -pl xxl-job-admin`
   - 测试文件：`src/test/java/com/xxl/job/admin/integration/`（三个文件，需本地 MySQL + `xxl_job` 库）
   - 基线红时不要继续改动；先查是测试环境问题还是已有代码 bug，再决定是修环境还是修代码

8. **改 `JobThread.java:170-190`（空闲销毁逻辑）时**，保持 `toStop` flag 的写入在 queue 操作之前，不要调整这个顺序。

9. **不要启用 `FIX_DELAY`**（`ScheduleTypeEnum.java:28` 注释掉的枚举值）——`JobCompleter.java:50` 里有占位逻辑但功能不完整，启用会引入不可预期行为。

### 判断不确定时

10. **对以下事项，不猜，直接说"需要你确认"**：
    - GLUE 版本历史上限（文档称 30 版，代码未直接确认）
    - `fastPool`/`slowPool` 线程数对当前生产 QPS 是否够用
    - `AdminBiz` 注释掉的 7 个方法是否有外部调用方在等待

11. **看到 `AdminBiz.java` 里注释掉的方法**（`jobAdd`/`jobUpdate`/`jobDelete` 等），不要把它们当成可以随意实现的空位——先问我这些方法的实现计划。

### 代码风格约束

12. **不要动 `deprecated/` 和 `old/` 目录**，也不要在新代码里引用它们——清理前需确认无外部 jar 依赖。

13. **升级 Netty 版本时**，`EmbedServer.java` 要全量回归，不能只改 pom 版本号。

14. **新增告警渠道**，实现 `JobAlarm` 接口注册为 Spring Bean 即可，不要改 `JobAlarmer.java` 的遍历逻辑。

15. **新增路由策略**，继承 `ExecutorRouter` 并在 `ExecutorRouteStrategyEnum` 加枚举值，不要改现有 10 种策略的实现。

---

## 未决问题

以下问题代码层面无法确认。遇到时不要猜，按指定行为处理。

### 问题清单

#### 问题 1：GLUE 版本清理上限

- 【问题】GLUE 历史版本最多保留几版，代码中未直接确认（文档称 30 版）
- 【AI 行为】被问到 GLUE 版本上限时，说"我不知道实际值"，提示去读 `xxl-job-admin/src/main/resources/mapper/XxlJobLogGlueMapper.xml` 里的删除 SQL，确认 `LIMIT` 参数后再做任何依赖此值的决策

#### 问题 2：AI 示例模块是否有生产依赖方

- 【问题】`xxl-job-executor-sample-springboot-ai` 是否已被外部业务方作为生产执行器使用
- 【AI 行为】被要求改动、重命名、重构该模块时，先说"需要你确认是否有外部依赖方"，不要直接执行；被问到"能不能改"时，回答"代码层面看不出来，需要问项目维护者或社区"

#### 问题 3：注册企业数量

- 【问题】官方声称 700+ 企业接入，无法通过代码核实
- 【AI 行为】被问到接入规模或社区活跃度时，说"这个数字来自社区自愿登记，我无法核实"；不要把它当作可靠数据引用

#### 问题 4：触发线程池生产建议值

- 【问题】`fastPool`/`slowPool` 的合适线程数取决于实际 QPS，代码只有默认值（300/200）和硬最小值（200/100）
- 【AI 行为】被问到线程池应该配多少时，给出公式 `(fastMax + slowMax) × 10 = 5s 内最大可调度任务数`，然后说"具体值需要你提供峰值 QPS 数据，我才能给建议"；不要直接给一个数字

#### 问题 5：分布式调度锁是否需要换机制

- 【问题】`SELECT ... FOR UPDATE` 在多 Admin 节点下是否成为性能瓶颈，是否值得换 Redis 锁，需要架构团队根据实际监控数据决策
- 【AI 行为】被问到"要不要换 Redis 锁"时，说"这是架构决策，需要你先确认 Admin 节点数和 DB 锁等待时长数据，我不能替你决定"；可以列出替代方案选项，但不要给出"应该这么做"的结论
