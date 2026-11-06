-- 全新空库安装脚本（已有库使用Flyway增量迁移，不要重跑CREATE）。
-- MySQL 8 initial design draft, not an executed production migration.
-- UTC timestamps; DATE columns are Asia/Shanghai business dates.
-- IDs supplied by app or AUTO_INCREMENT; API serializes BIGINT as string.
-- Append-only history and optimistic locking are enforced by application services.
SET NAMES utf8mb4;
CREATE TABLE product (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(200) NOT NULL,
 pool_state VARCHAR(32) NOT NULL DEFAULT 'PENDING_IDENTIFICATION',
 first_observed_at DATETIME(3) NOT NULL, canonical_product_id BIGINT NULL,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, version INT NOT NULL DEFAULT 0,
 KEY idx_product_state(pool_state, first_observed_at),
 FOREIGN KEY(canonical_product_id) REFERENCES product(id)
);
CREATE TABLE platform_version (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL,
 platform VARCHAR(24) NOT NULL, external_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL,
 name VARCHAR(200) NOT NULL, developer VARCHAR(200), package_name VARCHAR(255), entry_url TEXT,
 released_on DATE NULL, first_observed_at DATETIME(3) NOT NULL,
 source_fields JSON, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_platform_external(platform,external_id), KEY idx_version_product(product_id),
 FOREIGN KEY(product_id) REFERENCES product(id)
);
CREATE TABLE data_source (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL,
 adapter_key VARCHAR(100) NOT NULL, access_method VARCHAR(32) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, secret_ref VARCHAR(255), config JSON,
 authorization_notes TEXT, version INT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL
);
CREATE TABLE chart_definition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, source_id BIGINT NOT NULL,
 platform VARCHAR(24) NOT NULL, region VARCHAR(8) NOT NULL,
 external_chart_id VARCHAR(120) COLLATE utf8mb4_bin NOT NULL,
 name VARCHAR(200) NOT NULL, kind VARCHAR(16) NOT NULL COMMENT 'HEAT/AD_BUY',
 category_key VARCHAR(80) NOT NULL DEFAULT 'ALL', device_scope VARCHAR(40) NOT NULL DEFAULT 'DEFAULT',
 requested_top_n INT NOT NULL DEFAULT 100, enabled BOOLEAN NOT NULL DEFAULT FALSE,
 methodology TEXT, config JSON,
 UNIQUE KEY uq_chart(source_id,platform,region,external_chart_id,category_key,device_scope),
 FOREIGN KEY(source_id) REFERENCES data_source(id), CHECK(requested_top_n>0)
);
CREATE TABLE collection_job (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, source_id BIGINT NOT NULL,
 name VARCHAR(120) NOT NULL, schedule_spec VARCHAR(120) NOT NULL,
 timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai', enabled BOOLEAN NOT NULL DEFAULT FALSE,
 config JSON, version INT NOT NULL DEFAULT 0,
 FOREIGN KEY(source_id) REFERENCES data_source(id)
);
CREATE TABLE collection_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, job_id BIGINT NOT NULL, parent_run_id BIGINT NULL,
 business_date DATE NOT NULL, run_mode VARCHAR(16) NOT NULL,
 status VARCHAR(24) NOT NULL, stage VARCHAR(40), idempotency_key VARCHAR(180) NOT NULL,
 started_at DATETIME(3), finished_at DATETIME(3), heartbeat_at DATETIME(3),
 error_code VARCHAR(80), error_message TEXT, metrics JSON, model_metadata JSON,
 UNIQUE KEY uq_run_idempotency(idempotency_key), KEY idx_run_date(job_id,business_date,status),
 FOREIGN KEY(job_id) REFERENCES collection_job(id), FOREIGN KEY(parent_run_id) REFERENCES collection_run(id)
);
CREATE TABLE ranking_snapshot (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, chart_id BIGINT NOT NULL, run_id BIGINT NOT NULL,
 business_date DATE NOT NULL, source_date DATE, revision INT NOT NULL,
 status VARCHAR(20) NOT NULL COMMENT 'STAGED/PUBLISHED', completeness VARCHAR(16) NOT NULL,
 requested_top_n INT NOT NULL, covered_top_n INT NOT NULL, actual_count INT NOT NULL,
 source_url TEXT, raw_locator TEXT, raw_sha256 CHAR(64) NOT NULL,
 captured_at DATETIME(3) NOT NULL, published_at DATETIME(3),
 UNIQUE KEY uq_snapshot_revision(chart_id,business_date,revision),
 UNIQUE KEY uq_snapshot_material(chart_id,business_date,raw_sha256),
 KEY idx_snapshot_query(chart_id,business_date,status,published_at),
 FOREIGN KEY(chart_id) REFERENCES chart_definition(id), FOREIGN KEY(run_id) REFERENCES collection_run(id),
 CHECK(revision>0), CHECK(covered_top_n>=0), CHECK(actual_count>=0)
);
CREATE TABLE ranking_entry (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, snapshot_id BIGINT NOT NULL, platform_version_id BIGINT NOT NULL,
 rank_position INT NOT NULL, rating DECIMAL(4,2), rating_count BIGINT, positive_review_count BIGINT,
 source_metrics JSON COMMENT 'include units, periods and estimate flags',
 UNIQUE KEY uq_entry_version(snapshot_id,platform_version_id),
 UNIQUE KEY uq_entry_rank(snapshot_id,rank_position), KEY idx_entry_version(platform_version_id,snapshot_id),
 FOREIGN KEY(snapshot_id) REFERENCES ranking_snapshot(id), FOREIGN KEY(platform_version_id) REFERENCES platform_version(id),
 CHECK(rank_position>0), CHECK(rating_count IS NULL OR rating_count>=0),
 CHECK(positive_review_count IS NULL OR positive_review_count>=0)
);
CREATE TABLE evidence (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, platform_version_id BIGINT NOT NULL, source_id BIGINT NULL,
 kind VARCHAR(40) NOT NULL, source_url TEXT, source_record_id VARCHAR(200),
 locator TEXT, raw_sha256 CHAR(64), excerpt TEXT, observed_at DATETIME(3) NOT NULL,
 valid_until DATETIME(3), scope JSON, metadata JSON,
 created_by VARCHAR(100), created_at DATETIME(3) NOT NULL,
 KEY idx_evidence_version(platform_version_id,observed_at),
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id), FOREIGN KEY(source_id) REFERENCES data_source(id)
);
CREATE TABLE monetization_assessment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, platform_version_id BIGINT NOT NULL,
 mode VARCHAR(24) NOT NULL COMMENT 'UNKNOWN/PURE_IAA/MIXED/IAP/OTHER',
 method VARCHAR(40) NOT NULL, confidence DECIMAL(5,4), reason TEXT NOT NULL,
 effective_at DATETIME(3) NOT NULL, expires_at DATETIME(3), supersedes_id BIGINT,
 created_by VARCHAR(100), created_at DATETIME(3) NOT NULL,
 KEY idx_assessment_version(platform_version_id,effective_at),
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id),
 FOREIGN KEY(supersedes_id) REFERENCES monetization_assessment(id),
 CHECK(confidence IS NULL OR (confidence>=0 AND confidence<=1))
);
CREATE TABLE assessment_evidence (
 assessment_id BIGINT NOT NULL, evidence_id BIGINT NOT NULL,
 relation_kind VARCHAR(16) NOT NULL COMMENT 'SUPPORTS/CONFLICTS',
 PRIMARY KEY(assessment_id,evidence_id),
 FOREIGN KEY(assessment_id) REFERENCES monetization_assessment(id), FOREIGN KEY(evidence_id) REFERENCES evidence(id)
);
CREATE TABLE ai_provider_config (
 id BIGINT PRIMARY KEY,
 provider_name VARCHAR(80) NOT NULL, base_url VARCHAR(500) NOT NULL, model VARCHAR(160) NOT NULL,
 api_key_ciphertext TEXT NULL, api_key_configured BOOLEAN NOT NULL DEFAULT FALSE,
 timeout_seconds INT NOT NULL DEFAULT 60, temperature DECIMAL(4,3) NOT NULL DEFAULT 0.200,
 enabled BOOLEAN NOT NULL DEFAULT FALSE, created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
 version INT NOT NULL DEFAULT 0, CHECK(timeout_seconds BETWEEN 5 AND 300), CHECK(temperature>=0 AND temperature<=2)
);
CREATE TABLE rule_version (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, version_number INT NOT NULL UNIQUE,
 status VARCHAR(16) NOT NULL COMMENT 'DRAFT/PUBLISHED/SUPERSEDED', rules_json JSON NOT NULL,
 content_sha256 CHAR(64) NOT NULL, published_at DATETIME(3), created_by VARCHAR(100), created_at DATETIME(3) NOT NULL
);
CREATE TABLE rule_hit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL, platform_version_id BIGINT NOT NULL,
 rule_version_id BIGINT NOT NULL, chart_id BIGINT NOT NULL, business_date DATE NOT NULL,
 signal_type VARCHAR(40) NOT NULL, reason TEXT NOT NULL, metrics JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_rule_hit(platform_version_id,rule_version_id,chart_id,business_date,signal_type),
 KEY idx_rule_product(product_id,business_date),
 FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(platform_version_id) REFERENCES platform_version(id),
 FOREIGN KEY(rule_version_id) REFERENCES rule_version(id), FOREIGN KEY(chart_id) REFERENCES chart_definition(id)
);
CREATE TABLE rule_hit_snapshot (
 rule_hit_id BIGINT NOT NULL, snapshot_id BIGINT NOT NULL, PRIMARY KEY(rule_hit_id,snapshot_id),
 FOREIGN KEY(rule_hit_id) REFERENCES rule_hit(id), FOREIGN KEY(snapshot_id) REFERENCES ranking_snapshot(id)
);
CREATE TABLE rule_hit_evidence (
 rule_hit_id BIGINT NOT NULL, evidence_id BIGINT NOT NULL, PRIMARY KEY(rule_hit_id,evidence_id),
 FOREIGN KEY(rule_hit_id) REFERENCES rule_hit(id), FOREIGN KEY(evidence_id) REFERENCES evidence(id)
);
CREATE TABLE product_state_history (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL, pool_state VARCHAR(32) NOT NULL,
 rule_hit_id BIGINT NULL, reason TEXT NOT NULL, effective_at DATETIME(3) NOT NULL,
 recorded_at DATETIME(3) NOT NULL, created_by VARCHAR(100),
 KEY idx_state_history(product_id,effective_at,recorded_at),
 FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(rule_hit_id) REFERENCES rule_hit(id)
);
CREATE TABLE field_override (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL, platform_version_id BIGINT NULL,
 field_name VARCHAR(80) NOT NULL, value_json JSON NOT NULL, reason TEXT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, effective_at DATETIME(3) NOT NULL,
 supersedes_id BIGINT NULL, version INT NOT NULL DEFAULT 0, created_by VARCHAR(100) NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_override_product(product_id,field_name,effective_at),
 FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(platform_version_id) REFERENCES platform_version(id),
 FOREIGN KEY(supersedes_id) REFERENCES field_override(id)
);
CREATE TABLE selection_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, product_id BIGINT NOT NULL, reference_version_id BIGINT NULL,
 owner_name VARCHAR(40) NOT NULL, status VARCHAR(24) NOT NULL,
 selected_on DATE NOT NULL, started_on DATE, finished_on DATE, note VARCHAR(1000),
 created_by VARCHAR(100) NOT NULL, updated_by VARCHAR(100) NOT NULL,
 created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, version INT NOT NULL DEFAULT 0,
 KEY idx_selection_product(product_id,status), KEY idx_selection_owner(owner_name,status),
 FOREIGN KEY(product_id) REFERENCES product(id), FOREIGN KEY(reference_version_id) REFERENCES platform_version(id),
 CHECK(started_on IS NULL OR started_on>=selected_on),
 CHECK(finished_on IS NULL OR finished_on>=selected_on),
 CHECK(started_on IS NULL OR finished_on IS NULL OR finished_on>=started_on)
);
CREATE TABLE weekly_report (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, period_start DATE NOT NULL, period_end DATE NOT NULL,
 revision INT NOT NULL, status VARCHAR(20) NOT NULL, rule_version_id BIGINT NOT NULL,
 coverage_json JSON NOT NULL, model_metadata JSON, generated_at DATETIME(3), published_at DATETIME(3),
 UNIQUE KEY uq_week_revision(period_start,period_end,revision),
 FOREIGN KEY(rule_version_id) REFERENCES rule_version(id), CHECK(period_end>period_start)
);
CREATE TABLE report_item (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, report_id BIGINT NOT NULL, product_id BIGINT NOT NULL,
 main_reference_version_id BIGINT NOT NULL, section VARCHAR(32) NOT NULL,
 display_order INT NOT NULL, frozen_facts JSON NOT NULL, evidence_refs JSON NOT NULL, content TEXT NOT NULL,
 UNIQUE KEY uq_report_product(report_id,product_id),
 FOREIGN KEY(report_id) REFERENCES weekly_report(id), FOREIGN KEY(product_id) REFERENCES product(id),
 FOREIGN KEY(main_reference_version_id) REFERENCES platform_version(id)
);
CREATE TABLE identity_link_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, platform_version_id BIGINT NOT NULL,
 from_product_id BIGINT NOT NULL, to_product_id BIGINT NOT NULL,
 reverts_event_id BIGINT NULL, evidence_refs JSON, reason TEXT NOT NULL,
 effective_at DATETIME(3) NOT NULL, created_at DATETIME(3) NOT NULL, created_by VARCHAR(100) NOT NULL,
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id), FOREIGN KEY(from_product_id) REFERENCES product(id),
 FOREIGN KEY(to_product_id) REFERENCES product(id), FOREIGN KEY(reverts_event_id) REFERENCES identity_link_event(id)
);
CREATE TABLE audit_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, actor_id VARCHAR(100) NOT NULL, action VARCHAR(80) NOT NULL,
 entity_type VARCHAR(80) NOT NULL, entity_id VARCHAR(100) NOT NULL,
 before_json JSON, after_json JSON, request_id VARCHAR(100), created_at DATETIME(3) NOT NULL,
 KEY idx_audit_entity(entity_type,entity_id,created_at)
);
CREATE TABLE idempotency_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, actor_id VARCHAR(100) NOT NULL,
 operation VARCHAR(100) NOT NULL, request_key VARCHAR(180) NOT NULL, request_sha256 CHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL, response_json JSON, created_at DATETIME(3) NOT NULL, expires_at DATETIME(3) NOT NULL,
 UNIQUE KEY uq_idempotency(actor_id,operation,request_key)
);
-- Before production: add migration tests, state enum validation, transaction/lock policy,
-- source-specific metric schema, identity-link conflict checks and controlled auth integration.
-- For sources with tied rankings, revise uq_entry_rank under a documented chart-specific tie policy.

