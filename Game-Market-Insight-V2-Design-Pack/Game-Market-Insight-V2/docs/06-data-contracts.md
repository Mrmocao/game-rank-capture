# 数据与分析契约（逻辑层）

## 1. 存放原则

Java负责业务API、权限、资格规则、数值计算、报告版本及任务协调；MySQL保存结构化事实、引用、关系、版本、决策和填报记录。原始网页/截图/视频等大对象放对象存储，MySQL记录定位、hash、授权、有效期与删除状态。具体存储供应商、抓取组件和物理分表在后续实现阶段决定。

事实、AI推断、人工判断分开保存。事实有来源；分析有输入和版本；人工纠正为新记录，不静默覆盖旧事实。原始材料不可用时，依权限保留可引用摘要和链接，标注无法回放，不能假称完整证据。

## 2. 逻辑实体

| 对象 | 粒度与重要字段 |
| --- | --- |
| Product | 统一研究产品、名称/别名、合并版本、首次观察 |
| PlatformVersion | 产品+平台+稳定外部ID+市场可用范围，版本历史 |
| ProductRelation | 同款/相似机制/续作等有证据的关系，支持撤销 |
| SourceDefinition | 来源与根来源族、允许用途、覆盖目标、质量与可得性 |
| Evidence | 来源定位、事件/发布/观察/可用时间、权限、材料hash、原文范围 |
| Observation | 对象+指标+平台/国家/语言+窗口+来源+观测版本，数值可null |
| ChartDefinition / Snapshot / Entry | 榜单身份、定义版本、快照完整率、原始名次、是否监测范围内 |
| ReviewContent / ContentRevision | 评价原文、星级、来源内作者、内容版本、互动快照 |
| DedupCluster | 重复/转载/引用关系、根来源、人工修正版本 |
| AIAnalysis / Claim | 输入证据、结构化结论、事实/推断/假设标签、模型与规则版本 |
| SentimentAspect / Topic | 内容或产品窗口的主题、方面倾向、分母、分类体系版本 |
| MarketEvent | 事件时间、影响版本、证据、冲突与复核结论 |
| GameplayFamily / Membership | 玩法机制分类、成员关系、有效时间和证据 |
| ResearchProfileVersion | 平台、国家、IAA范围、团队能力、必需证据、权重、数量、多样性 |
| EligibilityDecision | 版本+研究方案+截止日，逐条门槛、结果、理由码、引用 |
| RecommendationRun / Item | 候选集合、维度分、解释、入选/未选、证据快照 |
| WeeklyReportRevision | 完整周期、可用时间截止、方案版本、冻结正文与条目 |
| SelectionRecord / StatusHistory | 多人选用、负责人、目标版本、状态日期、修改记录 |
| Experiment / Outcome | 团队实验、预算/假设/样本、版本、结果、停止原因 |

这是逻辑契约，不是最终MySQL DDL；旧版仅榜单产品库的物理SQL不能直接代表V2完整数据模型。

## 3. 共用元信息与缺失语义

所有指标/证据至少携带source_id、origin_family_id、entity_id、platform、market、language（未知可null）、event_time或统计窗口、observed_at、available_at、metric_definition_version、unit、coverage、evidence_ref、value_kind、data_status。

value_kind分 observed（来源明确展示/实测）、estimated（供应商或模型估计）、derived（本系统计算）、manual（人工判断）。下载、收入、活跃等估算保存供应商、区间与方法版本；看不到则null，不反推真实营收。

data_status至少区分observed、observed_zero、out_of_monitored_range、missing、not_supported、not_authorized、stale、unknown、conflict。data_status=missing时rank=null，不能rank=101。来源失败的页面与正常榜单完整抓取但没有该产品，含义完全不同。

实体可因纠正产生新版本；已发报告绑定当时实体和证据版本。事件时间回答“何时发生”，available_at回答“系统何时知道”。默认市场时间保留源时区；报告使用研究方案时区，初始Asia/Shanghai，明确海外日界映射，避免混日。

## 4. 资格与分析的结果状态

资格结果为eligible、observation、review_required、ineligible。决策必须包含原因码、关联版本、截止日、必需项逐条结果、证据ID、规则版本、未知项和复核记录。推荐原因不只返回一个总分。

建议原因码：IDENTITY_UNRESOLVED、IAA_UNKNOWN、MIXED_MONETIZATION、OUT_OF_SCOPE、MARKET_EVIDENCE_INSUFFICIENT、GAMEPLAY_UNVERIFIED、EVIDENCE_STALE、MATERIAL_CONFLICT、NEGATIVE_EVENT_REVIEW、TEAM_CONSTRAINT、ALREADY_TRACKED、DIVERSITY_LIMIT、CAPACITY_LIMIT、NO_MATERIAL_CHANGE。

## 5. 未来API形态（待实现）

