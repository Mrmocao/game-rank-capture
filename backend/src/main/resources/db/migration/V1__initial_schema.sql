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
