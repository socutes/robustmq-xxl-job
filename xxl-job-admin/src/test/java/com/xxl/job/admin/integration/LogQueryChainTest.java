package com.xxl.job.admin.integration;

import com.xxl.job.admin.mapper.XxlJobLogMapper;
import com.xxl.job.admin.model.XxlJobLog;
import com.xxl.job.core.context.XxlJobContext;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务日志查询链路集成测试
 * 链路：XxlJobLogMapper.pageList() 按 jobId + 时间范围过滤
 * 核心断言：API 能按 jobId 和时间范围查到正确的日志记录
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class LogQueryChainTest {

    @Resource
    private XxlJobLogMapper xxlJobLogMapper;

    private static final int TEST_JOB_ID = 99999; // 不会与生产数据冲突的测试 jobId
    private long insertedLogId = -1;

    @BeforeEach
    void insertTestLog() {
        XxlJobLog log = new XxlJobLog();
        log.setJobGroup(1);
        log.setJobId(TEST_JOB_ID);
        log.setTriggerTime(new Date());
        log.setTriggerCode(XxlJobContext.HANDLE_CODE_SUCCESS);
        log.setTriggerMsg("集成测试-触发记录");
        log.setHandleCode(XxlJobContext.HANDLE_CODE_SUCCESS);
        log.setHandleTime(new Date());
        xxlJobLogMapper.save(log);
        insertedLogId = log.getId();
        assertTrue(insertedLogId > 0, "前置：测试日志写入失败");
    }

    @AfterEach
    void cleanup() {
        xxlJobLogMapper.delete(TEST_JOB_ID);
    }

    @Test
    void queryByJobId_shouldReturnInsertedLog() {
        // 按 jobId 精确查询，不限时间范围
        List<XxlJobLog> logs = xxlJobLogMapper.pageList(0, 20, 0, TEST_JOB_ID, null, null, -1);
        int count = xxlJobLogMapper.pageListCount(0, 20, 0, TEST_JOB_ID, null, null, -1);

        assertFalse(logs.isEmpty(),
                "链路断裂点：pageList 按 jobId=" + TEST_JOB_ID + " 查询无结果，SQL 条件或参数绑定有问题");
        assertEquals(count, logs.size(),
                "链路断裂点：pageList 与 pageListCount 返回数量不一致，两个 SQL 的 WHERE 条件不同步");

        // 确认查到的就是刚写入的那条
        boolean found = logs.stream().anyMatch(l -> l.getId() == insertedLogId);
        assertTrue(found,
                "链路断裂点：查询结果不含刚插入的 logId=" + insertedLogId + "，可能存在缓存或事务隔离问题");
    }

    @Test
    void queryByTimeRange_pastHour_shouldReturnLog() {
        // 时间范围覆盖"1小时前到1分钟后"，应能查到刚插入的记录
        long now = System.currentTimeMillis();
        Date from = new Date(now - 3600_000L);
        Date to   = new Date(now + 60_000L);

        List<XxlJobLog> logs = xxlJobLogMapper.pageList(0, 20, 0, TEST_JOB_ID, from, to, -1);

        assertFalse(logs.isEmpty(),
                "链路断裂点：时间范围查询无结果，pageList 的 triggerTimeStart/End 参数绑定或 SQL BETWEEN 子句有问题");
    }

    @Test
    void queryByTimeRange_future_shouldReturnEmpty() {
        // 时间范围设在未来，应查不到任何记录
        long now = System.currentTimeMillis();
        Date from = new Date(now + 3600_000L);
        Date to   = new Date(now + 7200_000L);

        List<XxlJobLog> logs = xxlJobLogMapper.pageList(0, 20, 0, TEST_JOB_ID, from, to, -1);

        assertTrue(logs.isEmpty(),
                "链路断裂点：时间范围过滤失效，未来时间范围仍查出了记录，SQL 的时间过滤条件未生效");
    }
}
