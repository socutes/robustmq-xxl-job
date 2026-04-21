# API 接口清单

> 本文档由 AI 基于代码生成，第一版可能有遗漏或偏差。发现问题请直接修改，后续改造中 AI 会持续补全。

**本文档讲什么**：XXL-JOB 对外暴露的所有 HTTP 接口，包括调度中心 OpenAPI（供执行器调用）和执行器内嵌 HTTP 服务（供调度中心调用）。

**不讲什么**：Web 管理 UI 的页面接口（`/jobinfo`、`/joblog` 等 Controller）、内部线程间通信。

---

## 一、调度中心 OpenAPI（执行器 → 调度中心）

**Base URL**：`http://{admin-host}:{port}/xxl-job-admin/api/{uri}`

**认证**：所有接口统一通过 HTTP Header `XXL-JOB-ACCESS-TOKEN` 传递 accessToken，值须与 Admin 配置的 `xxl.job.accessToken` 一致。

**协议**：HTTP POST，Body 为 JSON，Content-Type: application/json

**统一响应格式**：
```json
{"code": 200, "msg": null, "content": "..."}
```
- `code=200` 表示成功，其他值表示失败
- 失败时 `msg` 包含错误信息

入口类：[OpenApiController.java](../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/openapi/OpenApiController.java)

---

### 1.1 执行器回调

| 项 | 值 |
|----|----|
| **路径** | `POST /api/callback` |
| **用途** | 执行器任务执行完成后回报结果给调度中心 |
| **Header** | `XXL-JOB-ACCESS-TOKEN: {token}` |
| **请求 Body** | JSON 数组，每个元素为一条回调 |

请求体结构（`List<CallbackRequest>`）：
```json
[
  {
    "logId": 123,
    "logDateTim": 1700000000000,
    "handleCode": 200,
    "handleMsg": "success"
  }
]
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `logId` | long | 调度日志 ID，由调度中心下发 |
| `logDateTim` | long | 触发时间戳（毫秒） |
| `handleCode` | int | 执行结果码：200=成功，500=失败 |
| `handleMsg` | String | 执行结果描述，写入 `xxl_job_log.handle_msg` |

---

### 1.2 执行器注册

| 项 | 值 |
|----|----|
| **路径** | `POST /api/registry` |
| **用途** | 执行器启动或心跳续约时，向调度中心注册自身地址 |
| **Header** | `XXL-JOB-ACCESS-TOKEN: {token}` |

请求体结构（`RegistryRequest`）：
```json
{
  "registryGroup": "EXECUTOR",
  "registryKey": "xxl-job-executor-sample",
  "registryValue": "http://192.168.1.100:9999/"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `registryGroup` | String | 固定值 `"EXECUTOR"` |
| `registryKey` | String | 执行器的 AppName |
| `registryValue` | String | 执行器的访问地址（含端口） |

---

### 1.3 执行器注销

| 项 | 值 |
|----|----|
| **路径** | `POST /api/registryRemove` |
| **用途** | 执行器正常关闭时，主动从调度中心注销 |
| **Header** | `XXL-JOB-ACCESS-TOKEN: {token}` |
| **请求 Body** | 与 `/api/registry` 完全相同 |

---

### 1.4 未实现的 OpenAPI（占位，不可调用）

以下方法在 `AdminBiz` 接口中有注释占位，但目前没有实现：

| 方法 | 说明 |
|------|------|
| `jobAdd` | 创建任务 |
| `jobUpdate` | 更新任务 |
| `jobDelete` | 删除任务 |
| `jobQuery` | 查询任务 |
| `jobStart` | 启动任务 |
| `jobStop` | 停止任务 |
| `jobTrigger` | 手动触发任务 |

---

## 二、执行器内嵌 HTTP 服务（调度中心 → 执行器）

执行器通过 Netty 内嵌 HTTP 服务监听，默认端口由 `xxl.job.executor.port` 配置（示例默认 9999）。

入口类：[EmbedServer.java](../xxl-job-core/src/main/java/com/xxl/job/core/server/EmbedServer.java)、接口定义：[ExecutorBiz.java](../xxl-job-core/src/main/java/com/xxl/job/core/openapi/ExecutorBiz.java)

**协议**：HTTP POST，Body 为 JSON

**认证**：同样校验 `XXL-JOB-ACCESS-TOKEN` Header

---

### 2.1 心跳检测

| 项 | 值 |
|----|----|
| **路径** | `POST /beat` |
| **用途** | 调度中心检测执行器是否存活（FAILOVER 路由策略使用） |
| **请求 Body** | 空 |
| **响应** | `{"code":200}` 表示存活 |

---

### 2.2 空闲心跳

| 项 | 值 |
|----|----|
| **路径** | `POST /idleBeat` |
| **用途** | 检测执行器对应 jobId 的线程是否空闲（BUSYOVER 路由策略使用） |
| **请求 Body** | `{"jobId": 1}` |
| **响应** | `{"code":200}` 表示空闲，`{"code":500}` 表示繁忙 |

---

### 2.3 触发任务

| 项 | 值 |
|----|----|
| **路径** | `POST /run` |
| **用途** | 调度中心下发任务执行指令，执行器立即入队，异步返回 |

请求体结构（`TriggerRequest`）：
```json
{
  "jobId": 1,
  "executorHandler": "demoJobHandler",
  "executorParams": "param1",
  "executorBlockStrategy": "SERIAL_EXECUTION",
  "executorTimeout": 0,
  "logId": 456,
  "logDateTime": 1700000000000,
  "glueType": "BEAN",
  "glueSource": "",
  "glueUpdatetime": 0,
  "broadcastIndex": 0,
  "broadcastTotal": 1
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `jobId` | int | 任务 ID |
| `executorHandler` | String | BEAN 模式下的 Handler 名称（`@XxlJob` 注解值） |
| `executorParams` | String | 任务参数 |
| `executorBlockStrategy` | String | 阻塞策略：`SERIAL_EXECUTION` / `DISCARD_LATER` / `COVER_EARLY` |
| `executorTimeout` | int | 超时秒数，0 表示不限制 |
| `logId` | long | 调度日志 ID |
| `logDateTime` | long | 触发时间戳（毫秒） |
| `glueType` | String | GLUE 类型：`BEAN` / `GLUE_GROOVY` / `GLUE_SHELL` 等 |
| `glueSource` | String | GLUE 模式下的源码内容 |
| `broadcastIndex` | int | 分片广播：当前分片下标（从 0 开始） |
| `broadcastTotal` | int | 分片广播：总分片数 |

**响应**：立即返回 `{"code":200}`，任务异步执行，结果通过回调通知。

---

### 2.4 终止任务

| 项 | 值 |
|----|----|
| **路径** | `POST /kill` |
| **用途** | 调度中心通知执行器终止指定 jobId 的运行中线程 |
| **请求 Body** | `{"jobId": 1}` |

---

### 2.5 查询执行日志

| 项 | 值 |
|----|----|
| **路径** | `POST /log` |
| **用途** | 调度中心拉取执行器本地日志文件内容（分页读取） |

请求体：
```json
{
  "logDateTim": 1700000000000,
  "logId": 456,
  "fromLineNum": 1
}
```

响应体包含日志内容和读取到的行号，支持分页拉取。
