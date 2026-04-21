# 术语表

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：XXL-JOB 项目特有的内部术语、缩写、枚举值的含义。

**不讲什么**：通用 Java/Spring 术语、行业通识概念。

---

## 核心实体

### AppName
执行器集群的唯一标识字符串，对应 `xxl_job_group.app_name` 字段。调度中心通过 AppName 找到一组在线执行器地址，再按路由策略从中选一个。执行器配置 `xxl.job.executor.appname` 必须与调度中心创建的执行器分组 AppName 一致。

### JobHandler
具体任务逻辑的实现单元。BEAN 模式下在 Spring Bean 的方法上加 `@XxlJob("handlerName")` 注解注册，调度中心下发任务时通过 `executorHandler` 字段指定 Handler 名称。代码中对应 `IJobHandler` 接口。

### jobId
任务的唯一整数 ID，对应 `xxl_job_info.id`。每个 jobId 在执行器上对应一个专属 `JobThread`，相同 jobId 的任务串行进入同一线程的队列（受阻塞策略控制）。

### logId
每次调度触发生成的唯一日志 ID，对应 `xxl_job_log.id`。贯穿整个调度生命周期：调度中心插入日志 → 下发给执行器 → 执行器执行结束后回调时带回 → 调度中心用 logId 更新执行结果。

---

## 调度模式

### BEAN 模式
任务 Handler 以 Spring Bean 形式部署在执行器中，用 `@XxlJob("name")` 注解注册。是最常用的模式。代码实现：`MethodJobHandler`。

### GLUE 模式（GLUE_GROOVY）
任务源码托管在调度中心数据库（`xxl_job_info.glue_source`），触发时随 `TriggerRequest` 下发给执行器，执行器用 Groovy 运行时编译执行，无需重新部署。代码实现：`GlueFactory` + `GlueJobHandler`。

### 脚本模式（GLUE_SHELL / GLUE_PYTHON 等）
源码为 Shell / Python / Node.js / PHP / PowerShell 脚本，执行器写入临时文件后调用系统命令执行。代码实现：`ScriptJobHandler`。

### GlueTypeEnum
枚举所有 GLUE 类型：`BEAN` / `GLUE_GROOVY` / `GLUE_SHELL` / `GLUE_PYTHON` / `GLUE_PHP` / `GLUE_NODEJS` / `GLUE_POWERSHELL`。

---

## 调度策略

### ScheduleType（调度类型）
决定任务何时触发：
- `NONE`：不自动调度，只能手动触发
- `CRON`：按 Cron 表达式触发，`schedule_conf` 存表达式
- `FIX_RATE`：按固定频率（秒）触发，`schedule_conf` 存间隔秒数
- `FIX_DELAY`：按固定延迟（上次完成后等待 N 秒）触发，**当前代码已注释禁用**，功能未完整实现

### MisfireStrategy（调度过期策略）
任务调度时间已过但未触发时的处理方式：
- `DO_NOTHING`：忽略，跳过本次
- `FIRE_ONCE_NOW`：立即触发一次补偿

### 时间轮（Ring）
调度中心的内部触发机制。`scheduleThread` 每秒扫描 DB 预读 5 秒内到期的任务，写入 `ringData: Map<Integer秒数, List<Integer jobId>>`；`ringThread` 每秒从 `ringData` 取出当前秒及前后各 2 格的任务触发，防止秒级跨格遗漏。代码：`JobScheduleHelper.java`。

---

## 路由策略（ExecutorRouteStrategyEnum）

调度中心选择目标执行器地址的算法，共 10 种：

