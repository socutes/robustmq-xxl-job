# xxl_job_log 表治理——改造点清单与影响矩阵

> 本文档是 [log-table-refactor-plan.md](log-table-refactor-plan.md) 第四节的配套执行文档。
> 方案选型决策见 [decisions/001-log-table-refactor-solution.md](decisions/001-log-table-refactor-solution.md)。
> 代码位置基于 v3.4.1-SNAPSHOT。

---

## 一、改造点清单

共 13 个改造点，分三个模块：A（索引优化）、H（清理逻辑修复）、C（分区表改造）。

---

### 模块 A：索引优化（纯 DDL + SQL，零停机风险）

---

#### A-1：添加联合索引 `(handle_code, alarm_status)`

**文件**：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)

**当前问题**：
`findFailJobLogIds`（[XxlJobLogMapper.xml:230-240](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L230)）使用 `WHERE !((...) OR (...)) AND alarm_status=0` 的负向复合条件，MySQL 无法有效利用单列 `I_handle_code` 索引，每 10 秒全扫后回表过滤。

**改造动作**：
```sql
ALTER TABLE xxl_job_log
  ADD INDEX I_handle_alarm (handle_code, alarm_status);
```

**难度**：低

**依赖**：与 A-3 同时上线（索引和 SQL 改写配合才能命中）

---

#### A-2：添加联合索引 `(job_id, trigger_time)`

**文件**：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)

**当前问题**：
`pageList`（[XxlJobLogMapper.xml:47-79](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L47)）最常见查询模式 `WHERE job_id = ? AND trigger_time BETWEEN ? AND ?`，只有单列索引，MySQL 只能选一个，另一个条件回表过滤；`ORDER BY t.id DESC` 与过滤索引不对齐，产生 filesort。

**改造动作**：
```sql
ALTER TABLE xxl_job_log
  ADD INDEX I_jobid_trigtime (job_id, trigger_time);
```
加完后执行 `EXPLAIN SELECT ... WHERE job_id=? AND trigger_time BETWEEN ? AND ? ORDER BY id DESC`，确认 `key` 为 `I_jobid_trigtime`，评估 `Extra` 中 filesort 是否消失（如未消失联动评估 A-4）。

**难度**：低（DDL）至 中（如联动改 ORDER BY）

**依赖**：A-4（如决定同步改 ORDER BY 方向）

---

#### A-3：改写 `findFailJobLogIds` 为正向等值条件

**文件**：[XxlJobLogMapper.xml:230-240](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L230)

**当前代码**（第 232-236 行）：
```xml
WHERE !(
    (trigger_code in (0, 200) and handle_code = 0)
    OR
    (handle_code = 200)
)
AND `alarm_status` = 0
```

**问题**：负向复合条件（`NOT (... OR ...)`）无法直接走索引，需扫描所有 `alarm_status=0` 行后逐行判断 NOT 条件。

**改造动作**：
```xml
WHERE handle_code = 500
  AND alarm_status = 0
ORDER BY id ASC
LIMIT #{pagesize}
```
**前置验证**：确认 `handle_code` 和 `trigger_code` 的合法值只有 `{0, 200, 500}`（查 `XxlJobContext` 常量），两个表达式语义等价后方可替换；若不确定先用 `handle_code NOT IN (0, 200)` 作为过渡。

**难度**：低（改 XML），但需语义等价验证

**依赖**：A-1（配合联合索引，两者同时上线）

---

#### A-4：评估并决定 `pageList ORDER BY` 方向

**文件**：[XxlJobLogMapper.xml:77](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L77)

**当前代码**（第 77 行）：
```xml
ORDER BY t.id DESC
```

**问题**：`id` 是自增主键，语义等价于按插入时间倒序；但加了 `I_jobid_trigtime(job_id, trigger_time)` 后，索引排序方向与 `ORDER BY id` 不同，filesort 可能仍存在。

