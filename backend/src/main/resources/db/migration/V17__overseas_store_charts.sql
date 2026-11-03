ALTER TABLE capture_target_config ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'SERVER' COMMENT '执行位置：SERVER服务器本地，REMOTE独立云端采集后校验入库';
UPDATE capture_target_config SET execution_mode='REMOTE',field_scope='第三方Google Play免费、付费、畅销、新免费、新付费5榜各请求Top100；公开缺行保留；云端定时采集回传',revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE source_key='appbrain';
UPDATE capture_target_config SET name='Apple · 中国游戏免费/付费/畅销榜',field_scope='官方游戏分类6014的免费、付费、畅销3榜各Top100；独立名次、Apple ID、开发者、图标、来源更新时间',revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE source_key='apple-games';
