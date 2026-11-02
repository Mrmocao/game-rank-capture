CREATE TABLE capture_target_config (
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务端工作区',
 target_key VARCHAR(80) NOT NULL COMMENT '稳定采集目标键',
 name VARCHAR(200) NOT NULL COMMENT '目标显示名称',
 source_url VARCHAR(1000) NOT NULL COMMENT '目标网站或固定采集入口，不允许凭证',
 source_key VARCHAR(80) NULL COMMENT '已实现来源适配器键；空值为候选登记',
 region VARCHAR(16) NOT NULL COMMENT '目标地区',
 tool_key VARCHAR(64) NOT NULL COMMENT '抓取工具稳定键',
 field_scope TEXT NOT NULL COMMENT '预期获取的信息字段与口径',
 notes TEXT NOT NULL COMMENT '来源说明与维护备注',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否纳入抓取范围；不代表定时计划已开启',
 revision INT NOT NULL DEFAULT 0 COMMENT '配置乐观锁版本',
 created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC',
 PRIMARY KEY(tenant_id,target_key)
) COMMENT='外部信息抓取目标；候选登记与可执行适配器分开';

CREATE TABLE capture_schedule_config (
 tenant_id VARCHAR(64) PRIMARY KEY COMMENT '服务端工作区',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用独立采集计划',
 cron_expression VARCHAR(80) NOT NULL COMMENT '受控每日计划表达式',
 timezone VARCHAR(40) NOT NULL COMMENT '计划时区固定Asia/Shanghai',
 revision INT NOT NULL DEFAULT 0 COMMENT '计划乐观锁版本',
 next_due_at DATETIME(3) NULL COMMENT '下一次应执行时间UTC',
 worker_seen_at DATETIME(3) NULL COMMENT '执行器最近心跳UTC',
 last_started_at DATETIME(3) NULL COMMENT '最近计划开始时间UTC',
 last_finished_at DATETIME(3) NULL COMMENT '最近计划结束时间UTC',
 last_result_json JSON NULL COMMENT '最近计划结果摘要',
 active_token CHAR(36) NULL COMMENT '本次计划执行租约所有者',
 lease_until DATETIME(3) NULL COMMENT '防重复运行租约到期时间UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC'
) COMMENT='独立采集持久计划与执行器状态，不触发周报';

CREATE TABLE capture_repair_job (
 id CHAR(36) PRIMARY KEY COMMENT '修复工单UUID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务端工作区',
 run_id CHAR(36) NOT NULL COMMENT '失败运行ID',
 target_key VARCHAR(80) NOT NULL COMMENT '关联采集目标',
 tool_key VARCHAR(64) NOT NULL COMMENT '关联抓取工具',
 error_code VARCHAR(80) NOT NULL COMMENT '来源失败稳定错误码',
 status VARCHAR(40) NOT NULL COMMENT 'OPEN/NEEDS_LOGIN/AI_RUNNING/AI_SUGGESTED/AI_FAILED/RETRYING/RECOVERED/STILL_FAILING',
 context_json JSON NOT NULL COMMENT '脱敏范围、错误、材料摘要与维护路径，不含凭证',
 proposal_json JSON NULL COMMENT '经结构校验的AI诊断与修改建议，非已发布代码',
 ai_error_code VARCHAR(80) NULL COMMENT 'AI失败稳定错误码，不保存响应秘钥',
 retry_batch_id CHAR(36) NULL COMMENT '验证重试产生的真实批次ID',
 created_at DATETIME(3) NOT NULL COMMENT '工单创建时间UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '工单最近更新时间UTC',
 UNIQUE KEY uq_capture_repair_run(tenant_id,run_id),
 KEY idx_capture_repair_state(tenant_id,status,updated_at)
) COMMENT='AI诊断与Agent修复交接工单，真实重试后才确认恢复';