-- 中文数据字典注释。仅修改元数据，不改变字段类型、数据和历史记录。
ALTER TABLE product COMMENT='产品聚合身份：跨平台统一产品的一行主记录';
ALTER TABLE platform_version COMMENT='平台版本：承载平台、地区、外部ID和版本级变现证据';
ALTER TABLE data_source COMMENT='数据来源配置：供应商、授权方式和密钥引用';
ALTER TABLE chart_definition COMMENT='榜单定义：来源、平台、地区、榜单类型和设备口径';
ALTER TABLE collection_job COMMENT='采集任务配置：来源、调度、时区和启停状态';
ALTER TABLE collection_run COMMENT='采集运行记录：一次任务执行、阶段、错误和幂等键';
ALTER TABLE ranking_snapshot COMMENT='榜单快照：指定来源和业务日的不可变修订版本';
ALTER TABLE ranking_entry COMMENT='榜单明细：快照中的平台版本排名和评价指标';
ALTER TABLE evidence COMMENT='证据材料：原始来源、定位、摘要、哈希和有效期';
ALTER TABLE monetization_assessment COMMENT='版本级变现结论：PURE_IAA、MIXED、IAP或待识别';
ALTER TABLE assessment_evidence COMMENT='变现结论与证据的支持/冲突关系';
ALTER TABLE ai_provider_config COMMENT='OpenAI-compatible AI 中转配置，密钥仅保存加密密文';
ALTER TABLE rule_version COMMENT='规则版本：发布后不可覆盖，历史报告引用固定版本';
ALTER TABLE rule_hit COMMENT='确定性规则命中：产品、版本、榜单、业务日和可读理由';
ALTER TABLE rule_hit_snapshot COMMENT='规则命中引用的榜单快照';
ALTER TABLE rule_hit_evidence COMMENT='规则命中引用的证据材料';
ALTER TABLE product_state_history COMMENT='产品资格状态历史：保留每次状态变化原因';
ALTER TABLE field_override COMMENT='人工字段覆盖：独立于原始采集值并可撤销';
ALTER TABLE selection_record COMMENT='选用记录：多人独立填写的制作状态和日期';
ALTER TABLE weekly_report COMMENT='周报版本：冻结报告期、规则、覆盖和生成结果';
ALTER TABLE report_item COMMENT='周报条目：冻结产品事实、证据引用和推荐/观察文案';
ALTER TABLE identity_link_event COMMENT='产品身份关联事件：合并、改归属和撤销历史';
ALTER TABLE audit_event COMMENT='审计事件：配置、人工修改和重要操作的前后值';
ALTER TABLE idempotency_record COMMENT='幂等记录：防止重复提交并保存原始响应';

