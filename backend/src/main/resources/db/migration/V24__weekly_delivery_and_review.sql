CREATE TABLE weekly_report_delivery (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '研发交付记录ID',
    report_id BIGINT NOT NULL COMMENT '周报ID；同一周唯一报告',
    week_start DATE NOT NULL COMMENT '北京时间周一；研发交付所属周',
    recipient_team VARCHAR(80) NOT NULL COMMENT '接收团队，默认研发',
    status VARCHAR(24) NOT NULL COMMENT 'READY已生成/ACKNOWLEDGED已确认',
    package_json JSON NOT NULL COMMENT '冻结的研发交付包摘要，不替代周报证据',
    delivered_at DATETIME(3) NOT NULL COMMENT '交付包生成时间UTC',
    acknowledged_at DATETIME(3) NULL COMMENT '研发确认接收时间UTC',
    acknowledged_by VARCHAR(120) NULL COMMENT '确认人标识',
    note VARCHAR(500) NULL COMMENT '交付备注',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
    UNIQUE KEY uq_weekly_delivery_report_team(report_id, recipient_team),
    KEY idx_weekly_delivery_week(week_start),
    CONSTRAINT fk_weekly_delivery_report FOREIGN KEY(report_id) REFERENCES weekly_report(id)
) COMMENT='每周精选研发交付包及确认状态';

CREATE TABLE weekly_report_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '周报复盘记录ID',
    report_id BIGINT NOT NULL COMMENT '被复盘的上周报告ID',
    week_start DATE NOT NULL COMMENT '被复盘报告所属周周一',
    status VARCHAR(24) NOT NULL COMMENT 'PUBLISHED已生成/DRAFT草稿',
    review_json JSON NOT NULL COMMENT '规则总结、结果观察、证据缺口和迭代建议',
    generated_at DATETIME(3) NOT NULL COMMENT '复盘生成时间UTC',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
    UNIQUE KEY uq_weekly_review_report(report_id),
    KEY idx_weekly_review_week(week_start),
    CONSTRAINT fk_weekly_review_report FOREIGN KEY(report_id) REFERENCES weekly_report(id)
) COMMENT='每周五对上周精选规则与结果的复盘说明';
