# 关键业务流程

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：以时序方式描述 XXL-JOB 的 4 条关键业务流程：任务触发、执行器注册、失败告警、GLUE 任务执行。

**不讲什么**：代码实现细节（见 [architecture.md](architecture.md)）、接口参数（见 [api.md](api.md)）。

---

## 流程一：Cron 任务定时触发

```
Admin scheduleThread          Admin ringThread         Admin TriggerPool        执行器 EmbedServer        执行器 JobThread
       │                             │                        │                         │                        │
       │ 每秒循环                      │                        │                         │                        │
       │ SELECT FOR UPDATE            │                        │                         │                        │
       │ 获取分布式调度锁               │                        │                         │                        │
       │                             │                        │                         │                        │
       │ 查询 nowTime+5s 内到期任务     │                        │                         │                        │
       │ (trigger_status=1)           │                        │                         │                        │
       │                             │                        │                         │                        │
       │ 按触发秒写入 ringData Map       │                        │                         │                        │
       │ 更新 trigger_next_time        │                        │                         │                        │
       │ 释放锁（事务提交）              │                        │                         │                        │
       │                             │                        │                         │                        │
       │                             │ 每秒循环                  │                         │                        │
       │                             │ 取 ringData[now±2格]      │                        │                        │
       │                             │ ─────────────────────────> trigger(jobId)          │                        │
       │                             │                        │                         │                        │
       │                             │                   插入 xxl_job_log                │                        │
       │                             │                   (handle_code=0, 进行中)          │                        │
       │                             │                        │                         │                        │
       │                             │                   路由选址                         │                        │
       │                             │                   (ExecutorRouteStrategyEnum)     │                        │
       │                             │                        │                         │                        │
       │                             │                   HTTP POST /run ──────────────────>                       │
       │                             │                   (TriggerRequest JSON)           │                        │
       │                             │                        │                         │                        │
       │                             │                        │                  入队 triggerQueue                │
       │                             │                        │                  立即返回 200 ──────────────────> │
       │                             │                        │                         │                        │
       │                             │                   UPDATE xxl_job_log              │                        │
       │                             │                   (trigger_code=200)              │                        │
       │                             │                        │                         │                    poll triggerQueue
       │                             │                        │                         │                    执行 handler.execute()
       │                             │                        │                         │                    写本地日志文件
       │                             │                        │                         │                        │
```

**手动触发 / API 触发**：跳过 scheduleThread 和 ringThread，直接从 TriggerPool 开始（STEP 3）。

> **护栏状态（L-1）**
>
> - **当前护法**：集成测试 `TriggerChainTest`
> - **覆盖范围**：Admin 侧触发路径（`TriggerPool.trigger()` → `processTrigger()` → INSERT `xxl_job_log`）。测试用 mock 执行器地址，不覆盖 Executor 侧。
> - **未覆盖**：scheduleThread/ringThread 的时间轮写入逻辑、misfire 两种策略分支、多节点锁竞争。
> - **未来补充**：为 misfire 补独立用例；scheduleThread 的 ±2 格边界用 Characterization Test 覆盖（需要 mock 时钟输入）。

---

## 流程二：执行结果回调

```
执行器 JobThread         执行器 TriggerCallbackThread       Admin OpenApiController      Admin JobCompleteHelper
       │                          │                                  │                           │
   执行完成                        │                                  │                           │
   pushCallBack(CallbackRequest)   │                                  │                           │
       │ ────────────────────────> │                                  │                           │
       │                          │                                  │                           │
       │                    批量 drainTo                              │                           │
       │                    HTTP POST /api/callback ─────────────────>│                           │
       │                    (List<CallbackRequest>)                   │                           │
       │                          │                                  │                           │
       │                          │                           校验 accessToken                    │
       │                          │                           ──────────────────────────────────> │
       │                          │                                  │                           │
       │                          │                                  │                    UPDATE xxl_job_log
       │                          │                                  │                    (handle_code / handle_msg / handle_time)
       │                          │                                  │                           │
       │                          │                                  │                    若有 childJobId → 触发子任务
       │                          │                                  │                           │
       │                    返回 Response.ofSuccess()                  │                           │
       │                          │                                  │                           │
```

**回调失败时**：

1. 写文件 `{logPath}/xxl-job-callback-{md5}.log`
2. `TriggerRetryCallbackThread` 每 30 秒读取失败文件重试
3. 重试成功后删除文件

> **护栏状态（L-2）**
>
> - **当前护法**：集成测试 `CallbackChainTest`（主流程）；文件重试分支暂无测试
> - **覆盖范围**：`JobCompleteHelper.callback()` → `doCallback()` → `JobCompleter.complete()` → UPDATE `xxl_job_log`；覆盖成功回调（handle_code=200）和失败回调（handle_code=500）两个分支。
> - **未覆盖**：文件重试分支（`TriggerRetryCallbackThread`）；多条 CallbackRequest 批量回调的幂等性（handle_code > 0 时的"重复回调"保护）。
> - **未来补充**：为文件重试分支补 Characterization Test，重点固化 callback 文件的 Gson 序列化格式作为契约，防止 `CallbackRequest` 字段改名破坏存量文件。

---

## 流程三：执行器注册与心跳