ALTER TABLE product MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '产品ID';
ALTER TABLE product MODIFY COLUMN name VARCHAR(200) NOT NULL COMMENT '产品统一名称';
ALTER TABLE product MODIFY COLUMN pool_state VARCHAR(32) NOT NULL DEFAULT 'PENDING_IDENTIFICATION' COMMENT '产品池状态：待识别/观察/正式/排除';
ALTER TABLE product MODIFY COLUMN first_observed_at DATETIME(3) NOT NULL COMMENT '首次有效观察时间（UTC）';
ALTER TABLE product MODIFY COLUMN canonical_product_id BIGINT NULL COMMENT '归一产品ID，身份关联后使用';
ALTER TABLE platform_version MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '所属产品ID';
ALTER TABLE platform_version MODIFY COLUMN platform VARCHAR(24) NOT NULL COMMENT '平台枚举：WECHAT/DOUYIN/IOS/GOOGLE_PLAY';
ALTER TABLE platform_version MODIFY COLUMN external_id VARCHAR(200) COLLATE utf8mb4_bin NOT NULL COMMENT '平台稳定外部ID：appid/App Store ID/package';
ALTER TABLE platform_version MODIFY COLUMN developer VARCHAR(200) COMMENT '发行商或开发商';
ALTER TABLE platform_version MODIFY COLUMN package_name VARCHAR(255) COMMENT 'Android包名或平台包标识';
ALTER TABLE platform_version MODIFY COLUMN entry_url TEXT COMMENT '平台原始入口链接';
ALTER TABLE data_source MODIFY COLUMN name VARCHAR(100) NOT NULL COMMENT '来源名称';
ALTER TABLE data_source MODIFY COLUMN adapter_key VARCHAR(100) NOT NULL COMMENT '适配器标识';
ALTER TABLE data_source MODIFY COLUMN access_method VARCHAR(32) NOT NULL COMMENT '访问方式：API/导出/网页/模拟';
ALTER TABLE data_source MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用真实采集';
ALTER TABLE data_source MODIFY COLUMN secret_ref VARCHAR(255) COMMENT '密钥管理引用，不保存明文密钥';
ALTER TABLE chart_definition MODIFY COLUMN platform VARCHAR(24) NOT NULL COMMENT '榜单平台';
ALTER TABLE chart_definition MODIFY COLUMN region VARCHAR(8) NOT NULL COMMENT '国家或地区代码';
ALTER TABLE chart_definition MODIFY COLUMN kind VARCHAR(16) NOT NULL COMMENT '榜单指标：HEAT/AD_BUY';
ALTER TABLE chart_definition MODIFY COLUMN requested_top_n INT NOT NULL DEFAULT 100 COMMENT '请求目标TopN';
ALTER TABLE ranking_snapshot MODIFY COLUMN business_date DATE NOT NULL COMMENT '业务统计日（Asia/Shanghai）';
ALTER TABLE ranking_snapshot MODIFY COLUMN source_date DATE COMMENT '来源自身统计日';
ALTER TABLE ranking_snapshot MODIFY COLUMN revision INT NOT NULL COMMENT '同榜单同业务日修订号';
ALTER TABLE ranking_snapshot MODIFY COLUMN completeness VARCHAR(16) NOT NULL COMMENT '覆盖完整性：COMPLETE/PARTIAL';
ALTER TABLE ranking_snapshot MODIFY COLUMN requested_top_n INT NOT NULL COMMENT '请求TopN';
ALTER TABLE ranking_snapshot MODIFY COLUMN covered_top_n INT NOT NULL COMMENT '实际覆盖到的名次数';
ALTER TABLE ranking_snapshot MODIFY COLUMN actual_count INT NOT NULL COMMENT '实际写入条数';
ALTER TABLE ranking_snapshot MODIFY COLUMN raw_sha256 CHAR(64) NOT NULL COMMENT '原始材料SHA-256';
ALTER TABLE ranking_entry MODIFY COLUMN rank_position INT NOT NULL COMMENT '原始榜单名次，从1开始';
ALTER TABLE ranking_entry MODIFY COLUMN rating DECIMAL(4,2) COMMENT '来源评分，无来源时为空';
ALTER TABLE ranking_entry MODIFY COLUMN rating_count BIGINT COMMENT '评分数量，无来源时为空';
ALTER TABLE evidence MODIFY COLUMN kind VARCHAR(40) NOT NULL COMMENT '证据类型：IAA声明/公开排名/IAP冲突等';
ALTER TABLE evidence MODIFY COLUMN locator TEXT COMMENT '原文、字段、页码或截图定位';
ALTER TABLE evidence MODIFY COLUMN excerpt TEXT COMMENT '证据原文摘录';
ALTER TABLE evidence MODIFY COLUMN observed_at DATETIME(3) NOT NULL COMMENT '证据观察时间（UTC）';
ALTER TABLE monetization_assessment MODIFY COLUMN mode VARCHAR(24) NOT NULL COMMENT '变现结论：UNKNOWN/PURE_IAA/MIXED/IAP/OTHER';
ALTER TABLE monetization_assessment MODIFY COLUMN method VARCHAR(40) NOT NULL COMMENT '判定方法';
ALTER TABLE monetization_assessment MODIFY COLUMN reason TEXT NOT NULL COMMENT '判定理由';
ALTER TABLE ai_provider_config MODIFY COLUMN provider_name VARCHAR(80) NOT NULL COMMENT '中转供应商名称';
ALTER TABLE ai_provider_config MODIFY COLUMN base_url VARCHAR(500) NOT NULL COMMENT 'OpenAI-compatible Base URL';
ALTER TABLE ai_provider_config MODIFY COLUMN model VARCHAR(160) NOT NULL COMMENT '模型名称';
ALTER TABLE ai_provider_config MODIFY COLUMN api_key_ciphertext TEXT COMMENT 'AES-GCM加密后的API Key';
ALTER TABLE ai_provider_config MODIFY COLUMN api_key_configured BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已配置API Key';
ALTER TABLE rule_version MODIFY COLUMN version_number INT NOT NULL COMMENT '规则版本号';
ALTER TABLE rule_version MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT '规则状态：DRAFT/PUBLISHED/SUPERSEDED';
ALTER TABLE rule_version MODIFY COLUMN rules_json JSON NOT NULL COMMENT '规则参数JSON';
ALTER TABLE selection_record MODIFY COLUMN owner_name VARCHAR(40) NOT NULL COMMENT '负责人姓名，可多人重复';
ALTER TABLE selection_record MODIFY COLUMN status VARCHAR(24) NOT NULL COMMENT '选用状态：SELECTED/IN_PROGRESS/COMPLETED/ABANDONED';
ALTER TABLE selection_record MODIFY COLUMN selected_on DATE NOT NULL COMMENT '选用日期';
ALTER TABLE selection_record MODIFY COLUMN started_on DATE COMMENT '开始制作日期';
ALTER TABLE selection_record MODIFY COLUMN finished_on DATE COMMENT '完成日期';
ALTER TABLE weekly_report MODIFY COLUMN period_start DATE NOT NULL COMMENT '报告期开始日，含当天';
ALTER TABLE weekly_report MODIFY COLUMN period_end DATE NOT NULL COMMENT '报告期结束日，不含当天';
ALTER TABLE weekly_report MODIFY COLUMN revision INT NOT NULL COMMENT '同报告期版本号';
ALTER TABLE weekly_report MODIFY COLUMN coverage_json JSON NOT NULL COMMENT '报告期来源覆盖和缺口';
ALTER TABLE report_item MODIFY COLUMN section VARCHAR(32) NOT NULL COMMENT '条目分组：DOMESTIC/OVERSEAS/OBSERVATION';
ALTER TABLE report_item MODIFY COLUMN frozen_facts JSON NOT NULL COMMENT '生成时冻结的事实指标';
ALTER TABLE report_item MODIFY COLUMN evidence_refs JSON NOT NULL COMMENT '快照和证据引用';
ALTER TABLE report_item MODIFY COLUMN content TEXT NOT NULL COMMENT '推荐或观察理由文案';

