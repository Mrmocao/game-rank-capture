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
