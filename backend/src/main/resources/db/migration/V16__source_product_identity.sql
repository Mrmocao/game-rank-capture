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