-- V5：补齐V4未覆盖的全部业务字段。保留原类型、默认值、索引和外键。
SET NAMES utf8mb4;
ALTER TABLE `product` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `product` MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '记录更新时间（UTC）';
ALTER TABLE `product` MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；修改时校验防止并发覆盖';
ALTER TABLE `platform_version` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `platform_version` MODIFY COLUMN name VARCHAR(200) NOT NULL COMMENT '名称';
ALTER TABLE `platform_version` MODIFY COLUMN released_on DATE NULL COMMENT '来源提供的实际发布日期；未知为空';
ALTER TABLE `platform_version` MODIFY COLUMN first_observed_at DATETIME(3) NOT NULL COMMENT '首次有效观察时间（UTC），不等于实际上线时间';
ALTER TABLE `platform_version` MODIFY COLUMN source_fields JSON COMMENT '来源原始字段JSON，不覆盖人工修正';
ALTER TABLE `platform_version` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `platform_version` MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '记录更新时间（UTC）';
ALTER TABLE `data_source` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `data_source` MODIFY COLUMN config JSON COMMENT '配置JSON；不得包含明文密钥';
ALTER TABLE `data_source` MODIFY COLUMN authorization_notes TEXT COMMENT '来源授权方式、使用范围及保存限制说明';
ALTER TABLE `data_source` MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；修改时校验防止并发覆盖';
ALTER TABLE `data_source` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `data_source` MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '记录更新时间（UTC）';
ALTER TABLE `chart_definition` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `chart_definition` MODIFY COLUMN source_id BIGINT NOT NULL COMMENT '数据来源ID，关联data_source';
ALTER TABLE `chart_definition` MODIFY COLUMN external_chart_id VARCHAR(120) COLLATE utf8mb4_bin NOT NULL COMMENT '来源提供的稳定榜单标识';
ALTER TABLE `chart_definition` MODIFY COLUMN name VARCHAR(200) NOT NULL COMMENT '名称';
ALTER TABLE `chart_definition` MODIFY COLUMN category_key VARCHAR(80) NOT NULL DEFAULT 'ALL' COMMENT '榜单分类口径，ALL为全分类，GAME为游戏';
ALTER TABLE `chart_definition` MODIFY COLUMN device_scope VARCHAR(40) NOT NULL DEFAULT 'DEFAULT' COMMENT '榜单设备口径；DEFAULT表示来源未细分';
ALTER TABLE `chart_definition` MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用该配置';
ALTER TABLE `chart_definition` MODIFY COLUMN methodology TEXT COMMENT '来源统计方法与指标口径说明';
ALTER TABLE `chart_definition` MODIFY COLUMN config JSON COMMENT '配置JSON；不得包含明文密钥';
ALTER TABLE `collection_job` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `collection_job` MODIFY COLUMN source_id BIGINT NOT NULL COMMENT '数据来源ID，关联data_source';
ALTER TABLE `collection_job` MODIFY COLUMN name VARCHAR(120) NOT NULL COMMENT '名称';
ALTER TABLE `collection_job` MODIFY COLUMN schedule_spec VARCHAR(120) NOT NULL COMMENT '调度表达式；manual表示仅手动执行';
ALTER TABLE `collection_job` MODIFY COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '任务业务时区';
ALTER TABLE `collection_job` MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用该配置';
ALTER TABLE `collection_job` MODIFY COLUMN config JSON COMMENT '配置JSON；不得包含明文密钥';
ALTER TABLE `collection_job` MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；修改时校验防止并发覆盖';
ALTER TABLE `collection_run` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `collection_run` MODIFY COLUMN job_id BIGINT NOT NULL COMMENT '采集任务ID，关联collection_job';
ALTER TABLE `collection_run` MODIFY COLUMN parent_run_id BIGINT NULL COMMENT '重试所关联的上一次运行ID；首次为空';
ALTER TABLE `collection_run` MODIFY COLUMN business_date DATE NOT NULL COMMENT '业务统计日（Asia/Shanghai），缺失不得用其他日期回填';
ALTER TABLE `collection_run` MODIFY COLUMN run_mode VARCHAR(16) NOT NULL COMMENT '运行模式；DEMO为模拟，LIVE_PUBLIC为公开来源实采';
ALTER TABLE `collection_run` MODIFY COLUMN status VARCHAR(24) NOT NULL COMMENT '任务状态：RUNNING运行、SUCCEEDED成功、PARTIAL部分成功、FAILED失败';
ALTER TABLE `collection_run` MODIFY COLUMN stage VARCHAR(40) COMMENT '当前处理阶段，如FETCHED或PUBLISHED';
ALTER TABLE `collection_run` MODIFY COLUMN idempotency_key VARCHAR(180) NOT NULL COMMENT '运行幂等键，同一次材料重试不得重复写入';
ALTER TABLE `collection_run` MODIFY COLUMN started_at DATETIME(3) COMMENT '运行开始时间（UTC）';
ALTER TABLE `collection_run` MODIFY COLUMN finished_at DATETIME(3) COMMENT '运行完成或失败时间（UTC）';
ALTER TABLE `collection_run` MODIFY COLUMN heartbeat_at DATETIME(3) COMMENT '最近任务心跳时间（UTC）';
ALTER TABLE `collection_run` MODIFY COLUMN error_code VARCHAR(80) COMMENT '可检索的失败错误码；成功为空';
ALTER TABLE `collection_run` MODIFY COLUMN error_message TEXT COMMENT '脱敏错误说明，不记录凭据';
ALTER TABLE `collection_run` MODIFY COLUMN metrics JSON COMMENT '运行计数、耗时及覆盖指标JSON';
ALTER TABLE `collection_run` MODIFY COLUMN model_metadata JSON COMMENT '模型、提示词版本及调用状态JSON；未调用须明确标记';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN chart_id BIGINT NOT NULL COMMENT '榜单定义ID，关联chart_definition';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN run_id BIGINT NOT NULL COMMENT '生成快照的采集运行ID';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT '快照状态：STAGED暂存、PUBLISHED已发布；失败不发布';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN source_url TEXT COMMENT '原始来源URL，不含凭据';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN raw_locator TEXT COMMENT '原始材料存储定位，结合哈希校验';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN captured_at DATETIME(3) NOT NULL COMMENT '材料实际获取时间（UTC）';
ALTER TABLE `ranking_snapshot` MODIFY COLUMN published_at DATETIME(3) COMMENT '发布时间（UTC）；未发布为空';
ALTER TABLE `ranking_entry` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `ranking_entry` MODIFY COLUMN snapshot_id BIGINT NOT NULL COMMENT '具体榜单快照ID；历史引用不随新修订变化';
ALTER TABLE `ranking_entry` MODIFY COLUMN platform_version_id BIGINT NOT NULL COMMENT '所属平台版本ID，关联platform_version';
ALTER TABLE `ranking_entry` MODIFY COLUMN positive_review_count BIGINT COMMENT '来源明确给出的好评数；不得从评分总量推算';
ALTER TABLE `ranking_entry` MODIFY COLUMN source_metrics JSON COMMENT '来源指标JSON，保留单位、期间及是否估算';
ALTER TABLE `evidence` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `evidence` MODIFY COLUMN platform_version_id BIGINT NOT NULL COMMENT '所属平台版本ID，关联platform_version';
ALTER TABLE `evidence` MODIFY COLUMN source_id BIGINT NULL COMMENT '数据来源ID，关联data_source';
ALTER TABLE `evidence` MODIFY COLUMN source_url TEXT COMMENT '原始来源URL，不含凭据';
ALTER TABLE `evidence` MODIFY COLUMN source_record_id VARCHAR(200) COMMENT '来源记录标识，用于定位原始条目';
ALTER TABLE `evidence` MODIFY COLUMN raw_sha256 CHAR(64) COMMENT '原始材料UTF-8内容的SHA-256摘要';
ALTER TABLE `evidence` MODIFY COLUMN valid_until DATETIME(3) COMMENT '证据有效期截止时间（UTC）；为空表示来源未声明';
ALTER TABLE `evidence` MODIFY COLUMN scope JSON COMMENT '证据适用的版本、地区和业务日JSON';
ALTER TABLE `evidence` MODIFY COLUMN metadata JSON COMMENT '证据来源属性及真实性标记JSON';
ALTER TABLE `evidence` MODIFY COLUMN created_by VARCHAR(100) COMMENT '创建人或任务标识';
ALTER TABLE `evidence` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `monetization_assessment` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `monetization_assessment` MODIFY COLUMN platform_version_id BIGINT NOT NULL COMMENT '所属平台版本ID，关联platform_version';
ALTER TABLE `monetization_assessment` MODIFY COLUMN confidence DECIMAL(5,4) COMMENT '识别置信度0到1；不能单独作为纯IAA依据';
ALTER TABLE `monetization_assessment` MODIFY COLUMN effective_at DATETIME(3) NOT NULL COMMENT '结论或事件生效时间（UTC）';
ALTER TABLE `monetization_assessment` MODIFY COLUMN expires_at DATETIME(3) COMMENT '记录有效期截止时间（UTC）';
ALTER TABLE `monetization_assessment` MODIFY COLUMN supersedes_id BIGINT COMMENT '被此记录取代的旧记录ID；保留旧记录';
ALTER TABLE `monetization_assessment` MODIFY COLUMN created_by VARCHAR(100) COMMENT '创建人或任务标识';
ALTER TABLE `monetization_assessment` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `assessment_evidence` MODIFY COLUMN assessment_id BIGINT NOT NULL COMMENT '变现判定ID，关联monetization_assessment';
ALTER TABLE `assessment_evidence` MODIFY COLUMN evidence_id BIGINT NOT NULL COMMENT '证据ID，关联evidence';
ALTER TABLE `assessment_evidence` MODIFY COLUMN relation_kind VARCHAR(16) NOT NULL COMMENT '证据关系：SUPPORTS支持、CONFLICTS冲突';
ALTER TABLE `ai_provider_config` MODIFY COLUMN id BIGINT NOT NULL COMMENT '本表主键ID';
ALTER TABLE `ai_provider_config` MODIFY COLUMN timeout_seconds INT NOT NULL DEFAULT 60 COMMENT 'AI请求超时秒数，允许5至300';
ALTER TABLE `ai_provider_config` MODIFY COLUMN temperature DECIMAL(4,3) NOT NULL DEFAULT 0.200 COMMENT '模型采样温度，允许0至2';
ALTER TABLE `ai_provider_config` MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否启用该配置';
ALTER TABLE `ai_provider_config` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `ai_provider_config` MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '记录更新时间（UTC）';
ALTER TABLE `ai_provider_config` MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；修改时校验防止并发覆盖';
ALTER TABLE `rule_version` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `rule_version` MODIFY COLUMN content_sha256 CHAR(64) NOT NULL COMMENT '已发布内容SHA-256摘要';
ALTER TABLE `rule_version` MODIFY COLUMN published_at DATETIME(3) COMMENT '发布时间（UTC）；未发布为空';
ALTER TABLE `rule_version` MODIFY COLUMN created_by VARCHAR(100) COMMENT '创建人或任务标识';
ALTER TABLE `rule_version` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `rule_hit` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `rule_hit` MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '所属统一产品ID，关联product';
ALTER TABLE `rule_hit` MODIFY COLUMN platform_version_id BIGINT NOT NULL COMMENT '所属平台版本ID，关联platform_version';
ALTER TABLE `rule_hit` MODIFY COLUMN rule_version_id BIGINT NOT NULL COMMENT '本次计算或报告冻结使用的规则版本ID';
ALTER TABLE `rule_hit` MODIFY COLUMN chart_id BIGINT NOT NULL COMMENT '榜单定义ID，关联chart_definition';
ALTER TABLE `rule_hit` MODIFY COLUMN business_date DATE NOT NULL COMMENT '业务统计日（Asia/Shanghai），缺失不得用其他日期回填';
ALTER TABLE `rule_hit` MODIFY COLUMN signal_type VARCHAR(40) NOT NULL COMMENT '市场信号类型，如NEW_OBSERVED、RAPID_RISE、SUSTAINED';
ALTER TABLE `rule_hit` MODIFY COLUMN reason TEXT NOT NULL COMMENT '判定或操作的可读理由';
ALTER TABLE `rule_hit` MODIFY COLUMN metrics JSON NOT NULL COMMENT '运行计数、耗时及覆盖指标JSON';
ALTER TABLE `rule_hit` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `rule_hit_snapshot` MODIFY COLUMN rule_hit_id BIGINT NOT NULL COMMENT '确定性规则命中ID，关联rule_hit';
ALTER TABLE `rule_hit_snapshot` MODIFY COLUMN snapshot_id BIGINT NOT NULL COMMENT '具体榜单快照ID；历史引用不随新修订变化';
ALTER TABLE `rule_hit_evidence` MODIFY COLUMN rule_hit_id BIGINT NOT NULL COMMENT '确定性规则命中ID，关联rule_hit';
ALTER TABLE `rule_hit_evidence` MODIFY COLUMN evidence_id BIGINT NOT NULL COMMENT '证据ID，关联evidence';
ALTER TABLE `product_state_history` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `product_state_history` MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '所属统一产品ID，关联product';
ALTER TABLE `product_state_history` MODIFY COLUMN pool_state VARCHAR(32) NOT NULL COMMENT '资格状态：PENDING_IDENTIFICATION待识别、OBSERVING观察、FORMAL正式、EXCLUDED排除';
ALTER TABLE `product_state_history` MODIFY COLUMN rule_hit_id BIGINT NULL COMMENT '确定性规则命中ID，关联rule_hit';
ALTER TABLE `product_state_history` MODIFY COLUMN reason TEXT NOT NULL COMMENT '判定或操作的可读理由';
ALTER TABLE `product_state_history` MODIFY COLUMN effective_at DATETIME(3) NOT NULL COMMENT '结论或事件生效时间（UTC）';
ALTER TABLE `product_state_history` MODIFY COLUMN recorded_at DATETIME(3) NOT NULL COMMENT '状态历史实际登记时间（UTC）';
ALTER TABLE `product_state_history` MODIFY COLUMN created_by VARCHAR(100) COMMENT '创建人或任务标识';
ALTER TABLE `field_override` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `field_override` MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '所属统一产品ID，关联product';
ALTER TABLE `field_override` MODIFY COLUMN platform_version_id BIGINT NULL COMMENT '所属平台版本ID，关联platform_version';
ALTER TABLE `field_override` MODIFY COLUMN field_name VARCHAR(80) NOT NULL COMMENT '被人工覆盖的字段名称';
ALTER TABLE `field_override` MODIFY COLUMN value_json JSON NOT NULL COMMENT '人工覆盖值JSON，独立于原始采集字段';
ALTER TABLE `field_override` MODIFY COLUMN reason TEXT NOT NULL COMMENT '判定或操作的可读理由';
ALTER TABLE `field_override` MODIFY COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用该配置';
ALTER TABLE `field_override` MODIFY COLUMN effective_at DATETIME(3) NOT NULL COMMENT '结论或事件生效时间（UTC）';
ALTER TABLE `field_override` MODIFY COLUMN supersedes_id BIGINT NULL COMMENT '被此记录取代的旧记录ID；保留旧记录';
ALTER TABLE `field_override` MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；修改时校验防止并发覆盖';
ALTER TABLE `field_override` MODIFY COLUMN created_by VARCHAR(100) NOT NULL COMMENT '创建人或任务标识';
ALTER TABLE `field_override` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `selection_record` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `selection_record` MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '所属统一产品ID，关联product';
ALTER TABLE `selection_record` MODIFY COLUMN reference_version_id BIGINT NULL COMMENT '选用参考的平台版本ID；可为空，非空须属于该产品';
ALTER TABLE `selection_record` MODIFY COLUMN note VARCHAR(1000) COMMENT '选用备注；不影响市场资格';
ALTER TABLE `selection_record` MODIFY COLUMN created_by VARCHAR(100) NOT NULL COMMENT '创建人或任务标识';
ALTER TABLE `selection_record` MODIFY COLUMN updated_by VARCHAR(100) NOT NULL COMMENT '最后修改人标识';
ALTER TABLE `selection_record` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `selection_record` MODIFY COLUMN updated_at DATETIME(3) NOT NULL COMMENT '记录更新时间（UTC）';
ALTER TABLE `selection_record` MODIFY COLUMN version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号；修改时校验防止并发覆盖';
ALTER TABLE `weekly_report` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `weekly_report` MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT '报告状态：PUBLISHED已冻结发布；旧版保留';
ALTER TABLE `weekly_report` MODIFY COLUMN rule_version_id BIGINT NOT NULL COMMENT '本次计算或报告冻结使用的规则版本ID';
ALTER TABLE `weekly_report` MODIFY COLUMN model_metadata JSON COMMENT '模型、提示词版本及调用状态JSON；未调用须明确标记';
ALTER TABLE `weekly_report` MODIFY COLUMN generated_at DATETIME(3) COMMENT '报告生成完成时间（UTC）';
ALTER TABLE `weekly_report` MODIFY COLUMN published_at DATETIME(3) COMMENT '发布时间（UTC）；未发布为空';
ALTER TABLE `report_item` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `report_item` MODIFY COLUMN report_id BIGINT NOT NULL COMMENT '所属周报ID，关联weekly_report';
ALTER TABLE `report_item` MODIFY COLUMN product_id BIGINT NOT NULL COMMENT '所属统一产品ID，关联product';
ALTER TABLE `report_item` MODIFY COLUMN main_reference_version_id BIGINT NOT NULL COMMENT '报告冻结的主要参考平台版本ID';
ALTER TABLE `report_item` MODIFY COLUMN display_order INT NOT NULL COMMENT '报告内展示顺序，从1开始';
ALTER TABLE `identity_link_event` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `identity_link_event` MODIFY COLUMN platform_version_id BIGINT NOT NULL COMMENT '所属平台版本ID，关联platform_version';
ALTER TABLE `identity_link_event` MODIFY COLUMN from_product_id BIGINT NOT NULL COMMENT '身份关联前所属产品ID';
ALTER TABLE `identity_link_event` MODIFY COLUMN to_product_id BIGINT NOT NULL COMMENT '身份关联后所属产品ID';
ALTER TABLE `identity_link_event` MODIFY COLUMN reverts_event_id BIGINT NULL COMMENT '本事件撤销的原始身份关联事件ID';
ALTER TABLE `identity_link_event` MODIFY COLUMN evidence_refs JSON COMMENT '支撑操作的证据引用JSON';
ALTER TABLE `identity_link_event` MODIFY COLUMN reason TEXT NOT NULL COMMENT '判定或操作的可读理由';
ALTER TABLE `identity_link_event` MODIFY COLUMN effective_at DATETIME(3) NOT NULL COMMENT '结论或事件生效时间（UTC）';
ALTER TABLE `identity_link_event` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `identity_link_event` MODIFY COLUMN created_by VARCHAR(100) NOT NULL COMMENT '创建人或任务标识';
ALTER TABLE `audit_event` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `audit_event` MODIFY COLUMN actor_id VARCHAR(100) NOT NULL COMMENT '操作者身份标识';
ALTER TABLE `audit_event` MODIFY COLUMN action VARCHAR(80) NOT NULL COMMENT '审计操作名称';
ALTER TABLE `audit_event` MODIFY COLUMN entity_type VARCHAR(80) NOT NULL COMMENT '被操作实体类型';
ALTER TABLE `audit_event` MODIFY COLUMN entity_id VARCHAR(100) NOT NULL COMMENT '被操作实体ID，字符串形式';
ALTER TABLE `audit_event` MODIFY COLUMN before_json JSON COMMENT '操作前脱敏数据快照';
ALTER TABLE `audit_event` MODIFY COLUMN after_json JSON COMMENT '操作后脱敏数据快照';
ALTER TABLE `audit_event` MODIFY COLUMN request_id VARCHAR(100) COMMENT '请求追踪ID，便于关联日志';
ALTER TABLE `audit_event` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `idempotency_record` MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本表主键ID';
ALTER TABLE `idempotency_record` MODIFY COLUMN actor_id VARCHAR(100) NOT NULL COMMENT '操作者身份标识';
ALTER TABLE `idempotency_record` MODIFY COLUMN operation VARCHAR(100) NOT NULL COMMENT '幂等操作名称';
ALTER TABLE `idempotency_record` MODIFY COLUMN request_key VARCHAR(180) NOT NULL COMMENT '客户端幂等请求键';
ALTER TABLE `idempotency_record` MODIFY COLUMN request_sha256 CHAR(64) NOT NULL COMMENT '请求负载SHA-256；同键不同负载应报冲突';
ALTER TABLE `idempotency_record` MODIFY COLUMN status VARCHAR(20) NOT NULL COMMENT '幂等请求处理状态';
ALTER TABLE `idempotency_record` MODIFY COLUMN response_json JSON COMMENT '首次处理的响应JSON，用于重试复用';
ALTER TABLE `idempotency_record` MODIFY COLUMN created_at DATETIME(3) NOT NULL COMMENT '记录创建时间（UTC）';
ALTER TABLE `idempotency_record` MODIFY COLUMN expires_at DATETIME(3) NOT NULL COMMENT '记录有效期截止时间（UTC）';

