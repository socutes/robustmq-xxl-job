# xxl_job_log 表治理计划

> 本文档是 xxl_job_log 表治理的持续输入文档，覆盖现状、问题诊断、需求目标三层。
> 后续每个阶段的方案选型、改动记录都在本文档扩充。
> 代码位置基于 v3.4.1-SNAPSHOT 实测。

---

## 一、现状报告

### 1.1 表结构

**DDL 来源**：[doc/db/tables_xxl_job.sql:83-106](../doc/db/tables_xxl_job.sql#L83)

#### 字段

| 字段 | 类型 | NOT NULL | 默认值 | 说明 |
|------|------|----------|--------|------|
| `id` | bigint(20) | YES | AUTO_INCREMENT | 日志 ID，下发给执行器，回调时带回 |
| `job_group` | int(11) | YES | — | 执行器分组 ID |
| `job_id` | int(11) | YES | — | 任务 ID |
| `executor_address` | varchar(255) | NO | NULL | 本次执行的执行器地址 |
| `executor_handler` | varchar(255) | NO | NULL | Handler 名 |
| `executor_param` | text | NO | NULL | 执行参数 |
| `executor_sharding_param` | varchar(20) | NO | NULL | 分片参数，格式 `index/total` |
| `executor_fail_retry_count` | int(11) | YES | 0 | 剩余失败重试次数 |
| `trigger_time` | datetime | NO | NULL | 调度触发时间 |
| `trigger_code` | int(11) | YES | — | 调度结果码：200=成功，500=失败，0=初始 |
| `trigger_msg` | text | NO | NULL | 调度过程日志（含路由信息、执行器地址列表），HTML 格式，**无长度截断** |
| `handle_time` | datetime | NO | NULL | 执行完成时间（回调写入） |
| `handle_code` | int(11) | YES | — | 执行结果码：0=进行中，200=成功，500=失败 |
| `handle_msg` | text | NO | NULL | 执行结果描述（回调写入），超过 15000 chars 时应用层截断 |
| `alarm_status` | tinyint(4) | YES | 0 | 告警状态，见下方状态机说明 |

**alarm_status 状态机**（DDL 注释只记录了 0/1/2/3，-1 仅在代码注释中定义）：

| 值 | 含义 | 代码位置 |
|----|------|---------|
| 0 | 默认（待处理） | DDL 默认值 |
| -1 | 锁定中（处理中间态，进程崩溃后会永久卡在此状态） | [JobFailAlarmMonitorHelper.java:62](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java#L62) |
| 1 | 无需告警（job 已不存在） | 同上 |
| 2 | 告警成功 | 同上 |
| 3 | 告警失败 | 同上 |

#### 索引

| 索引名 | 字段 | 类型 | DDL 行号 |
|--------|------|------|---------|
| PRIMARY | `id` | PRIMARY KEY | :100 |
| `I_trigger_time` | `trigger_time` | 普通索引 | :101 |
| `I_handle_code` | `handle_code` | 普通索引 | :102 |
| `I_jobgroup` | `job_group` | 普通索引 | :103 |
| `I_jobid` | `job_id` | 普通索引 | :104 |

**无联合索引，无覆盖索引，无前缀索引。**

#### 引擎与字符集

ENGINE=InnoDB，DEFAULT CHARSET=utf8mb4。

---

### 1.2 写入路径

每次任务执行产生最多 4 次写（2 次触发 + 1 次回调 + 2 次告警状态），其中告警状态写在任务失败时触发。

#### 写入 W-1：INSERT（触发开始时）

- **写入方**：`JobTrigger.processTrigger()`
- **代码位置**：[JobTrigger.java:148-152](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/trigger/JobTrigger.java#L148)
- **写入字段**：仅 `job_group / job_id / trigger_time / trigger_code=0 / handle_code=0`，其余字段为 NULL/0
- **触发时机**：每次 `processTrigger()` 调用（Cron/FIX_RATE/手动/API/子任务触发均走此路径）
- **SHARDING_BROADCAST 特殊性**：每个分片节点各调用一次，N 个执行器产生 N 行，见 [JobTrigger.java:102-104](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/trigger/JobTrigger.java#L102)

#### 写入 W-2：UPDATE trigger info（HTTP /run 完成后）

- **写入方**：`JobTrigger.processTrigger()`
- **代码位置**：[JobTrigger.java:238-246](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/trigger/JobTrigger.java#L238)
- **写入字段**：trigger_time / trigger_code / trigger_msg / executor_address / executor_handler / executor_param / executor_sharding_param / executor_fail_retry_count
- **trigger_msg 内容**：拼接触发类型、Admin IP、**执行器地址列表（全量）**、路由策略、阻塞策略、超时时间、重试次数、HTTP /run 响应结果。HTML 格式，**无长度截断**

#### 写入 W-3：UPDATE handle info（执行器回调时）

- **写入方**：`JobCompleter.complete()` → `xxlJobLogMapper.updateHandleInfo()`
- **调用链**：
  - 正常回调：`AdminBizImpl.callback()` → `JobCompleteHelper.callbackThreadPool` → `doCallback()` → `JobCompleter.complete()`
  - 执行器失联：`JobCompleteHelper.monitorThread` 直接调 `JobCompleter.complete()`，[JobCompleteHelper.java:82-90](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobCompleteHelper.java#L82)
  - 手动 Kill：`JobLogController.logKill()` → `JobCompleter.complete()`，[JobLogController.java:207-210](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java#L207)
- **handle_msg 截断**：超过 15000 chars 时截断，截断发生在子任务触发消息拼接**之后**，[JobCompleter.java:45-47](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java#L45)

#### 写入 W-4：UPDATE alarm_status（告警处理时，仅失败任务）

- **写入方**：`JobFailAlarmMonitorHelper`
- **每条失败日志 2 次写**：先 0→-1（加锁），处理完后 -1→终态（1/2/3），[JobFailAlarmMonitorHelper.java:46-70](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java#L46)

---

### 1.3 查询路径

| 查询 ID | SQL 方法 | 调用方 | 频率 | WHERE 条件 | 可用索引 |
|---------|---------|--------|------|-----------|---------|
| Q-1 | `pageList` / `pageListCount` | `JobLogController.pageList()` | 用户操作触发 | job_group / job_id / trigger_time 范围 / handle_code 状态（动态组合） | I_jobid 或 I_trigger_time（单次只能选一个） |
| Q-2 | `load(id)` | `JobLogController.logDetailPage()` | 用户操作触发 | `id = ?` | PRIMARY KEY |
| Q-3 | `load(id)` | `JobLogController.logDetailCat()` | 用户轮询（每次查看日志流式加载均触发） | `id = ?` | PRIMARY KEY |
| Q-4 | `findFailJobLogIds` | `JobFailAlarmMonitorHelper` | **每 10 秒** | `NOT(... handle_code ...) AND alarm_status=0 LIMIT 1000` | I_handle_code（负向条件，实际可能失效） |
| Q-5 | `load(id)` | `JobCompleteHelper.doCallback()` | 执行器回调时 | `id = ?` | PRIMARY KEY |
| Q-6 | `findLostJobIds` | `JobCompleteHelper.monitorThread` | **每 60 秒** | trigger_code=200 AND handle_code=0 AND trigger_time<=losedTime，LEFT JOIN xxl_job_registry | I_handle_code（JOIN 被驱动侧无 registry_value 单列索引） |
| Q-7 | `findLogReport` | `JobLogReportHelper` | **每 1 分钟 × 3 天** | `trigger_time BETWEEN day_start AND day_end`（全天聚合） | I_trigger_time（需回表取 handle_code/trigger_code） |
| Q-8 | `findClearLogIds` | `JobLogReportHelper`（自动）/ `JobLogController.clearLog()`（手动） | 自动：每 24h；手动：随机 | trigger_time <= clearBeforeTime 或按条数 NOT IN 子查询 | I_trigger_time |

**pageList 排序问题**：`ORDER BY t.id DESC`，排序字段是主键 id，与可用过滤索引（I_trigger_time、I_jobid）均不对齐，多条件查询时会产生 filesort。[XxlJobLogMapper.xml:77](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L77)

---

### 1.4 清理路径

#### 自动清理

- **触发方**：`JobLogReportHelper.logReportThread`，[JobLogReportHelper.java:97-127](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L97)
- **触发条件**：`logretentiondays > 0` 且距上次清理 > 24h
- **触发时机**：不固定。`lastCleanLogTime` 初始值为 0，Admin **每次重启后第一分钟**必然触发清理，之后滚动计算 24h 间隔
- **清理范围**：全表按 `trigger_time <= now - logretentiondays` 过期，不区分 job_group / job_id
- **批次大小**：1000 行/批，`do-while` 循环无 batch 间隔，直到返回空
- **可观测性**：`clearLog()` 返回值被忽略，正常完成**无任何日志输出**，[JobLogReportHelper.java:116](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L116)
- **配置项**：`xxl.job.logretentiondays`，-1 表示永不清理

#### 手动清理（管理台）

- **触发方**：`JobLogController.clearLog()`，[JobLogController.java:217-265](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java#L217)
- **策略**：按时间（1/3/6/12 个月前）或按条数保留（最新 1000/10000/30000/100000 条）
- **并发风险**：与自动清理共用同一 SQL，无互斥锁

**无归档**：清理是物理 DELETE，无数据备份步骤。

---

### 1.5 后台线程清单

| 线程名 | 所在类 | 间隔 | 涉及 xxl_job_log 的操作 |
|--------|--------|------|------------------------|
| `xxl-job, admin JobLogReportHelper` | [JobLogReportHelper.java:143](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L143) | 每 1 分钟；清理每 24h | findLogReport（读）；findClearLogIds + clearLog（删） |
| `xxl-job, admin JobFailMonitorHelper` | [JobFailAlarmMonitorHelper.java:95](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java#L95) | 每 10 秒 | findFailJobLogIds（读）；updateAlarmStatus ×2（写）；load（读）；updateTriggerInfo（写，重试时） |
| `xxl-job, admin JobLosedMonitorHelper` | [JobCompleteHelper.java:115](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobCompleteHelper.java#L115) | 每 60 秒 | findLostJobIds（读）；updateHandleInfo（写） |
| `callbackThreadPool-*`（core=2，max=20，队列 3000） | [JobCompleteHelper.java:37-55](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobCompleteHelper.java#L37) | 事件驱动 | load（读）；updateHandleInfo（写） |

---

### 1.6 涉及的 Controller / Service / Mapper 清单

| 类型 | 类 | 职责 |
|------|-----|------|
| Controller | [JobLogController.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java) | pageList / logKill / clearLog / logDetailPage / logDetailCat |
| Component | [JobTrigger.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/trigger/JobTrigger.java) | W-1 INSERT + W-2 UPDATE trigger info |
| Component | [JobCompleter.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java) | W-3 UPDATE handle info，含 handle_msg 截断 |
| Thread | [JobCompleteHelper.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobCompleteHelper.java) | callbackThreadPool + monitorThread 生命周期 |
| Thread | [JobLogReportHelper.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java) | 报表刷新 + 自动清理 |
| Thread | [JobFailAlarmMonitorHelper.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java) | 失败告警 + 重试 |
| Service | [AdminBizImpl.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/service/impl/AdminBizImpl.java) | 执行器回调 HTTP 入口 |
| Mapper | [XxlJobLogMapper.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/mapper/XxlJobLogMapper.java) | xxl_job_log 全部 CRUD |
| Mapper | [XxlJobLogReportMapper.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/mapper/XxlJobLogReportMapper.java) | xxl_job_log_report 统计聚合 |
| XML | [XxlJobLogMapper.xml](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml) | 所有 SQL 定义 |

---

### 1.7 待确认项

以下内容代码层面无法直接确认，需人工核实：

| # | 待确认内容 | 影响 |
|---|-----------|------|
| U-1 | `xxl.job.logretentiondays` 生产配置值 | 决定主表当前实际行数量级 |
| U-2 | `logretentiondays` 配置文件默认值（-1 还是其他） | 若缺少配置项是否抛异常 |
| U-3 | 生产环境单个执行器组最大地址数 | 影响 trigger_msg 的 P99 体积 |
| U-4 | `xxl_job_registry` 生产行数 | 影响 findLostJobIds JOIN 代价 |
| U-5 | `logDetailCat` 的生产 QPS | 决定重复主键查询是否构成实际瓶颈 |
| U-6 | Admin 发版/重启频率与时间规律 | 影响 O-1 清理时机问题的严重程度 |
| U-7 | 生产环境是否有 SHARDING_BROADCAST 类型任务 | 影响 C-3 行数统计口径的准确性 |

---

## 二、问题诊断清单

> 每条问题附：具体卡点、证据（代码位置/SQL）、根因假设、待验证动作。
> 根因标"假设"的表示尚未通过测试验证。

### 性能类

---

#### P-1：管理台翻页查询在多条件组合时无法同时利用多列索引

**问题卡点**：`pageList` 的 WHERE 条件是 `job_id + trigger_time 范围 + handle_code 状态` 的动态组合，表上 4 个索引全部是单列独立索引，MySQL 单次查询只能选一个，其余条件回表逐行判断。`ORDER BY t.id DESC` 与所有过滤索引均不对齐，产生 filesort。

**证据**：

- 4 个单列索引，无联合索引：[doc/db/tables_xxl_job.sql:100-104](../doc/db/tables_xxl_job.sql#L100)
- pageList WHERE + ORDER BY：[XxlJobLogMapper.xml:47-79](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L47)

**根因假设**：

- 假设 A：缺少 `(job_id, trigger_time)` 联合索引，两个最常见过滤字段只能其一走索引
- 假设 B：`ORDER BY id DESC` 与 `I_trigger_time` 不对齐，即便 trigger_time 过滤命中索引，排序仍需 filesort
- 假设 C：logStatus=2 的 `NOT IN (0,200) OR NOT IN (0,200)` 负向 OR 条件，优化器大概率走全表

**待验证**：对等量测试库执行 EXPLAIN，确认 type、key、rows，取得查询基线。

---

#### P-2：`findFailJobLogIds` 负向 WHERE 条件导致索引失效，每 10 秒潜在全表扫描

**问题卡点**：`NOT ((trigger_code IN (0,200) AND handle_code=0) OR (handle_code=200)) AND alarm_status=0`，负向复合条件对 `I_handle_code` 利用率差，`alarm_status` 无独立索引。此查询每 10 秒执行一次。

**证据**：

- SQL：[XxlJobLogMapper.xml:230-240](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L230)
- 调用频率：[JobFailAlarmMonitorHelper.java:81](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java#L81)
- alarm_status 无索引：[doc/db/tables_xxl_job.sql:100-104](../doc/db/tables_xxl_job.sql#L100)

**根因假设**：

- 假设 A：`NOT (...)` 复合负向条件让优化器无法估算选择率，选择全表扫描
- 假设 B：`alarm_status=0` 在表中行占比在稳定运行期间可能较高（所有进行中任务均为 0）

**待验证**：EXPLAIN 确认 type 是否为 ALL；统计生产库 alarm_status=0 行占比。

---

#### P-3：`findLostJobIds` 的 JOIN 被驱动侧无单列索引

**问题卡点**：`xxl_job_log LEFT JOIN xxl_job_registry ON executor_address = registry_value`，`registry_value` 只出现在三列联合唯一索引 `i_g_k_v(registry_group, registry_key, registry_value)` 的第三位，单独 registry_value 无法利用该索引做 ref 访问，被驱动侧走全表扫描。此查询每 60 秒执行一次。

**证据**：

- SQL：[XxlJobLogMapper.xml:249-260](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L249)
- xxl_job_registry 索引定义：[doc/db/tables_xxl_job.sql:32](../doc/db/tables_xxl_job.sql#L32)

**根因假设**：

- 假设 A：被驱动表走全表扫描，扫描行数 = xxl_job_registry 总行数 × 驱动表结果集大小
- 假设 B：`xxl_job_registry` 行数通常不大（执行器数 × 1 条/实例），但在执行器多时放大

**待验证**：EXPLAIN 确认 xxl_job_registry 侧 type；确认生产 xxl_job_registry 实际行数。

---

#### P-4：`findLogReport` 聚合每分钟对最近 3 天全量扫描，过去两天重复计算

**问题卡点**：`JobLogReportHelper` 每分钟对"今天/昨天/前天"各跑一次全天聚合（`BETWEEN day_start AND day_end`），昨天和前天的数据不再变化，一旦写入 `xxl_job_log_report` 后每分钟仍被重复聚合覆盖写入。聚合需回表取 handle_code 和 trigger_code（不在 `I_trigger_time` 覆盖范围内）。

**证据**：

- 聚合 SQL：[XxlJobLogMapper.xml:179-186](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L179)
- 每分钟 3 次调用：[JobLogReportHelper.java:44-89](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L44)

**根因假设**：

- 假设 A：设计意图是"实时刷新最近 3 天"，但未区分"今天（需实时）"和"历史天（已固化）"
- 假设 B：回表代价随单天日志量线性增长，日志量大时每分钟 3 次回表成本可感知

**待验证**：统计生产库单天平均日志行数；对照测试库观察聚合耗时。

---

#### P-5：清理批次之间无间隔，积压大量过期数据时连续持行锁阻塞在线查询

**问题卡点**：`do-while` 循环每批 DELETE 1000 行，批次间无 sleep。积压场景下（首次开启清理、logretentiondays 从 -1 改为正值）连续批量 DELETE 持续持有行锁，与在线 SELECT/UPDATE 产生锁争用。

**证据**：

- 清理循环无 sleep：[JobLogReportHelper.java:112-118](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L112)
- DELETE IN (1000 ids) SQL：[XxlJobLogMapper.xml:222-228](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L222)

**根因假设**：

- 假设 A：InnoDB DELETE IN (...) 持有被删行的行锁，1000 行同时锁定期间其他事务等待
- 假设 B：RR 隔离级别下 DELETE 额外加间隙锁，锁范围比 RC 更大

**待验证**：压测环境同时跑清理和查询，观察 `SHOW ENGINE INNODB STATUS` 的 lock wait；确认生产隔离级别。

---

### 容量类

---

#### C-1：`trigger_msg` 无长度上限，拼入全量执行器地址列表导致单行体积不可控

**问题卡点**：`trigger_msg` 由 `triggerMsgSb` 拼接，其中包含 `group.getRegistryList()`（执行器组全部地址列表），执行器数量多时体积大。`handle_msg` 有 15000 chars 截断保护，`trigger_msg` 无对应保护。

**证据**：

- `group.getRegistryList()` 整体拼入：[JobTrigger.java:205](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/trigger/JobTrigger.java#L205)
- handle_msg 截断逻辑：[JobCompleter.java:45-47](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java#L45)

**根因假设**：

- 假设 A：大执行器组触发时 trigger_msg 达到数 KB，放大主表平均行体积
- 假设 B：TEXT 字段超过 768 bytes 时 InnoDB 溢出到 off-page，回表读 TEXT 额外 IO

**待验证**：`SELECT MAX(LENGTH(trigger_msg)), AVG(LENGTH(trigger_msg)) FROM xxl_job_log`；确认生产最大执行器组地址数。

---

#### C-2：`handle_msg` 截断在子任务消息拼接之后执行，截断点可能落在 HTML 中间

**问题卡点**：`JobCompleter.complete()` 先拼接子任务触发结果（HTML 字符串），再做 15000 chars 截断。若父任务 handleMsg 接近上限且有多子任务，截断点落在 HTML 中间，管理台详情页渲染异常。

**证据**：

- 执行顺序：先 `processChildJob()` 再截断：[JobCompleter.java:42-47](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java#L42)
- 子任务消息拼接：[JobCompleter.java:108](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java#L108)

**根因假设**：正常情况下 handleMsg 不接近上限，此问题仅在执行器打印大量日志后回调时触发。影响是 UI 显示异常，不影响 DB 存储和调度逻辑。

**待验证**：`SELECT MAX(LENGTH(handle_msg)) FROM xxl_job_log` 评估实际分布。

---

#### C-3：`xxl_job_log_report` 不记录物理行数，主表行数增长趋势无可查数据

**问题卡点**：`xxl_job_log_report` 记录 running/suc/fail 三种结果计数，不是主表物理行数。容量预测最直接的指标（每天新增多少行）无任何记录。

**证据**：

- `xxl_job_log_report` 字段定义：[doc/db/tables_xxl_job.sql:108-119](../doc/db/tables_xxl_job.sql#L108)，无 log_count 或 total_rows 字段
- 聚合 SQL 用 COUNT(handle_code) 而非 COUNT(*)：[XxlJobLogMapper.xml:179-186](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L179)

**根因假设**：

- 假设 A：无 SHARDING_BROADCAST 任务时，触发次数等于物理行数，两者一致
- 假设 B：有分片任务时，一次触发产生 N 行，running+suc+fail 计数 < 实际行数

**待验证**：确认生产是否有分片任务；对照 COUNT(*) 与报表汇总值。

---

#### C-4：手动清理"按条数保留"策略使用 NOT IN 子查询，大保留数时性能不可控

**问题卡点**：`findClearLogIds` 按条数清理时，内层子查询 `SELECT id ... ORDER BY trigger_time DESC LIMIT 0, #{clearBeforeNum}`，外层 `id NOT IN (子查询结果)`。`clearBeforeNum=100000` 时子查询返回 10 万行，外层 NOT IN 代价极高。

**证据**：

- NOT IN 子查询 SQL：[XxlJobLogMapper.xml:200-216](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L200)
- clearBeforeNum 最大值 100000（type=8）：[JobLogController.java:249](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java#L249)

**根因假设**：MySQL 对 `NOT IN (子查询)` 物化为临时表再做 anti-join，10 万 id 临时表匹配主表代价高。

**待验证**：在 100 万行测试库上执行 type=8 清理，记录耗时；确认该选项是否在生产实际被使用。

---

### 运维类

---

#### O-1：Admin 重启后第一分钟必然触发清理，触发时机无法预期

**问题卡点**：`lastCleanLogTime` 初始值为 0，Admin 每次重启后第一分钟的循环必然满足 `System.currentTimeMillis() - 0 > 24h`，立即触发清理。重启时间随机（高峰期发版、故障重启），清理可能在非预期时间触发。

**证据**：

- `lastCleanLogTime = 0` 初始值：[JobLogReportHelper.java:36](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L36)
- 24h 判断逻辑：[JobLogReportHelper.java:99-100](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L99)

**根因假设**：设计意图是"每 24 小时清理一次"，但用 JVM 内存变量记录上次时间而非持久化，重启后状态丢失。

**待验证**：确认生产 Admin 的重启频率和时间规律；确认 logretentiondays 是否大于 0（若为 -1 此问题不触发）。

---

#### O-2：清理执行结果无日志输出，运维完全不可观测

**问题卡点**：`clearLog()` 返回被删除行数（int），调用方忽略返回值，正常完成无任何日志输出。运维无法从日志确认清理是否执行、删了多少行、耗时多久。

**证据**：

- 返回值被忽略：[JobLogReportHelper.java:116](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L116)，无变量接收
- 正常路径无日志，仅 catch 块有 error 日志：[JobLogReportHelper.java:122-126](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L122)

**根因**：确定性问题，无需额外验证。

---

#### O-3：`alarm_status=-1` 无超时恢复，进程崩溃后永久泄漏

**问题卡点**：`JobFailAlarmMonitorHelper` 将日志 alarm_status 从 0 改为 -1 后，若告警发送期间进程崩溃（kill -9 或 SMTP 无响应超时后进程终止），该行永远停留在 alarm_status=-1，既不重试告警也不被后续扫描发现（`findFailJobLogIds` 的 WHERE 限定 `alarm_status=0`）。

**证据**：

- `findFailJobLogIds` WHERE 限定 alarm_status=0：[XxlJobLogMapper.xml:230-240](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L230)
- 0→-1 到 -1→终态中间段：[JobFailAlarmMonitorHelper.java:46-70](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java#L46)，catch 块只打日志不回滚 alarm_status

**根因假设**：

- 假设 A：正常运行时单线程处理，崩溃窗口极短，-1 泄漏概率低但不为零
- 假设 B：告警发送（SMTP/Webhook）无响应超时时，进程被 kill -9 的概率更高

**待验证**：`SELECT COUNT(*) FROM xxl_job_log WHERE alarm_status=-1` 确认生产是否已有积压。

---

#### O-4：`logDetailCat` 每次轮询都读 DB 取不变的 executorAddress，作者已注释 TODO

**问题卡点**：用户查看任务执行日志时，`logDetailCat` 接口每次轮询均执行 `load(logId)` 取 `executorAddress`，而该字段在任务触发后不再变化。作者已在代码中注释 `// todo, need to improve performance`。

**证据**：[JobLogController.java:294-295](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java#L294)

**根因假设**：主键查询代价低，在低并发场景不构成瓶颈。并发查看日志时（如告警爆发期间多人同时看日志流）叠加。

**待验证**：确认 logDetailCat 生产 QPS；是否有 DB 读副本分担读流量。

---

#### O-5：手动清理与自动清理并发执行无互斥，可能相互锁等待

**问题卡点**：`JobLogController.clearLog()` 和 `JobLogReportHelper.logReportThread` 共用同一套 `findClearLogIds + clearLog` SQL，两者之间无锁或标记防止并发。同时触发时两个 do-while 循环查到重叠 id 集合，各自 DELETE，相互等待行锁。

**证据**：

- 自动清理：[JobLogReportHelper.java:112-118](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L112)
- 手动清理：[JobLogController.java:256-262](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java#L256)
- 无共享状态

**根因假设**：自动清理时间不固定（随重启漂移），手动清理随机触发，碰撞概率低。并发 DELETE 同行在 MySQL 层不损坏数据，但会产生锁等待延长清理总时间。

**待验证**：确认是否多 Admin 节点部署（多节点时并发概率更高，每个节点独立运行自动清理线程）。

---

## 三、需求文档

### 3.1 目标清单

| ID | 目标描述 | 对应问题 | 优先级 |
|----|---------|---------|--------|
| G-1 | 新增联合索引 `idx_jobid_triggertime(job_id, trigger_time)`，覆盖管理台翻页查询最常见的多字段过滤模式 | P-1 | 必须 |
| G-2 | 重写 `findFailJobLogIds` SQL，消除 `NOT(...)` 负向复合条件，使查询可走索引 | P-2 | 重要 |
| G-3 | 在 `xxl_job_registry.registry_value` 新增单独索引，修复 `findLostJobIds` JOIN 被驱动侧全表扫描 | P-3 | 重要 |
| G-4 | 在 `JobLogReportHelper` 中区分"今天"和"历史天"，历史天数据已固化后跳过重复聚合 | P-4 | 锦上添花 |
| G-5 | 清理 `do-while` 每批 DELETE 后增加 sleep（候选值：100ms），防止积压时持续持行锁 | P-5 | 必须 |
| G-6 | 在 `JobTrigger.processTrigger()` 写入 `updateTriggerInfo` 前对 `trigger_msg` 做 15000 chars 截断 | C-1 | 必须 |
| G-7 | 接收 `clearLog()` 返回值，每次清理完成后打 INFO 日志（删除行数 + 耗时 + 清理边界时间） | O-2 | 必须 |
| G-8 | 在 `JobFailAlarmMonitorHelper` 扫描循环中增加对 `alarm_status=-1 AND trigger_time < now-10min` 的检测与自动重置 | O-3 | 重要 |
| G-9 | 将 `lastCleanLogTime` 初始值从 0 改为 `System.currentTimeMillis()`，防止 Admin 重启后立即触发清理 | O-1 | 重要 |
| G-10 | 在 `xxl_job_log_report` 新增 `log_count` 列，记录每天主表新增行数趋势 | C-3 | 锦上添花 |

---

### 3.2 验收标准

#### G-1 验收标准

- **AC-1a**（自动化测试）：在含 100 万行的测试库上，对 `pageList` 执行 `EXPLAIN`，过滤条件 `job_id=? AND trigger_time BETWEEN ? AND ?`，结果中 `type` 为 `ref` 或 `range`，`rows` 估算 ≤ 50000。
- **AC-1b**（压测）：对 `/joblog/pageList` 按单个 job_id + 最近 30 天时间范围请求，P95 响应时间 ≤ 300ms（需在加索引前先压测获取基线作为对照）。
- **AC-1c**（人工检查）：ORDER BY 对齐改造后，`EXPLAIN` 的 `Extra` 列不含 `Using filesort`。

#### G-2 验收标准

- **AC-2a**（自动化测试）：在含 10 万行、其中 5 万行 `alarm_status=0` 的测试库上，`EXPLAIN` 的 `type` 不为 `ALL`，`rows` 估算 ≤ 实际 `alarm_status=0` 行数的 150%。
- **AC-2b**（人工检查）：改写后的 SQL 与原 SQL 在相同测试数据集上结果集完全一致（行数相同，id 集合相同）。

#### G-3 验收标准

- **AC-3a**（自动化测试）：`EXPLAIN` 中 `xxl_job_registry` 侧的 `type` 为 `ref`，不为 `ALL`。
- **AC-3b**（人工检查）：在 `xxl_job_registry` 含 500 行的测试环境下，`findLostJobIds` 单次执行耗时 ≤ 10ms（`SHOW PROFILES` 验证）。

#### G-4 验收标准

- **AC-4a**（人工检查）：修改后，在 `xxl_job_log_report` 已有昨天记录的情况下，观察 `logReportThread` 日志，确认昨天和前天的聚合查询不再被执行（通过 MySQL general log 或应用日志验证）。
- **AC-4b**（监控观察）：对比修改前后 `Slow_queries` 计数器在 1 小时内的差值，改造后减少 ≥ 2 条/小时。

#### G-5 验收标准

- **AC-5a**（压测）：同时运行清理（积压 50 万行过期数据）和翻页查询，查询 P99 ≤ 1000ms（加 sleep 前同等条件下基线记录对照）。
- **AC-5b**（人工检查）：单批 1000 行 DELETE 耗时 ≤ 200ms（slow query log 阈值设 200ms，不触发则达标）。

#### G-6 验收标准

- **AC-6a**（自动化测试）：单元测试覆盖截断逻辑——构造长度超过 15000 chars 的 triggerMsg，断言写入 DB 前的值长度 ≤ 15000。
- **AC-6b**（人工检查）：在有 50 个执行器地址的组上触发任务后，`SELECT LENGTH(trigger_msg) FROM xxl_job_log ORDER BY id DESC LIMIT 1` 结果 ≤ 15000。

#### G-7 验收标准

- **AC-7a**（人工检查）：触发清理后，应用日志中找到含 `deleted=N rows` 和 `cost=Xms` 字段的 INFO 级别日志行。
- **AC-7b**（人工检查）：日志中 `deleted` 行数与 `SELECT COUNT(*)` 前后差值误差为 0。

#### G-8 验收标准

- **AC-8a**（集成测试）：插入 `alarm_status=-1`、`trigger_time=now-20min` 的日志行，触发扫描，断言：① 应用日志出现含该 logId 的 WARN 行；② 扫描后该行 `alarm_status=0`。
- **AC-8b**（集成测试）：插入 `alarm_status=-1`、`trigger_time=now-5min` 的行（未超过 10min），触发扫描，断言该行 `alarm_status` 仍为 -1（防止误重置正常处理中的行）。

#### G-9 验收标准

- **AC-9a**（人工检查）：重启 Admin 后观察前 2 分钟应用日志，无清理相关 SQL 执行。
- **AC-9b**（代码审查）：确认代码中 `lastCleanLogTime = System.currentTimeMillis()`（而非 `= 0`）。

#### G-10 验收标准（如纳入）

- **AC-10a**（自动化测试）：清理完成后，`xxl_job_log_report` 当天记录的 `log_count` 不为 NULL，且与 `SELECT COUNT(*) FROM xxl_job_log WHERE DATE(trigger_time) = today` 差值 ≤ 1%。

---

### 3.3 非目标清单

| # | 不做的事 | 原因 |
|---|---------|------|
| N-1 | 不引入分库分表或分区表 | 联合索引可解决百万级数据量的查询问题；分表需大幅改造 Mapper / 清理 / 统计逻辑，工程量与当前问题规模不匹配。触发条件：单表超 5000 万行且索引优化后仍不满足延迟目标时再评估 |
| N-2 | 不引入应用层查询缓存（Redis 等） | pageList 是带时间范围和 job_id 的动态查询，缓存命中率接近 0；日志数据实时性要求高，缓存失效复杂度远超收益 |
| N-3 | 不对 trigger_msg / handle_msg 做大字段拆表 | 拆表需改造 7 个 Mapper SQL、新增表、存量数据迁移，工程风险高；应用层截断（G-6）先做，观察效果后再决定是否拆表 |
| N-4 | 不将物理 DELETE 改为软删除 | 软删除需全部 7 个查询加 `AND is_deleted=0`，改动面极大，且软删除行永久占存储需第二套清理机制，无价值收益 |
| N-5 | 不做执行器侧本地日志文件清理 | 执行器本地 `/data/applogs/xxl-job/` 是独立路径，Admin 无直接控制权，属于另一个独立治理范围 |
| N-6 | 不替换分布式调度锁机制 | `xxl_job_lock SELECT FOR UPDATE` 改为 Redis 锁是架构级决策，需架构团队评审和全集群灰度，超出本次表治理范围 |
| N-7 | 不修正 xxl_job_log_report 的分片任务统计口径 | 修正需在触发链路区分分片任务，改动点在 JobTrigger 和 JobLogReportHelper 两个核心文件，属于独立问题，独立立项 |
| N-8 | 不为 alarm_status 增加状态历史追踪表 | G-8 只需一个检测查询和 UPDATE，引入状态历史表是过度设计，超出"防止 -1 卡死"的范围 |

---

## 四、方案与改动

### 4.1 方案选型

**选定方案**：A（索引优化）+ H（清理逻辑修复）+ C（MySQL 分区表，中期）

| 方向 | 覆盖问题 | 覆盖目标 |
|------|---------|---------|
| A 索引优化（5 个改造点 A-1~A-5） | P-1、P-2、P-3 | G-1、G-2、G-3 |
| H 清理逻辑修复（5 个改造点 H-1~H-5） | O-1、O-2、O-3、O-4、O-5 | G-5、G-7、G-8、G-9 |
| C 分区表（4 个改造点 C-1~C-4） | C-1（行数增长）| G-3（清理效率） |

**决策依据摘要**（完整决策见 [decisions/001-log-table-refactor-solution.md](decisions/001-log-table-refactor-solution.md)）：

- 选 A：零停机风险，团队 MySQL 技能栈覆盖，收益最直接
- 选 H：纯 Java 改动，每个子点独立可逐一上线，alarm_status=-1 是已知存量 bug 必须修
- 选 C：中长期清理成本远低于持续 DELETE；允许停机窗口，全表重建技术上可行
- 不选 E/F/G/I：与"不引入新基础设施"约束冲突
- 暂缓 D/J/K：收益条件当前不满足，按需评估

**考虑过但未选的方案**：pt-archiver 归档（B）、冷热分离（D）、ClickHouse（E）、分库分表（F）、Kafka 异步写（G）、时序库（I）、读写分离（J）、垂直拆表（K）——见 ADR 表格。

---

### 4.2 改动记录

> 本节用于记录实际上线的改造点进展。改造点清单和影响矩阵见 [log-table-refactor-changes.md](log-table-refactor-changes.md)。

#### 改造点状态汇总

| 改造点 | 描述 | 状态 | 上线日期 | PR / 提交 |
|--------|------|------|---------|-----------|
| A-1 | 索引 (handle_code, alarm_status) | 待执行 | — | — |
| A-2 | 索引 (job_id, trigger_time) | 待执行 | — | — |
| A-3 | findFailJobLogIds SQL改写 | 待执行 | — | — |
| A-4 | pageList ORDER BY 评估决策 | 待执行 | — | — |
| A-5 | findLostJobIds 补索引 | 待执行 | — | — |
| H-1 | lastCleanLogTime 初始值 | 待执行 | — | — |
| H-2 | 清理批次间 sleep | 待执行 | — | — |
| H-3 | clearLog 返回值 | 待执行 | — | — |
| H-4 | alarm_status=-1 补偿重置 | 待执行 | — | — |
| H-5 | 手动/自动清理互斥 | 待执行 | — | — |
| C-1 | PK 兼容性确认（决策点） | 待执行 | — | — |
| C-2 | 分区 DDL 全表重建 | 待执行 | — | — |
| C-3 | 月度新增分区运维规程 | 待执行 | — | — |
| C-4 | DROP PARTITION 清理 | 待执行 | — | — |

#### 变更日志

首个实际上线记录在此处追加，格式：日期 / 改造点 / 实际变更描述 / 结果。

---

### 4.3 上线核查清单

待填入（计划在 A + H 上线前补充具体操作步骤和回滚方案）。
