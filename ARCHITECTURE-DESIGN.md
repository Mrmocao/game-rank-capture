# 游探 / 锦鲤云 IAA 产品情报系统

**整体架构与产品设计基线 · V2.7 · 2026-10-30；档案 2.3.0；推荐算法 2.2.0**

实现状态：V2.7 来源登记、门户字段模板、采集运行只读日志和运维导航已接入；V2.3 档案、材料/复核、任务、选用、导出和授权材料 AI 草案链路已实现。Flyway V9～V12 已在配置数据库与生产数据库执行，后端测试与前端构建通过；桌面/移动浏览器已验证。S03 Apple公开RSS已完成4次生产运行并登记为V3_POC，来源目录显示实际运行次数与最近成功时间。真实模型供应商、Firecrawl/门户自动采集、完整来源授权、媒体上传缓存与长期运行尚未验收。具体发布状态以 DEPLOYMENT.md 为准。

本文是当前工程唯一的实现基线。后续新增页面、接口、采集来源、AI任务、数据库字段和周报规则，先更新本文的相应章节，再修改代码与迁移。当前工程不维护可切换的产品多版本；历史周报是业务事实快照，必须保留，但新代码统一按本文最新设计实现。

## 1. 目标和边界

系统的目标是从可追溯的市场材料中发现值得验证的休闲游戏机会，并按周输出少量、可解释、可行动的精选。系统回答“为什么现在值得研究、证据是什么、未知什么、下一步验证什么”，不输出爆款概率、收益承诺或从竞品排名推算 ROI。

研究对象按“统一产品 → 平台版本 → 市场/语言 → 日期窗口”组织。微信、抖音、iOS 中国和 Google Play 美国、英国、德国、法国分别保留平台、市场、榜单和日期。榜单名次不跨平台平均；玩家热度与广告买量分开。正式纯 IAA 资格必须落在目标平台版本，并有有效证据；未知进入线索/观察，不由高分抵消。

系统可以使用免费公开来源、人工结构化材料和有授权数据。来源失败、未授权、过期、冲突、部分覆盖都必须进入状态字段和运行记录。缺失不能当作榜外，也不能补齐成连续历史。

## 2. 技术架构

```text
浏览器 Vue 3 + TypeScript + Element Plus
        │ REST/JSON（/api/v1）
        ▼
Java 21 + Spring Boot 3.5.6
  ├─ 查询/API层：产品、榜单、周报、选用、AI配置、规则
  ├─ 确定性领域层：日期快照、资格、去重、缺失状态、V2.2六维范围、报告冻结
  ├─ 采集层：Apple RSS、AppBrain材料、公开微信/抖音观察、未来安卓端材料
  ├─ AI适配层：OpenAI-compatible中转；Schema校验；失败可弃权
  ├─ 采集运维：source_definition、collection_task_definition、运行/尝试/阶段日志
  └─ 迁移/调度：Flyway、JDBC、Asia/Shanghai业务时区
        │ JDBC（不依赖 MySQL CLI）
        ▼
MySQL 8 iaa_radar
  ├─ 结构化事实：产品、平台版本、来源、榜单快照/条目
  ├─ 证据与资格：旧 evidence / assessment、profile_evidence / field_observation / claim
  ├─ 平台范围：market_listing、release_record
  ├─ 档案协作：profile_material_submission / review、enrichment_task、media_asset、ai_run
  ├─ 运营记录：selection_record、audit_event、idempotency_record
  └─ 报告：weekly_report、report_item、Skill/候选审计/输入哈希
        │
        └─ 原始 HTML/JSON/截图/图标材料：MATERIAL_DIR / 对象存储迁移接口
```

正式部署不需要 Docker。数据库由环境变量 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 注入；生产禁止使用 `dev` profile。前端由 Nginx 提供静态文件，API 运行在后端端口。

## 3. 核心数据原则