-- V6：小游戏公开观察与来源图标（26张业务表）。
-- 公开页面仅提供名称时，独立存为榜单观察材料，不冒充平台 appid 或产品身份。
CREATE TABLE public_chart_observation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '公开榜单材料ID',
 platform VARCHAR(24) NOT NULL COMMENT '平台：WECHAT或DOUYIN',
 board_type VARCHAR(24) NOT NULL COMMENT '来源榜单口径：POPULARITY人气榜',
 business_date DATE NOT NULL COMMENT '来源页面显示日期；缺日不回填',
 source_url VARCHAR(500) NOT NULL COMMENT '公开来源页面URL',
 raw_sha256 CHAR(64) NOT NULL COMMENT '原始渲染HTML的SHA256',
 raw_locator TEXT NOT NULL COMMENT '原始渲染HTML存储路径',
 entries_json JSON NOT NULL COMMENT '公开可见条目、原始名次、名称、图标、发行方；不代表产品身份',
 actual_count INT NOT NULL COMMENT '实际公开可见行数，不能宣称Top100完整',
 captured_at DATETIME(3) NOT NULL COMMENT '材料接收时间UTC；来源时区未声明',
 UNIQUE KEY uq_public_material(platform,board_type,business_date,raw_sha256),
 KEY idx_public_day(platform,board_type,business_date)
) COMMENT='小游戏公开榜单观察：身份待核验，独立于正式产品和规则';
CREATE TABLE snapshot_media (
 snapshot_id BIGINT NOT NULL COMMENT '原始已发布快照ID',
 platform_version_id BIGINT NOT NULL COMMENT '平台版本ID；图标不可跨平台套用',
 source_url TEXT NOT NULL COMMENT '原始材料中的缩略图地址',
 raw_sha256 CHAR(64) NOT NULL COMMENT '提取该图标的原始材料哈希',
 extracted_at DATETIME(3) NOT NULL COMMENT '解析图标时间UTC；不改写历史快照',
 PRIMARY KEY(snapshot_id,platform_version_id),
 FOREIGN KEY(snapshot_id) REFERENCES ranking_snapshot(id),
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id)
) COMMENT='快照缩略图引用：来源材料派生信息，不修改排名或历史报告';

-- V7: 每周市场洞察与不可变历史
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


