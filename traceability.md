# P0/P1 需求追踪矩阵

> 当前实现基线为 V2.2；下方早期 P0/P1 行保留历史状态，新增 V2.2 追踪见文末，状态以最近一条对应记录为准。

| ID | 类型 | 交付/位置 | 状态 | 验证 |
|---|---|---|---|---|
| C01/C02/C03 | 已确认 | Java API、平台/地区字典、MySQL 迁移 | 已实现 | `mvn test`、V1/V2 |
| C04/C05/C06/C10/C11/C12/C13/C14 | 已确认 | 产品/版本/榜单/快照模型，业务日查询与人工值边界 | P1 最小闭环 | API 测试、A01/A06-A11 |
| C07/C08/C09/C15 | 已确认 | 页面菜单、产品库/榜单/选用 API、报告占位页、CSV | P1 最小闭环 | 页面构建与 Playwright 模拟 API；周报生成留 P4 |
| D01 | 建议默认 | Java21、Spring Boot 3.5.6、MySQL8、Vue3/TS/Vite/Element Plus | 已锁定 | `pom.xml`、`package.json` |
| D02 | 建议默认 | 开发身份 `dev-user`；配置 API 预留鉴权边界 | P1 开发模式 | 生产鉴权待 U04 |
| D03/D04 | 建议默认 | Asia/Shanghai；默认日期最新可用、精选数 8 配置保留 | 已实现/预留 | DatePolicy 测试 |
| D05/D06/D07 | 建议默认 | 规则与证据表迁移，市场规则服务接口预留 | P3 | 迁移存在，运行逻辑留 P3 |
| A01 | 验收 | 产品列表统一一行、三个版本独立排名 | 代码已实现 | Playwright 模拟 API；MySQL 集成待验证 |
| A06-A11/A13 | 验收 | 日期、不回填、缺失/未入覆盖/部分状态、7日缺失 | 代码已实现 | `BusinessDateTest`；SQL集成待验证 |
| A21-A24 | 验收 | 多人选用、幂等、乐观锁、日期顺序 | 代码已实现 | `BusinessDateTest`/`ApiContractTest`；数据库并发待验证 |
| U01/U02 | 外部阻塞 | 真实来源授权、费用、字段、覆盖 | 阻塞 P2 | `SOURCE-VERIFICATION.md` |
| U03 | 外部阻塞 | AI 供应商、配额、预算 | 阻塞 P2/P3 | 保留 Schema 校验契约 |
| U04/U05/U06 | 外部阻塞 | 公司登录、部署/MySQL、对象存储/材料授权 | 不阻塞本地 P1 | `.env.example` |
| RPT-01 | 用户新增核心目标 | 每周国内最多5、海外最多5，证据和数字冻结 | 已实现服务 | `WeeklyReportService`；真实来源覆盖待 P2 |
| AI-01 | 用户新增配置需求 | OpenAI-compatible Base URL/模型/Key 配置页 | 已实现 | `AiConfigController` + AI采集中心页面；中转连通性待填写后验证 |

## 阶段计划

1. **P0（本次）**：核查契约；确认没有可复用工程；来源验证表和阻塞清单。
2. **P1（本次）**：工程骨架、迁移/seed、产品库/榜单/选用闭环和模拟测试。
3. **P2**：取得一份真实授权来源，完成原始材料→解析/Schema→校验→快照，并证明幂等和来源失败不造数据。
4. **P3**：版本级 IAA 证据冲突、规则版本/试算/发布、人工覆盖、身份关联与撤销。
5. **P4**：周报冻结/导出、观察提醒、真实调度和异常可观测，按已接入覆盖交付。

## DDL/API 核查结论

初始 `contracts/schema.sql` 已覆盖 API 中的产品、版本、来源、榜单、快照、证据、规则、选用、周报和审计实体。P1 补充了 API 实际需要的 `GET /meta/coverage`、产品查询聚合索引和选用幂等行为说明；DDL 中的 `idempotency_record` 可支持该契约。原草案未声明状态枚举、来源字段 JSON 结构和 tied-rank 策略，这些保留为 P3/P2 的受控校验项，没有在 P1 虚构实现。


## 2026-10-27 本次验证增量（以下状态优先于初期记录）

|需求/验收|完成位置|真实验证与边界|
|---|---|---|
|中文注释/可维护性|V4+V5、contracts/schema.sql、validation/database-dictionary-after.md|真实MySQL24表236列全部中文；字段结构无差异|
|P2免费来源|AppleGamesService/Controller、validate-free-source.ps1|CN/US iOS游戏分类各100条；US为补充，不替代GP|
|A07/A11/A13/A28|ProductQueryService、AppleGamesValidationTest、LiveMySqlPipelineTest|缺日不回填，D-7空，错配日期拒绝，相同材料幂等；已用真实MySQL|
|A10/A12/A25/A33|LiveMySqlPipelineTest|裁剪40条PARTIAL、修订冻结、坏材料不写成功快照；回滚事务。来源失败运行告警未做|
|A16/A17/A30/A31|WeeklyReportService|正式0、观察10；窗口内快照、DEMO隔离、覆盖缺口、无IAA不凑数|
|RPT-01/P4部分|报告12、Web、Markdown/HTML导出脚本|10条游戏观察逐条核原始排名/位置/哈希；未完成正式精选生产验收|
|Web真实链路|App.vue、WebConfig、ProductQueryService|页面生成→游戏榜100行→返回；桌面/移动检查，无JS错误|
|U03|AI配置已填写|本次AI未调用，端到端模型/Schema/文案校验未验证|
|U04/P3/P4|身份权限、规则试算、冲突、调度|仍未完成；不宣称完整生产交付|