1. **事实、分析、人工判断分开。** 原始值带来源、时间、版本、市场、口径、材料哈希和定位；AI推断、规则命中和人工覆盖产生新记录，不静默改写事实。
2. **未知有语义。** `MISSING_SNAPSHOT`、`PARTIAL_COVERAGE`、`NOT_IN_COVERAGE`、`NOT_AUTHORIZED`、`STALE`、`CONFLICT` 含义不同。缺失的 `rank` 保持 `null`；完整榜单明确不在监测范围才可使用榜外状态。
3. **业务日严格可复现。** 所有查询指定业务日；D-1/D-7比较只使用同平台、同国家、同榜单定义和当日已发布快照。没有快照时显示缺失，禁止用临近日或最新日回填。
4. **身份和资格按版本。** 名称不是唯一键；跨平台同款需要证据和可撤销关联。纯 IAA 按平台版本、市场和有效期判断，混变/IAP不进入纯 IAA 正式资格。
5. **历史不可覆盖。** 新材料或新规则生成新修订；旧周报保存当时的 Skill、规则、候选、来源快照、证据、正文、输入哈希和淘汰原因。

## 4. V2.2 市场洞察模型

每个已识别候选均给出结论、支持与反对依据、未知项和行动。资格先于排序；资料不足不会阻断整期报告，但会降低行动强度。

六维固定启动权重如下，后续只能通过规则版本调整：

| 维度 | 权重 | 含义 |
| --- | ---: | --- |
| D | 25% | 同口径 14 日市场持续性；榜内位置指数，不是市场规模 |
| M | 15% | 前后 7 日位置指数差，增长缺失时保留范围 |
| P | 20% | 可比玩家内容的 Wilson 区间；分子、分母、样本范围一起保存 |
| F | 20% | 原型工期范围相对团队预算；未知成本不填平均值 |
| A | 10% | 有引用的素材可验证阶段，0～4 阶段×25 |
| O | 10% | 有引用的需求改进阶段，0～4 阶段×25 |

综合结果保存 `lower`、`upper`、`estimate`、`rangeWidth`、`inputCoverage` 和 `status`。未知软维度为 `[0,100]`，不重新归一化权重。综合范围称“研究优先级范围”，不是置信区间、成功概率或盈利预测。

推荐层级：

- **重点验证 READY**：资格成立、覆盖至少75%、综合范围宽度不超过25，安排有限预算原型并写明停止条件。
- **条件推荐 CONDITIONAL**：资格成立但有软数据、样本或成本缺口，先补证或低成本试玩。
- **线索推荐 LEAD**：身份、IAA、实机或需求仍未知，只做核验任务，不冒充正式合格项目。
- **暂缓 PAUSED**：制作记录、预算下界或重大问题阻止当前方案投入。
- **范围不匹配 OUT_OF_SCOPE**：明确混变/IAP或不属于目标研究范围。

默认每周目标 8 款，允许配置 5～10 款；合格不足就少出。玩法家族上限、探索补证名额和平台/市场队列必须保留具体淘汰原因。当前 Java 已落地 V2.2 六维范围计算器 `V22RecommendationEngine`，周报候选冻结 `v22` 结构；玩家、工期、A/O 字段未接入真实采集时显示未知范围。

## 5. 页面和交互

### 游戏情报档案（当前单款信息唯一实现）

按设计包17～19实现12信息域、81展示字段、8分区。`product`为统一研究对象，`platform_version`沿用已有稳定平台实体ID，新增`market_listing`拆分市场/语言，`release_record`记录实际应用版本。旧平台实体ID不是应用版本号。仅从已有来源身份与快照建立发行范围，不按名称合并、不把虚构原型导入真实产品库。

详情深链接为`#/profile?product=&listing=&version=&market=&date=&tab=`；榜单入口携带对应平台实体与市场。日期为Asia/Shanghai默认知识日，服务端同时检查observed_at与available_at，迟到材料不进入当时已知视图。无当天榜单仍保留已知档案，名次明确无快照；所有版本榜单独立。切换范围请求失败时清空旧档案，避免在新日期标题下显示旧事实。

字段定义保存在受控JSON目录，81项是120指标的展示投影。经复核观察保存在`profile_field_observation`，AI分析独立保存在`profile_claim`，补全任务和待复核材料独立存储。已审核的有效反证不能被后写入记录静默覆盖；显式裁决需要新证据、理由及被替代记录ID。版本敏感字段必须绑定release；更新或到期后当前值为null，历史仍可回放。缺失不补0，不适用需原因并移出分母；AI草案数量与资料量并列。