-- 增量DDL参考：V8__research_workspace.sql；实际安装由Flyway执行。
CREATE TABLE research_profile (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '研究方案版本ID',
 revision INT NOT NULL UNIQUE COMMENT '递增版本号，已发布参数不可覆盖',
 profile_json JSON NOT NULL COMMENT '六维权重、数量、工期与分层阈值',
 content_sha256 CHAR(64) NOT NULL COMMENT '方案JSON哈希',
 created_by VARCHAR(100) NOT NULL COMMENT '登记人',
 created_at DATETIME(3) NOT NULL COMMENT '系统登记时间UTC'
) COMMENT='当前研究方案的不可变发布记录';
INSERT INTO research_profile(revision,profile_json,content_sha256,created_by,created_at)
VALUES(1,'{"weights":[25,15,20,20,10,10],"target":8,"familyCap":2,"budget":20,"maxReviewWidth":0.3,"z":1.96,"growthScale":0.3,"explorationSlots":2,"minTopDays":7,"minCoverage":0.75,"maxUtilityWidth":25}',
SHA2('research-default-2.2.0',256),'migration',UTC_TIMESTAMP(3));
CREATE TABLE research_packet (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '人工研究材料修订ID',
 platform_version_id BIGINT NOT NULL COMMENT '明确归属的平台版本ID',
 market VARCHAR(8) NOT NULL COMMENT '独立市场代码CN、US、GB、DE、FR',
 effective_date DATE NOT NULL COMMENT '材料适用业务日起点，非登记日期',
 valid_until DATE NOT NULL COMMENT '适用期最后业务日，过期不沿用当前结论',
 available_at DATETIME(3) NOT NULL COMMENT '系统实际获知时间UTC，服务端赋值防回放泄漏',
 payload JSON NOT NULL COMMENT '证据原文定位、人工核验、玩法、评价与成本；不接受综合分',
 content_sha256 CHAR(64) NOT NULL COMMENT '规范化负载哈希，同材料幂等',
 created_by VARCHAR(100) NOT NULL COMMENT '实名或可追溯登记人，非系统认证身份',
 UNIQUE KEY uq_research_packet(platform_version_id,market,content_sha256),
 KEY idx_research_asof(platform_version_id,market,effective_date,available_at),
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id)
) COMMENT='版本市场研究材料，事实与人工判断分字段，追加修订不覆盖';
CREATE TABLE research_constraint (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '已知约束ID',
 platform_version_id BIGINT NOT NULL COMMENT '约束适用平台版本',
 market VARCHAR(8) NOT NULL COMMENT '约束适用市场',
 packet_id BIGINT NOT NULL COMMENT '约束首次材料修订',
 code VARCHAR(40) NOT NULL COMMENT 'MAJOR_EVENT、COST_FLOOR_OVER、NON_IAA',
 detail_json JSON NOT NULL COMMENT '成本下界或事件原文、引用、作用范围',
 effective_date DATE NOT NULL COMMENT '约束生效业务日',
 created_at DATETIME(3) NOT NULL COMMENT '系统知道约束的时间UTC',
 resolved_at DATETIME(3) NULL COMMENT '解除登记时间UTC，未解除持续有效',
 resolution_json JSON NULL COMMENT '解除人、原因、新证据引用和生效日',
 UNIQUE KEY uq_constraint_packet(packet_id,code),
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id),
 FOREIGN KEY(packet_id) REFERENCES research_packet(id)
) COMMENT='已知负面约束独立留存，来源失效或新材料缺失不能撤销';
CREATE TABLE research_outcome (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '实验反馈记录ID',
 selection_id BIGINT NOT NULL COMMENT '对应独立选用记录',
 request_key VARCHAR(100) NOT NULL UNIQUE COMMENT '反馈幂等键',
 payload JSON NOT NULL COMMENT '假设、协议、样本、窗口、结果、成本和未知原因',
 content_sha256 CHAR(64) NOT NULL COMMENT '请求内容哈希，同键不同内容拒绝',
 created_by VARCHAR(100) NOT NULL COMMENT '反馈登记人',
 created_at DATETIME(3) NOT NULL COMMENT '反馈登记时间UTC',
 FOREIGN KEY(selection_id) REFERENCES selection_record(id)
) COMMENT='选用后实验反馈追加记录，制作完成不等同验证通过';
ALTER TABLE weekly_report
 ADD COLUMN research_profile_id BIGINT NULL COMMENT '生成使用的研究方案版本',
 ADD COLUMN research_snapshot JSON NULL COMMENT '冻结研究输入、分层、补证、健康和完整组合输出',
 ADD COLUMN available_cutoff DATETIME(3) NULL COMMENT '本次可用材料截止UTC；回放不得使用此后材料';


-- 增量DDL参考：V9__game_profiles.sql；实际安装由Flyway执行。
CREATE TABLE market_listing (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '平台发行市场ID，与应用版本区分',
 tenant_id VARCHAR(64) NOT NULL COMMENT '组织标识，服务端限定',
 platform_version_id BIGINT NOT NULL COMMENT '沿用既有平台实体稳定ID',
 market VARCHAR(8) NOT NULL COMMENT '发行市场CN、US、GB、DE、FR',
 language VARCHAR(20) NOT NULL COMMENT '资料语言，不跨语言混合评价',
 source_id BIGINT NULL COMMENT '最初确认市场的来源ID',
 first_observed_at DATETIME(3) NOT NULL COMMENT '该市场首次观察UTC',
 available_at DATETIME(3) NOT NULL COMMENT '系统获知该发行范围的UTC时间',
 UNIQUE KEY uq_listing(tenant_id,platform_version_id,market,language),
 FOREIGN KEY(platform_version_id) REFERENCES platform_version(id)
) COMMENT='组织内平台实体的独立市场发行范围';
CREATE TABLE release_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '应用发行版本ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '独立市场发行范围',
 app_version VARCHAR(100) NOT NULL COMMENT '来源或人工核验的应用版本号',
 released_at DATETIME(3) NOT NULL COMMENT '该版本生效或观察时间UTC',
 available_at DATETIME(3) NOT NULL COMMENT '系统获知时间UTC，历史回放门槛',
 evidence_id BIGINT NULL COMMENT '版本依据ID',
 UNIQUE KEY uq_release(tenant_id,listing_id,app_version),
 KEY idx_release_time(listing_id,available_at,released_at),
 FOREIGN KEY(listing_id) REFERENCES market_listing(id)
) COMMENT='应用更新历史；不以平台实体ID代替应用版本';
CREATE TABLE profile_evidence (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '档案证据ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '证据所属发行范围',
 source_name VARCHAR(200) NOT NULL COMMENT '来源名称',
 origin_family VARCHAR(200) NOT NULL COMMENT '同源家族，转载不当独立来源',
 source_url VARCHAR(2000) NULL COMMENT '仅http或https外链，不自动抓取',
 locator VARCHAR(1000) NOT NULL COMMENT '原文或片段定位',
 excerpt TEXT NOT NULL COMMENT '权限允许保留的原文摘要',
 content_sha256 CHAR(64) NOT NULL COMMENT '来源摘要及定位内容哈希',
 rights VARCHAR(32) NOT NULL COMMENT 'public_excerpt、authorized、internal、not_authorized',
 observed_at DATETIME(3) NOT NULL COMMENT '实际观察UTC时间',
 available_at DATETIME(3) NOT NULL COMMENT '服务器登记UTC时间',
 is_synthetic BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否模拟材料，隔离于真实推荐',
 FOREIGN KEY(listing_id) REFERENCES market_listing(id)
) COMMENT='档案证据、来源权限与材料定位';
CREATE TABLE profile_field_observation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '追加式字段观察ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '市场及语言范围',
 release_id BIGINT NULL COMMENT '版本敏感字段适用版本',
 field_key VARCHAR(64) NOT NULL COMMENT '受81项字段定义目录约束',
 typed_value JSON NULL COMMENT 'Java验证后的类型值，缺失为NULL',
 data_status VARCHAR(32) NOT NULL COMMENT 'available、missing、not_authorized、not_supported、stale、conflict、not_applicable',
 value_kind VARCHAR(24) NOT NULL COMMENT 'observed、manual、estimated、derived',
 observed_at DATETIME(3) NOT NULL COMMENT '实际观察UTC时间',
 available_at DATETIME(3) NOT NULL COMMENT '服务器首次可用UTC时间',
 valid_until DATETIME(3) NULL COMMENT '有效期UTC，不预设统一七天',
 evidence_id BIGINT NOT NULL COMMENT '该字段证据ID',
 reviewer VARCHAR(100) NOT NULL COMMENT '可追溯复核人',
 reason VARCHAR(2000) NOT NULL COMMENT '核查、冲突、不适用或修订原因',
 supersedes_id BIGINT NULL COMMENT '显式裁决替代的旧观察ID，不静默覆盖',
 definition_version VARCHAR(40) NOT NULL COMMENT '字段定义版本',
 KEY idx_profile_field(tenant_id,listing_id,field_key,available_at),
 FOREIGN KEY(listing_id) REFERENCES market_listing(id),
 FOREIGN KEY(release_id) REFERENCES release_record(id),
 FOREIGN KEY(evidence_id) REFERENCES profile_evidence(id),
 FOREIGN KEY(supersedes_id) REFERENCES profile_field_observation(id)
) COMMENT='已复核事实与人工核查的时序观察';
CREATE TABLE profile_claim (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '分析草案ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '平台市场语言范围',
 release_id BIGINT NULL COMMENT '适用应用版本',
 field_key VARCHAR(64) NOT NULL COMMENT '字段定义键',
 typed_value JSON NOT NULL COMMENT '经类型校验的分析假设',
 evidence_id BIGINT NOT NULL COMMENT '输入材料引用',
 observed_at DATETIME(3) NOT NULL COMMENT '输入材料观察时间UTC',
 available_at DATETIME(3) NOT NULL COMMENT '草案实际形成时间UTC',
 valid_until DATETIME(3) NULL COMMENT '草案有效期UTC',
 model_metadata JSON NOT NULL COMMENT '模型、提示、schema、输入哈希和弃权信息',
 status VARCHAR(24) NOT NULL DEFAULT 'draft' COMMENT '草案状态，不能自动提升资格',
 FOREIGN KEY(listing_id) REFERENCES market_listing(id),
 FOREIGN KEY(evidence_id) REFERENCES profile_evidence(id)
) COMMENT='AI或分析草案独立保存，不混入审核事实';
CREATE TABLE profile_material_submission (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '待复核材料ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '目标发行范围',
 request_key VARCHAR(100) NOT NULL COMMENT '提交幂等键',
 input_sha256 CHAR(64) NOT NULL COMMENT '完整提交内容哈希',
 field_key VARCHAR(64) NOT NULL COMMENT '目标字段',
 payload JSON NOT NULL COMMENT '类型值、原文、来源、版本、权限、提交人',
 status VARCHAR(24) NOT NULL COMMENT 'pending_review、approved、rejected、conflict、needs_material',
 created_at DATETIME(3) NOT NULL COMMENT '服务器收到材料时间UTC',
 revision INT NOT NULL DEFAULT 0 COMMENT '并发复核乐观锁',
 UNIQUE KEY uq_submission(tenant_id,request_key),
 FOREIGN KEY(listing_id) REFERENCES market_listing(id)
) COMMENT='材料草稿及复核状态，提交不改变字段事实';
CREATE TABLE profile_material_review (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '复核审计ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 submission_id BIGINT NOT NULL COMMENT '被复核材料ID',
 reviewer VARCHAR(100) NOT NULL COMMENT '复核人',
 decision VARCHAR(24) NOT NULL COMMENT '批准、拒绝、冲突或补材料决策',
 reason VARCHAR(2000) NOT NULL COMMENT '原文、范围、权限与冲突检查说明',
 observation_id BIGINT NULL COMMENT '批准或冲突形成的追加式观察ID',
 created_at DATETIME(3) NOT NULL COMMENT '复核登记UTC时间',
 FOREIGN KEY(submission_id) REFERENCES profile_material_submission(id)
) COMMENT='材料复核操作的不可变审计历史';
CREATE TABLE profile_enrichment_task (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '补全任务ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '平台市场语言范围',
 domain_key VARCHAR(40) NOT NULL COMMENT '信息域任务分组',
 field_keys JSON NOT NULL COMMENT '本次任务对应的缺口字段',
 planned_cutoff DATE NOT NULL COMMENT '任务生成的知识截止日',
 gap_sha256 CHAR(64) NOT NULL COMMENT '缺口版本内容哈希',
 owner_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '负责人',
 priority VARCHAR(8) NOT NULL COMMENT 'P0、P1、P2',
 status VARCHAR(24) NOT NULL COMMENT 'pending、in_progress、pending_review、unavailable、completed',
 note VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '进度、不可得原因或结果',
 retry_condition VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '暂不可得时的复查条件',
 revision INT NOT NULL DEFAULT 0 COMMENT '乐观锁修订号',
 created_at DATETIME(3) NOT NULL COMMENT '生成时间UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '最后进度更新时间UTC',
 KEY idx_task_scope(tenant_id,listing_id,domain_key,status),
 FOREIGN KEY(listing_id) REFERENCES market_listing(id)
) COMMENT='缺口补全与负责人进度，任务状态不改变事实';
CREATE TABLE profile_media_asset (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '媒体资源ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属组织',
 listing_id BIGINT NOT NULL COMMENT '平台市场语言范围',
 release_id BIGINT NULL COMMENT '对应应用版本',
 evidence_id BIGINT NOT NULL COMMENT '素材来源与权限证据',
 media_kind VARCHAR(32) NOT NULL COMMENT 'gameplay_screenshot、gameplay_video、ad_video、diagram',
 source_url VARCHAR(2000) NULL COMMENT '外部来源链接，不充当已缓存资源',
 asset_ref VARCHAR(1000) NULL COMMENT '受控文件或对象存储引用',
 thumbnail_ref VARCHAR(1000) NULL COMMENT '受控缩略图引用',
 content_sha256 CHAR(64) NULL COMMENT '实际文件哈希，未下载时为空',
 status VARCHAR(32) NOT NULL COMMENT 'external_only、available、missing、not_authorized、stale',
 observed_at DATETIME(3) NOT NULL COMMENT '拍摄或发布观察时间UTC',
 available_at DATETIME(3) NOT NULL COMMENT '系统可用时间UTC',
 FOREIGN KEY(listing_id) REFERENCES market_listing(id),
 FOREIGN KEY(evidence_id) REFERENCES profile_evidence(id)
) COMMENT='实机与广告素材独立记录，缺失不生成伪图片';


