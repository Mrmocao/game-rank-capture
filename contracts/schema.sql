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
