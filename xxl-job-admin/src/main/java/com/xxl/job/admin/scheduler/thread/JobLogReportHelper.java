package com.xxl.job.admin.scheduler.thread;

import com.xxl.job.admin.scheduler.config.XxlJobAdminBootstrap;
import com.xxl.job.admin.model.XxlJobLogReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * job log report helper
 *
 * @author xuxueli 2019-11-22
 */
public class JobLogReportHelper {
    private static final Logger logger = LoggerFactory.getLogger(JobLogReportHelper.class);


    private Thread logReportThread;
    private volatile boolean toStop = false;
    public final AtomicBoolean cleaning = new AtomicBoolean(false);

    /**
     * start
     */
    public void start(){
        logReportThread = new Thread(new Runnable() {

            @Override
            public void run() {

                // last clean log time — init to now to avoid immediate cleanup on startup (H-1)
                long lastCleanLogTime = System.currentTimeMillis();


                while (!toStop) {

                    // 1、log-report refresh: refresh log report in 3 days
                    try {

                        for (int i = 0; i < 3; i++) {

                            // today
                            Calendar itemDay = Calendar.getInstance();
                            itemDay.add(Calendar.DAY_OF_MONTH, -i);
                            itemDay.set(Calendar.HOUR_OF_DAY, 0);
                            itemDay.set(Calendar.MINUTE, 0);
                            itemDay.set(Calendar.SECOND, 0);
                            itemDay.set(Calendar.MILLISECOND, 0);

                            Date todayFrom = itemDay.getTime();

                            itemDay.set(Calendar.HOUR_OF_DAY, 23);
                            itemDay.set(Calendar.MINUTE, 59);
                            itemDay.set(Calendar.SECOND, 59);
                            itemDay.set(Calendar.MILLISECOND, 999);

                            Date todayTo = itemDay.getTime();

                            // refresh log-report every minute
                            XxlJobLogReport xxlJobLogReport = new XxlJobLogReport();
                            xxlJobLogReport.setTriggerDay(todayFrom);
                            xxlJobLogReport.setRunningCount(0);
                            xxlJobLogReport.setSucCount(0);
                            xxlJobLogReport.setFailCount(0);
                            xxlJobLogReport.setUpdateTime(new Date());

                            // fill count-data
                            Map<String, Object> triggerCountMap = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().findLogReport(todayFrom, todayTo);
                            if (triggerCountMap!=null && !triggerCountMap.isEmpty()) {
                                int triggerDayCount = triggerCountMap.containsKey("triggerDayCount")?Integer.parseInt(String.valueOf(triggerCountMap.get("triggerDayCount"))):0;
                                int triggerDayCountRunning = triggerCountMap.containsKey("triggerDayCountRunning")?Integer.parseInt(String.valueOf(triggerCountMap.get("triggerDayCountRunning"))):0;
                                int triggerDayCountSuc = triggerCountMap.containsKey("triggerDayCountSuc")?Integer.parseInt(String.valueOf(triggerCountMap.get("triggerDayCountSuc"))):0;
                                int triggerDayCountFail = triggerDayCount - triggerDayCountRunning - triggerDayCountSuc;

                                xxlJobLogReport.setRunningCount(triggerDayCountRunning);
                                xxlJobLogReport.setSucCount(triggerDayCountSuc);
                                xxlJobLogReport.setFailCount(triggerDayCountFail);
                            }

                            // do refresh:
                            XxlJobAdminBootstrap.getInstance().getXxlJobLogReportMapper().saveOrUpdate(xxlJobLogReport);      // 0-fail; 1-save suc; 2-update suc;
                            /*if (ret < 1) {
                                XxlJobAdminBootstrap.getInstance().getXxlJobLogReportMapper().save(xxlJobLogReport);
                            }*/
                        }

                    } catch (Throwable e) {
                        if (!toStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobLogReportHelper(log-report refresh) error:{}", e.getMessage(), e);
                        }
                    }

                    // 2、log-clean: switch open & once each day
                    try {
                        if (XxlJobAdminBootstrap.getInstance().getLogretentiondays()>0
                                && System.currentTimeMillis() - lastCleanLogTime > 24*60*60*1000) {

                            // expire-time
                            Calendar expiredDay = Calendar.getInstance();
                            expiredDay.add(Calendar.DAY_OF_MONTH, -1 * XxlJobAdminBootstrap.getInstance().getLogretentiondays());
                            expiredDay.set(Calendar.HOUR_OF_DAY, 0);
                            expiredDay.set(Calendar.MINUTE, 0);
                            expiredDay.set(Calendar.SECOND, 0);
                            expiredDay.set(Calendar.MILLISECOND, 0);
                            Date clearBeforeTime = expiredDay.getTime();

                            // clean expired log — skip if manual clean is running concurrently (H-5)
                            if (!cleaning.compareAndSet(false, true)) {
                                logger.info(">>>>>>>>>>> xxl-job, log-clean skipped: manual clean in progress");
                            } else {
                                try {
                                    boolean dropDone = tryDropExpiredPartitions(clearBeforeTime);
                                    if (!dropDone) {
                                        // fallback: batch DELETE (non-partitioned table or DROP PARTITION failed)
                                        List<Long> logIds = null;
                                        do {
                                            logIds = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().findClearLogIds(0, 0, clearBeforeTime, 0, 1000);
                                            if (logIds!=null && !logIds.isEmpty()) {
                                                int deleted = XxlJobAdminBootstrap.getInstance().getXxlJobLogMapper().clearLog(logIds);
                                                logger.info(">>>>>>>>>>> xxl-job, log-clean batch deleted:{} rows", deleted);
                                                TimeUnit.MILLISECONDS.sleep(100);
                                            }
                                        } while (logIds!=null && !logIds.isEmpty() && !toStop);
                                    }
                                } finally {
                                    cleaning.set(false);
                                }
                                // update clean time only when cleanup actually ran (H-5)
                                lastCleanLogTime = System.currentTimeMillis();
                            }
                        }
                    } catch (Throwable e) {
                        if (!toStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobLogReportHelper(log-clean) error:{}", e.getMessage(), e);
                        }
                    }

                    try {
                        TimeUnit.MINUTES.sleep(1);
                    } catch (Throwable e) {
                        if (!toStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                }

                logger.info(">>>>>>>>>>> xxl-job, job log report thread stop");

            }
        });
        logReportThread.setDaemon(true);
        logReportThread.setName("xxl-job, admin JobLogReportHelper");
        logReportThread.start();
    }

    /**
     * Drop all expired partitions whose upper bound <= clearBeforeTime.
     * Returns true if the table is partitioned and at least one partition was processed
     * (dropped or confirmed already absent). Returns false if the table is not partitioned
     * or any DDL error occurs (caller should fall back to batch DELETE).
     *
     * Partition naming convention: p_YYYY_MM (e.g. p_2026_04).
     * Only partitions with LESS THAN boundary <= clearBeforeTime are dropped;
     * p_future (MAXVALUE) is never touched.
     */
    private boolean tryDropExpiredPartitions(Date clearBeforeTime) {
        Connection conn = null;
        try {
            conn = XxlJobAdminBootstrap.getInstance().getDataSource().getConnection();

            // 1. detect whether xxl_job_log is a partitioned table
            List<String> expiredPartitions = new ArrayList<>();
            String detectSql =
                "SELECT partition_name, partition_description " +
                "FROM information_schema.partitions " +
                "WHERE table_schema = DATABASE() " +
                "  AND table_name = 'xxl_job_log' " +
                "  AND partition_name != 'p_future' " +
                "  AND partition_name IS NOT NULL " +
                "ORDER BY partition_ordinal_position";

            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(detectSql)) {

                if (!rs.isBeforeFirst()) {
                    // no named partitions → non-partitioned table, skip
                    return false;
                }

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                while (rs.next()) {
                    String pName = rs.getString("partition_name");
                    String lessThanStr = rs.getString("partition_description");
                    // partition_description for RANGE COLUMNS(datetime) is quoted: 'YYYY-MM-DD HH:MM:SS'
                    lessThanStr = lessThanStr.replace("'", "").trim();
                    try {
                        Date lessThan = sdf.parse(lessThanStr);
                        // drop if partition upper bound is entirely before clearBeforeTime
                        if (!lessThan.after(clearBeforeTime)) {
                            expiredPartitions.add(pName);
                        }
                    } catch (Exception ignored) {
                        // unparseable boundary (e.g. MAXVALUE already filtered out above)
                    }
                }
            }

            if (expiredPartitions.isEmpty()) {
                logger.info(">>>>>>>>>>> xxl-job, log-clean no expired partitions to drop (clearBeforeTime:{})", clearBeforeTime);
                return true;
            }

            // 2. DROP each expired partition individually so a single failure doesn't block others
            for (String pName : expiredPartitions) {
                String dropSql = "ALTER TABLE xxl_job_log DROP PARTITION " + pName;
                try (Statement st = conn.createStatement()) {
                    st.execute(dropSql);
                    logger.info(">>>>>>>>>>> xxl-job, log-clean dropped partition:{}", pName);
                } catch (Exception e) {
                    // partition may have been dropped by a concurrent operation; log and continue
                    logger.warn(">>>>>>>>>>> xxl-job, log-clean drop partition:{} failed, reason:{}", pName, e.getMessage());
                }
            }
            return true;

        } catch (Exception e) {
            logger.warn(">>>>>>>>>>> xxl-job, log-clean tryDropExpiredPartitions failed, will fallback to batch DELETE. reason:{}", e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * stop
     */
    public void stop(){
        toStop = true;
        // interrupt and wait
        logReportThread.interrupt();
        try {
            logReportThread.join();
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }
    }

}
