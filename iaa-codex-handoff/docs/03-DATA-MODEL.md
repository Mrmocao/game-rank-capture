# 03 数据模型与历史契约

## 1. 关系
product 1—N platform_version 1—N monetization_assessment；platform_version 1—N ranking_entry；ranking_snapshot 1—N ranking_entry。
chart_definition 1—N ranking_snapshot；product 1—N selection_record；weekly_report 1—N report_item。
chart_definition 绑定 source；采集 job 的一次运行生成0或多张候选快照；快照校验后发布。

## 2. 实体职责
| 表 | 职责/关键约束 |
| --- | --- |
| product | 产品聚合身份、名称、当前状态、首次观察时间；不存统一排名 |
| platform_version | 平台和外部稳定ID唯一、产品归属、包名/开发商/入口/实际发布日期 |
| data_source | 供应商、接入方式、授权范围、secret_ref；不落明文密钥 |
| chart_definition | 来源+平台+地区+外部榜单ID+分类+设备口径唯一；HEAT/AD_BUY分类 |
| collection_job | 配置与启停、定时策略、时区 |
| collection_run | 每次执行ID、统计日、状态、数量、失败、重试、幂等键 |
| ranking_snapshot | chart+business_date+revision唯一，完整性、TopN、来源日、原始材料hash、发布时间 |
| ranking_entry | snapshot+version唯一，snapshot+rank唯一；排名必须正整数 |
| evidence | 来源、原文/定位、材料hash、观察时间、版本范围、授权/真实性信息 |
| monetization_assessment | 版本级模式结论与证据关系、方法、置信度、有效期；追加式历史 |
| rule_version | 已发布配置不可变、规则JSON及hash |
| rule_hit | 产品/版本、规则版本、统计日、所用快照和证据、可读原因 |
| product_state_history | 产品状态变化、原因、关联规则、发生/生效时间 |
| field_override | 人工字段覆盖值、原因、创建人、版本、启停；不改原始采集值 |
| selection_record | 多人选用、参考版本、负责人、状态、日期、备注、乐观锁 |
| weekly_report / report_item | 周期及版本、精选条目和冻结依据，产品跨平台去重 |
| identity_link_event | 合并/撤销/改归属事件与原始身份；不可破坏历史引用 |
| audit_event | 人工配置、修改和重要任务操作审计 |

## 3. 快照一致性
以来源返回的某张榜单为原子单位写入：原始材料→校验→事务写头和明细→发布。失败不发布半张完整榜。
完整性：COMPLETE、PARTIAL、FAILED（FAILED仅用于采集运行；榜单快照可不落失败行）。requested_top_n与实际covered_top_n分开。
每榜每天可多revision；重跑同材料hash幂等复用；更新材料创建新revision，旧快照不删除。
“当日快照”选择当天截止查询as_of时已发布的最大revision；默认查询为当前已知的修正版历史。周报/规则命中永远引用具体snapshot_id，因此可追溯当时使用版本。高级双时态界面不要求首期实现。
Source优先级按榜单配置，切源创建新chart_id，不跨源计算7日涨幅，历史可分别查看。

## 4. 指标与空值
rank_delta_7d = rank(D-7) - rank(D)，两天均有效、同chart才输出。负值下跌，正值上涨；缺失返回null及reason，不返回0。
持续表现窗口按自然日计算；返回eligible_days、observed_days、missing_days，不仅一个分数。
首次发现取有效观察最早时间；实际上线日来自独立来源，可为空。补录更早记录需记录导入批次，不追溯篡改已冻结周报。
评分、评价总数、星级计数如无来源为null。不能用rating_count推算好评数。下载/收入估算必须带estimated、单位、期间与来源；首期非必填。

## 5. 身份合并
external_id优先，名称仅辅助。对跨平台由可信发行方关联等证据建立归属，AI仅提出建议。
首期人工关联可用“指定已有产品”操作，无复杂审核；保留before/after及证据。误合并通过事件回退，不删除平台版本/排名。
selection_record保持原产品ID可通过canonical映射展示，不批量破坏原始归属；报告item冻结产品快照不随合并改正文。
数据库事务保证合并与当前归属一致；审计记录操作者及时间。

## 6. 时间、索引与容量
数据库存UTC DATETIME(3)，API返回ISO8601带时区；业务日DATE，业务时区Asia/Shanghai。周报默认周一00:00至下周一00:00左闭右开。
索引围绕chart+day、snapshot+version、product+observed、status+signal_day、owner/status。大量评价正文、截图、原始网页存对象存储，MySQL只存元数据/hash/locator。
MySQL不承担图片视频大字段。首期保留全部排名历史，不做未经确认的数据清理；原始材料保留周期按授权另定。
DDL为初始草案，迁移工具版本化；状态枚举由Java枚举+数据库校验统一维护。用户权限可对接现有锦鲤登录，不新增人员组织管理模块。