新增`profile_evidence`存来源、权限、原文定位、hash与时间；媒体通过`profile_media_asset`区分截图、实机视频、广告视频和示意。只保存http/https来源引用，不主动抓取用户提交URL；无权限不提供资源正文，失效不冒充可播放。

材料提交→待复核→通过/拒绝/冲突/需补材料，review与观测写入同一事务，使用revision乐观锁。任务按组织+listing+信息域锁定去重，支持负责人、优先级、进度、不可得原因及复查条件；任务状态不能改事实。档案导出冻结schema、范围和时间，当前协作记录单列。多人选用复用既有记录及审计，资料完整率不参与V2.2六维评分。

档案API采用服务端配置组织`PROFILE_TENANT_ID`与可选访问令牌`PROFILE_ACCESS_TOKEN`，禁止客户端切换组织。当前生产按业务要求留空访问令牌，直接进入单组织工作区；以后配置令牌后才启用共享口令登录。`GET/POST /api/v1/access` 用于状态/登录，浏览器不保存令牌到localStorage；启用令牌时会话为8小时HttpOnly SameSite=Strict Cookie，重启后需重新登录，失败登录限速。脚本使用Bearer，CORS仅允许配置中的准确Origin。所有新业务表含tenant_id，材料、任务与证据读取均检查组织。该部署仍是单组织工作区加姓名填报，不宣称已有企业SSO、人员身份认证或多组织管理界面。

API新增：`GET /api/v1/profile/products`、`GET /api/v1/products/{id}/profile`、`GET /api/v1/profile/listings/{id}/fields/{key}/history`、`GET /api/v1/profile/evidence/{id}`、`POST /api/v1/profile/tasks/plan`、`PATCH /api/v1/profile/tasks/{id}`、`POST /api/v1/profile/submissions`、`POST /api/v1/profile/submissions/{id}/review`、`GET /api/v1/products/{id}/profile-export`。存量API保留同一产品模型，不提供旧版/新版切换。

左侧两级菜单：

- 实际使用：市场概览、产品库、榜单观察、每周精选、选用记录。
- 配置管理：AI采集中心、规则配置。

页面共用业务日选择器。日期无快照时显示原因，不回退。产品详情展示各平台原始排名、覆盖状态、变现状态、证据和多人选用记录。周报卡展示机会信号、V2.2推荐层级、六维范围、综合范围、来源日、缺口和下一步行动。旧报告读取冻结 JSON，不会按当前规则重算。

AI采集中心支持 OpenAI-compatible `Base URL`、模型、API Key、超时、temperature 和启用状态；Key 服务端 AES-GCM 加密，读取只返回是否已配置。AI只做授权材料提取、主题/玩法分类、证据定位和文案；Java负责校验、规则、去重、快照、冻结和发布。当前报告元数据 `aiStatus=NOT_CALLED` 表示本期未调用模型。

## 6. 采集与材料协议

每次采集先形成不可变原始材料，再解析和发布快照。最小元数据：`source_key`、`platform`、`market`、`board_name`、`business_date`、`observed_at`、`source_date`、`raw_sha256`、`raw_locator`、`coverage`、`permission_state`、`status`、`failure_code`。

榜单材料必须区分：完整、部分、来源失败、无权限、尚未支持。失败不创建成功快照；同一材料哈希重试复用。微信/抖音公开观察没有稳定平台身份时独立保存为 `public_chart_observation`，不按名称合并产品、不计算跨日增长、不当纯 IAA 证据。后续安卓采集端应通过同一材料协议导入，不读取聊天、通讯录、不调用微信私有接口。

## 7. API 与数据库演进

现有 API 统一前缀 `/api/v1`，主要入口：

