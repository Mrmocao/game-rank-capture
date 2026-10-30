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
