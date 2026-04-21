# SKILL: 实现 AdminBiz 7 个占位方法（R-4）

## 问题描述

**债务编号**：R-4（红区）

`AdminBiz` 接口中有 7 个方法仅有注释占位，无实际定义和实现：

```java
// AdminBiz.java 当前状态（L46-59）
// jobAdd
// jobUpdate
// jobDelete
// jobQuery
// jobStart
// jobStop
// jobTrigger
```

调用方（执行器侧或集成方）若依赖 `AdminBiz` 接口做任务管理，会发现方法根本不存在。

**在改动前，必须先问维护者**：
> 这 7 个方法是否有外部调用方在等待？是否已有 issue 跟踪实现计划？
> 参见 CLAUDE.md 协作规则第 9 条。

---

## 前置确认（改动前必须完成）

- [ ] 确认 `AdminBiz` 接口是否被外部 jar 依赖（`deprecated/AdminBizClient` 是否有存量用户）
- [ ] 确认实现计划的优先级：全部实现 / 按需实现 / 仅实现指定几个
- [ ] 确认认证机制：7 个方法是否需要 accessToken 校验（与现有 3 个方法一致）
- [ ] 确认是否要在 `OpenApiController` 中暴露对应端点（管理台 `/api/*` 路径）

---

## 现有实现的参考链

**已实现的 3 个方法**（参考实现）：

| 方法 | Admin 实现 | OpenAPI 端点 |
|------|-----------|-------------|
| `callback` | [AdminBizImpl.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/service/impl/AdminBizImpl.java) | `POST /api/callback` |
| `registry` | [AdminBizImpl.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/service/impl/AdminBizImpl.java) | `POST /api/registry` |
| `registryRemove` | [AdminBizImpl.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/service/impl/AdminBizImpl.java) | `POST /api/registryRemove` |

**7 个方法的业务逻辑已在 Service 层存在**：

| AdminBiz 方法 | 对应 XxlJobService 方法 | 位置 |
|--------------|----------------------|------|
| `jobAdd` | `add(XxlJobInfo, LoginInfo)` | [XxlJobService.java:26](../../xxl-job-admin/src/main/java/com/xxl/job/admin/service/XxlJobService.java#L26) |
| `jobUpdate` | `update(XxlJobInfo, LoginInfo)` | L31 |
| `jobDelete` | `remove(int id, LoginInfo)` | L36 |
| `jobQuery` | `pageList(...)` | L21 |
| `jobStart` | `start(int id, LoginInfo)` | L41 |
| `jobStop` | `stop(int id, LoginInfo)` | L46 |
| `jobTrigger` | `trigger(LoginInfo, int, String, String)` | L51 |

**实现策略**：`AdminBizImpl` 调用 `XxlJobService`，与现有 3 个方法调用 Mapper 的模式略有不同，
但核心业务逻辑可以直接复用 `XxlJobServiceImpl`。

---

## 实现步骤（确认可以动之后）

### 第一步：在 AdminBiz 接口中添加方法定义

[AdminBiz.java](../../xxl-job-core/src/main/java/com/xxl/job/core/openapi/AdminBiz.java) 是 core 模块接口，
需要为每个方法定义请求/响应模型（放在 `openapi/model/` 下）。

**注意**：`AdminBiz` 在 `xxl-job-core` 模块，修改后需要重新 install core jar，执行器侧才能感知接口变化。

示例（以 `jobTrigger` 为例）：

```java
// AdminBiz.java 新增
/**
 * trigger job
 *
 * @param triggerRequest
 * @return
 */
public Response<String> jobTrigger(AdminTriggerRequest triggerRequest);
```

```java
// model/AdminTriggerRequest.java（新建）
public class AdminTriggerRequest {
    private int jobId;
    private String executorParam;
    private String addressList;
    // getter/setter
}
```

### 第二步：在 AdminBizImpl 中实现

[AdminBizImpl.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/service/impl/AdminBizImpl.java)
注入 `XxlJobService`，转发调用：

```java
@Resource
private XxlJobService xxlJobService;

@Override
public Response<String> jobTrigger(AdminTriggerRequest request) {
    // accessToken 已在 OpenApiController 层校验，此处不重复校验
    return xxlJobService.trigger(null, request.getJobId(), request.getExecutorParam(), request.getAddressList());
}
```

**LoginInfo 问题**：`XxlJobService.add/update/remove/start/stop` 需要 `LoginInfo` 做权限检查。
OpenAPI 调用方没有登录态，需要决策：
- 方案 A：OpenAPI 调用时传 `null`，`XxlJobServiceImpl` 对 null LoginInfo 跳过权限校验
- 方案 B：为 OpenAPI 创建一个系统级 LoginInfo（角色=管理员）
- 方案 C：`XxlJobService` 重载，新增不需要 LoginInfo 的方法签名

**选方案前必须问维护者**，这涉及权限模型。

### 第三步：在 OpenApiController 中暴露端点

[OpenApiController.java](../../xxl-job-admin/src/main/java/com/xxl/job/admin/scheduler/openapi/OpenApiController.java)
参考现有 3 个方法的格式：

```java
@RequestMapping("/jobTrigger")
@ResponseBody
public Response<String> jobTrigger(HttpServletRequest request, @RequestBody(required = false) String data) {
    // 1、校验 accessToken
    // 2、解析 data → AdminTriggerRequest
    // 3、调用 adminBiz.jobTrigger()
}
```

### 第四步：为执行器侧的 AdminBiz 代理实现添加方法

执行器通过 `xxl-tool HttpTool.proxy()` 生成 `AdminBiz` 的 HTTP 代理实例。
确认 core 模块的代理工厂是否需要为新方法生成代理逻辑（取决于 HttpTool 的代理机制是否自动适配新方法）。

---

## 改动范围汇总

| 文件 | 改动类型 | 模块 |
|------|---------|------|
| `AdminBiz.java` | 添加 7 个方法签名 | `xxl-job-core`（需 reinstall） |
| `openapi/model/` 下新建请求模型 | 新建 | `xxl-job-core` |
| `AdminBizImpl.java` | 实现 7 个方法 | `xxl-job-admin` |
| `OpenApiController.java` | 添加 7 个端点 | `xxl-job-admin` |

这是**跨模块改动**，`xxl-job-core` 的变更会影响所有依赖 core jar 的执行器。
按 CLAUDE.md 协作规则第 3 条，改前给出完整改动清单等确认。

---

## 验证方法

1. 用 curl 或 Postman 直接调用新端点，带正确 accessToken
2. 验证 `jobAdd` 后数据库有新任务记录
3. 验证 `jobTrigger` 触发后 `xxl_job_log` 有执行记录
4. 验证错误场景：错误 accessToken 返回 401，jobId 不存在返回具体错误信息
