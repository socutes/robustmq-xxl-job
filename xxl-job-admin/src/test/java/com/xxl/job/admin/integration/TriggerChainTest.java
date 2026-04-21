package com.xxl.job.admin.integration;

import com.xxl.job.admin.mapper.XxlJobInfoMapper;
import com.xxl.job.admin.mapper.XxlJobLogMapper;
import com.xxl.job.admin.model.XxlJobInfo;
import com.xxl.job.admin.model.XxlJobLog;
import com.xxl.job.admin.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.admin.scheduler.misfire.MisfireStrategyEnum;
import com.xxl.job.admin.scheduler.route.ExecutorRouteStrategyEnum;
import com.xxl.job.admin.scheduler.trigger.TriggerTypeEnum;
import com.xxl.job.admin.scheduler.type.ScheduleTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L-1 任务触发链路集成测试
 * 链路：JobTriggerPoolHelper.trigger() → JobTrigger.processTrigger() → INSERT xxl_job_log
 * 核心断言：trigger() 调用后 xxl_job_log 中出现新记录（trigger_code 已写入）
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TriggerChainTest {

    @Resource
    private XxlJobInfoMapper xxlJobInfoMapper;
    @Resource
    private XxlJobLogMapper xxlJobLogMapper;

    private int savedJobId = -1;

    @AfterEach
    void cleanup() {
        if (savedJobId > 0) {
            xxlJobLogMapper.delete(savedJobId);
            xxlJobInfoMapper.delete(savedJobId);
        }
    }

    @Test
    void trigger_shouldWriteLogRecord() throws InterruptedException {
        // 准备：插一条 FIX_RATE 任务，执行器地址填 mock 地址（/run 会失败，但 log 仍会写入）
        XxlJobInfo job = buildMinimalJobInfo();
        xxlJobInfoMapper.save(job);
        savedJobId = job.getId();
        assertTrue(savedJobId > 0, "前置：任务保存失败，无法进入触发流程");

        // 执行：手动触发（跳过 scheduleThread，直接进 TriggerPool）
        XxlJobAdminBootstrap.getInstance().getJobTriggerPoolHelper()
                .trigger(savedJobId, TriggerTypeEnum.MANUAL, 0, null, null, null);

        // 等待 TriggerPool 异步完成（processTrigger 含 HTTP 调用，最多等 3s）
        TimeUnit.SECONDS.sleep(3);

        // 断言：xxl_job_log 中存在该任务的触发记录
        List<XxlJobLog> logs = xxlJobLogMapper.pageList(0, 10, 0, savedJobId, null, null, -1);
        assertFalse(logs.isEmpty(),
                "链路断裂点：TriggerPool 未调用 processTrigger，或 processTrigger 未 INSERT xxl_job_log");

        XxlJobLog log = logs.get(0);
        assertEquals(savedJobId, log.getJobId(),
                "链路断裂点：log 记录的 jobId 与触发的 jobId 不符");
        assertTrue(log.getTriggerCode() > 0,
                "链路断裂点：trigger_code=0 说明 processTrigger 的 UPDATE 步骤未完成");
    }

    private XxlJobInfo buildMinimalJobInfo() {
        XxlJobInfo info = new XxlJobInfo();
        info.setJobGroup(1);
        info.setJobDesc("[集成测试] TriggerChainTest");
        info.setAuthor("integration-test");
        info.setScheduleType(ScheduleTypeEnum.FIX_RATE.name());
        info.setScheduleConf("60");
        info.setMisfireStrategy(MisfireStrategyEnum.DO_NOTHING.name());
        info.setExecutorRouteStrategy(ExecutorRouteStrategyEnum.FIRST.name());
        // mock 地址：/run 会失败，但触发 log 仍写入（trigger_code=500 也算链路跑通了）
        info.setExecutorHandler("demoJobHandler");
        info.setExecutorBlockStrategy("SERIAL_EXECUTION");
        info.setGlueType("BEAN");
        info.setGlueRemark("");
        info.setAddTime(new Date());
        info.setUpdateTime(new Date());
        info.setGlueUpdatetime(new Date());
        return info;
    }
}