| API | 用途 |
| --- | --- |
| `GET /products`、`GET /products/{id}` | 指定日产品、平台版本、排名和选用 |
| `GET /rankings`、`GET /public-rankings` | 单榜快照和公开观察 |
| `GET /meta/coverage` | 来源覆盖、缺失和部分状态 |
| `POST /admin/reports/generate` | 幂等生成周报，冻结候选和 Skill |
| `GET /reports/latest`、`GET /reports/{id}`、`GET /reports` | 当前报告、历史修订、审计 |
| `GET/PUT /admin/ai-config` | AI中转配置状态和保存 |
| `GET /admin/insight-collection`、`POST /admin/insight-collection/run` | 采集运行和失败留痕 |
| `GET /admin/rules/current`、`POST /admin/rules/publish` | 规则版本发布 |
| `POST /products/{id}/selections`、`GET /selections` | 多人选用记录 |

新增字段必须新增 Flyway 迁移并写中文表/字段注释；不得修改已发布报告的事实字段。扩展平台优先增加 `chart_definition`、来源适配器和契约，不在业务代码复制一套排名逻辑。所有写接口应有幂等键、输入哈希或乐观锁。

## 8. 调度、运行和故障

业务时区为 Asia/Shanghai。每日来源采集和周报任务由 Java 服务执行，失败记录 `insight_collection_attempt`，不发布空快照。当前部署的免费 Apple 每日任务和周一周报任务已开启；AppBrain服务器直连失败时保持关闭；微信/抖音自动采集仍需材料或安卓端接入。报告生成使用数据库锁串行分配修订号。

来源健康、候选审计、输入哈希、原始材料哈希和发布状态必须可检索。异常恢复使用适用日期/版本/权限范围内的有效缓存，不能刷新原始观察时间，也不能因来源掉线删除已确认的负面约束。

## 9. 验证和验收

每次改动至少运行：

```text
mvn -f backend/pom.xml test
npm run build --prefix frontend
```

涉及真实数据库时运行 JDBC 集成测试并回滚合成数据；涉及页面时验证桌面和 390px 移动宽度、日期缺失、图片失败占位、报告历史和无控制台错误。V2.2 领域检查必须覆盖：未知不变0、榜外与缺失区分、Wilson小样本范围、成本未知、资格未知转线索、补数据生成新修订、旧报告冻结、空候选输出补证行动、同产品多人选用互不覆盖。

当前不能宣称：真实微信/抖音全 Top100 自动采集、所有平台授权、真实 AI 调用质量、真实增长预测、收益/ROI、真实时间外回测准确率。真实来源权限、字段协议和费用由业务方或供应商另行授权；系统在未授权时保留结构化模拟/人工材料测试链路。

## 10. 持续维护规则

新增功能按以下顺序更新：

1. 在本文补充目标、数据对象、状态语义、API、迁移、页面、验证和真实边界。
2. 在 `traceability.md` 增加需求ID、实现文件、验证命令和未完成项。
3. 新增数据库字段/表使用新的 `Vn__*.sql`，全部写中文注释，并补 JDBC 迁移说明。
4. 新增 Skill 或规则更新版本、哈希、测试夹具和历史冻结策略；不得改写旧报告。
5. 更新 `README.md`、`.env.example`、`DEPLOYMENT.md` 和 `MIGRATION-NOTES.md` 的启动与部署说明。
6. 完成后记录真实、模拟、未验证和外部阻塞，避免把页面存在称为后端能力已完成。

关联设计依据：`Game-Market-Insight-V2-Design-Pack/Game-Market-Insight-V2/docs/01`～`31`，其中 V2.2 的主规范为 `docs/14-fault-tolerant-recommendation.md`；V2.3 档案与字段语义为 docs/17～19、81字段目录及 AC81～98；V2.4～V2.7采集、来源中心、导航和门户扩展为 docs/20～31。独立原型夹具不得导入真实来源产品。


## 11. 授权材料 AI 草案链路

新增 `profile_ai_run` 保存组织、目标listing、输入证据、字段、模型/提示/Schema版本、输入输出hash、失败代码和重试信息。`POST /api/v1/profile/ai-extract` 仅接受本组织已登记且允许使用的证据，用户明确选择材料与目标字段后向配置中的 OpenAI-compatible `/chat/completions` 发起请求。受控材料作为不可信数据传递，模型不能改系统权限、范围或可信资格。

