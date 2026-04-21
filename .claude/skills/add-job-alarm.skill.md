# SKILL: 新增告警渠道（JobAlarm）

## 适用场景

在邮件告警之外，接入新的告警渠道（飞书、钉钉、Webhook、企业微信等）。
任务失败时 `JobFailAlarmMonitorHelper` 会调用 `JobAlarmer`，后者遍历所有 `JobAlarm` Bean，
新增渠道只需实现接口并注册为 Bean，不改任何现有代码。

## 扩展点位置

| 文件 | 作用 |
|------|------|
| [JobAlarm.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/alarm/JobAlarm.java) | 接口定义，只有 `doAlarm(XxlJobInfo, XxlJobLog): boolean` |
| [JobAlarmer.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/alarm/JobAlarmer.java) | 聚合器，通过 `List<JobAlarm>` 注入全部 Bean，**不要改这里** |
| [EmailJobAlarm.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/alarm/impl/EmailJobAlarm.java) | 参考实现（邮件告警） |

## 实现步骤

### 第一步：实现接口

在 `xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/alarm/impl/` 下新建实现类。

```java
package com.xxl.job.admin.scheduler.alarm.impl;

import com.xxl.job.admin.model.XxlJobInfo;
import com.xxl.job.admin.model.XxlJobLog;
import com.xxl.job.admin.scheduler.alarm.JobAlarm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author your-name 2025-xx-xx
 */
@Component
public class WebhookJobAlarm implements JobAlarm {
    private static Logger logger = LoggerFactory.getLogger(WebhookJobAlarm.class);

    @Override
    public boolean doAlarm(XxlJobInfo info, XxlJobLog jobLog) {
        try {
            // 构造告警内容
            String alarmContent = buildAlarmContent(info, jobLog);

            // 发送 Webhook（示例，替换为实际 HTTP 客户端）
            // httpPost(webhookUrl, alarmContent);

            return true;
        } catch (Exception e) {
            logger.error(">>>>>>>>>>> xxl-job, job fail alarm webhook send error, JobLogId:{}", jobLog.getId(), e);
            return false;
        }
    }

    private String buildAlarmContent(XxlJobInfo info, XxlJobLog jobLog) {
        return "任务失败：jobId=" + info.getId()
                + ", jobDesc=" + info.getJobDesc()
                + ", logId=" + jobLog.getId()
                + ", handleCode=" + jobLog.getHandleCode();
    }
}
```

### 第二步：验证自动注入

`JobAlarmer` 通过 `@Resource List<JobAlarm> jobAlarmList` 自动收集所有 Bean，
无需修改任何配置。启动后确认日志中 `JobAlarmer` 初始化时包含新实现类的类名。

### 第三步：处理 `doAlarm` 返回值语义

- 返回 `true`：告警成功，`JobFailAlarmMonitorHelper` 将 `alarm_status` 更新为 `2`
- 返回 `false`：告警失败，`alarm_status` 更新为 `3`（下次扫描不会重试，注意此语义）

## 注意事项

- `doAlarm()` 在 `JobFailAlarmMonitorHelper` 的后台线程中调用，**不要在里面做阻塞 I/O 超时过长**（建议 HTTP 超时 ≤ 3s）
- 告警接口的 Webhook URL/Token 等配置通过 Spring `@Value` 或 `application.properties` 注入，**不要硬编码**
- 可用的任务信息字段：`info.getJobDesc()`、`info.getAlarmEmail()`、`info.getJobGroup()`；日志字段：`jobLog.getTriggerCode()`、`jobLog.getHandleCode()`、`jobLog.getHandleMsg()`
- `XxlJobAdminBootstrap.getInstance().getXxlJobGroupMapper().load(info.getJobGroup())` 可获取执行器分组名称（参考 `EmailJobAlarm` 第 52 行）

## 验证方法

1. 手动触发一个必然失败的任务（Handler 返回失败）
2. 等待 10s，`JobFailAlarmMonitorHelper` 扫描周期到达
3. 检查数据库 `xxl_job_log.alarm_status`：值为 `2` 表示告警成功，值为 `3` 表示告警失败
4. 检查告警渠道是否收到消息
