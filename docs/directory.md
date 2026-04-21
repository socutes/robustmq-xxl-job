# 目录结构说明

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：项目根目录及各模块内部关键目录的职责说明，精确到包级别。

**不讲什么**：每个文件的内容（见 [architecture.md](architecture.md) 和各专项文档）。

---

## 根目录

```
xxl-job/
├── CLAUDE.md                   # AI 协作上下文（给 Claude 读的）
├── NOTICE                      # 开源声明
├── LICENSE                     # GPL-3.0 许可证
├── README.md                   # 官方项目介绍（中英文）
├── pom.xml                     # 根 POM，统一管理版本号和依赖声明
├── doc/                        # 项目文档原始资源
│   ├── db/tables_xxl_job.sql   # 数据库 DDL（建表语句 + 初始数据）
│   └── images/                 # 文档截图
├── docker/                     # Docker/Compose 相关文件（待补充详细说明）
├── docs/                       # AI 生成的项目文档体系（本目录）
├── xxl-job-core/               # 核心 Jar，Admin 和 Executor 共用
├── xxl-job-admin/              # 调度中心 Spring Boot 应用
└── xxl-job-executor-samples/   # 执行器接入示例（非生产代码）
    ├── xxl-job-executor-sample-springboot/
    ├── xxl-job-executor-sample-frameless/
    └── xxl-job-executor-sample-springboot-ai/
```

---

## xxl-job-core（公共核心库）

```
xxl-job-core/src/main/java/com/xxl/job/core/
├── constant/
│   ├── Const.java                  # 全局常量（BEAT_TIMEOUT=30, DEAD_TIMEOUT=90 等）
│   ├── ExecutorBlockStrategyEnum.java  # 阻塞策略枚举（SERIAL/DISCARD/COVER）
│   └── RegistType.java             # 注册类型枚举（EXECUTOR）
│
├── context/
│   ├── XxlJobContext.java          # 任务执行上下文，ThreadLocal 存储（jobId/logId/分片参数）
│   └── XxlJobHelper.java           # Handler 调用的静态工具类（getJobParam/log/getShardIndex 等）
│
├── executor/
│   ├── XxlJobExecutor.java         # 执行器基类，管理 Handler 注册、AdminBiz 列表、EmbedServer 生命周期
│   └── impl/
│       ├── XxlJobSpringExecutor.java   # Spring 环境执行器，扫描 @XxlJob Bean 自动注册
│       └── XxlJobSimpleExecutor.java   # 简单执行器，手动注册 Handler
│
├── glue/
│   ├── GlueFactory.java            # GLUE 运行时工厂，管理 Groovy 编译和 Handler 实例缓存
│   ├── GlueTypeEnum.java           # GLUE 类型枚举（BEAN/GLUE_GROOVY/脚本类型）
│   └── impl/SpringGlueFactory.java # Spring 感知的 GLUE 工厂，Bean 注入支持
│
├── handler/
│   ├── IJobHandler.java            # Handler 接口（只有 execute() 方法）
│   ├── annotation/
│   │   ├── XxlJob.java             # 方法级注解，标记 Handler（value=handlerName, init, destroy）
│   │   └── JobHandler.java         # 类级注解（已不推荐，保留兼容）
│   └── impl/
│       ├── MethodJobHandler.java   # @XxlJob 方法对应的 Handler 包装
│       ├── GlueJobHandler.java     # GLUE_GROOVY 模式 Handler 包装
│       └── ScriptJobHandler.java   # 脚本类型 Handler（Shell/Python 等）
│
├── log/
│   └── XxlJobFileAppender.java     # 执行器本地日志文件读写（按 logId 存文件）
│
├── openapi/
│   ├── AdminBiz.java               # 调度中心接口定义（callback/registry/registryRemove + 7个占位方法）
│   ├── ExecutorBiz.java            # 执行器接口定义（beat/idleBeat/run/kill/log）
│   ├── impl/ExecutorBizImpl.java   # 执行器接口实现（Handler 选取、阻塞策略、入队）
│   └── model/                      # 接口请求/响应模型（TriggerRequest/CallbackRequest 等）
│
├── server/
│   └── EmbedServer.java            # 基于 Netty 的执行器内嵌 HTTP 服务（复制自 xxl-rpc）
│
├── thread/
│   ├── ExecutorRegistryThread.java     # 执行器注册/心跳线程（每 30s 向 Admin 注册）
│   ├── JobThread.java                  # 每 jobId 一个专属线程，循环消费触发队列
│   ├── TriggerCallbackThread.java      # 回调线程，批量将执行结果 POST 到 Admin
│   └── JobLogFileCleanThread.java      # 执行器本地日志清理线程（按 logretentiondays）
│
└── util/
    ├── ScriptUtil.java             # 脚本任务执行工具（写文件、调系统命令）
    └── deprecated/                 # 废弃工具类（AdminBizClient/ExecutorBizClient 等，勿引用）
```

---

## xxl-job-admin（调度中心）

