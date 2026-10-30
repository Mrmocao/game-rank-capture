ALTER TABLE selection_record
 ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT '所属工作区，旧记录归默认组织',
 ADD COLUMN reference_listing_id BIGINT NULL COMMENT '参考平台市场语言条目；旧记录未知不回填',
 ADD KEY idx_selection_tenant(tenant_id,product_id,owner_name),
 ADD CONSTRAINT fk_selection_listing FOREIGN KEY(reference_listing_id) REFERENCES market_listing(id);
