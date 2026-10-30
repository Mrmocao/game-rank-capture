# V2.3 游戏档案数据契约与Java/MySQL交接

本章补充第06章，不是已完成的API或DDL。推荐引擎仍遵循V2.2；本章定义其输入事实的组织方式。

## 1. 身份模型

统一Game表示研究对象；PlatformEntity用platform+external_id标识平台实体，不用游戏名称作为唯一键。MarketListing保存国家地区、语言和商店范围；Release保存发行版本及有效时间。跨平台同款关系保存证据、置信状态和裁决历史，同名不得自动合并。

原型versionKey如P0-WX-CN是“产品+平台+市场”的显示键，不是生产稳定ID，也不是appVersion。后端必须拆开平台实体、市场上架和应用版本。不可直接把DEMO字段作为真实识别依据。

## 2. 建议逻辑表

| 逻辑表 | 关键列 / 约束 | 用途 |
| --- | --- | --- |
| game | id、canonical_name、first_observed_at、identity_revision | 统一产品 |
| platform_entity | game_id、platform_code、external_id；唯一(platform_code,external_id) | 平台实体，不混同appVersion |
| market_listing | platform_entity_id、market、language、source_id | 平台发行市场与语言 |
| release_record | listing_id、app_version、released_at、available_at | 发行版本历史 |
| profile_field_definition | key、domain、type、unit、scope、ttl_policy、schema_version、required_evidence | 可扩展字段定义 |
| field_observation | listing_id、release_id可空、field_key、typed_value、status、kind、observed_at、available_at、valid_until、revision | 可追溯事实/估计/人工核查 |
| claim | listing_id、field_key、text、claim_type、model/prompt/rule_version、input_hash、status | AI分析与待验证假设 |
| evidence / observation_evidence | source_id、origin_family_id、url、excerpt、asset_ref、hash、time、rights；关联角色 | 原始依据及其引用 |
| media_asset | listing_id、release_id、media_kind、thumbnail_ref、asset_ref或source_url、rights、status | 截图、实机视频、广告素材分开 |
| profile_gap / enrichment_task | target_scope、field_keys、reason、priority、owner、status、idempotency_key | 缺口与任务，不存未经核验事实 |
| material_submission / material_review | target_scope、field_key、claim_kind、submitter、source、observed_at、decision、reviewer、audit | 人工/AI提交与复核 |
| selection_record / status_history | game_id、listing_id、owner_id、status、dates、note、revision | 多人选用；禁止game_id唯一 |
| experiment / outcome | selection_id、version、hypothesis、sample、actual_cost、result_status、evidence | 内部验证 |

榜单、评论内容、情感主题、舆情事件及推荐运行沿用06章实体。大型素材放对象存储，MySQL记录定位及访问控制，不把视频字节写入档案JSON。

## 3. 类型与字段值

基础可检索字段（平台ID、主体、名称、市场、版本）优先规范化列；时序指标使用Observation与专门快照表。可扩展资料使用受FieldDefinition约束的类型值，不能任意堆积无校验键值。

支持string、enum、number、range、date、list、object、reference；字段的单位、取值约束和schema_version固定在定义中。MySQL可使用类型列或经Java校验的JSON，索引与查询需求确定后再选物理结构。状态与值必须一致：missing等状态value=null；observed_zero允许数值0；不适用需原因。

原型字段到生产映射：fact→observed，manual→manual，estimate→estimated，derived→derived，inference→Claim且status=draft。原型available仅代表存在可读资料，不能直接等价于生产资格“已验证”。原型not_yet_known是查询视图状态，不必作为永久观察写入。

## 4. 共用观察契约

```json
{
  "field_key": "mode",
  "game_id": "demo-game-0",
  "platform_entity_id": "demo-platform-0",
  "market": "CN",
  "language": "zh-CN",
  "release_id": "demo-release-1-4-0",
  "app_version": "1.4.0",
  "value": "pure_iaa",
  "value_kind": "manual",
  "data_status": "observed",
  "observed_at": "2026-10-28T10:00:00+08:00",
  "available_at": "2026-10-28T12:00:00+08:00",
  "valid_until": "2026-11-04T23:59:59+08:00",
  "evidence_ids": ["DEMO-evidence-package"],
  "verification_status": "reviewed",
  "definition_version": "monetization-mode-v1",
  "revision": 1,
  "is_synthetic": true
}
```

