# 代码风格说明

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：从代码库实际代码反推出的命名规范、分包规范、注释规范、日志规范。不是通用 Java 规范，是"这个项目实际怎么写的"。

**不讲什么**：通用 Java 编码规范、IDE 配置、Checkstyle 配置（项目无 Checkstyle）。

---

## 命名规范

### 类名

| 类型 | 命名模式 | 示例 |
|------|---------|------|
| 实体模型 | `XxlJob{TableName}` | `XxlJobInfo`、`XxlJobLog`、`XxlJobGroup` |
| Mapper 接口 | `XxlJob{TableName}Mapper` | `XxlJobInfoMapper`、`XxlJobLockMapper` |
| Controller | `{功能}Controller` | `JobInfoController`、`OpenApiController` |
| Service 接口 | `XxlJob{功能}Service` / `{模块}Biz` | `XxlJobService`、`AdminBiz`、`ExecutorBiz` |
| Service 实现 | `{接口名}Impl` | `XxlJobServiceImpl`、`AdminBizImpl`、`ExecutorBizImpl` |
| 后台线程/Helper | `Job{功能}Helper` | `JobScheduleHelper`、`JobRegistryHelper`、`JobTriggerPoolHelper` |
| 策略枚举 | `{主体}{策略类型}Enum` | `ExecutorRouteStrategyEnum`、`MisfireStrategyEnum`、`GlueTypeEnum` |
| 策略接口 | `{主体}{策略类型}` | `ExecutorRouter`、`MisfireHandler`、`ScheduleType` |
| 策略实现 | `{主体}{策略名}` | `ExecutorRouteFirst`、`ExecutorRouteLFU`、`MisfireDoNothing` |
| 枚举 | `{主体}Enum` | `TriggerTypeEnum`、`ExecutorBlockStrategyEnum` |
| 请求模型 | `{动作}Request` | `TriggerRequest`、`CallbackRequest`、`RegistryRequest` |
| 响应模型 | `{内容}Result` | `LogResult` |
| 线程类 | `{功能}Thread` | `JobThread`、`ExecutorRegistryThread`、`TriggerCallbackThread` |

### 字段名

- Java Bean 字段使用 **camelCase**，与数据库 **snake_case** 字段通过 MyBatis XML 映射（无注解映射，SQL 中显式 `AS` 或 `<result>` 标签）
- 布尔字段不使用 `is` 前缀：如 `toStop`（volatile boolean），而非 `isStopped`
- 枚举值用 **全大写下划线**：`SERIAL_EXECUTION`、`DO_NOTHING`、`SHARDING_BROADCAST`

### 方法名

- Service/Mapper 方法：`pageList`、`add`、`update`、`remove`、`start`、`stop`、`trigger`（动词/动词+名词）
- Mapper 查询：`load`（单条）、`findByXxx`（按条件）、`scheduleJobQuery`（特定业务查询直接描述场景）
- Helper 线程生命周期：`start()` / `stop()`（统一命名，无 `init`/`destroy`/`shutdown`）
- 枚举获取策略：`route()`（ExecutorRouter）、`nextTriggerTime()`（ScheduleType）、`doMisfire()`（MisfireHandler）

---

## 分包规范

### xxl-job-core 包结构

```
com.xxl.job.core
├── constant/       # 枚举和常量（不含业务逻辑）
├── context/        # 执行上下文（ThreadLocal + 静态工具）
├── executor/       # 执行器主类和实现（Spring/非 Spring 两套）
├── glue/           # GLUE 运行时（工厂 + 类型枚举 + Spring 实现）
├── handler/        # Handler 接口、注解、各模式实现
├── log/            # 执行器本地日志文件读写
├── openapi/        # 接口定义（AdminBiz/ExecutorBiz）、模型、实现
├── server/         # Netty 内嵌 HTTP 服务
├── thread/         # 后台线程（注册/执行/回调/日志清理）
└── util/           # 工具类（ScriptUtil + deprecated/）
```

**原则**：core 模块不依赖 Spring（`spring-context` 是 provided），Spring 感知的代码放 `executor/impl/` 和 `glue/impl/`，其余代码纯 Java。

### xxl-job-admin 包结构

```
com.xxl.job.admin
├── constant/       # Admin 侧常量和枚举
├── controller/     
│   ├── base/       # 基础页面（首页/登录）
│   └── biz/        # 业务页面 Controller（任务/日志/用户/执行器）
├── mapper/         # MyBatis Mapper 接口
├── model/          # 数据库实体（+ dto/ 子包放 DTO）
├── service/        # 业务 Service 接口和实现
├── scheduler/      # 调度核心，不依赖 Web 层
│   ├── alarm/      # 告警接口和实现
│   ├── complete/   # 任务完成处理
│   ├── config/     # Bootstrap（生命周期管理）
│   ├── cron/       # Cron 解析
│   ├── misfire/    # 过期策略
│   ├── openapi/    # OpenAPI Controller（/api/*）
│   ├── route/      # 路由策略
│   ├── thread/     # 后台线程（调度/触发池/注册/告警/日志）
│   ├── trigger/    # 触发核心
│   └── type/       # 调度类型
├── util/           # Admin 工具（I18n/权限）
└── web/            # Web 层基础设施（错误处理/SSO 配置）
```

