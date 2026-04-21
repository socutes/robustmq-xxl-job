# 生产部署说明

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：生产环境部署 XXL-JOB 调度中心和执行器的关键配置和注意事项。

**不讲什么**：本地开发启动（见 [build-and-run.md](build-and-run.md)）、Kubernetes 编排（待补充）。

---

## 整体拓扑

```
                    ┌─────────────────────────────────┐
                    │        Load Balancer / VIP       │
                    └──────────────┬──────────────────┘
                                   │
                ┌──────────────────┼──────────────────┐
                │                  │                  │
        ┌───────┴──────┐   ┌───────┴──────┐   ┌───────┴──────┐
        │  Admin 节点 1 │   │  Admin 节点 2 │   │  Admin 节点 N │
        └───────┬──────┘   └───────┬──────┘   └───────┬──────┘
                │                  │                  │
                └──────────────────┼──────────────────┘
                                   │
                          ┌────────┴────────┐
                          │     MySQL        │
                          │  (xxl_job 库)    │
                          └─────────────────┘

        执行器集群（各业务方自行部署，通过 AppName 分组）
        ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
        │  Executor A1 │  │  Executor A2 │  │  Executor B1 │
        └──────────────┘  └──────────────┘  └──────────────┘
```

---

## 调度中心（Admin）生产配置

### 必改配置项

```properties
# 数据库（生产账号，最小权限：SELECT/INSERT/UPDATE/DELETE，无需 DDL 权限）
spring.datasource.url=jdbc:mysql://{mysql-host}:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai&useSSL=true
spring.datasource.username={prod_user}
spring.datasource.password={prod_password}

# 访问令牌（随机字符串，与所有执行器保持一致）
xxl.job.accessToken={random_strong_token}

# 日志保留（按实际存储规划设置）
xxl.job.logretentiondays=30
```

### 建议调整配置项

```properties
# HikariCP 连接池（根据 Admin 节点数和调度 QPS 评估）
spring.datasource.hikari.minimum-idle=10
spring.datasource.hikari.maximum-pool-size=30

# 触发线程池（按公式评估：(fast+slow)×10 = 5s 内最大可调度任务数）
xxl.job.triggerpool.fast.max=300
xxl.job.triggerpool.slow.max=200

# 调度批次（调小可缩短分布式锁持锁时长，Admin 节点多时建议减小）
xxl.job.schedule.batchsize=100

# 邮件告警（生产必配，否则失败告警无法发出）
spring.mail.host={smtp-host}
spring.mail.port=465
spring.mail.username={sender@domain.com}
spring.mail.password={smtp_password}
```

### Admin 集群注意事项

1. **所有 Admin 节点共享同一 MySQL**，通过 `SELECT FOR UPDATE` 互斥调度，无需额外配置
2. **所有节点 accessToken 必须相同**，执行器回调任意节点都能处理
3. **Admin 节点数不宜过多**（3 个通常足够），节点过多会加剧调度锁竞争
4. **负载均衡层只需 4 层 TCP 转发**，不需要 Session 粘连（调度是无状态的）
5. **Admin 端口（默认 8080）建议仅内网可达**，不要暴露在公网

---

## 执行器生产配置

### 必改配置项

```properties
# Admin 地址（多节点逗号分隔，执行器会依次尝试注册）
xxl.job.admin.addresses=http://{admin-vip}:{port}/xxl-job-admin

# 访问令牌（与 Admin 保持一致）
xxl.job.admin.accessToken={random_strong_token}

# AppName（与管理台执行器分组一致）
xxl.job.executor.appname={your-appname}

# 执行器注册地址（容器/多网卡环境下必须显式指定，否则可能注册错误 IP）
xxl.job.executor.address=http://{executor-ip}:{port}/

# 执行器端口（确保防火墙/安全组允许 Admin → Executor 的该端口访问）
xxl.job.executor.port=9999

# 日志路径（持久化存储，确保有写权限）
xxl.job.executor.logpath=/data/applogs/xxl-job/jobhandler
xxl.job.executor.logretentiondays=30
```

### 容器/Kubernetes 环境注意事项

- `xxl.job.executor.ip` 和 `xxl.job.executor.address` 必须显式配置为**容器外可访问**的地址，不能用容器内部 IP
- 若使用 Kubernetes Service，address 填 Service 的 ClusterIP 或 NodePort 地址
- Pod 重启后 IP 变化时，执行器会自动重新注册（注销旧地址需靠超时机制，约 90s）

---

## 数据库生产配置

### 建表

```bash
mysql -h {mysql-host} -u root -p < doc/db/tables_xxl_job.sql
```

### 建议索引（已在 DDL 中包含）

`xxl_job_log` 表的 `I_handle_code` 索引对 `JobFailAlarmMonitorHelper` 的扫描查询至关重要，确认创建。

### 连接数规划

每个 Admin 节点默认连接池上限 30，N 个节点总连接数约 `N × 30`，MySQL 的 `max_connections` 需留足余量。

---

## 安全配置

| 项 | 建议 |
|----|------|
| accessToken | 使用随机 UUID 或强随机字符串，不要用默认值 `default_token` |
| 管理台账号 | 上线前修改 admin 默认密码（数据库直接 UPDATE 密码字段，SHA-256 哈希） |
| 网络隔离 | Admin 管理台（8080 端口）仅内网可达；Admin OpenAPI（同端口 `/api/*`）只允许执行器 IP 段访问 |
| 数据库账号 | 使用最小权限账号，仅授权 DML 权限 |
| SMTP 密码 | 使用应用专用密码，不要用邮箱登录密码 |

---

## 监控建议

| 监控点 | 方式 |
|--------|------|
| 调度中心存活 | 对 `http://{admin}/xxl-job-admin` 做 HTTP 健康检查 |
| 任务失败 | 配置邮件告警或接入自定义 `JobAlarm` 实现 |
| 调度延迟 | 关注 `xxl_job_log` 中 `trigger_time - trigger_next_time` 的差值 |
| 线程池积压 | 关注 Admin GC 日志和 TriggerPool 的拒绝日志（`logger.error` 级别） |
| 日志表增长 | 确认 `JobLogReportHelper` 的定时清理在运行，关注 `xxl_job_log` 表行数 |

---

## Docker 部署

`docker/` 目录下有相关文件，具体使用方式待补充。