上述有效期为测试样例，不是推荐的统一7天策略。生产有效期由来源、字段变化速度、版本变化和研究用途配置。删除/纠正产生新版本并保留权限允许的审计信息。

## 5. 历史查询与资格

GET档案需要listing_id、as_of、timezone；查询仅使用available_at和observed_at不晚于截止的有效证据。选择当前事实时依据该字段的裁决规则，不是简单“最后抓到的覆盖前面”；相互矛盾的有效材料进入冲突状态。

应用更新时使绑定旧release的玩法/广告核查失效，主体等非版本敏感字段不必全部失效。历史查询读取当时的有效版本。重建历史推荐使用当时信息，不能带入后来纠正的事实；纠正后报告产生新revision。

IAA资格需目标版本的广告变现证据、排除内购依据以及可追溯复核；仅“含广告”标签、未看到购买入口或AI推测均不充分。发现内购为混变；矛盾或失效为待复核。来源事实判定与用户有多少填报记录无关。

档案完整性只计算适用字段中当前有资料的比例，AI草案数量另示。权重、来源可信度、时效折扣和精选资格不混入此比例。生产配置可分别计算基础/深度/精选依据完备度，但需公开分母。

## 6. API草案

| 接口 | 作用与返回要求 |
| --- | --- |
| GET /api/games | 筛选关键词、平台、市场、日期、资格与缺口；返回每行reference_listing_id |
| GET /api/games/{id}/profile?listingId=&asOf=&timezone= | 范围、当前版本、字段状态/类型、资料统计、证据、资格与缺口 |
| GET /api/listings/{id}/fields/{key}/history?asOf= | 仅截至当日的历史；可见范围由服务端校验 |
| GET /api/evidence/{id} | 来源、摘要、时间、权限和资源入口；无权限明确返回 |
| POST /api/enrichment-tasks:plan | 按scope+cutoff+缺口版本生成任务；幂等键、防重复 |
| PATCH /api/enrichment-tasks/{id} | owner、status、note、expectedRevision；不能写入事实 |
| POST /api/material-submissions | 类型校验、来源和范围校验，返回pending_review |
| POST /api/material-submissions/{id}:review | 通过/拒绝/冲突/补材料；reviewer、reason、expectedRevision |
| POST /api/games/{id}/selections | 为每位负责人单独创建；可同产品多人选用 |
| GET /api/games/{id}/profile-export | 历史档案与当前协作记录分开；冻结导出范围与规则版本 |

400为参数或字段类型无效；403为没有权限；404为未知实体；409为版本冲突；可读空资料返回200和明确状态，而不是把业务缺口当服务器异常。分页、导出数量和材料大小需限制。浏览器不能提交或覆盖可信综合分、IAA已审核状态。

## 7. AI工作契约

每次AI任务包含target_scope、field_keys、input_evidence_ids、source_policy、model/prompt/schema_version及deadline。返回结构化claims、每条引用、unknown_fields、conflicts、suggested_tasks和弃权原因。输出先校验类型、范围、时间和引用，再进入草稿。

AI提取事实时必须引用可定位原文；无来源摘要为推断。没有真实截图或视频，不能生成画面后标成实机证据。成本输出人日区间、假设和工作拆分，需负责人确认。网络/模型失败保留输入并产生可重试任务，档案读取不被阻塞。

资料来源文本按不可信输入处理，抓取工作器不能执行其中指令、访问任意内网或扩大凭证权限。素材URL获取由受控服务处理，检查重定向、私网地址、大小与媒体类型；不让浏览器提交的任意URL直接成为服务端抓取目标。

## 8. Java/MySQL实施与扩展

先用模块化Java服务实现Identity、Profile、Evidence、MaterialReview、Selection和Recommendation边界；不要求先拆微服务。规则和字段定义版本化，新增平台/字段通过注册适配器和定义扩展。数值和资格判断服务端可复算，AI不直接改数据库决策列。

建议索引围绕(platform,external_id)、(listing_id,field_key,available_at)、(listing_id,observed_at)、(task_scope,status)、(game_id,owner_id)、(report_id,revision)。有效记录选择、素材授权、任务幂等和修订写入应事务处理；并发更新使用revision乐观锁。

所有表在大型平台环境保留tenant_id和访问策略，服务端校验组织、材料和内部测试数据权限。最小上线先支持人工证据导入和受控文件来源，再接真实平台采集。不要提前承诺通过公开接口获得竞争游戏的真实收入、留存、活跃或完整用户评论。
