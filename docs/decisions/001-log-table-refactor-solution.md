# ADR-001：xxl_job_log 表治理方案选型

| 项目 | 内容 |
|------|------|
| **状态** | 已接受（Accepted） |
| **决策日期** | 2026-04-22 |
| **决策人** | wenqiang.xu |
| **关联文档** | [log-table-refactor-plan.md](../log-table-refactor-plan.md)、[log-table-refactor-changes.md](../log-table-refactor-changes.md) |

---

## 上下文

xxl_job_log 是 XXL-JOB 调度平台的核心日志表，记录每次任务触发和执行的完整生命周期。随着接入任务数量增长，该表出现以下问题：

- 查询性能：管理台翻页查询无联合索引，失败告警扫描（每 10 秒）走负向条件导致索引失效，失联兜底 JOIN 无索引
- 清理可靠性：自动清理在 Admin 重启后立即触发，批次间无限速，执行结果无日志，与手动清理无互斥
- 运维盲区：`alarm_status=-1` 进程崩溃后永久卡死，无恢复机制

完整问题诊断见 [log-table-refactor-plan.md 第二节](../log-table-refactor-plan.md#二问题诊断清单)（P-1~P-5、C-1~C-4、O-1~O-5 共 14 个问题）。

**项目约束**：
- 团队熟 MySQL，无 ClickHouse/ES/分布式存储运维经验
- 不引入新基础设施
- 内部工具，允许短暂停机窗口
- 验收目标参考需求文档 G-1~G-10

---

## 考虑过的方案

以下 11 种业界主流大表治理做法均经过评估：

| 方案 | 评定 | 不选/有保留的原因 |
|------|------|-----------------|
| A. 索引优化（Index Tuning） | **可行** | 选定，零依赖，收益最直接 |
| B. 数据归档（pt-archiver） | 部分可行 | 归档后历史查询入口需改造；与分区表二选一，本次选 C |
| C. MySQL 分区表 | **部分可行** | 选定；需确认停机窗口和 PK 兼容性，作为中期清理优化 |
| D. 冷热分离 | 部分可行 | 本质是 B 的变体，应用层路由逻辑复杂，暂不选 |
| E. ClickHouse/Doris 列式存储 | 不可行 | 团队不熟，引入新基础设施，无 OLAP 查询需求 |
| F. 分库分表（Sharding） | 不可行 | 两个常见查询模式无法被同一分片键同时优化，复杂度高 |
| G. MQ + 异步写入（Kafka） | 不可行 | 写入路径有状态（多步 UPDATE），真实瓶颈不在写入吞吐 |
| H. 应用层 TTL（清理逻辑修复） | **可行** | 选定；纯 Java 改动，覆盖 O 类所有运维问题 |
| I. 时序数据库（InfluxDB） | 不可行 | 查询模式不匹配，引入新存储类型 |
| J. 读写分离 | 部分可行 | 有主从延迟问题，当前瓶颈不在读吞吐，A 做完后再评估 |
| K. 垂直拆表（列拆分） | 部分可行 | 仅在 trigger_msg 平均超 2KB 时才值得；暂缓，G-6 先做截断 |

---

## 选定方案

**A（索引优化）+ H（清理逻辑修复）+ C（MySQL 分区表，中期）**

具体改造点见 [log-table-refactor-changes.md](../log-table-refactor-changes.md)，共 13 个改造点（A-1~A-5、H-1~H-5、C-1~C-4）。

---

## 决策理由

### 选 A（索引优化）

- 解决 P-1、P-2、P-3 三个性能问题，覆盖 G-1、G-2、G-3
- 纯 DDL + SQL，零停机风险（InnoDB online DDL）
- 团队 MySQL 技能栈完全覆盖，无学习成本
- 不需要任何代码架构变动

### 选 H（清理逻辑修复）

- 解决 O-1、O-2、O-3、O-4、O-5 五个运维问题，覆盖 G-5、G-7、G-8、G-9
- 纯 Java 代码改动，无基础设施依赖
- 每个子改造点独立，可逐一上线，风险可控
- alarm_status=-1 卡死是已知存量 bug，必须修

### 选 C（分区表，中期）

- 解决 C-1（行数增长）和 G-3（清理效率），DROP PARTITION 比 DELETE 快几个数量级
- 团队 MySQL 运维范围内的成熟方案
- 允许停机窗口，全表重建技术上可行
- 中长期清理成本远低于持续 DELETE

### 不选其他

- E/F/G/I：与"不引入新基础设施"约束直接冲突
- B（pt-archiver）：与 C 功能重叠，选一即可；C 的长期运维更简单
- D/J/K：收益条件不满足（当前数据规模和查询模式），按需评估

---

## 预期后果

### 正面

- `findFailJobLogIds`（每 10 秒）和 `pageList`（用户操作）执行计划由全表扫描改为索引访问，主库 CPU 和 IO 下降可见
- Admin 重启后不再产生非预期 IO 毛刺
- 清理执行全程可观测（行数 + 耗时日志）
- alarm_status=-1 卡死问题在重启时自动修复，存量泄漏被清除
- 分区表建立后，过期数据清理从 DELETE 批次改为 DROP PARTITION，耗时从分钟级降至秒级

### 负面

- C-2（分区 DDL 全表重建）需要停机窗口，重建期间 IO 升高，所有依赖 xxl_job_log 的链路均受短暂影响
- C-4（DROP PARTITION 清理）需在应用层执行 DDL，需要特殊 MySQL 权限，且引入 MDL 锁竞争
- H-4（alarm_status=-1 修复）在 Admin 启动时触发一次补偿 UPDATE，可能导致一批历史失败任务补发告警

---

## 已知风险

| 风险 | 严重性 | 缓解措施 |
|------|--------|---------|
| A-3（SQL 改写）语义等价未验证 | 高 | 改写前先确认 handle_code/trigger_code 合法值范围；上线前在测试库对比结果集 |
| C-2（全表重建）停机窗口超预期 | 高 | 先在测试库估算重建时长（行数 / 插入速度）；使用 pt-online-schema-change 替代 ALTER TABLE 以缩短锁窗口 |
| C-1（联合 PK）影响 load(id) 查询计划 | 中 | EXPLAIN 验证联合 PK 下 `WHERE id = ?` 仍走聚簇索引最左前缀 |
| H-4 补偿告警风暴 | 中 | 告警渠道设限速（已有 JobAlarmer 遍历机制）；上线前确认生产 alarm_status=-1 存量数量，评估告警数量 |
| C-4（DROP PARTITION）与 findLostJobIds 时间窗口边界 | 低 | findLostJobIds 的时间窗口是 10 分钟，分区边界是月，正常情况不重叠；上线时确认清理时间点与最近一次 findLostJobIds 执行时间间隔 |

---

## 回顾节点

- **A + H 上线后 2 周**：收集 MySQL slow query log，对比索引生效前后的 P99 查询时间；确认清理日志可观测
- **C-2 执行前**：重新评估当前表行数和停机窗口长度；决定是用 `ALTER TABLE` 还是 pt-online-schema-change
