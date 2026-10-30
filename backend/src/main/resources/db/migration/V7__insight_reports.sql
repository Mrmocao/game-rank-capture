ALTER TABLE weekly_report
 ADD COLUMN skill_snapshot JSON NULL COMMENT '生成时冻结的Skill正文、策略参数及内容哈希；旧报告为空',
 ADD COLUMN candidate_audit JSON NULL COMMENT '本次全部候选的指标、引用、入选或淘汰原因；生成后不变',
 ADD COLUMN input_sha256 CHAR(64) NULL COMMENT '周期、规则、Skill及候选输入内容哈希，用于重试幂等';
CREATE TABLE insight_generation_lock (
 id INT PRIMARY KEY COMMENT '报告生成互斥锁，固定为1'
) COMMENT='序列化周报版本分配，事务提交自动释放';
INSERT INTO insight_generation_lock(id) VALUES(1);
CREATE TABLE insight_collection_attempt (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '公开来源采集尝试ID',
 source_key VARCHAR(80) NOT NULL COMMENT '来源与地区标识',
 requested_date DATE NOT NULL COMMENT '尝试时中国业务日，不代替来源日期',
 status VARCHAR(24) NOT NULL COMMENT 'RUNNING、SUCCESS、FAILED或INTERRUPTED',
 result_json JSON NULL COMMENT '快照ID、来源日、覆盖或失败代码，不含密钥',
 started_at DATETIME(3) NOT NULL COMMENT '开始时间UTC',
 finished_at DATETIME(3) NULL COMMENT '结束时间UTC',
 INDEX idx_insight_attempt(source_key,started_at)
) COMMENT='每日免费来源尝试及失败留痕，失败不生成榜单';
