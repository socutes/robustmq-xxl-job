# 本地构建与运行

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：本地开发环境下如何把项目跑起来。

**不讲什么**：生产环境部署（见 [deployment.md](deployment.md)）、Docker 部署（`docker/` 目录有 Compose 文件，待补充）。

---

## 前置要求

| 依赖 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 根 pom 编译目标 Java 17 |
| Maven | 3.8+ | 构建工具 |
| MySQL | 8.x 推荐 | Admin 的唯一外部依赖 |

执行器（Executor）无需任何中间件，只要能访问到调度中心即可。

---

## 第一步：初始化数据库

```sql
-- 1. 执行 DDL（建库 + 建表 + 初始数据）
mysql -u root -p < doc/db/tables_xxl_job.sql
```

DDL 文件路径：[doc/db/tables_xxl_job.sql](../doc/db/tables_xxl_job.sql)

执行后创建：
- 数据库 `xxl_job`
- 8 张表（含初始执行器分组、示例任务、admin 账号、调度锁）
- 默认 admin 账号：`admin` / `123456`

---

## 第二步：配置调度中心（Admin）

修改配置文件：`xxl-job-admin/src/main/resources/application.properties`

必改项：

```properties
# 数据库连接
spring.datasource.url=jdbc:mysql://127.0.0.1:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai
spring.datasource.username=root
spring.datasource.password=your_password

# 访问令牌（Admin 与执行器通信的认证凭证，生产必改）
xxl.job.accessToken=default_token
```

选改项：

```properties
# 邮件告警（不用邮件可不配）
spring.mail.host=smtp.qq.com
spring.mail.port=25
spring.mail.username=xxx@qq.com
spring.mail.password=xxx

# 日志保留天数（默认 30 天）
xxl.job.logretentiondays=30

# 触发线程池上限（默认 fast=300, slow=200）
xxl.job.triggerpool.fast.max=300
xxl.job.triggerpool.slow.max=200

# 调度批次大小（默认 100）
xxl.job.schedule.batchsize=100
```

---

## 第三步：启动调度中心

```bash
# 方式一：Maven 直接运行
cd xxl-job-admin
mvn spring-boot:run

# 方式二：打包后运行
mvn -pl xxl-job-admin package -DskipTests
java -jar xxl-job-admin/target/xxl-job-admin-3.4.1-SNAPSHOT.jar
```

启动成功后访问：`http://localhost:8080/xxl-job-admin`（默认账号 admin / 123456）

---

## 第四步：配置执行器（以 springboot 示例为例）

修改：`xxl-job-executor-samples/xxl-job-executor-sample-springboot/src/main/resources/application.properties`

```properties
# 调度中心地址（多个逗号分隔）
xxl.job.admin.addresses=http://127.0.0.1:8080/xxl-job-admin

# 访问令牌（必须与 Admin 配置一致）
xxl.job.admin.accessToken=default_token

# 执行器配置
xxl.job.executor.appname=xxl-job-executor-sample   # 必须与管理台执行器分组 AppName 一致
xxl.job.executor.address=                          # 留空则自动获取本机 IP
xxl.job.executor.ip=                               # 留空则自动获取
xxl.job.executor.port=9999                         # 执行器监听端口
xxl.job.executor.logpath=/data/applogs/xxl-job/jobhandler
xxl.job.executor.logretentiondays=30
```

---

## 第五步：启动执行器

```bash
cd xxl-job-executor-samples/xxl-job-executor-sample-springboot
mvn spring-boot:run
```

执行器启动后会自动向调度中心注册，注册成功后在管理台「执行器管理」页面可见在线地址。

---

## 全量构建（仅构建不运行）

```bash
# 构建所有模块（跳过测试）
mvn clean package -DskipTests

# 仅构建 core（发布到本地 Maven 仓库，供执行器引入）
mvn -pl xxl-job-core install -DskipTests
```

---

## 验证运行是否正常

1. 访问管理台首页，Dashboard 数据正常显示
2. 进入「执行器管理」，确认执行器实例出现在 OnlineAddress 列
3. 进入「任务管理」，找到「示例任务01」，点击「执行一次」
4. 进入「调度日志」查看该任务的执行记录，`handle_code=200` 表示成功

---

## 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| Admin 启动失败，报数据库连接错误 | 数据库未启动或配置错误 | 检查 `spring.datasource.*` 配置 |
| 执行器注册不上 | accessToken 不一致，或 Admin 地址不通 | 核对两侧 accessToken，确认网络连通 |
| 任务触发后一直「进行中」 | 执行器无法回调 Admin | 检查执行器日志，确认 `/api/callback` 能访问到 Admin |
| 端口冲突 | 9999 或 8080 被占用 | 修改 `xxl.job.executor.port` 或 `server.port` |