输出采用固定 JSON Schema（additionalProperties=false），再核查字段白名单、值类型、证据引用、原文定位和适用版本。成功只新增 `pending_review` 材料；复核后仍进入 Claim 草案而非审核事实。失败或弃权保存run与具体补全任务，HTTP失败不回显供应商原始响应或密钥。模型请求不持有数据库事务；写入结果时以事务保证同批材料一致性。

运行时 Schema 唯一源为 `backend/src/main/resources/profile/ai-output.schema.json`，Draft 2020-12，校验库 `com.networknt:json-schema-validator:1.5.9`。旧 `contracts/ai-extraction.schema.json` 是来源采集契约，不与档案 AI 输出混用。模型客户端仅向用户配置的中转发送请求，不抓取材料中的来源URL、不跟随重定向；最长300秒，响应上限256KB。真实供应商尚未调用，当前验收是本地mock HTTP与真实JDBC合成材料测试。

## 12. 档案到推荐的边界

`ResearchService → GameProfileService.enrichRecommendation → V22RecommendationEngine → WeeklyReportService` 将已确认的产品、稳定平台实体、市场、有效release和知识截止时间映射到唯一档案范围。资料完整率不进入六维评分；没有经复核且在有效release内的广告/无内购/范围证据，不提升IAA资格。合成事实、AI草案和其他平台的资格均不能提升真实正式候选。报告冻结档案证据、输入快照及时间；旧报告只读，不实时重算。

实际迁移：V9档案9表；V10选用组织/平台市场范围；V11 AI运行审计；V12来源登记与任务模板。完整DDL参考 `contracts/schema.sql`，实际启动以Flyway迁移为准，禁止修改已执行迁移。评分/评分数/评论数是market listing聚合；玩家样本为独立对象count/from/to/method/language，不能借评分数充当情感分母。历史最佳/观察天数等派生字段附参考榜单、窗口、输入快照、材料hash与捕获/发布时间。

## 13. 本轮验收与明确限制

80项测试通过（含真实MySQL回滚和并发事务、mock HTTP、42项来源登记与28项门户字段）；前端构建、8分区档案与390px浏览器通过。UI写操作使用合成API响应，数据库写入与并发由单独JDBC测试验证，两者不混称真实端到端采集。生产迁移前957行历史报告和榜单逐行比对保持一致。

当前不包含：实机媒体上传/转码/播放缓存、企业SSO/人员角色、供应商权限采购、完整微信/抖音Top100、真实模型质量评估、长期调度稳定性或时间外市场回测。真实正面IAA证据不足时仍输出待补证，而非为了凑数生成精选。每次新增能力都按第10节同步维护本文、迁移、验收和运行说明。

## 14. V2.7 来源中心与采集运维

来源目录由设计包21、29的42项登记和30的28项门户字段模板生成，存于 `backend/src/main/resources/collection/`，并在启动时同步到 `source_definition`（V12）。登记行保存来源族、网站、平台/语境市场、可采字段、访问方式、核验级别、实际深度、限制、替代路径、纯IAA默认unknown及生产启用标记。S03 Apple公开RSS绑定运行键`APPLE_CN`，目录额外显示实际运行次数、最近运行和最近成功时间；未执行来源仍显示尚未执行。门户来源S31～S42与市场来源共享稳定ID，门户数量是42项中的子集，不重复计算。

API为 `GET /api/v1/collection/sources`、`GET /api/v1/collection/sources/{id}`、`GET /api/v1/collection/portal-fields`、`GET /api/v1/collection/runs`。来源页、信息范围页和执行日志页仅展示登记与真实 `insight_collection_attempt`；未执行来源显示“尚未执行”，不填成功率。`source_definition`是能力配置，`collection_task_definition`是计划模板，运行/尝试/阶段事实继续追加，三者不混为一个状态。

当前真实采集仍只按已验收的Apple和可用AppBrain路径运行；门户、Firecrawl、微信/抖音新路径全部关闭。来源页面不能触发第三方抓取，新增网站入口只进入待PoC范围。目标Top100与实际Top25/部分覆盖分别记录，空页、登录页、403/429、解析失败不会生成完整零条榜。
