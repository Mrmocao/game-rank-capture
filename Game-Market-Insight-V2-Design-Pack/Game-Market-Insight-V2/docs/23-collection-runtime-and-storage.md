# Java / MySQL 采集架构与数据契约

采集方案1.0；补充18章，沿用Game/PlatformEntity/MarketListing/Release及证据模型。以下为开发契约，未实现生产后端。技术组件选团队当前有维护支持的稳定版本，落地前固定版本与依赖。

## 1. 模块与运行边界

采用Java模块化服务：SourceRegistry、Scheduler、FetchWorker、ParseValidate、IdentityResolver、EvidenceStore、AiEnrichment、FeatureBuilder、ReportBuilder、OpsConsole。HTTP可用Java HTTP客户端，HTML用明确规则解析；需要浏览器时使用隔离的Java浏览器工作器。AI服务通过统一模型网关，按来源使用策略限制输入。

启动阶段用MySQL任务表+租约+事务outbox即可。队列积压、吞吐和运维要求达到阈值后，可替换为现有消息基础设施，领域数据结构不变。Redis用于可选缓存/限流，不作为唯一任务持久化。公开原始事实可以统一采一次，但按授权范围服务各组织；内部材料、凭证、测试和选用记录必须租户隔离，授权数据不能因为缓存共享泄漏。

建议状态流为：scheduled → leased → fetched → parsed → validated → committed；异常分retry_wait / access_blocked / quarantined / dead_letter。AI分析是事实提交之后的独立任务，模型挂掉不阻塞原始榜单入库。

## 2. 连接器能力合同

每个adapter显式声明supported_metrics、scope、access_mode、pagination_mode、max_verified_depth、requires_browser、supports_as_of、supports_etag、allowed_uses与policy_version。尚未验证的能力不能默认true。

建议接口语义：

```java
interface SourceAdapter {
    Capability describe();
    FetchResult fetch(FetchRequest request, AccessContext access);
    ParseResult parse(RawArtifact raw, SourceDefinition definition);
    ValidationResult validate(ParseResult result, ValidationContext context);
}
```

FetchRequest包含sourceDefinitionVersion、resourceKey、requestedMarket、locale、deviceClass、scheduledBusinessDate、requestedAsOf、cursor、预算及幂等键。FetchResult包含HTTP状态、实际URL、响应头白名单、观测时间、证据对象、原始哈希及抓取失败原因。ParseResult包括实际市场、榜定义、发布期间、记录集合、已验深度、缺口、分页完整度和解析证据。不要把解析成功等同于完整覆盖。

尚未确定HTTP路径的来源由source registry标待PoC；不向Codex提供虚构微信/抖音私有接口、签名算法或浏览器Cookie模板。已文档化请求可采用Apple Search/Lookup官方参数，具体返回字段仍先验收。

## 3. 核心表与索引

| 表/领域对象 | 关键内容 | 唯一性/主要索引 |
| --- | --- | --- |
| source_definition + source_policy | 定义/出处/访问/用途/保留/准入状态/版本 | source_id+definition_version；policy单独版本 |
| source_probe_run | 检测环境、时间、实际URL、返回范围、失败/限制、人工核验 | source_id+probe_at |
| collection_task | 资源、计划日、市场、任务类、状态、重试、租约/心跳、预算 | dedupe_key；status+next_run_at；租约token |
| collection_attempt | 每次请求的状态、耗时、返回字节、实际范围 | task_id+attempt_no |
| raw_artifact | 允许的原文对象地址、hash、类型、保留期限、删除状态 | source_id+content_hash+policy_scope；不可跨权限自动复用 |
| evidence_ref | 字段、原文区间/DOM定位/页码/图片坐标、适用范围 | artifact_id+locator_hash；entity_id+field_key |
| identity_candidate / entity_link | 未匹配对象、候选关联、理由、人工裁决 | platform+external_id唯一；关联带版本 |
| observation | 类型化事实头、来源/版本/时间、数据状态、血缘 | entity+metric+market+observed_at；available_at |
| chart_snapshot | 榜定义、国家、设备、来源发布日、抓取时点、覆盖、修订 | source_version+chart+market+device+scheduled_slot+revision |
| chart_entry | snapshot、平台ID、原始名次、行证据 | snapshot_id+rank；snapshot_id+entity_id；重复需隔离 |
| listing_snapshot | 元数据/版本/IAP等来源值 | listing_id+observed_at；字段事实与断言可追溯 |
| review + review_revision | 来源评论ID/指纹、正文/允许摘录、日期、版本、原始星级、修订 | source_id+native_review_key；revision_hash |
| sampling_run | query/sort/window/cursor、n/k/去重、截断/偏差 | source+panel_version+entity+sampling_window |
| public_content / mention_link | 文章视频讨论、根来源族、实体关联、商业标记 | source+native_content_key；root_cluster_id |
| creative_observation | 广告素材/落地实体/观察时间、地区、权限 | source+native_creative_id+observed_at |
| ai_job + claim_draft | 输入版本/hash、模型/提示词/schema、引用、草案/弃权 | job_dedupe_key；entity+field+review_state |
| feature_snapshot | 输入观测ID、指标定义、窗口、区间、覆盖、未知原因 | cohort+entity+window+definition_version+knowledge_cutoff |
| weekly_report_revision | 候选/算法版本/输入清单/决策/层级/变更原因 | report_week+scope+revision |
| audit_event / deletion_task | 人工编辑/权限/规则/删除血缘 | tenant+resource+event_at |

类型明确的高频字段使用专列，变化较多且低频的源原始属性可用受Schema约束的JSON扩展。数值存decimal或合适整型，计数不使用float；原始“5.78M”“1B+”独立保存display_value、numeric_lower/upper及is_rounded，未经确认不伪造精确整数。大对象只存对象地址，访问由后端按权限签发。

