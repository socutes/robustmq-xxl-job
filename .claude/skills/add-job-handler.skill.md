# SKILL: 新增 @XxlJob Handler

## 适用场景

在执行器（Executor）中新增任务处理器，包含三种模式：
1. **BEAN 模式**（最常用）：`@XxlJob` 注解方法，Spring 自动扫描注册
2. **分片广播模式**：BEAN 模式的变体，Handler 内读取分片参数
3. **GLUE 模式**：任务逻辑存数据库，无需修改执行器代码（本 SKILL 仅说明如何从 Handler 侧准备）

## 关键类位置

| 文件 | 作用 |
|------|------|
| [XxlJob.java](../../xxl-job-core/src/main/java/com/xxl/job/core/handler/annotation/XxlJob.java) | 方法注解，`value`=handlerName，`init`/`destroy`=生命周期方法名 |
| [XxlJobHelper.java](../../xxl-job-core/src/main/java/com/xxl/job/core/context/XxlJobHelper.java) | Handler 内获取上下文的唯一入口（参数、分片信息、日志写入） |
| [XxlJobContext.java](../../xxl-job-core/src/main/java/com/xxl/job/core/context/XxlJobContext.java) | ThreadLocal 存储当前执行上下文，通过 `XxlJobHelper` 访问，不要直接操作 |
| [XxlJobSpringExecutor.java](../../xxl-job-core/src/main/java/com/xxl/job/core/executor/impl/XxlJobSpringExecutor.java) | Spring 环境执行器，启动时扫描 `@XxlJob` 并注册到 HandlerMap |

## 模式一：BEAN 模式（标准 Handler）

```java
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.springframework.stereotype.Component;

@Component
public class SampleJobHandler {

    @XxlJob("demoJobHandler")
    public void demoJobHandler() throws Exception {
        // 读取任务参数（管理台「执行一次」或任务配置的 executorParam）
        String param = XxlJobHelper.getJobParam();
        XxlJobHelper.log("任务参数: {}", param);

        // 业务逻辑
        // ...

        // 成功：方法正常返回即为成功（handle_code=200）
        // 失败：抛出异常，或调用 XxlJobHelper.handleFail("原因")
    }

    // 可选：初始化方法（init 参数对应方法名）
    public void initHandler() {
        XxlJobHelper.log("handler init");
    }

    // 可选：销毁方法（destroy 参数对应方法名）
    public void destroyHandler() {
        XxlJobHelper.log("handler destroy");
    }
}
```

**`@XxlJob` 参数说明**：
- `value`：Handler 名称，必须全局唯一，与管理台执行器分组中配置的 handlerName 对应
- `init`：同类中方法名（无参），`JobThread` 创建时调用一次
- `destroy`：同类中方法名（无参），`JobThread` 销毁时调用一次

## 模式二：分片广播模式

路由策略选 `SHARDING_BROADCAST` 时，Admin 向执行器集群所有实例同时发送 `/run`，
每个实例收到不同的 `shardIndex`，Handler 内按分片分配数据范围：

```java
@XxlJob("shardingJobHandler")
public void shardingJobHandler() throws Exception {
    int shardIndex = XxlJobHelper.getShardIndex();   // 当前分片序号（0-based）
    int shardTotal = XxlJobHelper.getShardTotal();   // 总分片数（=执行器实例数）

    XxlJobHelper.log("分片执行: {}/{}", shardIndex, shardTotal);

    // 示例：按 id % shardTotal == shardIndex 分配数据
    List<Long> ids = queryBySharding(shardIndex, shardTotal);
    for (Long id : ids) {
        process(id);
        XxlJobHelper.log("处理 id={}", id);
    }
}
```

**分片注意事项**：
- `shardTotal` 等于触发时 Admin 查到的执行器在线实例数，不固定
- Handler 内不要依赖 `shardTotal` 做硬编码分区，每次执行按实际值计算
- 每个实例的执行结果独立回调，Admin 日志页面会显示多条记录（每实例一条）

## 模式三：GLUE 模式（Groovy）

GLUE 模式任务的代码存在 `xxl_job_logglue` 表，执行器按需从 Admin 拉取并用 Groovy 编译。
Handler 侧要准备的事：

1. 确认 `GlueFactory` 已初始化（Spring 环境由 `XxlJobSpringExecutor` 自动完成）
2. GLUE 代码中可以 `@Resource` 注入 Spring Bean（`SpringGlueFactory` 负责注入）
3. GLUE 任务在管理台「GLUE IDE」中在线编辑，每次保存生成新版本

GLUE 任务无需在执行器代码中声明 Handler，管理台直接维护代码。

## 在管理台注册任务

新增 `@XxlJob("demoJobHandler")` 后，执行器启动时自动注册 Handler 名称，无需管理台手动操作。
在管理台「任务管理」创建任务时：
- **执行器**：选择对应的执行器分组（AppName 匹配）
- **运行模式**：选 BEAN
- **JobHandler**：填写 `@XxlJob` 的 `value` 值（如 `demoJobHandler`）

## XxlJobHelper 常用 API

```java
XxlJobHelper.getJobParam()       // 获取任务参数字符串
XxlJobHelper.getJobId()          // 获取 jobId
XxlJobHelper.getShardIndex()     // 分片序号（非广播模式返回 0）
XxlJobHelper.getShardTotal()     // 分片总数（非广播模式返回 1）
XxlJobHelper.log("msg {}", arg)  // 写执行日志（存本地文件，可在管理台查看）
XxlJobHelper.handleSuccess("msg") // 标记成功并附加消息（方法返回 void 时用）
XxlJobHelper.handleFail("msg")   // 标记失败并附加消息（替代抛异常）
```

## 注意事项

- Handler 方法签名必须是 `public void methodName() throws Exception`，不接受参数，不返回值
- 执行结果由三种方式决定（优先级由高到低）：
  1. 调用 `XxlJobHelper.handleFail()` → 失败
  2. 方法抛出异常 → 失败
  3. 方法正常返回 → 成功
- Handler 内的日志用 `XxlJobHelper.log()` 而非 `logger.info()`，前者写执行日志文件（管理台可查），后者只写应用日志文件
- 同一 JVM 内 Handler 名称不可重复，重复时 `XxlJobSpringExecutor` 启动报错

## 验证方法

1. 启动执行器，查看日志确认 Handler 已注册（`XxlJobSpringExecutor` 输出注册日志）
2. 管理台「执行器管理」确认执行器在线
3. 管理台「任务管理」创建任务，配置 JobHandler 名称
4. 点击「执行一次」，进入「调度日志」查看执行结果和执行日志内容