-- 增量DDL参考：V10__selection_listing_scope.sql；实际安装由Flyway执行。
ALTER TABLE selection_record
 ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '所属工作区，旧记录归默认组织',
 ADD COLUMN reference_listing_id BIGINT NULL COMMENT '参考平台市场语言条目；旧记录未知不回填',
 ADD KEY idx_selection_tenant(tenant_id,product_id,owner_name),
 ADD CONSTRAINT fk_selection_listing FOREIGN KEY(reference_listing_id) REFERENCES market_listing(id);

-- 增量DDL参考：V11__profile_ai_runs.sql；实际安装由Flyway执行。
CREATE TABLE profile_ai_run (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '授权材料AI提取运行ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '服务端工作区',
 listing_id BIGINT NOT NULL COMMENT '固定目标平台市场语言范围',
 evidence_id BIGINT NOT NULL COMMENT '唯一授权输入证据',
 field_keys JSON NOT NULL COMMENT '允许提取的字段白名单',
 model VARCHAR(200) NOT NULL COMMENT '模型标识，不含供应商密钥',
 prompt_version VARCHAR(40) NOT NULL COMMENT '固定提示契约版本',
 schema_version VARCHAR(40) NOT NULL COMMENT '模型输出Schema版本',
 input_sha256 CHAR(64) NOT NULL COMMENT '范围、摘录、模型及字段输入hash',
 output_sha256 CHAR(64) NULL COMMENT '经校验输出hash，失败时空',
 status VARCHAR(24) NOT NULL COMMENT 'RUNNING、SUCCEEDED、ABSTAINED、FAILED或INTERRUPTED',
 actor VARCHAR(100) NOT NULL COMMENT '发起人填报',
 result_json JSON NULL COMMENT '材料ID、未知字段、失败代码和重试条件；不含原始供应商响应',
 created_at DATETIME(3) NOT NULL COMMENT '实际发起UTC时间',
 finished_at DATETIME(3) NULL COMMENT '完成UTC时间',
 KEY idx_profile_ai_scope(tenant_id,listing_id,created_at),
 FOREIGN KEY(listing_id) REFERENCES market_listing(id),
 FOREIGN KEY(evidence_id) REFERENCES profile_evidence(id)
) COMMENT='授权材料AI提取审计，失败不修改档案事实';
-- 增量DDL参考：V12__collection_source_registry.sql；实际安装由Flyway执行。
CREATE TABLE source_definition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '采集来源登记ID', source_id VARCHAR(32) NOT NULL COMMENT '设计包稳定来源编号', definition_version VARCHAR(40) NOT NULL COMMENT '来源定义版本', name VARCHAR(200) NOT NULL COMMENT '来源名称', origin_family VARCHAR(100) NOT NULL COMMENT '原始来源族', platform VARCHAR(64) NOT NULL COMMENT '平台或来源分类', region VARCHAR(64) NOT NULL COMMENT '来源语境市场', verification_level VARCHAR(40) NOT NULL COMMENT '来源核验级别', state VARCHAR(40) NOT NULL COMMENT '登记状态', definition_json JSON NOT NULL COMMENT '字段、入口、深度、限制和策略', production_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否允许生产计划', enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前配置是否启用', created_at DATETIME(3) NOT NULL COMMENT '登记时间UTC', updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC', UNIQUE KEY uq_source_definition(source_id,definition_version), KEY idx_source_state(state,platform,region)
) COMMENT='V2.7来源能力与权限边界登记；不代表真实采集成功';
CREATE TABLE collection_task_definition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '采集任务定义ID', tenant_id VARCHAR(64) NOT NULL COMMENT '所属工作区', source_id VARCHAR(32) NOT NULL COMMENT '来源登记编号', task_type VARCHAR(40) NOT NULL COMMENT '任务类型', schedule_json JSON NOT NULL COMMENT '业务日、时区、频率、范围和限流策略', state VARCHAR(24) NOT NULL COMMENT 'DRAFT、DISABLED或ENABLED', revision INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本', created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC', updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC', UNIQUE KEY uq_collection_task(tenant_id,source_id,task_type), KEY idx_collection_task_state(state,source_id)
) COMMENT='采集计划模板；任务启用不等于上游数据可用';

