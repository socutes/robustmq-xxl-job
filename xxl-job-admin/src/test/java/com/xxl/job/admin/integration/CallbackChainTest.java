package com.xxl.job.admin.integration;

import com.xxl.job.admin.mapper.XxlJobLogMapper;
import com.xxl.job.admin.model.XxlJobLog;
import com.xxl.job.admin.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.core.context.XxlJobContext;
import com.xxl.job.core.openapi.model.CallbackRequest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L-2 回调链路集成测试
 * 链路：JobCompleteHelper.callback() → doCallback() → JobCompleter.complete() → UPDATE xxl_job_log
 * 核心断言：callback() 调用后 xxl_job_log.handle_code 从 0 更新为实际值
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class CallbackChainTest {

    @Resource
    private XxlJobLogMapper xxlJobLogMapper;

    private long testLogId = -1;

    @BeforeEach
    void insertPendingLog() {
        // 准备：插一条 handle_code=0（进行中）的日志，模拟已触发未完成的任务
        XxlJobLog log = new XxlJobLog();
        log.setJobGroup(1);
        log.setJobId(1);
        // handle_code 默认 0，不设置即为"进行中"
        xxlJobLogMapper.save(log);
        testLogId = log.getId();
        assertTrue(testLogId > 0, "前置：测试 log 记录创建失败，无法验证回调链路");
    }

    @AfterEach
    void cleanup() {
        if (testLogId > 0) {
            xxlJobLogMapper.delete((int) testLogId); // delete by jobId=1
        }
    }

    @Test
    void callback_success_shouldUpdateHandleCode() throws InterruptedException {
        // 构造成功回调请求
        CallbackRequest req = new CallbackRequest();
        req.setLogId(testLogId);
        req.setHandleCode(XxlJobContext.HANDLE_CODE_SUCCESS);
        req.setHandleMsg("集成测试回调");

        // 执行：调用 callback（内部异步提交到 callbackThreadPool）
        XxlJobAdminBootstrap.getInstance().getJobCompleteHelper()
                .callback(List.of(req));

        // 等待 callbackThreadPool 异步处理完成
        TimeUnit.SECONDS.sleep(2);

        // 断言：handle_code 已从 0 更新为 200
        XxlJobLog updated = xxlJobLogMapper.load(testLogId);
        assertNotNull(updated, "链路断裂点：log 记录消失，doCallback 可能执行了非预期的删除");
        assertEquals(XxlJobContext.HANDLE_CODE_SUCCESS, updated.getHandleCode(),
                "链路断裂点：handle_code 未更新，doCallback→JobCompleter.complete()→updateHandleInfo 链路断裂");
        assertNotNull(updated.getHandleTime(),
                "链路断裂点：handle_time 未写入，updateHandleInfo 的字段映射可能有问题");
    }

    @Test
    void callback_fail_shouldUpdateHandleCodeToFail() throws InterruptedException {
        CallbackRequest req = new CallbackRequest();
        req.setLogId(testLogId);
        req.setHandleCode(XxlJobContext.HANDLE_CODE_FAIL);
        req.setHandleMsg("集成测试-执行失败");

        XxlJobAdminBootstrap.getInstance().getJobCompleteHelper()
                .callback(List.of(req));

        TimeUnit.SECONDS.sleep(2);

        XxlJobLog updated = xxlJobLogMapper.load(testLogId);
        assertEquals(XxlJobContext.HANDLE_CODE_FAIL, updated.getHandleCode(),
                "链路断裂点：失败回调后 handle_code 未更新为 500");
    }
}
