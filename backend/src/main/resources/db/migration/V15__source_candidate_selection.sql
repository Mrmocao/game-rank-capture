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