-- V13: 独立外部榜单采集；已有数据库由 Flyway 增量迁移。
CREATE TABLE external_capture_run (
 id CHAR(36) PRIMARY KEY COMMENT '每次真实执行的UUID',
 batch_id CHAR(36) NOT NULL COMMENT '同次批量采集编号',
 tenant_id VARCHAR(64) NOT NULL COMMENT '工作区，由服务端指定',
 source_key VARCHAR(80) NOT NULL COMMENT '稳定来源键，如gravity-wechat',
 chart_key VARCHAR(64) NOT NULL COMMENT '来源内榜单键，如popularity',
 region VARCHAR(16) NOT NULL COMMENT '请求地区',
 requested_top_n INT NOT NULL COMMENT '请求前N名，不代表实际覆盖',
 requested_date DATE NOT NULL COMMENT '运行请求日，不能覆盖来源日期',
 status VARCHAR(24) NOT NULL COMMENT 'RUNNING/SUCCEEDED/PARTIAL/FAILED/INTERRUPTED',
 stage VARCHAR(32) NOT NULL COMMENT 'FETCHING/FETCHED/STORED/FAILED/EXPIRED',
 freshness VARCHAR(16) NULL COMMENT 'CURRENT/STALE/UNKNOWN，独立于覆盖状态',
 active_scope CHAR(64) NULL COMMENT '运行互斥键，结束清空；仅限制同工作区来源榜单地区',
 lease_until DATETIME(3) NULL COMMENT '互斥有效期UTC；崩溃任务10分钟后可恢复',
 source_url TEXT NOT NULL COMMENT '固定公开榜单URL，不含凭据',
 captured_at DATETIME(3) NULL COMMENT '本次材料实际取得时间UTC',
 raw_sha256 CHAR(64) NULL COMMENT '本次原始字节SHA256',
 raw_locator TEXT NULL COMMENT '原始材料相对采集根目录路径',
 observation_id BIGINT NULL COMMENT '本次产生或复用的来源观察ID，无产品关联',
 reused BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否复用同范围同材料观察',
 result_json JSON NULL COMMENT '覆盖、来源日与文件交付摘要',
 error_code VARCHAR(80) NULL COMMENT '脱敏稳定错误码',
 started_at DATETIME(3) NOT NULL COMMENT '运行开始时间UTC',
 finished_at DATETIME(3) NULL COMMENT '运行结束时间UTC',
 UNIQUE KEY uq_external_capture_active(active_scope),
 KEY idx_external_capture_runs(tenant_id,started_at),
 KEY idx_external_capture_batch(tenant_id,batch_id)
) COMMENT='外部榜单真实执行日志；失败不会清空已成功的观察';

CREATE TABLE external_ranking_observation (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '不可变来源观察ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属工作区',
 dedupe_key CHAR(64) NOT NULL COMMENT '工作区来源榜单地区TopN解析器版本源日期及材料哈希去重键',
 source_key VARCHAR(80) NOT NULL COMMENT '来源适配器键',
 origin_family VARCHAR(80) NOT NULL COMMENT '原始来源族apple或gravity-engine等',
 source_authority VARCHAR(24) NOT NULL COMMENT 'OFFICIAL或THIRD_PARTY',
 chart_key VARCHAR(64) NOT NULL COMMENT '来源内榜单键',
 board_name VARCHAR(200) NOT NULL COMMENT '原榜名',
 platform VARCHAR(32) NOT NULL COMMENT 'WECHAT/DOUYIN/IOS/GOOGLE_PLAY',
 region VARCHAR(16) NOT NULL COMMENT '来源地区范围',
 source_date DATE NULL COMMENT '来源显示日期，未提供则空',
 business_date DATE NULL COMMENT '按来源时间策略得到的业务日',
 captured_at DATETIME(3) NOT NULL COMMENT '首次取得这份材料的时间UTC',
 available_at DATETIME(3) NOT NULL COMMENT '本系统保存该观察的时间UTC',
 parser_version VARCHAR(64) NOT NULL COMMENT '确定性解析器版本',
 completeness VARCHAR(32) NOT NULL COMMENT 'REQUESTED_RANGE_COMPLETE或PARTIAL，完整仅针对请求范围',
 requested_top_n INT NOT NULL COMMENT '请求前N名',
 actual_count INT NOT NULL COMMENT '实际观察行数',
 max_observed_rank INT NOT NULL COMMENT '最大可见名次，不表示连续覆盖',
 contiguous_top_n INT NOT NULL COMMENT '从第1名开始连续覆盖数',
 source_url TEXT NOT NULL COMMENT '公开来源URL',
 raw_sha256 CHAR(64) NOT NULL COMMENT '原始字节SHA256',
 raw_locator TEXT NOT NULL COMMENT '原始材料相对采集根目录路径',
 bundle_json JSON NOT NULL COMMENT '来源观察契约1.1完整内容含缺口证据和警告',
 UNIQUE KEY uq_external_observation(dedupe_key),
 KEY idx_external_observation_scope(tenant_id,platform,region,business_date),
 KEY idx_external_observation_chart(tenant_id,source_key,chart_key,business_date)
) COMMENT='独立外部来源观察，不自动合并或写入产品库';

CREATE TABLE external_ranking_entry (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '来源榜单明细ID',
 observation_id BIGINT NOT NULL COMMENT '所属不可变观察',
 rank_position INT NOT NULL COMMENT '来源真实名次，缺口不补齐',
 external_id VARCHAR(200) COLLATE utf8mb4_bin NULL COMMENT '来源明确提供的平台ID，未知为空',
 name VARCHAR(500) NOT NULL COMMENT '来源名称，不能作为跨来源唯一身份',
 developer VARCHAR(500) NULL COMMENT '来源显示开发商或发行方，未知为空',
 evidence_locator TEXT NOT NULL COMMENT '原始材料中的JSON路径或DOM定位',
 source_fields JSON NOT NULL COMMENT '标签、简介、变化原文、图片引用等来源字段',
 UNIQUE KEY uq_external_entry_rank(observation_id,rank_position),
 FOREIGN KEY(observation_id) REFERENCES external_ranking_observation(id)
) COMMENT='外部榜单逐行事实，保持来源口径和未知平台身份';


-- V14 capture workspace reference
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

-- V15 来源候选选用
CREATE TABLE source_candidate_selection (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '来源候选研究记录ID，不是平台产品身份',
 tenant_id VARCHAR(100) NOT NULL COMMENT '服务端组织范围',
 request_key VARCHAR(64) NOT NULL COMMENT '同一次填报的幂等请求标识',
 observation_id BIGINT NOT NULL COMMENT '填报参考的不可变来源观察',
 rank_position INT NOT NULL COMMENT '参考观察中的真实名次',
 owner VARCHAR(100) NOT NULL COMMENT '研究负责人，可多人分别填报',
 status VARCHAR(20) NOT NULL COMMENT 'SELECTED已选用、TESTING研究测试中、COMPLETED已完成、ABANDONED已放弃',
 note VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '研究目的、验证计划或结论备注',
 source_snapshot JSON NOT NULL COMMENT '填报时来源名称、平台、榜单及证据快照；IAA仍未知',
 created_at DATETIME(3) NOT NULL COMMENT '服务器UTC创建时间',
 UNIQUE KEY uq_source_selection_request(tenant_id,request_key),
 KEY idx_source_selection_time(tenant_id,created_at),
 FOREIGN KEY(observation_id,rank_position) REFERENCES external_ranking_entry(observation_id,rank_position)
) COMMENT='来源候选多人研究选用记录，不提升正式产品或IAA资格';

-- V16: unified source identity and scoped gameplay review
CREATE TABLE source_entity_review_head (
 tenant_id VARCHAR(100) NOT NULL COMMENT '服务端组织范围',
 entity_sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '来源平台市场及稳定ID或名称发行方的身份键哈希',
 revision BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发修订号；事务内锁定',
 PRIMARY KEY(tenant_id,entity_sha256)
) COMMENT='来源身份及玩法核验的并发控制，不保存历史结论';

CREATE TABLE source_entity_review_event (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '不可变核验事件ID',
 tenant_id VARCHAR(100) NOT NULL COMMENT '服务端组织范围',
 entity_sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '来源身份键哈希，不是平台appid',
 revision BIGINT NOT NULL COMMENT '该来源身份的事件修订号',
 request_key VARCHAR(64) NOT NULL COMMENT '本次操作幂等键，多项关联共享一个键',
 request_sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '请求正文哈希，阻止幂等键用于不同操作',
 observation_id BIGINT NOT NULL COMMENT '核验参考的真实来源观察',
 rank_position INT NOT NULL COMMENT '来源观察内的真实条目位置',
 action VARCHAR(32) NOT NULL COMMENT 'LINK关联、UNLINK撤销关联、CLASSIFY核验玩法、CLEAR_GAMEPLAY撤销玩法',
 product_id BIGINT NULL COMMENT '确认关联的统一产品；空值代表明确未关联',
 identity_reviewed BOOLEAN NOT NULL COMMENT '是否有人工身份决定；撤销后仍阻止自动关联恢复',
 gameplay_family VARCHAR(80) NULL COMMENT '该来源平台版本核验的玩法；不跨平台传播',
 effective_date DATE NOT NULL COMMENT '结论生效的北京时间业务日',
 evidence JSON NOT NULL COMMENT '核验引用地址、原文摘录与判断原因',
 actor VARCHAR(100) NOT NULL COMMENT '人工核对人，不是登录用户体系',
 recorded_at DATETIME(3) NOT NULL COMMENT '服务器UTC登记时间，历史知识回放边界',
 UNIQUE KEY uq_source_review_revision(tenant_id,entity_sha256,revision),
 UNIQUE KEY uq_source_review_request(tenant_id,request_key,entity_sha256),
 KEY idx_source_review_cutoff(tenant_id,effective_date,recorded_at),
 KEY idx_source_review_product(tenant_id,product_id),
 FOREIGN KEY(observation_id,rank_position) REFERENCES external_ranking_entry(observation_id,rank_position),
 FOREIGN KEY(product_id) REFERENCES product(id)
) COMMENT='统一产品身份与来源玩法核验的追加事件；撤销新增事件，旧报告不重算';

-- V17: canonical incremental reference; do not re-run after Flyway
ALTER TABLE capture_target_config ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'SERVER' COMMENT '执行位置：SERVER服务器本地，REMOTE独立云端采集后校验入库';
UPDATE capture_target_config SET execution_mode='REMOTE',field_scope='第三方Google Play免费、付费、畅销、新免费、新付费5榜各请求Top100；公开缺行保留；云端定时采集回传',revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE source_key='appbrain';
UPDATE capture_target_config SET name='Apple · 中国游戏免费/付费/畅销榜',field_scope='官方游戏分类6014的免费、付费、畅销3榜各Top100；独立名次、Apple ID、开发者、图标、来源更新时间',revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE source_key='apple-games';

-- V18 Online web capture
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


-- V19: independent public store metrics
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