后续优先：取得版本级IAA正面证据，核验现有候选；连续积累自然日榜单以试算持续/上涨规则；接入微信/抖音和GP实际来源；完成认证、调度与失败运行日志。无需再次创建已存在的MySQL数据库。

## 2026-10-27 来源扩展核查

|来源|可见事实|处理|
|---|---|---|
|引力引擎公开页|微信/抖音小游戏日/周/月榜，人气/畅销/畅玩；未登录前20，完整100和历史要求登录；公开API响应为加密文本|只记录来源能力与限制，不虚构字段、不采集加密未知字段|
|AppBrain公开页|Google Play美英德法游戏免费榜；国家、榜单、更新日、包名、开发商、评分、安装量显示值、近30日估算值|新增AppBrain HTML适配器；原始HTML+哈希，缺行PARTIAL，UNKNOWN IAA|
|GameLook|IAA行业文章、统计口径和案例背景，无逐产品榜单|保存检索页面证据，不作为排名来源|
|微信合集|当前返回账号被屏蔽页面|不入库，标记来源不可访问|


## 2026-10-27 参考图一致性与国内榜单增量

|需求|实现|验收与边界|
|---|---|---|
|C08 缩略图|SnapshotMediaService、snapshot_media、IconCache、GameIcon|从原始JSON/HTML按快照身份提取499个图标引用，缓存311个独立来源URL的实际图片；不修改冻结事实|
|C13/A34 菜单与页面|App.vue、style.css、ReportCard|按三张参考图恢复蓝白紧凑表格/三列卡片，菜单组可收起；未接入的外部模块不放空按钮|
|微信/抖音公开观察|PublicMiniGameService、public_chart_observation|各20行、来源日、哈希、重复导入幂等；身份待核验，仅榜单观察|
|A07/A28 日期与重跑|public-rankings API|缺失日期空数组；同材料不增加材料行；不伪造Top100|
|C11 榜单口径|平台/地区/榜单独立选择|微信人气、抖音热门、iOS/Google Play免费；不把热度与买量混排|
|C15 导出|Web Markdown导出|从当前冻结报告导出完整10条事实与证据，不改变规则或旧报告|

新实现使用既有Java/Vue工程，没有把reference/prototype的JS搬进生产。公开小游戏身份关联、完整Top100授权、调度和IAA证据仍待后续；当前公开40条的展示不代表这些能力已完成。

## MI：市场洞察新增要求（2026-10-27）

|ID|要求|交付|状态/边界|
|---|---|---|---|
|MI-01|核心热度和增长洞察|MarketInsightService，精确D-1/D-7、7日持续与缺失状态|已实现；真实历史尚不足|
|MI-02|合计最多5款|Skill策略、去重/轮选/淘汰原因|已实现；覆盖旧每组5款要求|
|MI-03|加载Skill，清楚的模型逻辑|JAR资源Skill/参数、正文和哈希冻结、Web查看|已实现；确定性模型，AI未调用|
|MI-04|每周历史可追溯|分页历史、URL reportId、相同输入幂等、新输入新修订|已实现并真实MySQL验证|
|MI-05|优化采集数据内容|来源指标定义、Apple声明上架日期/分类、8日覆盖、任务日志|已实现；历史快照不篡改|
|MI-06|每日/每周运行|Java定时任务与来源独立失败记录，按环境开关|部署启用状态以运行页为准，未声称长期试运行完成|
|MI-07|可靠选择论据|每款为什么现在看、事实、玩法假设、缺口和验证动作|已实现；版本级IAA仍须真实证据|
|MI-08|国内小游戏/海外扩展|公开20条覆盖留痕、AppBrain可选取材|稳定appid、完整Top100、服务器出站网络或授权仍有具体缺口|

## V2.2 研究工作区追踪（2026-10-29）

|需求ID|实现|状态|验证/边界|
|---|---|---|---|
|V22-01|`V22RecommendationEngine` 六维范围、资格分层、未知语义、队列/玩法上限|已实现|`V22RecommendationEngineTest`；未知仍为范围，不能解释为成功率|
|V22-02|`ResearchService` 研究方案、材料、证据引用、约束、实验反馈|已实现|`ResearchPipelineMySqlTest`；材料按 `available_at` 回放，坏材料不写成功快照|
|V22-03|`WeeklyReportService` 研究快照、截止时间、输入哈希、历史修订幂等|已实现|相同输入复用、变更输入新修订，旧报告冻结|
|V22-04|`V8__research_workspace.sql` 中文注释与 JDBC/Flyway 执行|已实现|MySQL 注释核验缺失0；生产部署需运行 V8 后复核|
|V22-05|研究页面、材料导入、只读试算、补证/约束和精选卡片|已实现|前端构建、桌面/390px Playwright；真实授权字段未完成|
|V22-06|AI OpenAI-compatible 配置与状态脱敏|部分实现|配置保存已测；真实模型调用、Schema/引用一致性和弃权链路待 P3|
|V22-07|真实微信/抖音/Google Play授权采集|外部阻塞|缺平台账号、字段协议、费用和再分发授权；保留公开材料与模拟契约测试|
