-- xxl_job_log 归档脚本
-- 用途：将超过保留期的过期日志从主表迁移到归档表，替代分区表 DROP PARTITION 方案
-- 执行时机：每月初由 DBA 手动执行，或通过 xxl-job 本身的 CRON 任务调度
-- 前置：xxl_job_log_archive 表已存在（见 tables_xxl_job.sql）
-- 执行环境：MySQL 5.7+ / 8.x，需要 INSERT、DELETE、SELECT 权限

-- ---------------------------------------------------------------
-- 参数说明（执行前按实际情况修改）
-- ---------------------------------------------------------------
-- @retain_days    保留天数，与 xxl.job.logretentiondays 配置保持一致，默认 30
-- @batch_size     每批处理行数，建议 1000，超大表可调低至 500
-- @sleep_ms       批次间等待毫秒数，建议 100，避免打满主库 IO
-- ---------------------------------------------------------------

SET @retain_days = 30;
SET @batch_size  = 1000;
SET @archive_before = DATE_SUB(DATE(NOW()), INTERVAL @retain_days DAY);

SELECT CONCAT('归档截止时间：', @archive_before) AS info;
SELECT COUNT(*) AS pending_rows FROM xxl_job_log WHERE trigger_time < @archive_before;

-- ---------------------------------------------------------------
-- Step 1：分批归档（INSERT INTO archive SELECT + DELETE）
-- ---------------------------------------------------------------
-- 注意：每次执行前先确认 pending_rows 数量，超过 100 万行建议分多天执行

DROP PROCEDURE IF EXISTS archive_xxl_job_log;

DELIMITER $$
CREATE PROCEDURE archive_xxl_job_log()
BEGIN
    DECLARE done       INT DEFAULT 0;
    DECLARE batch_count INT DEFAULT 0;
    DECLARE total      INT DEFAULT 0;

    archive_loop: LOOP
        -- 找本批次需要归档的 id 范围
        INSERT IGNORE INTO xxl_job_log_archive
            (id, job_group, job_id, executor_address, executor_handler,
             executor_param, executor_sharding_param, executor_fail_retry_count,
             trigger_time, trigger_code, trigger_msg, handle_time,
             handle_code, handle_msg, alarm_status)
        SELECT id, job_group, job_id, executor_address, executor_handler,
               executor_param, executor_sharding_param, executor_fail_retry_count,
               trigger_time, trigger_code, trigger_msg, handle_time,
               handle_code, handle_msg, alarm_status
        FROM xxl_job_log
        WHERE trigger_time < @archive_before
        ORDER BY id ASC
        LIMIT 1000;

        SET batch_count = ROW_COUNT();
        SET total = total + batch_count;

        IF batch_count = 0 THEN
            LEAVE archive_loop;
        END IF;

        -- 删除已归档的行（与 INSERT 使用相同条件，幂等安全）
        DELETE FROM xxl_job_log
        WHERE trigger_time < @archive_before
          AND id IN (
              SELECT id FROM xxl_job_log_archive
              WHERE trigger_time < @archive_before
              ORDER BY id ASC
              LIMIT 1000
          );

        SELECT CONCAT('本批归档：', batch_count, ' 行，累计：', total, ' 行') AS progress;

        -- 批次间 sleep，让出主库 IO
        DO SLEEP(0.1);

    END LOOP;

    SELECT CONCAT('归档完成，共处理：', total, ' 行') AS result;
END$$
DELIMITER ;

CALL archive_xxl_job_log();
DROP PROCEDURE IF EXISTS archive_xxl_job_log;

-- ---------------------------------------------------------------
-- Step 2：验证（执行后核对行数）
-- ---------------------------------------------------------------
SELECT COUNT(*) AS remaining_in_main    FROM xxl_job_log          WHERE trigger_time < @archive_before;
SELECT COUNT(*) AS rows_in_archive      FROM xxl_job_log_archive   WHERE trigger_time < @archive_before;
-- remaining_in_main 应为 0，rows_in_archive 应等于归档前的 pending_rows

-- ---------------------------------------------------------------
-- Step 3（可选）：归档表数据确认无误后，清理更老的归档数据
-- 归档表本身也需要定期清理，建议保留 12 个月
-- ---------------------------------------------------------------
-- DELETE FROM xxl_job_log_archive WHERE trigger_time < DATE_SUB(NOW(), INTERVAL 365 DAY) LIMIT 10000;
