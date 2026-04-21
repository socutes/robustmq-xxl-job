# SKILL: 修复 TriggerPool 静默丢任务（R-1）

## 问题描述

**债务编号**：R-1（红区，高危）

`JobTriggerPoolHelper` 的 `fastTriggerPool` 和 `slowTriggerPool` 在队列满时，
拒绝策略只打一条 `error` 日志，**任务直接丢失，不执行，不重试，无任何告警**。
`TriggerCallbackThread` 的回调线程池使用 `r.run()`（调用者线程执行），两者拒绝策略不一致。

高峰期任务堆积时，丢任务是静默的——Cron 任务下次不会补跑，手动触发的任务彻底丢失。

## 问题位置

[JobTriggerPoolHelper.java:42-66](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/thread/JobTriggerPoolHelper.java#L42)

```java
// 当前代码（fastTriggerPool，slowTriggerPool 同结构）
new RejectedExecutionHandler() {
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        // 只打日志，任务丢失
        logger.error(">>>>>>>>>>> xxl-job, admin JobTriggerPoolHelper-fastTriggerPool execute too fast, Runnable="+r.toString() );
    }
}
```

**队列容量**：`fastTriggerPool` LinkedBlockingQueue 容量 2000，`slowTriggerPool` 容量 5000。
被拒绝意味着队列已满且线程数已达上限，是真实的过载信号。

## 修复方案选项

### 方案 A：调用者线程执行（CallerRunsPolicy）—— 推荐

阻塞 `ringThread`（触发来源），形成反压，不丢任务，但会延迟后续任务的入队：

```java
new ThreadPoolExecutor.CallerRunsPolicy()
```

**适用**：调度 QPS 有弹性，可以接受少量触发延迟换取零丢任务。

### 方案 B：记录丢失任务 ID，打告警日志 + 写数据库标记

在 `rejectedExecution` 中提取 jobId，更新 `xxl_job_log.trigger_code` 为失败状态：

```java
new RejectedExecutionHandler() {
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        logger.error(">>>>>>>>>>> xxl-job, trigger pool is full, task is discarded. " + r);
        // 需要从 Runnable 中提取 jobId（要求 Runnable 实现对应方法或转型）
        // 写 xxl_job_log 标记 trigger_code=500, trigger_msg="trigger pool full"
    }
}
```

**适用**：不能接受调用者线程阻塞（如 ringThread 绝对不能被阻塞的场景）。
**注意**：当前 `Runnable` 是匿名类，无法直接拿到 jobId，需要先将 trigger lambda 改成具名类。

### 方案 C：扩大队列容量（治标不治本）

把队列从 2000/5000 调大，延后丢任务发生的时机，不解决根本问题。仅作为临时缓解。

## 改动前确认（CLAUDE.md 协作规则第 1 条）

这个文件属于核心数据流，**改动前先列出要改哪几行，等维护者确认再动**：

1. 确认 `ringThread` 是否可以被阻塞（影响方案 A 是否可行）
2. 确认是否需要丢任务时写 `xxl_job_log`（影响方案 B 的工作量）
3. 确认 `slowTriggerPool` 是否与 `fastTriggerPool` 采用相同修复方案

## 推荐改动（方案 A）

**改动范围**：`JobTriggerPoolHelper.java` 第 42-46 行（fastTriggerPool）+ 第 62-66 行（slowTriggerPool）

将两处 `RejectedExecutionHandler` 匿名类替换为：

```java
new ThreadPoolExecutor.CallerRunsPolicy()
```

**改动后验证**：
1. 模拟触发线程池满（设极小队列 + 极小线程数），验证调用者线程接管执行
2. 验证 `ringThread` 在过载时会延迟，但不丢任务
3. 检查是否引入新的死锁风险（`ringThread` 持有锁期间不能阻塞太久）

## 同步检查（CLAUDE.md 协作规则第 5 条）

改动 `JobTriggerPoolHelper` 后，检查：

- `JobScheduleHelper` 的 `ringThread` 调用 `trigger()` 的频率（L200 附近），确认反压不会导致 ringThread 积压
- `TriggerCallbackThread` 的回调线程池（`r.run()` 策略），确认两者拒绝语义统一后行为一致

## 后续配套建议

修复丢任务后，建议同步添加监控：

- 在拒绝处理中写 metrics（Micrometer Counter 或日志统计），建立"触发池拒绝率"指标
- 告警规则：拒绝率 > 0 时触发 P2 告警，持续 5 分钟触发 P1
