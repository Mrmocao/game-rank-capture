-- 业务确认：自然周只保留一份报告，重新生成覆盖；执行前须备份数据库。
-- 以实际分析截止日（period_end - 1）归周，避免旧滚动窗口跨周归错。
ALTER TABLE weekly_report
 ADD COLUMN week_start DATE NULL COMMENT '北京时间自然周周一；周报唯一分析键';
UPDATE weekly_report SET week_start=DATE_SUB(DATE_SUB(period_end,INTERVAL 1 DAY),INTERVAL WEEKDAY(DATE_SUB(period_end,INTERVAL 1 DAY)) DAY);

CREATE TABLE weekly_report_alias (
 old_report_id BIGINT PRIMARY KEY COMMENT '合并前的报告ID，仅兼容旧链接，不保存旧报告内容',
 report_id BIGINT NOT NULL COMMENT '该自然周唯一报告ID',
 FOREIGN KEY(report_id) REFERENCES weekly_report(id)
) COMMENT='周报归并链接映射；不属于报告历史版本';

-- 只排序标量，避免把大JSON放入MySQL排序缓冲。保留最近生成的一份完整结果。
INSERT INTO weekly_report_alias(old_report_id,report_id)
SELECT id,retained_id FROM (
 SELECT id,FIRST_VALUE(id) OVER(PARTITION BY week_start ORDER BY generated_at DESC,id DESC) retained_id
 FROM weekly_report
) ranked WHERE id<>retained_id;
DELETE i FROM report_item i JOIN weekly_report_alias a ON a.old_report_id=i.report_id;
DELETE r FROM weekly_report r JOIN weekly_report_alias a ON a.old_report_id=r.id;
ALTER TABLE weekly_report
 MODIFY COLUMN week_start DATE NOT NULL COMMENT '北京时间自然周周一；周报唯一分析键',
 ADD UNIQUE KEY uq_report_week(week_start),
 MODIFY COLUMN revision INT NOT NULL COMMENT '当前周报内容更新序号；不保留历次报告副本',
 MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT 'PUBLISHED已发布；同周显式生成可覆盖',
 MODIFY COLUMN candidate_audit JSON NULL COMMENT '最近一次成功生成时的候选指标与筛选依据',
 COMMENT='每自然周唯一报告：保存最近一次成功生成的完整结果';
