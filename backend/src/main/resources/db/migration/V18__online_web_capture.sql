CREATE TABLE capture_web_provider (
 tenant_id VARCHAR(64) PRIMARY KEY COMMENT '服务端工作区',
 base_url VARCHAR(1000) NOT NULL COMMENT 'Firecrawl v2 API入口',
 api_key_ciphertext TEXT NULL COMMENT 'AES-GCM加密密钥，不向页面返回',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '服务是否启用',
 revision INT NOT NULL DEFAULT 0 COMMENT '配置乐观锁与试抓版本',
 updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC'
) COMMENT='网页工具供应商配置；每次执行动态读取';

CREATE TABLE capture_web_rule (
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务端工作区',
 target_key VARCHAR(80) NOT NULL COMMENT '网页目标键',
 draft_version INT NOT NULL DEFAULT 0 COMMENT '当前草稿版本',
 published_version INT NULL COMMENT '当前发布版本；空为未发布',
 revision INT NOT NULL DEFAULT 0 COMMENT '编辑/发布并发控制版本',
 updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC',
 PRIMARY KEY(tenant_id,target_key)
) COMMENT='在线规则草稿和发布指针';

CREATE TABLE capture_web_rule_version (
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务端工作区',
 target_key VARCHAR(80) NOT NULL COMMENT '网页目标键',
 version INT NOT NULL COMMENT '不可变版本号',
 tool_key VARCHAR(64) NOT NULL COMMENT 'firecrawl或web-http',
 rule_json JSON NOT NULL COMMENT '声明式URL、CSS、字段、数量、等待规则',
 created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
 PRIMARY KEY(tenant_id,target_key,version)
) COMMENT='不可变网页规则历史，不含可执行代码';

CREATE TABLE capture_web_run (
 id CHAR(36) PRIMARY KEY COMMENT '运行UUID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务端工作区',
 target_key VARCHAR(80) NOT NULL COMMENT '网页目标键',
 rule_version INT NOT NULL COMMENT '执行的不可变规则版本',
 provider_revision INT NOT NULL COMMENT '供应商配置版本，普通HTTP为0',
 tool_key VARCHAR(64) NOT NULL COMMENT '实际工具',
 run_mode VARCHAR(16) NOT NULL COMMENT 'PREVIEW试抓或LIVE已发布采集',
 status VARCHAR(24) NOT NULL COMMENT 'RUNNING/SUCCEEDED/PARTIAL/FAILED/INTERRUPTED',
 error_code VARCHAR(80) NULL COMMENT '脱敏错误码',
 active_scope CHAR(64) NULL COMMENT '同租户同目标互斥',
 lease_until DATETIME(3) NULL COMMENT '运行租约UTC',
 source_url VARCHAR(2000) NOT NULL COMMENT '请求来源URL',
 raw_sha256 CHAR(64) NULL COMMENT '实际网页材料哈希',
 raw_locator TEXT NULL COMMENT '相对材料根目录路径',
 result_json JSON NULL COMMENT '来源字段、逐项证据与校验问题；不是业务产品数据',
 started_at DATETIME(3) NOT NULL COMMENT '开始UTC',
 finished_at DATETIME(3) NULL COMMENT '完成UTC',
 UNIQUE KEY uq_capture_web_active(active_scope),
 KEY idx_capture_web_history(tenant_id,target_key,started_at)
) COMMENT='真实网页试抓与已发布运行；与榜单日志隔离';