```text
GET  /api/research-profiles/{id}/versions/{version}
GET  /api/products?platform=&market=&asOf=&eligibility=&query=
GET  /api/products/{id}/versions
GET  /api/charts/{chartId}/snapshots?date=&market=
GET  /api/products/{id}/opinions?from=&to=&sourceSet=&topic=&sentiment=
GET  /api/evidence/{id}
GET  /api/information-catalog?domain=&priority=&availability=
POST /api/recommendation-runs                  # profileVersion + cutoff
GET  /api/recommendation-runs/{id}/items        # selected + excluded
POST /api/weekly-reports                      # idempotencyKey + runId
GET  /api/weekly-reports/{id}/revisions/{rev}
POST /api/products/{id}/selections
PATCH /api/selections/{selectionId}            # optimistic revision
POST /api/selections/{selectionId}/outcomes
```

API所有时间序列返回coverage和data_status；空数组不能代替权限/来源错误。数值统一单位，百分比与百分点明确不同。写入需要身份与记录版本，周报生成要幂等，记录并发编辑使用乐观锁。生产接口不接受浏览器传入的综合分作为可信决策。

## 6. 扩展方式

新增平台先扩展平台/市场/榜单定义，不能在业务代码里增加多套排名逻辑。新增指标先登记字段定义、单位、来源约束和适用对象。新增AI能力实现任务契约并提供弃权路径。新增研究目标复制方案为新版本，不修改历史版本。配置按组织隔离并分编辑权限，保留修改人和审计。

暂不指定微服务拆分、消息队列选型、向量数据库或供应商SDK；业务契约先稳定，避免因工具选择限制信息范围。


当前V2.2以14容错方案、15演算和16验证记录为准；04、11、13保留V2.1历史对照。旧V2固定分仅作产品浏览示例。

## V2.1 数值与推荐接口补充

每个维度返回raw_inputs、formula_id、score或null、unit、numerator/denominator、coverage、evidence_ids、assumptions、analysis_version。玩家维度另返sampling_scope、dedup_version、aspect_version、k、n、ambiguous_count、z、interval_low/high；工期返scope、range、scenario_upper、estimator与假设，不能仅存“难度80”。

RecommendationRun另保存cohort_key、case_set_hash、profile_version、cutoff、candidate_ids、eligible_ids、selected_ids、excluded原因、scenario_set与rank_range。完整/有限证据使用不同track；不同行业/市场方案的总分不混排。所有输入校验在Java服务端重新执行；前端仅作交互试算。

生成顺序：校验输入和时间 → 冻结候选 → 身份/版本与样本门槛 → 特征计算 → 六维分数 → 同队列排序 → 产品去重/组合约束 → 敏感性 → AI叙述及一致性检查 → 冻结修订。正式上线需在服务端快照事务中绑定相同版本，防止生成过程中资料变化造成前后不一致。

## V2.2 覆盖条款：范围、状态、已知约束与补证

以下是生产契约，不是已完成的Java API。V2.1“完整/有限轨道”改为同队列同尺度范围与推荐层级；资格仍独立保存。推荐route使用ready、conditional、lead、paused、excluded；错误运行另存config_error，不冒充业务资格。

| 对象/字段 | V2.2要求 |
| --- | --- |
| MetricValue | value可null；data_status补充source_error、partial、invalid；有效期、原始观察时间与使用原因分开 |
| FeatureRange | key、lower、upper、estimate/null、input_coverage、bound_method、evidence_ids、assumptions、errors |
| CandidateDecision | route、eligible_for_validation、why、action、blocking_facts、unresolved_fields、six_ranges、utility_range、input_coverage |
| KnownConstraint | 约束码、适用版本/范围、有效时间、依据、解除所需证据、resolution_revision；来源失败不自动撤销 |
| DemandProtocolVersion | 来源类型、适用范围、样本/观察窗、原始量、基线、通过公式、阈值、证据、验证状态；不得信任AI的passed字段 |
| EvidenceTask | 前提/数值缺口、依赖关系、影响上界、合并来源键、所需材料、状态、负责人可选 |
| RunHealth | 每源状态、最后成功时间、缓存适用性、影响候选、隔离记录、报告降级原因 |
| ReportRevision | 截止日、输入和候选集合hash、完整状态、参数/算法版本、合成范围、分层、组合、任务、生成时间 |

数值不使用数据库默认0表达unknown。MySQL层保存上下界为可审计定点精度，计算层统一舍入规范；内部未舍入结果用于排序，展示保留1～2位小数，边界比较用登记的容差。算法输出不是可直接发布的AI文本。

建议新增：GET /api/recommendation-runs/{id}/health、GET /api/recommendation-runs/{id}/evidence-tasks、POST /api/recommendation-simulations（明确isSimulation，不能覆盖事实）。正式每周任务至少返回每条已识别记录的结论；空候选返回系统恢复任务。

缓存键包含实体版本、指标定义、来源、市场、窗口、证据版本和权限。源失败时只复用适用的证据，不能刷新observed_at或available_at。对过期事实的推断必须另存inference，不能改写事实。修正冲突/解除约束都产生修订，旧周报不变。

处理顺序：校验公共方案 → 按截止日/权限冻结证据 → 隔离坏记录 → 判定已知约束/身份/需求 → 逐维范围 → 分层 → 同队列保守与探索组合 → 补证任务 → 规则报告 → AI可选叙述 → 一致性检查 → 冻结。AI失败仍可用规则报告完成任务。
