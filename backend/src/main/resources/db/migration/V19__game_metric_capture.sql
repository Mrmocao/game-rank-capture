CREATE TABLE capture_metric_config (
 tenant_id VARCHAR(64) PRIMARY KEY COMMENT '服务器工作区',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '自动排队公开商店指标',
 refresh_hours INT NOT NULL DEFAULT 24 COMMENT '成功缓存小时',
 transport VARCHAR(24) NOT NULL DEFAULT 'web-http' COMMENT '详情获取工具web-http或firecrawl',
 revision INT NOT NULL DEFAULT 0 COMMENT '配置版本',
 planned_at DATETIME(3) NULL COMMENT '上次自动排队UTC',
 worker_seen_at DATETIME(3) NULL COMMENT '独立指标执行器心跳UTC',
 remote_seen_at DATETIME(3) NULL COMMENT '海外指标执行器最近领取UTC，不代表常驻在线',
 updated_at DATETIME(3) NOT NULL COMMENT '配置更新时间UTC'
) COMMENT='独立指标补全计划；不占用榜单计划';

CREATE TABLE capture_metric_job (
 id CHAR(64) PRIMARY KEY COMMENT '工作区平台ID地区来源哈希',
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务器工作区',
 platform VARCHAR(32) NOT NULL COMMENT 'IOS或GOOGLE_PLAY',
 external_id VARCHAR(200) NOT NULL COMMENT 'Apple ID或包名；不按名字关联',
 region VARCHAR(8) NOT NULL COMMENT '商店地区，非默认指标地区',
 source_key VARCHAR(32) NOT NULL COMMENT 'apple-lookup/apple-detail/google-detail',
 name VARCHAR(500) NOT NULL COMMENT '榜单来源名称',
 ranking_observation_id BIGINT NOT NULL COMMENT '排队依据榜单观察',
 status VARCHAR(24) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
 attempts INT NOT NULL DEFAULT 0 COMMENT '实际尝试次数',
 due_at DATETIME(3) NOT NULL COMMENT '最早处理时间UTC',
 lease_token CHAR(36) NULL COMMENT '执行租约令牌不返回页面',
 lease_until DATETIME(3) NULL COMMENT '执行租约到期UTC',
 last_success_id CHAR(36) NULL COMMENT '最近成功观察；失败不清除',
 error_code VARCHAR(80) NULL COMMENT '脱敏错误码',
 last_started_at DATETIME(3) NULL COMMENT '开始UTC',
 last_finished_at DATETIME(3) NULL COMMENT '结束UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '任务更新时间UTC',
 UNIQUE KEY uq_metric_identity(tenant_id,platform,external_id,region,source_key),
 KEY idx_metric_due(tenant_id,status,due_at)
) COMMENT='商店指标持久去重队列';

CREATE TABLE capture_metric_observation (
 id CHAR(36) PRIMARY KEY COMMENT '指标观察UUID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务器工作区',
 job_id CHAR(64) NOT NULL COMMENT '来源任务',
 captured_at DATETIME(3) NOT NULL COMMENT '实际获取UTC，不冒充来源统计日',
 raw_sha256 CHAR(64) NOT NULL COMMENT '原始材料摘要',
 raw_locator TEXT NOT NULL COMMENT '材料根目录下相对路径',
 snapshot_json JSON NOT NULL COMMENT '指标原文、口径、单位、地域、证据与内购展示项目',
 KEY idx_metric_history(tenant_id,job_id,captured_at)
) COMMENT='只追加来源指标历史，不写产品和历史报告';

CREATE TABLE capture_metric_provider (
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务器工作区',
 provider_key VARCHAR(32) NOT NULL COMMENT 'appfigures/diandian/sensortower',
 api_key_ciphertext TEXT NULL COMMENT '独立AES-GCM加密，不返回页面',
 account_label VARCHAR(200) NOT NULL DEFAULT '' COMMENT '账号备注，不存登录密码',
 licensed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户声明具有API授权，仍需真实验证',
 revision INT NOT NULL DEFAULT 0 COMMENT '编辑版本',
 updated_at DATETIME(3) NOT NULL COMMENT '配置更新时间UTC',
 PRIMARY KEY(tenant_id,provider_key)
) COMMENT='估算数据商授权准备；未验证前不调用收费API';
