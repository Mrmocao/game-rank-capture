CREATE TABLE source_definition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '采集来源登记ID',
 source_id VARCHAR(32) NOT NULL COMMENT '设计包稳定来源编号，例如S01或S31',
 definition_version VARCHAR(40) NOT NULL COMMENT '来源定义版本',
 name VARCHAR(200) NOT NULL COMMENT '来源名称',
 origin_family VARCHAR(100) NOT NULL COMMENT '原始来源族，不把采集工具当来源',
 platform VARCHAR(64) NOT NULL COMMENT '平台或来源分类',
 region VARCHAR(64) NOT NULL COMMENT '来源语境市场，未指定不得推断玩家市场',
 verification_level VARCHAR(40) NOT NULL COMMENT '文档核对、样页可见、PoC或试运行级别',
 state VARCHAR(40) NOT NULL COMMENT '未启用、样页可见、PoC通过等登记状态',
 definition_json JSON NOT NULL COMMENT '字段、入口、深度、限制和策略的受控目录',
 production_enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否允许进入生产计划；默认关闭',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前来源配置启用状态',
 created_at DATETIME(3) NOT NULL COMMENT '登记时间UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '最近登记版本时间UTC',
 UNIQUE KEY uq_source_definition(source_id,definition_version),
 KEY idx_source_state(state,platform,region)
) COMMENT='V2.7来源能力与权限边界登记；不代表真实采集成功';

CREATE TABLE collection_task_definition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '采集任务定义ID',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属工作区',
 source_id VARCHAR(32) NOT NULL COMMENT '来源登记编号',
 task_type VARCHAR(40) NOT NULL COMMENT '榜单、详情、评论、材料或探测任务',
 schedule_json JSON NOT NULL COMMENT '业务日、时区、频率、范围和限流策略',
 state VARCHAR(24) NOT NULL COMMENT 'DRAFT、DISABLED或ENABLED',
 revision INT NOT NULL DEFAULT 0 COMMENT '任务定义乐观锁版本',
 created_at DATETIME(3) NOT NULL COMMENT '创建时间UTC',
 updated_at DATETIME(3) NOT NULL COMMENT '更新时间UTC',
 UNIQUE KEY uq_collection_task(tenant_id,source_id,task_type),
 KEY idx_collection_task_state(state,source_id)
) COMMENT='采集计划模板；任务启用不等于上游数据可用';