```
xxl-job-admin/src/main/java/com/xxl/job/admin/
├── XxlJobAdminApplication.java     # Spring Boot 启动类
│
├── constant/
│   ├── Consts.java                 # Admin 侧常量
│   └── TriggerStatus.java          # 触发状态枚举（0=停止, 1=运行）
│
├── controller/
│   ├── base/
│   │   ├── IndexController.java    # 首页/Dashboard 控制器
│   │   └── LoginController.java    # 登录/登出控制器
│   └── biz/
│       ├── JobInfoController.java  # 任务管理 CRUD（/jobinfo）
│       ├── JobGroupController.java # 执行器分组管理（/jobgroup）
│       ├── JobLogController.java   # 调度日志查询（/joblog）
│       ├── JobCodeController.java  # GLUE 在线编辑（/jobcode）
│       └── JobUserController.java  # 用户管理（/user）
│
├── mapper/                         # MyBatis Mapper 接口（对应 resources/mapper/*.xml）
│   ├── XxlJobInfoMapper.java       # 任务定义 CRUD + scheduleJobQuery（时间轮扫描）
│   ├── XxlJobLogMapper.java        # 调度日志 CRUD
│   ├── XxlJobLogReportMapper.java  # 统计报表
│   ├── XxlJobGroupMapper.java      # 执行器分组
│   ├── XxlJobRegistryMapper.java   # 执行器注册心跳
│   ├── XxlJobLockMapper.java       # 分布式调度锁（FOR UPDATE）
│   ├── XxlJobLogGlueMapper.java    # GLUE 版本历史
│   └── XxlJobUserMapper.java       # 用户账号
│
├── model/                          # 数据库实体模型（对应各表）
│
├── service/
│   ├── XxlJobService.java          # 任务管理业务接口（add/update/remove/start/stop/trigger）
│   └── impl/
│       ├── XxlJobServiceImpl.java  # 业务实现
│       └── AdminBizImpl.java       # AdminBiz 接口实现（callback/registry/registryRemove）
│
├── scheduler/                      # 调度中心核心（不依赖 Web 层，可独立测试）
│   ├── alarm/
│   │   ├── JobAlarm.java           # 告警接口（实现此接口加 Bean 即可新增告警渠道）
│   │   ├── JobAlarmer.java         # 告警聚合器（遍历所有 JobAlarm Bean）
│   │   └── impl/EmailJobAlarm.java # 邮件告警实现
│   │
│   ├── complete/
│   │   └── JobCompleter.java       # 任务完成处理（更新日志、触发子任务）
│   │
│   ├── config/
│   │   └── XxlJobAdminBootstrap.java   # Admin 生命周期管理（InitializingBean/DisposableBean，启动6个 Helper）
│   │
│   ├── cron/
│   │   └── CronExpression.java     # Cron 表达式解析（借自 Quartz，含未实现方法，勿随意修改）
│   │
│   ├── misfire/                    # 调度过期策略
│   │   ├── MisfireHandler.java     # 过期策略接口
│   │   ├── MisfireStrategyEnum.java
│   │   └── strategy/               # DO_NOTHING / FIRE_ONCE_NOW 实现
│   │
│   ├── openapi/
│   │   └── OpenApiController.java  # OpenAPI 入口（/api/*），供执行器调用
│   │
│   ├── route/                      # 路由策略
│   │   ├── ExecutorRouter.java     # 路由接口（route 方法）
│   │   ├── ExecutorRouteStrategyEnum.java  # 10 种路由枚举，每种持有一个 Router 实例
│   │   └── strategy/               # 10 种路由实现（First/Last/Round/Random/Hash/LFU/LRU/Failover/Busyover/Broadcast）
│   │
│   ├── thread/                     # 调度后台线程
│   │   ├── JobScheduleHelper.java      # scheduleThread（时间轮写入）+ ringThread（触发）
│   │   ├── JobTriggerPoolHelper.java   # fastPool/slowPool 触发线程池
│   │   ├── JobCompleteHelper.java      # 回调线程池 + 执行器失联检测
│   │   ├── JobRegistryHelper.java      # 执行器注册监控（刷新 address_list）
│   │   ├── JobFailAlarmMonitorHelper.java  # 失败重试 + 告警（每 10s 扫描）
│   │   └── JobLogReportHelper.java     # 日志统计报表 + 过期日志清理
│   │
│   ├── trigger/
│   │   ├── JobTrigger.java         # 触发核心：路由寻址 + HTTP POST /run + 日志落库
│   │   └── TriggerTypeEnum.java    # 触发类型（CRON/MANUAL/RETRY/PARENT/API/MISFIRE）
│   │
│   └── type/                       # 调度类型
│       ├── ScheduleType.java       # 调度类型接口（nextTriggerTime 计算）
│       ├── ScheduleTypeEnum.java   # 枚举（NONE/CRON/FIX_RATE，FIX_DELAY 已注释禁用）
│       └── strategy/               # CRON/FIX_RATE/NONE 实现
│
├── util/
│   ├── I18nUtil.java               # 国际化工具（读 i18n/*.properties）
│   ├── JobGroupPermissionUtil.java # 执行器权限过滤（普通用户只能访问分配的执行器）
│   └── old/                        # 废弃工具类（勿引用）
│
└── web/
    ├── error/                      # 全局异常处理和错误页注册
    └── xxlsso/                     # SSO 配置（SimpleLoginStore/XxlSsoConfig）
```

---

## xxl-job-admin 资源目录

```
xxl-job-admin/src/main/resources/
├── application.properties      # 所有配置项（DB/Mail/Token/ThreadPool 等）
├── mapper/                     # MyBatis XML（SQL 定义）
│   ├── XxlJobInfoMapper.xml
│   ├── XxlJobLogMapper.xml
│   ├── XxlJobLogReportMapper.xml
│   ├── XxlJobGroupMapper.xml
│   ├── XxlJobRegistryMapper.xml
│   ├── XxlJobLockMapper.xml    # 分布式锁 SELECT FOR UPDATE
│   ├── XxlJobLogGlueMapper.xml # GLUE 版本管理（含版本上限清理 SQL）
│   └── XxlJobUserMapper.xml
├── static/                     # AdminLTE 前端静态资源（CSS/JS/图片）
├── templates/                  # Freemarker 模板（.ftl 页面）
│   ├── base/                   # 公共头部/导航
│   └── biz/                    # 业务页面（job.list/job.log/index 等）
└── i18n/                       # 国际化资源（message_zh_CN/zh_TC/en.properties）
```