| 枚举值 | 含义 |
|--------|------|
| `FIRST` | 选地址列表第一个（固定） |
| `LAST` | 选地址列表最后一个（固定） |
| `ROUND` | 轮询，依次选下一个 |
| `RANDOM` | 随机选一个 |
| `CONSISTENT_HASH` | 一致性哈希，同一 jobId 固定路由到同一执行器 |
| `LFU` | 最不经常使用（选历史触发次数最少的） |
| `LRU` | 最近最少使用（选最久未触发的） |
| `FAILOVER` | 故障转移，顺序发 beat 心跳，选第一个存活的 |
| `BUSYOVER` | 忙碌转移，顺序发 idleBeat，选第一个空闲的 |
| `SHARDING_BROADCAST` | 分片广播，广播给所有在线执行器，每个收到不同的分片参数 |

---

## 阻塞策略（ExecutorBlockStrategyEnum）

同一 jobId 已有任务在执行时，新触发的处理方式：

| 枚举值 | 含义 |
|--------|------|
| `SERIAL_EXECUTION` | 串行执行，新任务进入队列等待 |
| `DISCARD_LATER` | 丢弃后者，队列非空时直接拒绝本次 |
| `COVER_EARLY` | 覆盖之前，杀掉旧 JobThread，重建线程执行新任务 |

---

## 执行器组件

### EmbedServer
执行器内嵌的 Netty HTTP 服务，监听调度中心的调度指令。启动时绑定配置端口，接收 `/beat`、`/idleBeat`、`/run`、`/kill`、`/log` 请求。代码复制自 xxl-rpc。

### JobThread
执行器中每个 jobId 对应一个专属线程，持有一个 `LinkedBlockingQueue<TriggerRequest>`。接收到 `/run` 请求后入队，线程循环取任务执行。空闲 30 次（约 90s）后自我销毁。

### XxlJobContext
任务执行时的上下文，通过 `ThreadLocal` 传递给 Handler，包含：`jobId`、`jobParam`、`jobLogFileName`（本地日志文件路径）、`shardIndex`/`shardTotal`（分片参数）。通过 `XxlJobHelper` 静态方法访问。

### XxlJobHelper
执行器 Handler 代码调用的静态工具类，提供：
- `getJobParam()`：获取任务参数
- `getShardIndex()` / `getShardTotal()`：获取分片参数
- `log(String msg)`：写执行日志到本地文件

---

## 分片广播

路由策略 `SHARDING_BROADCAST` 的执行方式。调度中心将同一任务广播给所有在线执行器，循环调用 `processTrigger` N 次（N = 在线执行器数量），每次传入不同的 `broadcastIndex`（0 到 N-1）和 `broadcastTotal`（N）。执行器通过 `XxlJobHelper.getShardIndex()` 获取自己负责处理哪段数据。

---

## Admin 内部组件

### TriggerPool（fastPool / slowPool）
调度中心的异步触发线程池，双池设计：
- **fastPool**：默认线程上限 300，处理正常任务
- **slowPool**：默认线程上限 200，处理近 1 分钟内超时次数 > 10 次的任务（自动降级）

### TriggerCallbackThread
执行器侧的回调线程，批量从 `callBackQueue` 取出执行结果，POST 到调度中心 `/api/callback`。失败时写文件 `xxl-job-callback-{md5}.log`，由 `TriggerRetryCallbackThread` 每 30s 重试。

### JobRegistryHelper
调度中心的注册监控线程，每 30s 刷新一次：清除超时（90s）实例，将存活实例地址聚合写回 `xxl_job_group.address_list`。

### schedule_lock
`xxl_job_lock` 表中唯一的一行数据，是所有 Admin 节点的分布式调度互斥锁。`scheduleThread` 每次循环都通过 `SELECT FOR UPDATE` 竞争此锁，保证同一时刻只有一个 Admin 节点执行调度扫描。

---

## accessToken

Admin 与执行器之间的双向认证凭证，配置项 `xxl.job.accessToken`。
- 执行器调用 Admin OpenAPI 时，在 HTTP Header `XXL-JOB-ACCESS-TOKEN` 中携带
- 调度中心调用执行器接口时同样携带
- 两侧配置值必须相同，任一侧为空则跳过校验（不建议生产环境留空）
