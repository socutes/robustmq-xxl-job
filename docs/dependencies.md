# 依赖说明

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：每个关键第三方依赖在本项目里实际承担什么职责，以及升级时需要注意什么。

**不讲什么**：纯传递依赖（没有直接调用点的包）、Maven 插件。

**版本来源**：根 [pom.xml](../pom.xml)（v3.4.1-SNAPSHOT）

---

## 核心运行时依赖

### io.netty : netty-codec-http `4.2.12.Final`

**用在哪**：`xxl-job-core` 模块，`EmbedServer.java`

**做什么**：执行器侧的内嵌 HTTP 服务器。每个执行器进程启动时用 Netty 在配置端口（默认 9999）监听 HTTP 请求，接收调度中心发来的 `/run`、`/beat`、`/kill`、`/log` 等调度指令。不依赖 Tomcat/Jetty，可在无 Spring 环境下运行。

**升级注意**：`EmbedServer.java` 从 xxl-rpc 复制而来，升级 Netty 版本不能只改 pom，必须全量回归执行器的调度接收链路。

---

### com.google.code.gson : gson `2.13.2`

**用在哪**：`xxl-job-core` 模块全局、`xxl-job-admin` 通过 `xxl-tool` 间接使用

**做什么**：调度中心与执行器之间所有 HTTP 请求/响应的 JSON 序列化/反序列化（`TriggerRequest`、`CallbackRequest`、`RegistryRequest` 等模型的编解码）。旧版本用的是自封装的 `GsonTool`（已移至 `deprecated/`），当前通过 `xxl-tool` 的 `GsonTool` 调用。

---

### com.xuxueli : xxl-tool `2.5.0`

**用在哪**：`xxl-job-core` 和 `xxl-job-admin`

**做什么**：
1. `HttpTool.proxy(ExecutorBiz.class, address, token)` —— 生成执行器 HTTP 代理客户端，Admin 通过此接口向执行器发送 `/run`、`/beat` 等请求，无需手写 HTTP 客户端代码
2. `GsonTool` —— JSON 工具封装
3. `StringTool` —— 字符串判空等工具方法
4. `Response<T>` —— 统一响应模型，Admin OpenAPI 和执行器接口均返回此类型

这是项目作者自己的工具库，与 xxl-sso 同源。

---

### org.apache.groovy : groovy `5.0.5`

**用在哪**：`xxl-job-core` 模块，`GlueFactory.java`

**做什么**：GLUE_GROOVY 模式下的运行时编译执行。调度中心将任务源码（Groovy/Java 语法）存储在数据库，触发时随 `TriggerRequest.glueSource` 下发给执行器，执行器用 Groovy 编译器将源码编译为 `IJobHandler` 并执行，无需重新部署。

**注意**：Groovy 5.x 要求 JDK 17+，与项目编译目标一致。

---

### org.springframework : spring-context `7.0.6`（provided）

**用在哪**：`xxl-job-core`，scope 为 provided

**做什么**：`XxlJobSpringExecutor` 扫描 Spring 容器中所有带 `@XxlJob` 注解的 Bean 方法，自动注册为 `MethodJobHandler`。frameless 场景下此依赖不存在，core 不强制 Spring 依赖。

---

## Admin 专属依赖

### org.springframework.boot : spring-boot-starter-web `4.0.5`

**用在哪**：`xxl-job-admin`

**做什么**：提供 Spring MVC + 内嵌 Tomcat，承载管理台 Web UI 和 OpenAPI 端点。Admin 本身是标准 Spring Boot 应用，Port 默认 8080，Context-Path `/xxl-job-admin`。

---

### org.springframework.boot : spring-boot-starter-freemarker `4.0.5`

**用在哪**：`xxl-job-admin`

**做什么**：管理台 HTML 页面的服务端渲染（任务列表、日志查看、执行器管理等）。模板文件在 `resources/templates/` 目录，后缀 `.ftl`。

---

### org.springframework.boot : spring-boot-starter-mail `4.0.5`

**用在哪**：`xxl-job-admin`，`EmailJobAlarm.java`

**做什么**：唯一内置的告警渠道。任务执行失败且配置了告警邮件时，通过 SMTP 发送邮件。配置项：`spring.mail.*`。生产环境需配置真实 SMTP 服务器。

---

### org.mybatis.spring.boot : mybatis-spring-boot-starter `4.0.1`

**用在哪**：`xxl-job-admin`

**做什么**：6 个 Mapper 接口的持久层框架（含 HikariCP 连接池）。6 个 Mapper 分别对应 `xxl_job_info`、`xxl_job_log`、`xxl_job_log_report`、`xxl_job_group`、`xxl_job_registry`、`xxl_job_lock`（调度锁）。SQL 在 `resources/mapper/` 目录的 XML 文件中。

---

### com.mysql : mysql-connector-j `9.6.0`

**用在哪**：`xxl-job-admin`

**做什么**：MySQL JDBC 驱动，连接 `xxl_job` 数据库。

---

### com.xuxueli : xxl-sso-core `2.4.0`

**用在哪**：`xxl-job-admin`，`XxlSsoConfig.java`

**做什么**：管理台 Web 页面的登录鉴权。`@XxlSso` 注解拦截 Controller 方法，未登录则重定向到登录页。OpenAPI 端点标注 `@XxlSso(login=false)` 跳过 SSO，改用 accessToken 校验。Token 存储在数据库 `xxl_job_user.token` 字段。

---

## AI 示例模块依赖（仅 executor-sample-springboot-ai）

### org.springframework.ai : spring-ai-starter-model-ollama / spring-ai-starter-model-openai `2.0.0-M4`

**用在哪**：`xxl-job-executor-sample-springboot-ai`

**做什么**：在示例执行器中集成 Ollama 本地模型和 OpenAI 云端模型，任务 Handler 可调用 AI 接口生成内容。当前为 Milestone 版本（M4），非 GA。

---

### io.github.imfangs : dify-java-client `1.2.5`

**用在哪**：`xxl-job-executor-sample-springboot-ai`

**做什么**：调用 Dify 工作流 API，示例中 `difyWorkflowJobHandler` 通过此客户端触发 Dify 流程。
