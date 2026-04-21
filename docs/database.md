# 数据库文档

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：XXL-JOB 的 MySQL 表结构、字段含义、索引、表间关系。

**不讲什么**：数据库调优参数、分库分表方案（当前无分库）。

**DDL 来源**：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)

---

## 数据库信息

- **数据库名**：`xxl_job`
- **字符集**：`utf8mb4 / utf8mb4_unicode_ci`
- **引擎**：InnoDB
- **连接串**（默认）：`jdbc:mysql://127.0.0.1:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai`

---

## 表清单

| 表名 | 职责 |
|------|------|
| `xxl_job_group` | 执行器分组（AppName 配置） |
| `xxl_job_registry` | 执行器实例注册表（心跳存活状态） |
| `xxl_job_info` | 任务定义（调度配置、Handler、参数等） |
| `xxl_job_logglue` | GLUE 任务源码版本历史 |
| `xxl_job_log` | 每次调度的执行日志 |
| `xxl_job_log_report` | 按天聚合的调度统计报表 |
| `xxl_job_lock` | 分布式调度锁（单行，SELECT FOR UPDATE） |
| `xxl_job_user` | 管理台用户账号 |

---

## 表结构详情

### xxl_job_group（执行器分组）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int PK AI | 自增主键 |
| `app_name` | varchar(64) NOT NULL | 执行器 AppName，对应执行器配置的唯一标识 |
| `title` | varchar(64) NOT NULL | 执行器名称（展示用） |
| `address_type` | tinyint NOT NULL DEFAULT 0 | 地址类型：0=自动注册（从 registry 表聚合），1=手动录入 |
| `address_list` | text | 当前在线执行器地址列表，逗号分隔，由 `JobRegistryHelper` 每 30s 刷新 |
| `update_time` | datetime | 最近更新时间 |

---

### xxl_job_registry（执行器注册心跳）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK AI | 自增主键 |
| `registry_group` | varchar(50) NOT NULL | 注册分组，固定值 `EXECUTOR` |
| `registry_key` | varchar(255) NOT NULL | 执行器 AppName |
| `registry_value` | varchar(255) NOT NULL | 执行器实例地址（含端口，如 `http://192.168.1.1:9999/`） |
| `update_time` | datetime | 最近心跳时间；`JobRegistryHelper` 用此字段判断实例是否存活（超时阈值 90s） |

**索引**：
- `UNIQUE KEY i_g_k_v (registry_group, registry_key, registry_value)`：保证同一实例只有一条记录，心跳时 upsert

---

### xxl_job_info（任务定义）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int PK AI | 任务 ID（jobId） |
| `job_group` | int NOT NULL | 关联 `xxl_job_group.id`，决定任务发到哪个执行器分组 |
| `job_desc` | varchar(255) NOT NULL | 任务描述（展示用） |
| `add_time` / `update_time` | datetime | 创建/更新时间 |
| `author` | varchar(64) | 负责人（展示用，不影响调度） |
| `alarm_email` | varchar(255) | 告警邮件，多个逗号分隔 |
| `schedule_type` | varchar(50) NOT NULL DEFAULT 'NONE' | 调度类型：`NONE` / `CRON` / `FIX_RATE` |
| `schedule_conf` | varchar(128) | 调度配置值：CRON 时为表达式，FIX_RATE 时为间隔秒数 |
| `misfire_strategy` | varchar(50) NOT NULL DEFAULT 'DO_NOTHING' | 调度过期策略：`DO_NOTHING` / `FIRE_ONCE_NOW` |
| `executor_route_strategy` | varchar(50) | 路由策略：FIRST / LAST / ROUND / RANDOM / CONSISTENT_HASH / LFU / LRU / FAILOVER / BUSYOVER / SHARDING_BROADCAST |
| `executor_handler` | varchar(255) | BEAN 模式下 Handler 名（对应 `@XxlJob` 注解值） |
| `executor_param` | text | 任务参数，通过 `XxlJobHelper.getJobParam()` 获取 |
| `executor_block_strategy` | varchar(50) | 阻塞策略：`SERIAL_EXECUTION` / `DISCARD_LATER` / `COVER_EARLY` |
| `executor_timeout` | int NOT NULL DEFAULT 0 | 执行超时秒数，0 表示不限制 |
| `executor_fail_retry_count` | int NOT NULL DEFAULT 0 | 失败重试次数 |
| `glue_type` | varchar(50) NOT NULL | GLUE 类型：`BEAN` / `GLUE_GROOVY` / `GLUE_SHELL` / `GLUE_PYTHON` / `GLUE_PHP` / `GLUE_NODEJS` / `GLUE_POWERSHELL` |
| `glue_source` | mediumtext | GLUE 源码（非 BEAN 模式下存储可执行代码） |
| `glue_remark` | varchar(128) | GLUE 备注 |
| `glue_updatetime` | datetime | GLUE 最近更新时间，执行器据此判断是否需要重新编译 |
| `child_jobid` | varchar(255) | 子任务 ID，多个逗号分隔；任务成功后触发 |
| `trigger_status` | tinyint NOT NULL DEFAULT 0 | 调度状态：0=停止，1=运行 |
| `trigger_last_time` | bigint NOT NULL DEFAULT 0 | 上次调度时间（毫秒时间戳） |
| `trigger_next_time` | bigint NOT NULL DEFAULT 0 | 下次调度时间（毫秒时间戳），`scheduleThread` 用此字段扫描待触发任务 |