**改造动作（二选一，需确认后执行）**：
- 选项 1：改为 `ORDER BY t.trigger_time DESC`，消除 filesort，需验证 UI 分页排序体验无变化
- 选项 2：保持 `ORDER BY t.id DESC`，接受 filesort（分区/归档后行数控制，filesort 成本可接受）

**难度**：低（改 XML），但需 UI 回归测试

**依赖**：A-2（先加索引再评估 filesort 是否消失）

---

#### A-5：修复 `findLostJobIds` JOIN 被驱动侧无单列索引

**文件**：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)（DDL 变更），[XxlJobLogMapper.xml:249-260](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml#L249)（只读，无需改）

**当前代码**（第 254 行）：
```xml
LEFT JOIN xxl_job_registry t2 ON t.executor_address = t2.registry_value
```

**问题**：`xxl_job_registry` 上索引 `UNIQUE KEY i_g_k_v (registry_group, registry_key, registry_value)`，`registry_value` 是第三列，单独做 JOIN 条件无法命中，被驱动侧全表扫描。

**改造动作**：
```sql
ALTER TABLE xxl_job_registry
  ADD INDEX I_registry_value (registry_value);
```
纯 DDL，不改 Java/XML 代码。

**难度**：低

**依赖**：无

---

### 模块 H：清理逻辑修复（Java 代码改动，无基础设施依赖）

---

#### H-1：修复 `lastCleanLogTime` 初始值导致启动后立即清理

**文件**：[JobLogReportHelper.java:36](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L36)

**当前代码**（第 36 行）：
```java
long lastCleanLogTime = 0;
```

**问题**：`System.currentTimeMillis() - 0 > 24h` 在任何时刻重启后立即为 true，导致重启后首分钟触发全量清理 do-while 循环，产生不可预期 IO 毛刺。

**改造动作**（第 36 行改为）：
```java
long lastCleanLogTime = System.currentTimeMillis();
```

**难度**：低（单行）

**依赖**：无

---

#### H-2：清理批次间加 sleep 防止 IO 毛刺

**文件**：[JobLogReportHelper.java:113-118](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L113)

**当前代码**（第 113-118 行）：
```java
do {
    logIds = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().findClearLogIds(0, 0, clearBeforeTime, 0, 1000);
    if (logIds!=null && !logIds.isEmpty()) {
        XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().clearLog(logIds);
    }
} while (logIds!=null && !logIds.isEmpty());
```

**问题**：批次间无 sleep，积压场景下连续 DELETE 持续占用主库 IO，影响 L-1 触发写入；无 `!toStop` 检查导致 Admin 关闭时清理循环阻塞 stop()。

**改造动作**（替换第 113-118 行）：
```java
do {
    logIds = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().findClearLogIds(0, 0, clearBeforeTime, 0, 1000);
    if (logIds!=null && !logIds.isEmpty()) {
        int deleted = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().clearLog(logIds);
        logger.info(">>>>>>>>>>> xxl-job, log-clean batch deleted:{} rows", deleted);
        TimeUnit.MILLISECONDS.sleep(100);
    }
} while (logIds!=null && !logIds.isEmpty() && !toStop);
```

**难度**：低

**依赖**：H-3（需 `clearLog` 返回 int 而非 void）

---

#### H-3：修复 `clearLog()` 返回值被忽略

**文件**：[JobLogReportHelper.java:116](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L116)；同时检查 `XxlJobLogMapper.java`（接口声明）

**当前代码**（第 116 行）：
```java
XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().clearLog(logIds);
```

**问题**：`<delete>` 语句 MyBatis 返回实际删除行数（int），调用方直接丢弃，清理行数完全不可观测。

**改造动作**：
1. 检查 `XxlJobLogMapper.java` 中 `clearLog` 方法签名，若返回 `void` 改为 `int`
2. 调用处改为（见 H-2 中已包含此变动）：
```java
int deleted = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().clearLog(logIds);
```

**难度**：低

**依赖**：H-2（两者联动修改同一代码块）

---

#### H-4：`alarm_status=-1` 永久卡死——启动时补偿重置

**文件**：[JobFailAlarmMonitorHelper.java:31](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobFailAlarmMonitorHelper.java#L31)（`start()` 方法内）；[XxlJobLogMapper.xml](../xxl-job-admin/src/main/resources/mapper/XxlJobLogMapper.xml)（需新增 updateStuckAlarmStatus SQL）

**当前问题**（第 46、70 行）：
- 第 46 行：`updateAlarmStatus(failLogId, 0, -1)` — 将记录锁定为 -1
- 第 70 行：`updateAlarmStatus(failLogId, -1, newAlarmStatus)` — 处理完后写终态
- 第 74-78 行 catch 块：只打 error 日志，不回滚 alarm_status
- 进程在 -1 期间崩溃 → 该记录永久卡在 -1，`findFailJobLogIds`（查 alarm_status=0）扫不到，既不重试也不告警

**改造动作（方案 A，最小改动）**：
在 `start()` 方法线程启动前（即 `monitorThread = new Thread(...)` 之前）加一次性补偿 UPDATE：
```java
// 启动时重置因进程崩溃卡在 -1 的记录，使其重新纳入告警扫描
XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().resetStuckAlarmStatus();
logger.warn(">>>>>>>>>>> xxl-job, reset stuck alarm_status=-1 records on startup");
```
配套在 `XxlJobLogMapper.xml` 新增：
```xml
<update id="resetStuckAlarmStatus">
    UPDATE xxl_job_log SET alarm_status = 0 WHERE alarm_status = -1
</update>
```
并在 `XxlJobLogMapper.java` 接口声明对应方法。

**上线注意**：执行后存量 -1 记录会重新进入 alarm_status=0，`JobFailAlarmMonitorHelper` 可能短时触发一批历史失败任务的补发告警。上线前先 `SELECT COUNT(*) FROM xxl_job_log WHERE alarm_status=-1` 确认存量数量。

**难度**：中（涉及 Mapper 新增接口）

**依赖**：无

---

#### H-5：手动清理与自动清理的并发互斥

**文件**：[JobLogReportHelper.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java)（新增字段）；[JobLogController.java:256-262](../xxl-job-admin/src/main/java/com/xxl/job/admin/controller/biz/JobLogController.java#L256)（新增判断）

**当前问题**：手动清理（Controller）和自动清理（后台线程）共用 `findClearLogIds + clearLog` SQL，无互斥，并发时相互锁等待（幂等，不损坏数据，但浪费 IO）。

**改造动作**：
在 `JobLogReportHelper` 类中新增：
```java
public final AtomicBoolean cleaning = new AtomicBoolean(false);
```
自动清理 do-while 前：
```java
if (!cleaning.compareAndSet(false, true)) {
    // 已有清理在运行，跳过
    return;
}
try {
    // ... do-while 清理循环 ...
} finally {
    cleaning.set(false);
}
```
`JobLogController.clearLog()` 中（在执行清理前）：
```java
if (XxlJobAdminBootstrap.getInstance().getJobLogReportHelper().cleaning.get()) {
    return Response.ofFail("自动清理进行中，请稍后重试");
}
```

**难度**：低

**依赖**：H-2（同步修 do-while，保持代码一致）

---

### 模块 C：分区表改造（中期，需停机窗口）

---

#### C-1：DDL 前置——确认 PK 与分区键兼容性

**文件**：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)（只读，决策确认）

**当前问题**：`xxl_job_log` 的 PK 是 `bigint id`（单列自增）。MySQL InnoDB 分区表要求分区键包含在主键中，按 `trigger_time` 分区必须将 PK 改为联合主键 `(id, trigger_time)`。

**需确认的三件事**：
1. `EXPLAIN SELECT ... WHERE id = ?` 在联合 PK `(id, trigger_time)` 下是否仍走聚簇索引（答案：InnoDB 联合 PK 最左前缀规则，`WHERE id = ?` 仍可走 PK，需在测试库验证）
2. `trigger_time` 字段无 NULL 值（分区键不可为 NULL）——查 [doc/db/tables_xxl_job.sql:95](../doc/db/tables_xxl_job.sql#L95) 确认 DDL 约束
3. 接受联合 PK 的 INSERT 略微增加维护开销

**这是决策确认点，不是代码改动，确认通过后才能执行 C-2。**

**难度**：中（决策分析）

**依赖**：C-2、C-3、C-4 均依赖本条确认

---

#### C-2：分区 DDL 执行（全表重建）

**文件**：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)（DDL 变更）

**当前问题**：无分区，清理只能 DELETE（逐行，产生 binlog），无法 DROP PARTITION（秒级，无 binlog 洪峰）。

**改造动作**：
```sql
-- Step 1：修改主键（包含分区键）
ALTER TABLE xxl_job_log
  DROP PRIMARY KEY,
  ADD PRIMARY KEY (id, trigger_time);

-- Step 2：添加按月分区（预建至未来 6 个月，含兜底分区）
ALTER TABLE xxl_job_log
  PARTITION BY RANGE COLUMNS(trigger_time) (
    PARTITION p_2025_01 VALUES LESS THAN ('2025-02-01'),
    PARTITION p_2025_02 VALUES LESS THAN ('2025-03-01'),
    -- 按实际时间预建 ...
    PARTITION p_future  VALUES LESS THAN (MAXVALUE)
  );
```

**关键风险**：`ALTER TABLE ... PARTITION BY RANGE` 是全表重建，持续时间与行数线性相关。MySQL 8.0 InnoDB 默认 `ALGORITHM=INPLACE`（online DDL），DML 可继续，但 IO 持续升高。行数大时建议使用 `pt-online-schema-change` 替代，缩短 MDL 锁窗口。

**难度**：高（需维护窗口 + 充分测试）

**依赖**：C-1（PK 决策确认）

---

#### C-3：每月添加新分区的运维规程

**文件**：新建 `scripts/add_log_partition.sql`（运维脚本）

**当前问题**：分区表建立后如不提前添加新月份分区，数据落入 `p_future`（MAXVALUE 分区），无法按月 DROP，丧失分区清理优势。

**改造动作**：
新建 `scripts/add_log_partition.sql`，模板：
```sql
-- 每月初执行，将下月分区从 p_future 中切分出来
-- 示例：2025 年 12 月初，切出 2026 年 1 月分区
ALTER TABLE xxl_job_log
  REORGANIZE PARTITION p_future INTO (
    PARTITION p_2026_01 VALUES LESS THAN ('2026-02-01'),
    PARTITION p_future  VALUES LESS THAN (MAXVALUE)
  );
```
配套：写入运维手册，要求每月 1 日执行；或在管理台配置月度 Cron 任务自动触发脚本。

**难度**：低（脚本简单，关键在运维规范建立）

**依赖**：C-2（分区表存在后才可执行）

---

#### C-4：清理路径改为 DROP PARTITION

**文件**：[JobLogReportHelper.java:103-118](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobLogReportHelper.java#L103)

**当前问题**：分区表上继续用 `DELETE WHERE id IN (...)` 清理，无法利用分区裁剪优势。清理优势只在 `ALTER TABLE ... DROP PARTITION` 时才能体现。

**改造动作**：
将自动清理路径（`JobLogReportHelper`）改为按到期分区名 DROP：

```java
// 根据 clearBeforeTime 计算到期分区名（格式：p_YYYY_MM）
String expiredPartition = buildExpiredPartitionName(clearBeforeTime);
// DROP PARTITION 是 DDL，需通过 JdbcTemplate 执行
jdbcTemplate.execute("ALTER TABLE xxl_job_log DROP PARTITION " + expiredPartition);
logger.info(">>>>>>>>>>> xxl-job, log-clean drop partition:{}", expiredPartition);
```

**注意**：
- `DROP PARTITION` 是 DDL，MyBatis Mapper 无法直接执行，需注入 `JdbcTemplate` 或使用 `DataSource.getConnection()`
- 需要 MySQL 用户具有 `ALTER` 权限（不是所有只读账号有）
- 手动清理路径（Controller）保留原 DELETE 语义（按条件精细删除）

**难度**：中（涉及 DDL 在应用层执行，需处理权限和异常）

**依赖**：C-2（分区表存在）、H-2（避免与 DELETE 清理逻辑混用）

---

## 二、改造点影响矩阵

链路定义参照 [docs/guardrail-chains.md](guardrail-chains.md)（L-1 至 L-8）。

| 改造点 | L-1 Cron触发 | L-2 回调链路 | L-3 分布式锁 | L-4 注册心跳 | L-5 失败告警 | L-6 失联兜底 | L-7 TriggerPool | L-8 文件重试 |
|--------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **A-1** 索引(handle_code, alarm_status) | 无影响 | 无影响 | 无影响 | 无影响 | **有影响** | 无影响 | 无影响 | 无影响 |
| **A-2** 索引(job_id, trigger_time) | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **A-3** findFailJobLogIds SQL改写 | 无影响 | 无影响 | 无影响 | 无影响 | **有影响** | 无影响 | 无影响 | 无影响 |
| **A-4** pageList ORDER BY评估 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **A-5** findLostJobIds补索引 | 无影响 | 无影响 | 无影响 | **不确定** | 无影响 | **有影响** | 无影响 | 无影响 |
| **H-1** lastCleanLogTime初始值 | **不确定** | 无影响 | **不确定** | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **H-2** 清理批次间sleep | **有影响** | 无影响 | **有影响** | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **H-3** clearLog返回值 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **H-4** alarm_status=-1修复 | 无影响 | **有影响** | 无影响 | 无影响 | **有影响** | 无影响 | 无影响 | **有影响** |
| **H-5** 手动/自动清理互斥 | **有影响** | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **C-1** PK兼容性确认 | **不确定** | **不确定** | 无影响 | 无影响 | **不确定** | **不确定** | 无影响 | 无影响 |
| **C-2** 分区DDL全表重建 | **有影响** | **有影响** | **有影响** | **有影响** | **有影响** | **有影响** | **有影响** | **有影响** |
| **C-3** 月度新增分区运维 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 | 无影响 |
| **C-4** DROP PARTITION清理 | **有影响** | 无影响 | **有影响** | 无影响 | 无影响 | **不确定** | 无影响 | 无影响 |

### 矩阵备注

| 单元格 | 含义 |
|--------|------|
| **有影响** | 改造点直接修改该链路涉及的 SQL/代码/数据，或确定性影响链路的执行计划/行为 |
| **不确定** | 改造点与链路有数据层共享或间接关联，具体影响取决于运行时状态，需 EXPLAIN 或集成测试验证 |
| 无影响 | 改造点与该链路无代码或数据交集 |

---

### 影响说明（有影响/不确定项的原因）

**A-1 + A-3 → L-5**：`findFailJobLogIds` 是 L-5（失败告警）的入口查询，改索引和 SQL 后执行计划改变，L-5 扫描行为直接受影响（期望变好，需 EXPLAIN 验证等价性）。

**A-5 → L-4（不确定）**：`findLostJobIds` JOIN `xxl_job_registry`，L-4 的心跳写入修改 `xxl_job_registry`，存在数据层共享；补索引本身不改行为，但 EXPLAIN 计划变化可能暴露原有隐性问题。

**A-5 → L-6（有影响）**：`findLostJobIds` 是 L-6（失联兜底）核心查询，补索引直接改善查询效率和正确性。

**H-1 → L-1、L-3（不确定）**：初始值改动减少重启后清理 IO 毛刺，对 L-1 调度写入和 L-3 分布式锁响应有间接保护；是否可见取决于数据量。

**H-2 → L-1（有影响）**：批次间 sleep 减少清理 IO，L-1 的 scheduleThread 扫表和 updateTriggerInfo 写入依赖主库，IO 竞争减少后延迟降低，正向影响。

**H-2 → L-3（有影响）**：主库 IO 压力降低，`SELECT FOR UPDATE` 响应时间间接改善，正向影响。

**H-4 → L-2（有影响）**：启动时补偿 UPDATE 修改 `xxl_job_log.alarm_status` 字段，与 L-2 `JobCompleter` 最终写同一字段存在字段级共享；一次性 DML，并发概率极低。

**H-4 → L-5（有影响）**：alarm_status 状态机是 L-5 的核心不变式，修复 -1 卡死直接改变 L-5 稳态行为，强有影响。

**H-4 → L-8（有影响）**：文件重试成功后经 L-2→L-5 路径写 alarm_status，H-4 修复后 L-8 成功的记录不再有卡死风险。

**H-5 → L-1（有影响）**：加清理互斥标志减少 IO 毛刺，与 H-2 同理对 L-1 有间接保护。

**C-1 → L-2/L-5/L-6（不确定）**：联合 PK `(id, trigger_time)` 改变按 `WHERE id = ?` 的索引路径，这些链路均有按 id 单列查询（`load(failLogId)` 等），需 EXPLAIN 确认联合 PK 下仍走聚簇索引最左前缀。

**C-2 → 全链路（有影响）**：全表重建期间 `xxl_job_log` 上的 DDL 锁（即使 online DDL）会短暂影响 DML 并发，所有使用该表的链路均受影响，必须在维护窗口执行。

**C-4 → L-1（有影响）**：`DROP PARTITION` 是 DDL，持有 MDL 锁期间主库 IO 升高，L-1 的调度写入延迟增加。

**C-4 → L-3（有影响）**：`DROP PARTITION` 与 `SELECT FOR UPDATE` 不同表，理论上不互斥；但主库 IO 饱和时 L-3 响应时间受影响。

**C-4 → L-6（不确定）**：`DROP PARTITION` 后该月数据消失，`findLostJobIds` 的 10 分钟窗口通常不覆盖月级分区边界，但边界附近执行时存在异常可能，需上线时确认清理时间点与 monitorThread 最近执行间隔。

---

## 三、改造前必须跑的护栏测试

| 改造点 | 必须通过的测试 | 原因 |
|--------|-------------|------|
| A-1 + A-3 同时上线 | L-5 集成测试（受控场景） | 验证告警扫描语义不变，SQL 等价 |
| A-5 补索引 | L-6 Characterization Test | 验证失联兜底查询结果在补索引前后等价 |
| H-4 alarm修复 | L-5 集成测试 | 验证 alarm_status 状态机完整性（含 -1→0 重置路径） |
| C-2 全表重建 | L-1 + L-2 集成测试（重建前跑基线，重建后复跑对比） | 确认全表重建后触发和回调行为无变化 |
| C-4 DROP PARTITION清理 | L-6 Characterization Test | 验证 findLostJobIds 在分区边界时行为符合预期 |

护栏测试规范参照 [docs/guardrail-chains.md](guardrail-chains.md)。

---

## 四、需要提前通知的团队

| 改造点 | 通知对象 | 通知内容 |
|--------|---------|---------|
| **C-2** 全表重建 | 所有使用 xxl-job 的业务团队 | 维护窗口期间任务调度可能有短暂延迟（online DDL 期间 IO 升高），建议窗口内不触发大批量任务 |
| **A-3** SQL改写 | DBA | 需审核 EXPLAIN 结果确认 SQL 等价性，确认 handle_code 合法值范围 |
| **H-4** alarm修复 | DBA / 运维 | Admin 重启时执行 `UPDATE xxl_job_log SET alarm_status=0 WHERE alarm_status=-1`，存量卡死记录会被重新纳入告警流程，可能短时出现一批历史失败任务补发告警 |
| **C-3** 月度分区运维 | 运维 / DBA | 需建立月度操作习惯或自动化脚本，否则数据落入 p_future 分区后无法按月 DROP |