## 4. 时间语义与幂等

- source_published_at：来源明确发布时点，缺失则null。
- event_at / window_start、window_end：事件发生或统计期间，不能用抓取时点替代。
- observed_at：本次实际看到页面/接口内容的时点。
- available_at：通过校验进入可用于决策数据集的时间，用于历史知识回放。
- scheduled_business_date：任务对应业务日，不因此宣称抓到了该日历史。
- knowledge_cutoff：报告冻结知识时点；business_cutoff：观察/事件业务截止。

数据库统一UTC，另存来源时区及日粒度date_origin；UI默认Asia/Shanghai，不把美国来源日期盲转成中国榜日期。周报冻结例：业务窗截至周日23:59:59+08，周一09:00+08冻结；实际周一观测不能回填周日，无独立有效源日期就不使用。

任务dedupe_key由source版本、任务类型、资源稳定键、市场、设备、计划时槽、明确的as_of及分页游标hash组成。同任务重试不重复写业务事实；重新解析同一raw生成新的parser_revision，不改原始证据；人工纠正写修订而非覆盖。

工作器使用随机lease_token并心跳续租，过期重新领取；写入时检查token避免旧worker重复提交。事实与outbox在同一事务提交，下游以event_id幂等。只有连接器声明并验证ETag语义才用条件请求；304记录“来源确认未变”及对应原始引用，不伪造新发布时间。

## 5. 覆盖与失败的类型

chart_snapshot分别保存target_depth、observed_count、verified_contiguous_depth、missing_rank_ranges、is_complete_for_requested_scope、pagination_exhausted、source_market_verified。完整性不是observed_count==100这么简单，必须验证范围内没有缺名次/重复实体/错误地区。

例如期望Top100实际只读到1–25：status=partial，verified_contiguous_depth=25。第18名可进入同尺度位置指数；未出现产品保持unknown。完整Top100中未见某产品才允许out_of_monitored_range，raw rank仍为null。404/403/429/空响应/结构变化必须分别处理，不产生“0个项目的完整榜”。

字段value_status建议包括observed、observed_zero、unknown_not_collected、unsupported、permission_required、fetch_failed、partial_scope、stale、conflict、not_applicable、removed。missing_reason是枚举+解释，不能只用空字符串。即使所有输入未知，也可产生补证动作。

## 6. 限流、重试与源健康

逐host并发初设1，任务预算、每日请求/字节/AI成本上限、域名间公平调度可配置。普通HTML初始间隔3～5秒；API按官方额度/协议更严格者。HTTP超时初设20秒，浏览器任务60秒；默认最多3次尝试，429尊重Retry-After，5xx/网络错误使用带抖动的退避。429/权限失败不能通过换IP绕过。

出现结构异常、地区不符、重复ID、非法名次或不可能的计数下降进入隔离；计数下降不直接拒绝，可能是真实清理/删评，应保留原值并复核。与过去差异过大先告警，不因为“变化异常”自动删掉可能的爆发。

监控按来源+市场+榜定义显示：计划任务数、有效完成数、覆盖深度、最后成功、数据时效、异常/重复率、身份匹配率、AI引用合格率、人工队列、采集费用、受影响周报。公开Top25源可以达成“Top25任务成功”，但Top100目标覆盖仍只有部分，两项指标分开。

## 7. AI任务与数据使用控制

输入仅含该任务允许的材料、field_schema、scope、policy与evidence_refs。输出claims[{field,value,type,evidence_ids,locator,scope,unknown_reason}]以及conflicts/suggested_tasks。JSON Schema校验后还要验证引用存在、引用内容支持字段、时间/版本一致；数值不能脱离原文推算。

任务分事实抽取、翻译、玩法标签、评价方面、实体关联候选、冲突识别、研究摘要、采集缺口建议。模型不能直接发布规则、合并实体、裁定IAA或改生产数据；依据充分的确定性规则可以自动入库，人工只处理关键裁决与异常。

每批允许材料去重后再调用；内容hash+模型版本+提示词版本+任务类型作为缓存键。模型不可用时只暂停增强字段；下游解释用规则模板。外部网页文本当不可信数据，任何“忽略规则/发送凭证”等内容不得成为执行指令。

## 8. 权限、保留与安全的必要实现

逐来源明确fetch_allowed、store_allowed、derive_allowed、ai_processing_allowed、redistribute_allowed、media_download_allowed，不以“公网”代替这些配置。来源条款/robots/合同及可访问范围作为准入记录，限制出现时降低频率或切换正式路径，不引入绕过机制。

抓取URL只允许白名单host与HTTP(S)，每次重定向重新检查目标，防私网/元数据地址/DNS重绑定；限制响应体大小、类型与解压比。凭证只放密钥服务，日志遮蔽token、Cookie及非必要个人信息，人工上传检查内容类型/恶意文件。

source policy变更触发血缘扫描：原文、评论、媒体、AI派生、特征、引用报告与导出均受影响。需要删除时不可仅删对象库却保留完整复制文本；允许保留的决策元数据或不可逆汇总也必须明确有依据。报告历史若内容依法或依约删除，显示“证据已移除/不可复算原因”，不伪称仍能全文复现。

## 9. 管理端新增的最小功能

沿用两级菜单。在“实际使用”显示资料来源、覆盖/缺失、某日快照、版本比较及补证入口；在“配置管理”下扩展数据源登记、采集任务/失败队列、字段映射、采样面板、源策略、AI抽取Schema与质量统计。

数据源页面必须区分“文档已核对、样例可见、PoC通过、试运行通过”；默认不把后两项勾选。新增源采用草案→样本验证→范围确认→小流量试运行→启用，停用源只停新抓取，不删除已有合法历史。