**原则**：`scheduler/` 是 Admin 的核心，与 Web 层（`controller/`）严格分离，`scheduler/` 通过 `XxlJobAdminBootstrap` 单例对外暴露 Mapper 和 Helper，而不是通过 Spring 注入。

---

## 注释规范

### 类注释

只写作者和日期，不写功能描述（功能从类名读）：
```java
/**
 * @author xuxueli 2017-07-27 21:52:49
 */
```

### 方法注释

简短 JavaDoc，只说明参数和返回含义，不重复方法名：
```java
/**
 * callback
 * @param callbackRequestList
 * @return
 */
public Response<String> callback(List<CallbackRequest> callbackRequestList);
```

### 行内注释

- 枚举字段用中文注释标注枚举含义：`// 执行器AppName`、`// 调度状态：0-停止，1-运行`
- 关键业务逻辑步骤用数字注释：`// 1、fail retry monitor`、`// 2、fail alarm monitor`
- 警告性注释直接写问题：`// FUTURE_TODO`、`// on the way`（不用 `// NOTE:` 或 `// IMPORTANT:`）

### 什么时候不写注释

- 纯 getter/setter 不写注释
- 命名已经自说明的方法不写注释（如 `start()`、`stop()`）
- 简单的条件判断不写注释

---

## 日志规范

### Logger 声明

```java
private static Logger logger = LoggerFactory.getLogger(XxxClass.class);
```
统一用 `logger`（小写），不用 `log`、`LOG`、`LOGGER`。

### 日志格式

前缀统一加 `>>>>>>>>>>> xxl-job, `：
```java
logger.info(">>>>>>>>>>> xxl-job, job registry monitor thread stop");
logger.error(">>>>>>>>>>> xxl-job, job fail monitor thread error:{}", e.getMessage(), e);
logger.warn(">>>>>>>>>>> xxl-job, registry or remove too fast, ...");
```

### 日志级别使用

| 级别 | 使用场景 |
|------|---------|
| `info` | 线程启动/停止、关键流程节点（注册成功、触发成功） |
| `warn` | 非致命异常、重要的业务降级（重复触发抑制、线程池满降级） |
| `error` | 线程异常（通常在 while 循环的 catch 里）、关键操作失败 |
| `debug` | 心跳等高频操作的详细日志（注册成功时用 debug） |

### 异常日志

后台线程的异常统一判断 `toStop` 后再打日志，避免关闭时的噪音：
```java
} catch (Throwable e) {
    if (!toStop) {
        logger.error("...", e);
    }
}
```

---

## 后台线程模式

项目所有后台线程遵循同一种模式：

```java
private Thread xxxThread;
private volatile boolean toStop = false;

public void start() {
    xxxThread = new Thread(() -> {
        while (!toStop) {
            try {
                // 业务逻辑
            } catch (Throwable e) {
                if (!toStop) {
                    logger.error("...", e);
                }
            }
            try {
                TimeUnit.SECONDS.sleep(N);
            } catch (Throwable e) {
                if (!toStop) { logger.warn(...); }
            }
        }
        logger.info(">>>>>>>>>>> xxl-job, xxx thread stop");
    });
    xxxThread.setDaemon(true);
    xxxThread.setName("xxl-job, xxx");
    xxxThread.start();
}

public void stop() {
    toStop = true;
    xxxThread.interrupt();
    try { xxxThread.join(); } catch (Throwable e) { logger.error(...); }
}
```

关键点：
- `volatile boolean toStop`（内存可见性）
- 线程设为 daemon（JVM 退出时自动结束）
- 线程名格式：`xxl-job, {模块} {线程功能}`
- `stop()` 先设标志位，再 interrupt，再 join

---

## MyBatis 规范

- Mapper 接口方法名与 XML 中 `id` 一致
- SQL 写在 XML 文件中，不用注解 SQL
- 参数超过 1 个时用 `@Param` 注解（部分旧代码用 Map，新代码用 @Param）
- 不用 MyBatis Generator 生成代码，全手写
- 动态 SQL 用 `<if>`、`<foreach>`，不用 `<choose>`

---

## 枚举策略模式

路由策略、调度类型、Misfire 策略均采用同一模式：

```java
public enum XxxStrategyEnum {
    STRATEGY_A("描述A", new StrategyAImpl()),
    STRATEGY_B("描述B", new StrategyBImpl());

    private String title;
    private XxxStrategy strategy;

    // 枚举持有策略实例，调用时直接 enum.getStrategy().doSomething()
}
```

新增策略时：实现接口 → 在枚举加值 → 不动现有策略实现。
