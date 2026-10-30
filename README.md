# 游探 / 锦鲤云 IAA 产品情报系统

GitHub 仓库：[Mrmocao/game-rank-capture](https://github.com/Mrmocao/game-rank-capture)。当前整体架构与后续开发维护基线为 [ARCHITECTURE-DESIGN.md](ARCHITECTURE-DESIGN.md)。仓库同步源码、自动化测试、迁移与设计文档；`.env`、数据库备份、原始采集材料以及包含部署会话的 `validation/` 目录仅本地保留。文中历史验证材料链接指向本地交付文件，克隆仓库不包含这些现场文件。

采用 Java 21 + Spring Boot 3.5.6 + MySQL 8 + Flyway，前端采用 Vue 3 + TypeScript + Vite + Element Plus。交接设计、原始契约和验收场景位于 [`docs/`](docs/) 与 [`contracts/`](contracts/)。

## 当前源码同步状态（2026-10-30）

本次 GitHub 同步包含最新 V2.3 设计包及游戏档案的开发中代码，线上仍为下文记录的 V2.2 发布。新增档案字段目录、解析服务、API、工作区访问控制、V9 迁移和 `GameProfile.vue`；档案组件尚未接入主页面，工作区登录表单尚未接入，档案与推荐模型尚未完成联调，不能视为已验收功能。

本次执行 `mvn -q -f backend/pom.xml test`：41 项，35 项通过、6 项跳过、0 失败；`npm --prefix frontend run build` 通过，保留现有大包提示。未运行真实 MySQL 集成检查，未执行 V9 迁移，未验证新档案页面或生产部署。V9 会在应用下次启动时由 Flyway 执行，部署前须备份并完成 JDBC 迁移与业务验收，详见 [MIGRATION-NOTES.md](MIGRATION-NOTES.md)。

后续仍以 [ARCHITECTURE-DESIGN.md](ARCHITECTURE-DESIGN.md) 为唯一维护入口，继续完成页面接入、证据复核与冲突测试、推荐输入关联和真实数据库验证；本次同步仅保存当前开发进度，不切换线上应用。

当前交付包含 V2.2 研究工作区、可重复的开发模拟数据和产品库、榜单观察、详情、多人选用、周报冻结、候选审计与 CSV 导出的闭环。模拟数据在来源、运行和产品名中明确标记 `DEMO`，不代表真实来源、IAA 结论或生产运行。

外部信息抓取的独立模块见 [`external-capture/`](external-capture/README.md)，模块边界与现有耦合审阅见 [采集模块抽象](docs/09-EXTERNAL-CAPTURE.md)。该模块独立构建运行，输出来源观察与证据文件，不依赖业务入库、规则或周报；现有后端尚未迁移到该模块。

## 快速启动

1. 安装 Java 21、Maven 3.9+、Node 20+ 和 MySQL 8。Docker 不是运行前提。
2. 复制 `.env.example` 为 `.env`，按需修改数据库连接。
3. 在 `.env` 填写 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`；应用通过 JDBC 连接已创建的 `iaa_radar`，不需要 MySQL CLI。
4. `mvn -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=dev` 启动 API。Flyway 会执行迁移和开发 seed。
5. `npm install --prefix frontend; npm run dev --prefix frontend` 启动 Web，默认访问 `http://localhost:5173`。

`iaa_radar` 已创建并连接成功，Flyway 已通过 JDBC 迁移至 V8；研究工作区新增的表和字段也全部有中文注释。连接说明见 [`MYSQL-CONFIG.md`](MYSQL-CONFIG.md)。真实验证启动不加 `dev`，避免继续加入 DEMO seed。

AI 配置在“配置管理 → AI采集中心”填写。生产环境必须设置 `APP_CONFIG_ENCRYPTION_KEY`；它用于 AES-GCM 加密 AI Key。首次没有数据库配置记录时，页面会读取 `.env` 中的 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 状态；在页面保存后以数据库加密配置为准。周报在“实际使用 → 每周精选”按当前日期生成；也可调用 `POST /api/v1/admin/reports/generate?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD`。生成前必须已有已发布规则版本和有效榜单/IAA证据。

开发 API 默认 `http://localhost:8080/api/v1`。生产环境禁止使用 `dev` profile；生产密钥仅通过环境变量或密钥管理注入。

## 当前 V2.2 基线（2026-10-29）

当前实现基线是 [`ARCHITECTURE-DESIGN.md`](ARCHITECTURE-DESIGN.md) 与设计包 `docs/14`～`docs/16`。V8 研究迁移、V2.2 六维范围推荐、材料导入/校验、补证任务、历史冻结和研究页面已完成本地 JDBC 集成验证。正式环境切换必须使用本次构建的发布目录并保留现有目录以便回滚。

V2.2 每周目标默认 8 款，可配置 5～10 款；资格优先于排序。未知字段使用范围而不是补 0，`READY/CONDITIONAL/LEAD/PAUSED/OUT_OF_SCOPE` 只在证据和约束满足时产生。当前真实材料没有版本级 IAA、身份、实机和连续历史证据，因此新报告可以生成候选审计，但不会虚构正式精选；报告元数据会标明 `aiStatus=NOT_CALLED`。

## 本次完成

- 需求追踪矩阵、阶段计划、DDL/API 一致性核查、来源验证缺口记录。
- MySQL 初始迁移（完整历史模型）与可重复 DEMO seed。
- 后端按业务日查询统一产品行、跨平台版本、快照和缺失状态；选用记录支持多人、日期校验、幂等键和乐观锁。
- Vue 蓝白紧凑后台：产品库、榜单观察、选用记录为可查询页面，每周精选支持冻结事实与观察项；AI配置可保存、规则配置可版本化，但规则试算及自动判定未完成；支持日期和筛选 URL 状态、详情抽屉、选用弹窗、导出。
- 新增 AI 采集中心配置页：支持 OpenAI-compatible 中转的 Base URL、模型、API Key、超时和 temperature；密钥服务端 AES-GCM 加密，读取只返回已配置状态。
- 周报核心规则已升级为 V2.2：默认目标 8 款、允许 5～10 款；Java 按资格、覆盖、六维范围、队列/玩法上限和探索名额确定候选，冻结榜单/日期/材料/证据理由；AI 只做授权材料提取、分类、证据定位与文案。
- 日期/选用顺序与请求约束单元测试；Playwright 已用模拟 API 验证桌面产品库/榜单导航、空数据状态及移动端首屏；本次另有真实 MySQL 采集、快照、报告回滚集成验证；多人并发等未覆盖场景仍待验收。

## 最近一次真实验证（2026-10-27）

Apple 官方游戏分类 RSS（genre=6014）CN 100 条、AppBrain Google Play 美英德法 399 条已在部署环境完成真实材料链路验证；美国榜保留缺失第90名并标记 PARTIAL。引力引擎、GameLook、微信合集已完成来源核查。报告正式精选仍要求版本级 IAA 证据；当前部署报告为正式0、观察10。完整结果：[验证报告](REAL-VALIDATION-REPORT-2026-10-27.md) / [中文数据字典](validation/database-dictionary-after.md)。

真实运行：`mvn -f backend/pom.xml spring-boot:run`；`npm run dev --prefix frontend -- --host 127.0.0.1`；Apple采集脚本 `powershell -File scripts/validate-free-source.ps1`；AppBrain脚本 `powershell -File scripts/validate-public-web.ps1 -Countries us,gb,de,fr`。`MATERIAL_DIR` 默认相对于后端工作目录的 `./data/materials`，用于保存原始材料；应随数据库一同备份。

实际检查：19项后端测试通过（含3项真实MySQL回滚集成测试），前端构建通过；部署 Chrome/Playwright 生成报告、进入中国100行和美国99行榜单、返回报告通过，控制台无错误。生产认证权限尚未完成。

## 尚缺与边界

只有2026-10-27一个真实业务日，无法判断7日上涨或持续表现；没有版本级纯IAA证据，因此不输出正式精选。微信、抖音尚未接入；AppBrain四国通过开发机取材后服务器导入，服务器直连网络不可达，商业再分发授权未核实。真实每日调度、规则试算/自动市场判定、版本级证据冲突策略和AI完整调用链尚未完成。配置页存在不代表这些能力已验收；V2.2 已提供只读试算与材料校验接口，但真实授权来源和模型连通性仍需单独验证。

周报观察按来源介绍关键词初筛，可能包含内购或大型游戏，须人工复核。报告1～4为调试历史，不作为本次验收；不覆盖旧版本。采购参考见 [PURCHASE-PLAN.md](PURCHASE-PLAN.md)。

## 2026-10-29 V2.2 正式发布

已部署 `/opt/iaa-radar/releases/20261029-v22`，Flyway V8 和 V2.2 研究工作区实际运行。发布前 JDBC 备份 29 张表、3061 行；生产 API、研究方案接口、历史周报接口和静态首页均返回 HTTP 200。正式报告 ID 5 保存 497 条候选审计但没有虚构精选，`aiStatus=NOT_CALLED`；缺少版本级 IAA、连续榜单历史和授权字段协议仍会使结果保持待补证。

## 目录

- `backend/`：Spring Boot 模块化单体 API、Flyway、测试。
- `frontend/`：Vue 3 Web。
- `contracts/`：DDL 和 AI JSON Schema。
- `docs/`：交接需求与验收。
- `traceability.md`：需求追踪及阶段状态。
- `ARCHITECTURE-DESIGN.md`：当前唯一的整体架构、数据语义、页面/API、验证和持续维护基线。
- `MIGRATION-NOTES.md`：迁移、seed 与回滚说明。
- `MYSQL-CONFIG.md`：JDBC 数据库连接填写说明。
- `SOURCE-ONBOARDING.md`：给供应商/数据服务商的询价与试采模板。
- `PURCHASE-PLAN.md`：按平台拆分的采购模块、询价条款和推荐顺序。


## 2026-10-27 界面与国内榜单更新

产品库、榜单观察、每周精选已按业务方三张参考图调整为蓝白紧凑后台：四项真实统计、状态标签、日期/平台/地区筛选、独立名次列、游戏缩略图、三列周报卡片及 Markdown 导出。参考图中的虚构趋势没有搬入真实页面；D-7 缺失仍显示“—”。非本项目功能的顶栏导航没有添加空按钮。

微信人气榜、抖音热门榜：引力引擎未登录页面各20条真实观察，显示名称、图标、发行方、原始名次。来源没有提供已核验的平台 appid，保存到 `public_chart_observation`，不按名称冒充产品或与 iOS 强行合并。只代表公开前20，不代表完整Top100。国内免费榜为 iOS 中国；海外免费榜为 Google Play 美英德法。不同榜单口径不互换。

V6 新增 `public_chart_observation` 和 `snapshot_media`，全部列附中文说明。图标来源从已保存材料按哈希复核后提取；快照图标引用独立存储，历史排名及报告事实不变。311个来源图标已转存PNG至服务器 `ICON_DIR`，保留原URL、原文件哈希和缓存文件哈希；当前网页优先从本站读取。

复现入口：`POST /api/v1/admin/sources/gravity/collect-material?platform=WECHAT|DOUYIN` 提交包含来源日期、选中平台、日榜的实际渲染HTML；`POST /api/v1/admin/sources/media/extract` 解析已留存材料图标。图标缓存脚本 `python scripts/cache-source-icons.py` 当前针对已部署地址，生成清单后经SSH上传，不属于已上线调度。

验收：23项后端测试通过（含4项真实MySQL回滚）；`npm run build --prefix frontend` 通过。浏览器验收脚本 `node validation/ui-acceptance.cjs`，结果见 `validation/deployed/ui-browser-check.json`。桌面1920×953、笔记本1440×1000、手机390×844；无Browser插件，使用Playwright与Chrome。图片未提供或失效时有明确占位，未生成假的游戏图标。

## 历史记录：2026-10-27 市场洞察与每周精选升级

新需求：每期**合计最多5款**，区分当前热度与真实增长，保存历史。已实现同榜D-1/D-7精确对比、7日持续/缺日统计、可解释关注分、跨平台产品去重、已制作排除、全候选审计和不可变报告。页面新增历史版本、Skill查看、生成截止日、数据覆盖、完整论据和下一步验证。旧10款报告原样保留。

实际加载的Skill：[SKILL.md](backend/src/main/resources/skills/market-weekly/SKILL.md)与[policy.json](backend/src/main/resources/skills/market-weekly/policy.json)。当前使用Java确定性模型，**AI未调用**。模型阈值是建议默认值，不是盈利结论。详细方法、来源扩展与缺口见[MARKET-INSIGHT.md](MARKET-INSIGHT.md)。

Flyway V7通过JDBC迁移，无需MySQL CLI或Docker。新增报告Skill/候选冻结字段、幂等输入哈希、生成锁和采集尝试记录；全部新表/字段有中文注释。每周任务由Java执行，历史生成不是Codex聊天自动化。

开发默认关闭定时器。部署可设置 `INSIGHT_DAILY_ENABLED=true`（每天08:00）、`INSIGHT_WEEKLY_ENABLED=true`（周一09:00生成上一周）；`INSIGHT_APPBRAIN_ENABLED` 仅在服务器网络验证后开启。时区均为Asia/Shanghai。运行记录在AI采集中心；失败不发布榜单。完整环境变量见 `.env.example`。

验证：33项后端测试通过（含4项真实MySQL检查，其合成变更事务回滚）；前端生产构建通过；Skill结构校验通过。尚无连续8日真实历史，不能以模拟上涨测试宣称真实增长发现。来源长期运行仍需观察；生产登录权限和完整IAA冲突规则仍待完善。

历史部署：`20261027-1935-insights`；报告4曾生成5条真实市场观察（正式IAA0），精确D-7均缺失，不输出增长结论。该版本已由2026-10-29 V2.2 发布替换；新报告入口仍可通过历史列表访问。

浏览器验收：已部署Chrome/Playwright验证5款展示、真实图标、论据展开、Skill副本、历史10款报告切换及刷新保持、生成重试复用、候选审计、Markdown导出、采集状态和390px手机无横向溢出，控制台错误0。Browser插件未提供，使用现有Playwright与Chrome；Vite仍有原有大包警告。验收材料保存在本机 `C:/Users/admin/AppData/Local/Temp/iaa-insight-20261027/`，不包含环境密钥。
