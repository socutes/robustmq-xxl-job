# SKILL: 新增路由策略（ExecutorRouter）

## 适用场景

在现有 10 种路由策略之外，新增自定义路由算法（如：按标签匹配、按执行器负载权重、按数据分区）。
策略枚举持有实例，调用时 `enum.getRouter().route()` 直接分发，新增策略不影响任何现有策略。

## 扩展点位置

| 文件 | 作用 |
|------|------|
| [ExecutorRouter.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/route/ExecutorRouter.java) | 抽象基类，`route(TriggerRequest, List<String>): Response<String>` |
| [ExecutorRouteStrategyEnum.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/route/ExecutorRouteStrategyEnum.java) | 枚举，持有各策略实例；新增枚举值是唯一入口 |
| [route/strategy/](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/route/strategy/) | 现有 10 种实现，参考 `ExecutorRouteFirst`（最简）或 `ExecutorRouteLFU`（有状态） |

## 现有 10 种策略（不要改）

| 枚举值 | 算法 |
|--------|------|
| `FIRST` | 固定第一个 |
| `LAST` | 固定最后一个 |
| `ROUND` | 轮询（AtomicInteger 计数） |
| `RANDOM` | 随机 |
| `CONSISTENT_HASH` | 一致性哈希（按 jobId） |
| `LEAST_FREQUENTLY_USED` | LFU（最不常用） |
| `LEAST_RECENTLY_USED` | LRU（最近最少使用） |
| `FAILOVER` | 故障转移（依次 `/beat` 探活） |
| `BUSYOVER` | 忙碌转移（依次 `/idleBeat` 探空闲） |
| `SHARDING_BROADCAST` | 广播（`router` 字段为 `null`，由 `JobTrigger` 特殊处理） |

## 实现步骤

### 第一步：实现路由算法

在 `strategy/` 目录下新建类，继承 `ExecutorRouter`：

```java
package com.xxl.job.admin.scheduler.route.strategy;

import com.xxl.job.admin.scheduler.route.ExecutorRouter;
import com.xxl.job.core.openapi.model.TriggerRequest;
import com.xxl.tool.response.Response;

import java.util.List;

public class ExecutorRouteWeighted extends ExecutorRouter {

    @Override
    public Response<String> route(TriggerRequest triggerParam, List<String> addressList) {
        // 示例：按 jobId 哈希权重选址（实际逻辑替换为权重配置）
        int index = Math.abs(triggerParam.getJobId()) % addressList.size();
        return Response.ofSuccess(addressList.get(index));
    }

}
```

**`route()` 契约**：
- 从 `addressList` 中选一个地址返回 `Response.ofSuccess(address)`
- 若无可用地址，返回 `Response.ofFail("no address")`（不要抛异常）
- `FAILOVER`/`BUSYOVER` 等需 HTTP 探活的策略，参考对应实现访问执行器 `/beat` 或 `/idleBeat`

### 第二步：在枚举中注册

在 `ExecutorRouteStrategyEnum.java` 末尾（`SHARDING_BROADCAST` 之前）添加枚举值：

```java
// 加在 SHARDING_BROADCAST 之前（SHARDING_BROADCAST 的 router 为 null，需保持在末位）
WEIGHTED(I18nUtil.getString("jobconf_route_weighted"), new ExecutorRouteWeighted()),
SHARDING_BROADCAST(I18nUtil.getString("jobconf_route_shard"), null);
```

### 第三步：添加 i18n 文案

在以下三个文件中同步添加 key：

- `xxl-job-admin/src/main/resources/i18n/message_zh_CN.properties`
- `xxl-job-admin/src/main/resources/i18n/message_zh_TC.properties`
- `xxl-job-admin/src/main/resources/i18n/message_en.properties`

```properties
# 中文
jobconf_route_weighted=加权路由
# 繁体
jobconf_route_weighted=加權路由
# 英文
jobconf_route_weighted=WEIGHTED
```

### 第四步：前端下拉框（如需在 UI 中展示）

`job.list.ftl` 中的路由策略下拉是通过枚举动态生成的（`ExecutorRouteStrategyEnum.values()`），
无需手动修改模板，刷新页面后新策略自动出现在任务配置表单中。

## 注意事项

- `SHARDING_BROADCAST` 的 `router` 为 `null`，`JobTrigger.processTrigger()` 对其有特殊处理分支，新增策略**不要让 router 为 null**，否则会被当成广播处理
- 有状态的路由（LFU/LRU/Round）存储在类字段中，是枚举单例持有的全局状态，**线程安全需自行保证**（参考 `ExecutorRouteLFU` 的 `synchronized` 用法）
- `triggerParam` 中可用字段：`getJobId()`、`getExecutorParam()`（任务参数，可用于按参数分片路由）
- 不要在 `route()` 中修改 `addressList`（是传入的引用，调用方会复用）

## 验证方法

1. 在管理台「任务管理」新建任务，路由策略选新枚举值
2. 手动触发，查看 `xxl_job_log.executor_address` 确认选址结果
3. 多次触发，验证路由算法行为符合预期
