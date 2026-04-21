# SKILL: 补全 FIX_DELAY 调度类型（R-3）

## 问题描述

**债务编号**：R-3（红区）

`FIX_DELAY`（固定延迟，即上次执行完成后延迟 N 秒再次触发）已有骨架但被注释禁用。
当前状态：
- `ScheduleTypeEnum.java:28`：枚举值注释掉，无法在管理台选择
- `JobCompleter.java:50`：有 `// on the way` 占位注释，是 FIX_DELAY 计算下次触发时间的逻辑入口
- `ScheduleType` 抽象类和 `FixRateScheduleType` 已给出完整的参考实现

**FIX_DELAY 与 FIX_RATE 的本质区别**：
- `FIX_RATE`：下次触发时间 = **上次触发时间** + N 秒（不管执行时长）
- `FIX_DELAY`：下次触发时间 = **本次执行完成时间** + N 秒（需等执行结束才能算）

因此 FIX_DELAY 的 `generateNextTriggerTime` **不能在 scheduleThread 中调用**（执行尚未完成），
必须在 `JobCompleter.complete()` 中，拿到 `handleTime` 后才能计算。

## 受影响文件清单

| 文件 | 当前状态 | 需要改什么 |
|------|---------|-----------|
| [ScheduleTypeEnum.java:28](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/type/ScheduleTypeEnum.java#L28) | FIX_DELAY 枚举值注释掉 | 取消注释，关联实现类 |
| [JobCompleter.java:50](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java#L50) | `// on the way` 占位 | 实现 FIX_DELAY 下次触发时间计算和写库 |
| `strategy/FixDelayScheduleType.java` | **不存在** | 新建，实现 `ScheduleType` |
| `JobScheduleHelper.java` | 当前 scheduleThread 跳过 FIX_DELAY 任务？ | 需核实：如果 FIX_DELAY 任务在 `trigger_next_time=0` 时被正确跳过，则无需改动 |

## 第一步：新建 FixDelayScheduleType

在 `xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/type/strategy/` 下新建：

```java
package com.xxl.job.admin.scheduler.type.strategy;

import com.xxl.job.admin.model.XxlJobInfo;
import com.xxl.job.admin.scheduler.type.ScheduleType;
import com.xxl.tool.core.DateTool;

import java.util.Date;

public class FixDelayScheduleType extends ScheduleType {

    /**
     * FIX_DELAY：下次触发时间从"执行完成时间"算起，
     * 此方法在 JobCompleter 中调用，fromTime = handleTime。
     */
    @Override
    public Date generateNextTriggerTime(XxlJobInfo jobInfo, Date fromTime) throws Exception {
        long delayMs = Long.parseLong(jobInfo.getScheduleConf()) * 1000L;
        Date nextTriggerTime = new Date(fromTime.getTime() + delayMs);

        // 对齐到整秒（与 FixRateScheduleType 保持一致）
        if (nextTriggerTime.getTime() % 1000 != 0) {
            nextTriggerTime = DateTool.addSeconds(DateTool.setMilliseconds(nextTriggerTime, 0), 1);
        }

        return nextTriggerTime;
    }
}
```

## 第二步：在枚举中取消注释并关联实现

[ScheduleTypeEnum.java:28](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/type/ScheduleTypeEnum.java#L28)：

```java
// 将注释掉的枚举值替换为：
FIX_DELAY(I18nUtil.getString("schedule_type_fix_delay"), new FixDelayScheduleType());
```

同步在三个 i18n 文件中确认 `schedule_type_fix_delay` key 已存在（如果注释前已有，取消注释后自动生效）。

## 第三步：在 JobCompleter 中实现触发时间更新

[JobCompleter.java:49-51](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/complete/JobCompleter.java#L49)，
将 `// on the way` 替换为实际逻辑：

```java
// 2、fix_delay: 执行完成后，重新计算并更新下次触发时间
XxlJobInfo xxlJobInfo = xxlJobInfoMapper.loadById(xxlJobLog.getJobId());
if (xxlJobInfo != null
        && "FIX_DELAY".equals(xxlJobInfo.getScheduleType())
        && xxlJobInfo.getTriggerStatus() == 1   // 运行中才重新调度
        && xxlJobLog.getHandleCode() > 0) {     // 执行已完成（有 handle 结果）

    try {
        Date handleTime = xxlJobLog.getHandleTime() != null
                ? xxlJobLog.getHandleTime()
                : new Date();

        Date nextTriggerTime = ScheduleTypeEnum.FIX_DELAY
                .getScheduleType()
                .generateNextTriggerTime(xxlJobInfo, handleTime);

        xxlJobInfo.setTriggerLastTime(xxlJobInfo.getTriggerNextTime());
        xxlJobInfo.setTriggerNextTime(nextTriggerTime.getTime());
        xxlJobInfoMapper.scheduleUpdate(xxlJobInfo);    // 或 scheduleBatchUpdate
    } catch (Exception e) {
        logger.error(">>>>>>>>>>> xxl-job, fix_delay next trigger time calc error, jobId:{}", xxlJobLog.getJobId(), e);
    }
}
```

**关键细节**：
- 只在 `trigger_status=1`（运行中）时更新，避免已停止的任务被重新调度
- `handleTime` 取执行完成时间（`xxl_job_log.handle_time`），不是当前时间
- `scheduleUpdate` 写 `trigger_next_time`，`scheduleThread` 下次扫描时会捡到并入时间轮

## 第四步：验证 scheduleThread 对 FIX_DELAY 任务的处理

**核查点**：`FIX_DELAY` 任务在初始创建时 `trigger_next_time=0`，`scheduleThread` 扫描时不应该预写时间轮。

在 [JobScheduleHelper.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobScheduleHelper.java) 中确认：
- `scheduleJobQuery` 的查询条件是 `trigger_next_time <= nowTime + 5s`
- 初始 `trigger_next_time=0` 会被扫到，触发第一次执行 ✓（这是对的，首次触发需要）
- 首次执行后 `JobCompleter` 计算下次时间并写库，之后每次完成后滚动

如果 `scheduleThread` 在任务执行中时反复扫到相同任务（`trigger_next_time` 未更新），
需要检查是否需要将 `trigger_next_time` 在触发时临时设为极大值，执行完再由 `JobCompleter` 覆盖。

## 改动前确认（CLAUDE.md 协作规则第 1 条）

**这是跨多个文件的改动，动前列清单等确认**：

1. `xxl_job_log.handle_time` 字段在回调时是否一定有值（null 的处理）
2. `scheduleThread` 首次触发 FIX_DELAY 任务后，`trigger_next_time` 如何避免重复触发
3. 失败重试（`executor_fail_retry_count > 0`）时，重试完成后也要触发 FIX_DELAY 的下次计算吗

## 验证方法

1. 创建 FIX_DELAY 任务，间隔 30s，执行一个耗时 10s 的 Handler
2. 记录触发时间序列：预期两次触发间隔 = 执行耗时(10s) + 延迟(30s) ≈ 40s
3. 对比 FIX_RATE 同参数任务的间隔（固定 30s）
4. 验证手动停止任务后不再触发（`trigger_status=0` 时 `JobCompleter` 不更新）