```
执行器启动                              Admin JobRegistryHelper
    │                                          │
    │ start(appname, address)                  │
    │                                          │
    │  每 30s 循环                              │
    │  POST /api/registry ────────────────────>│
    │  {group=EXECUTOR, key=appname,           │
    │   value=http://ip:port/}                 │
    │                                          │ upsert xxl_job_registry
    │                                          │ (update_time 刷新)
    │                                          │
    │                                          │ 每 30s 循环
    │                                          │ 查询 update_time > 90s 的死亡实例
    │                                          │ 删除死亡实例
    │                                          │ 聚合存活实例地址
    │                                          │ UPDATE xxl_job_group.address_list
    │                                          │
执行器关闭                                     │
    │  POST /api/registryRemove ──────────────>│
    │                                          │ DELETE xxl_job_registry
    │                                          │ 立即刷新 address_list
```

**关键阈值**：

- 心跳间隔：30s（`Const.BEAT_TIMEOUT`）
- 超时判定：90s（`Const.DEAD_TIMEOUT`），即连续 3 次心跳失败后被剔除

> **护栏状态（L-4）**
>
> - **当前护法**：无自动化测试，目前依赖手动观察管理台执行器在线状态
> - **覆盖范围**：无
> - **未来补充**：用 Characterization Test 直接操作 DB——插入构造好的 `xxl_job_registry` 数据，手动调用 `JobRegistryHelper` 的扫描方法，断言 `xxl_job_group.address_list` 的更新结果；重点覆盖 90s 超时边界的 off-by-one 和所有实例死亡时 address_list 清空行为。

---

## 流程四：失败告警与重试

```
Admin JobFailAlarmMonitorHelper                      Admin TriggerPool          告警渠道（Email 等）
            │                                               │                          │
            │ 每 10s 扫描                                    │                          │
            │ SELECT xxl_job_log WHERE handle_code=500       │                          │
            │ AND alarm_status=0 LIMIT 1000                  │                          │
            │                                               │                          │
    对每条失败日志：                                           │                          │
            │                                               │                          │
    UPDATE alarm_status=-1（加锁，防并发重复处理）              │                          │
            │                                               │                          │
    若 executor_fail_retry_count > 0：                       │                          │
            │ trigger(jobId, RETRY, retryCount-1) ─────────>│                          │
            │                                               │ 重走调度流程（STEP 4起）    │
            │                                               │                          │
    JobAlarmer.alarm(info, log)：                            │                          │
            │ 遍历所有 JobAlarm Bean ───────────────────────────────────────────────────>│
            │                                               │                    发邮件告警
            │                                               │                          │
    UPDATE alarm_status=2（告警成功）或 3（告警失败）            │                          │
            │                                               │                          │
```

> **护栏状态（L-5）**
>
> - **当前护法**：无自动化测试；`alarm_status` 的 5 个状态值（0/-1/1/2/3）仅在 `JobFailAlarmMonitorHelper.java:62` 的注释中定义，散落难查。
> - **覆盖范围**：无
> - **未来补充**：集成测试，直接插一条 `alarm_status=0`、`handle_code=500` 的 `xxl_job_log`，手动调用扫描逻辑，告警 Bean 替换为 mock，断言 `alarm_status` 的状态机流转（0 → -1 → 2/3）；重点验证 `alarm_status=-1` 的 CAS 语义防止并发重复处理。

---

## 流程五：执行器失联检测（兜底机制）

```
Admin JobCompleteHelper.monitorThread
            │
            │ 每 60s 扫描
            │ 查询 handle_code=0（进行中）
            │ AND trigger_time < now-10min 的日志
            │
    对每条超时日志：
            │
    检查执行器是否在线
    （address_list 中是否还包含该执行器地址）
            │
    若已离线：
            │
    UPDATE xxl_job_log:
    handle_code=500, handle_msg="executor lost"
    alarm_status=0（交给告警线程处理）
            │
```

此机制是保证调度日志不永远停在"进行中"状态的最后兜底，触发条件：执行器进程崩溃且未来得及发回调。

> **护栏状态（L-6）**
>
> - **当前护法**：无自动化测试；兜底逻辑依赖 60s 扫描周期和 10min 超时窗口，生产中不易感知是否正常运行。
> - **覆盖范围**：无
> - **未来补充**：Characterization Test——插入 `trigger_time = now - 11 minutes` 且 `handle_code=0` 的 log，将执行器地址从 `address_list` 移除，手动触发 `monitorThread` 的扫描方法，断言 `handle_code` 变为 500；同时验证执行器仍在线时 `handle_code=0` 不变（防止误杀慢任务）。

---

## 流程六：GLUE 任务执行

```
Admin JobTrigger                执行器 ExecutorBizImpl            GlueFactory
      │                                  │                            │
      │ HTTP POST /run                    │                            │
      │ (glueType=GLUE_GROOVY,           │                            │
      │  glueSource=..., glueUpdatetime) │                            │
      │ ─────────────────────────────────>│                           │
      │                                  │                            │
      │                            检查本地缓存：                      │
      │                            loadJobHandler(jobId)              │
      │                                  │                            │
      │                            若缓存不存在或版本过期               │
      │                            (glueUpdatetime 不同)              │
      │                                  │ refreshInstance() ─────────>│
      │                                  │                     Groovy 编译 glueSource
      │                                  │                     生成 IJobHandler 实例
      │                                  │ <──────────────────────────│
      │                            入队 triggerQueue                   │
      │                            立即返回 200                        │
      │ <─────────────────────────────────│                           │
      │                                  │                            │
      │                            JobThread 执行 handler.execute()    │
```