**关键约束**：`scheduleThread` 的 `scheduleJobQuery` 查询条件为 `trigger_status=1 AND trigger_next_time <= nowTime+5s`。

---

### xxl_job_logglue（GLUE 版本历史）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int PK AI | 自增主键 |
| `job_id` | int NOT NULL | 关联 `xxl_job_info.id` |
| `glue_type` | varchar(50) | GLUE 类型 |
| `glue_source` | mediumtext | 该版本的源码内容 |
| `glue_remark` | varchar(128) NOT NULL | 版本备注 |
| `add_time` / `update_time` | datetime | 时间戳 |

**注意**：版本上限（文档称 30 版）需读 `XxlJobLogGlueMapper.xml` 的删除 SQL 确认实际 LIMIT 值。

---

### xxl_job_log（调度执行日志）

每次调度触发生成一条记录，贯穿整个调度→执行→回调生命周期。

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK AI | 日志 ID（logId），下发给执行器，回调时带回 |
| `job_group` | int NOT NULL | 执行器分组 ID |
| `job_id` | int NOT NULL | 任务 ID |
| `executor_address` | varchar(255) | 本次实际执行的执行器地址 |
| `executor_handler` | varchar(255) | Handler 名 |
| `executor_param` | text | 执行参数 |
| `executor_sharding_param` | varchar(20) | 分片参数，格式 `broadcastIndex/broadcastTotal` |
| `executor_fail_retry_count` | int | 剩余重试次数 |
| `trigger_time` | datetime | 调度触发时间 |
| `trigger_code` | int NOT NULL | 调度结果码：200=触发成功，500=失败 |
| `trigger_msg` | text | 调度过程日志（路由信息、HTTP 请求结果等） |
| `handle_time` | datetime | 执行完成时间（由回调写入） |
| `handle_code` | int NOT NULL | 执行结果码：0=进行中，200=成功，500=失败 |
| `handle_msg` | text | 执行结果描述（由回调写入） |
| `alarm_status` | tinyint NOT NULL DEFAULT 0 | 告警状态：0=默认，1=无需告警，2=告警成功，3=告警失败 |

**索引**：
- `KEY I_trigger_time (trigger_time)`：按时间查询/清理日志
- `KEY I_handle_code (handle_code)`：`JobFailAlarmMonitorHelper` 扫描 handle_code=500
- `KEY I_jobgroup (job_group)` / `KEY I_jobid (job_id)`：按分组/任务查询

---

### xxl_job_log_report（调度统计报表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int PK AI | 自增主键 |
| `trigger_day` | datetime | 统计日期（按天聚合） |
| `running_count` | int NOT NULL DEFAULT 0 | 当天进行中任务数 |
| `suc_count` | int NOT NULL DEFAULT 0 | 当天成功任务数 |
| `fail_count` | int NOT NULL DEFAULT 0 | 当天失败任务数 |
| `update_time` | datetime | 更新时间 |

**索引**：`UNIQUE KEY i_trigger_day (trigger_day)`，每天只有一条统计记录。

---

### xxl_job_lock（分布式调度锁）

| 字段 | 类型 | 说明 |
|------|------|------|
| `lock_name` | varchar(50) PK | 锁名称 |

初始数据：只有一行 `lock_name='schedule_lock'`。`scheduleThread` 用 `SELECT * FROM xxl_job_lock WHERE lock_name='schedule_lock' FOR UPDATE` 实现所有 Admin 节点的互斥调度。

---

### xxl_job_user（管理台用户）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | int PK AI | 自增主键 |
| `username` | varchar(50) NOT NULL | 登录账号 |
| `password` | varchar(100) NOT NULL | SHA-256 哈希密码（初始 admin 密码为 `123456` 的 SHA-256） |
| `token` | varchar(100) | 登录 token（SSO 会话） |
| `role` | tinyint NOT NULL | 角色：0=普通用户，1=管理员 |
| `permission` | varchar(255) | 普通用户可访问的执行器 ID 列表（逗号分隔），NULL 表示管理员全部可见 |

**索引**：`UNIQUE KEY i_username (username)`

---

## 表关系图

```
xxl_job_group (id) ←── xxl_job_info (job_group)
xxl_job_info  (id) ←── xxl_job_log (job_id)
xxl_job_info  (id) ←── xxl_job_logglue (job_id)
xxl_job_group (app_name) ←── xxl_job_registry (registry_key)  [逻辑关联，无外键]
xxl_job_lock  (lock_name='schedule_lock')  [单行锁表，无关联]
```

所有关联均为逻辑外键，数据库层面无 `FOREIGN KEY` 约束。
