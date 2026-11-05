CREATE TABLE capture_profile_document (
 id CHAR(36) PRIMARY KEY COMMENT '来源资料观察UUID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '工作区',
 source_key VARCHAR(64) NOT NULL COMMENT '资料来源',
 external_id VARCHAR(64) NOT NULL COMMENT '中国iOS App ID',
 name VARCHAR(500) NOT NULL COMMENT '详情名称',
 name_key VARCHAR(500) NOT NULL COMMENT '名称匹配键，不等于跨平台身份',
 captured_at DATETIME(3) NOT NULL COMMENT '实际采集UTC',
 raw_sha256 CHAR(64) NOT NULL COMMENT '可见材料摘要',
 raw_locator TEXT NOT NULL COMMENT '仅保存资料DOM投影的相对路径',
 document_json JSON NOT NULL COMMENT '字段原文、来源、限制',
 UNIQUE KEY uq_profile_material(tenant_id,raw_sha256),
 KEY idx_profile_name(tenant_id,name_key(120),captured_at),
 KEY idx_profile_id(tenant_id,external_id,captured_at)
) COMMENT='独立扩展资料历史，不写产品或榜单数值';

CREATE TABLE capture_profile_job (
 id CHAR(64) PRIMARY KEY COMMENT '工作区名称哈希',
 tenant_id VARCHAR(64) NOT NULL COMMENT '工作区',
 seed_name VARCHAR(500) NOT NULL COMMENT '现有榜单游戏名称',
 status VARCHAR(32) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/NO_CANDIDATE/FAILED',
 attempts INT NOT NULL DEFAULT 0 COMMENT '实际搜索次数',
 error_code VARCHAR(80) NULL COMMENT '脱敏失败码',
 due_at DATETIME(3) NOT NULL COMMENT '下次可采集UTC',
 lease_token CHAR(36) NULL COMMENT '执行令牌',
 lease_until DATETIME(3) NULL COMMENT '租约截止UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '最近状态变化UTC',
 KEY idx_profile_due(tenant_id,status,due_at)
) COMMENT='按榜单游戏名称搜索资料的独立去重队列';
