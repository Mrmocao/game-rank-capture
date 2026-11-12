# 部署与填写说明

## 2026-11-12 精选配额20/40/40（当前发布）

当前release为`/opt/iaa-radar/releases/20261112-quota-122`。Skill2.7.0将5席分为成熟1、新入榜2、加速2；无新增迁移与环境参数，启动命令不变。报告36已按新规则生成，交付和机制摘要同步，前两周报告不变。构建、配额回归与生产浏览器验证见[验收记录](docs/acceptance/2026-11-12-quota-122.md)。

## 2026-11-12 每周精选研发交付与规则复盘

V24 新增周一研发交付包和周五上周规则复盘。默认计划为北京时间周一 09:00、周五 17:00，可用 `INSIGHT_WEEKLY_CRON` 和 `INSIGHT_WEEKLY_REVIEW_CRON` 覆盖。交付包和复盘均由 Java/JDBC 写入 MySQL；不依赖 Docker、MySQL CLI 或个人电脑，不自动发送外部消息。发布前执行 JDBC 备份、Flyway V24、后端测试和前端构建；回滚需同时回退应用并保留 V24 表，不删除已生成的冻结记录。线上验证见 [验收记录](docs/acceptance/2026-11-12-weekly-workflow.md)。

## 2026-11-09 研究卡片折叠

前端已更新到当前 release 的 dist，资源先上传、HTML 入口最后原子替换；无需重启 Java，无数据库迁移及环境参数变化。前端回滚备份为 `/opt/iaa-radar/backups/frontend-before-collapse-20261109.tar.gz`。构建通过；Playwright 在本地预览及生产站点验证 5 项默认折叠、独立展开/收起、键盘操作、完整导出、桌面 1440×1000 和手机 390×844，无页面运行时错误。Browser plugin 不可用，使用已有 Playwright/Chrome。

## 2026-11-09 研究补位

无新迁移，仍V23。按原Java/JDBC启动，环境配置不变。部署前备份，同周生成覆盖；其他周报告不得修改。策略及验证见[验收](docs/acceptance/2026-11-09-weekly-five.md)。

## 周报每周唯一（2026-11-06，当前规则）

按北京时间周一至周日每周一份；同周重新生成覆盖内容，报告 ID 固定，输入未变复用，其他周不变。V23 归并历史重复记录，仅保留最近生成的一份，旧链接通过轻量 ID 映射打开所属周报告；原始榜单和采集证据保持。

构建和启动命令不变，无新增环境参数。迁移前必须通过 JDBC 备份；迁移后不能直接切回旧生成程序，恢复旧语义需恢复备份。二次分析使用 `weekStart`、固定 `reportId` 及 `inputSha256` 标识所用结果。历史文中“每次修订保留旧报告”的描述由本规则取代。

设计与接口见 [整体架构](ARCHITECTURE-DESIGN.md)，发布与测试见 [本次验收](docs/acceptance/2026-11-06-weekly-retention.md)。


## 当前发布 · 2026-11-03

current为`/opt/iaa-radar/releases/20261103-opportunity`；应用JAR SHA256 `9bcdb5c6d7cb28c3a130bd3f8a61fcbe230a83e07676cff4f3f9cd057fdbe31c`。源代码功能提交b42ab9d，运维脚本修订随本次交付同步。仍免登录、Flyway V15，无新增环境变量。Web/采集/报告桥均active和enabled；每天北京时间08:30/23:00。

重启后重新用JDBC生成`/opt/iaa-radar/backups/before-opportunity-reboot-20261103.sql`，52表13492行；部署前后1372条旧冻结行内容不变。此前无完成回执的`before-opportunity-20261103.sql`不作为本次备份依据。新备份以`.partial`写入、完成后改正式文件名；篡改/不完整备份拒绝验证，192MiB堆通过真实正反校验。

当前主机8GB；Web1280MiB、采集1536MiB、报告桥512MiB内存上限。Web覆盖配置使用`scripts/systemd/iaa-radar-memory.conf`放入`/etc/systemd/system/iaa-radar.service.d/memory.conf`；其它服务模板同步当前限制。更新后daemon-reload，再在维护窗口重启对应服务。备份和采集串行验证，未增加采集并发。

真实批次466ea4af-cf6d-4f95-a863-e868252c55f6：8榜800条全部成功，数据库/原材料哈希/DOM定位一致；报告桥回执COMPLETE，生成报告18修订3，重试复用。公网桌面/手机、分组、证据、导出、旧报告16通过。完整记录见 [线上验收](docs/acceptance/2026-11-03-early-opportunities.md)。以下为历史发布记录。


## 当前发布：2026-11-02服务器原生采集

current为`/opt/iaa-radar/releases/20261102-server-capture`，JAR SHA256 `d73694f7488ec4cbe89b0aae51ec21b2168b59550bb1391b16419dbfc4f1455e`，无需新迁移（仍V15）。生产systemd独立采集与报告桥已enabled/active，计划北京时间08:30/23:00。个人Windows常驻进程与启动项已关闭；服务器新浏览器会话由用户手动登录，已直接取得796条并逐行核验，维护循环再次采集通过。当前报告12，历史9/10不变。

登录维护、会话提醒、服务单元、后台错误与精确未验收项见[docs/14-SERVER-CAPTURE-OPERATIONS.md](docs/14-SERVER-CAPTURE-OPERATIONS.md)。源码公开，SSH配置和浏览器profile均不提交。以下market-flow发布记录为迁移到服务器之前的阶段记录。


## 当前发布：2026-11-02市场流程

生产current指向`/opt/iaa-radar/releases/20261102-market-flow-r3`，JAR SHA256 `786ab07abc4c6125df23ac86d08db3833459200de079227c1b55921c43e93b73`，Flyway V15。免登录；保留Apple每日/每周Java调度，无服务器浏览器会话。Windows采集和同步进程已安装为用户登录启动项；实测后台发现真实材料重放、SSH送达、Java重解析、JDBC入库、报告10复用。

配置、来源入口、监控回执和重试见[docs/13-MARKET-FLOW.md](docs/13-MARKET-FLOW.md)。V15前备份51表10,086行，冻结记录1,263行比对不变。上一发布保留，应用切回旧目录后保留追加迁移。以下2026-10-30内容为历史验收，不代表当前源覆盖。


Java 21、Spring Boot、JDBC、MySQL 8、Nginx；Docker 与 MySQL CLI 均不是依赖。迁移由应用启动时通过 Flyway/JDBC 执行。当前代码只维护最新设计，旧发布目录仅供事故回滚，旧周报作为历史事实保留。

## 当前部署

| 项目 | 当前值 |
| --- | --- |
| 网页 | http://14.103.231.1:18080 |
| 发布 | `/opt/iaa-radar/releases/20261030-v28` |
| 当前链接 | `/opt/iaa-radar/current` |
| 应用服务 | `iaa-radar`，API `127.0.0.1:18081` |
| 专用MySQL | `127.0.0.1:13306/iaa_radar` |
| 环境文件 | `/etc/iaa-radar/app.env`，权限600 |
| 原始材料 / 图标 | `/opt/iaa-radar/shared/materials` / `/opt/iaa-radar/shared/icons` |
| 回滚发布 | `/opt/iaa-radar/releases/20261030-v27` |

开发机 `.env` 指向3306配置库，生产应用使用13306专用库，两个实例不得混淆报告ID。旧材料仍有引用指向历史发布目录，清理目录前须检查raw_locator，不能一并删除。

当前按业务方要求不启用用户登录体系：生产 `PROFILE_ACCESS_TOKEN` 留空，浏览器直接进入单组织工作区。代码仍保留可选的共享口令开关，后续若需要保护公网入口可再配置；当前没有注册、用户、角色或企业SSO。旧的本地口令文件仅是上一轮部署遗留，不作为当前登录要求。

## JDBC与AI配置

按 `.env.example` 填写，服务端示例：

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:13306/iaa_radar?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=填写账号
DB_PASSWORD=填写密码
BUSINESS_TIMEZONE=Asia/Shanghai
MATERIAL_DIR=/opt/iaa-radar/shared/materials
ICON_DIR=/opt/iaa-radar/shared/icons
APP_CONFIG_ENCRYPTION_KEY=填写独立随机加密密钥
PROFILE_TENANT_ID=default
PROFILE_ACCESS_TOKEN=填写独立随机访问口令
```

AI配置页接受OpenAI-compatible Base URL、模型、Key、超时和temperature。Base URL通常以`/v1`结尾，服务端追加`/chat/completions`。页面保存后Key使用AES-GCM加密；环境中首次配置作为默认。授权摘录须在档案中明确确认可发送至模型，不能把供应商配置存在称为真实模型效果已验收。

## 构建与发布

```text
mvn -q -f backend/pom.xml test
npm ci --prefix frontend
npm --prefix frontend run build
mvn -q -f backend/pom.xml -DskipTests package
```

1. 通过JDBC创建新的数据库备份；备份环境文件、材料和图标，敏感文件权限600。
2. 新建发布目录，上传JAR与dist；比对JAR SHA256，不能覆盖当前运行包。
3. 独立预览端口启动同一构建；预览必须关闭每日/每周调度。Flyway执行增量迁移后检查定义、产品、旧报告和历史记录不变。
4. 保留原生产环境参数及调度开关，切换current并重启iaa-radar。Nginx的Host使用`$http_host`保留端口；`proxy_read_timeout 360s`覆盖模型最长300秒。修改后先`nginx -t`再reload。
5. 公网验证登录、Cookie、未登录401、档案8分区/历史/导出、榜单进入对应范围和旧周报；确认不暴露Key。生产禁止使用dev profile。

当前HTTP地址可运行现有流程；配置TLS后由HTTPS入口代理并传递协议，登录会话应使用Secure Cookie。默认会话仅保存在单实例内存，多实例部署前须改为共享会话与统一限速。

## 2026-10-30 验收

本地80项测试通过，包含实际MySQL回滚、独立并发事务、来源目录和本地mock HTTP模型；本轮后端测试与前端构建再次通过（现有大包提示保留）。生产当前运行 `/opt/iaa-radar/releases/20261030-v28`，Flyway校验12个迁移均通过，服务重启后状态为active。切换前的 `20261030-v27` 保留为回滚目录。

应用身份、81字段定义、指定日期产品和报告接口实际验证；V2.7来源目录42项、门户12项和28字段、来源详情与执行日志API已部署。S03 Apple公开RSS在生产登记为V3_POC，实际运行4次（3次完整100条、1次98条部分覆盖），执行日志能回指来源名称；其它候选来源仍关闭。生产按业务要求关闭共享登录，浏览器直接进入工作区。历史冻结数据未被本次切换改写；未向真实模型服务或Firecrawl发起请求。

## 调度与来源边界

本次更新保留原生产配置：Apple每日采集/每周报告定时器按 `INSIGHT_DAILY_ENABLED` / `INSIGHT_WEEKLY_ENABLED` 控制；AppBrain服务器网络不可达仍关闭自动采集；微信/抖音公开前20仍为材料导入，不称为完整Top100。预览服务不执行调度。自然定时器长期稳定性未完成持续观察。

既有免费来源验证包括Apple中国和AppBrain美英德法，缺失行保持PARTIAL，版本级IAA不足则无正式精选。旧报告不可被新字段、新模型或新配置改写。真实来源权限/保存协议、实机媒体、玩家样本与模型效果仍有缺口。

## 回滚

停止新服务，将current切回上一发布目录，恢复对应备份环境文件后启动；新增V9～V11表保留，不drop、不清空历史、不改Flyway校验和。回滚到旧应用后如需继续保护旧接口，应由反向代理保留访问限制。历史V2 seed迁移已发布，不能从构建中删除。仅当需要完整灾难恢复时才使用数据库和材料备份；应先在独立库演练，本轮未执行全量恢复演练。

核验备份已有冻结行：`java --class-path <mysql-connector-j.jar> scripts/JdbcVerifyPreserved.java <env-file> <backup.sql>`。只打印统计，不打印业务正文、密码或访问口令。

## 2026-11-03 发布前阻塞（已解除）

首轮尝试因服务器内存耗尽导致SSH/API超时，未切换版本。用户重启并提供8GB环境后，按本文顶部流程重新备份、部署、实采及核验完成；不沿用未确认完成的备份结果。


## 2026-11-03 跨平台机制发布（当前）

当前release `/opt/iaa-radar/releases/20261103-cross-platform-final`，后端JAR SHA256 `a1a277757fef2110692937a3c15d12296724c2eb6008e0c1005e73cdee25e583`；Flyway V16。继续通过JDBC连接服务器app.env指定的13306实例，不使用开发.env的3306实例替代；免登录，无Docker要求。

最终备份 `/opt/iaa-radar/backups/before-cross-platform-final-20261103.sql`：54表14312行，1375条既有报告/榜单冻结行保持。Web、独立采集、报告桥已恢复active，沿用1280/1536/512MiB上限。报告20/修订4重试复用；16/18旧哈希不变。没有新增常驻进程，也未为本轮重复进行真实抓取。验收与来源缺口见docs/acceptance/2026-11-03-cross-platform.md。

## 商店榜单与海外执行器

V17先JDBC备份/迁移再发布Java及前端；海外不使用本机worker。专用HTTPS受限接收、GitHub Secrets、计划启停、无个人电脑依赖及验证步骤见 [运行手册](docs/17-STORE-CHARTS.md)。每次发布须验证旧冻结行、三项systemd服务与实际来源回传。

## 2026-11-03 周报读取性能

release `20261103-report-feedback-final2`，JAR SHA256 `230d355460822c0d877f2df897ddef89248431c7e356f544f8a4b7d2d128f2c2`。发布Java和前端同时更新；无新增迁移/参数。nginx业务站点server块启用JSON gzip（见scripts/nginx/report-response-gzip.conf），执行nginx -t后reload。HTML入口仍引用带内容哈希资源，刷新加载新前端。备份核验使用当前scripts/JdbcVerifyPreserved.java，在192MiB下分块核对。

## 2026-11-04 Firecrawl与在线工作台（当前）

用户确认后已切换 `/opt/iaa-radar/releases/20261104-online-web`，JAR SHA256 `f5550f426a991e55f56062418b413c1616502c3f244d899cf5a0dc8fc1e870ac`；Flyway V18。升级前备份 `/opt/iaa-radar/backups/before-online-web-switch-20261104-1122.sql`，54表21605行，1482条既有冻结行保持。原release 20261103-report-feedback-final2保留。Web/Worker/报告桥active，08:30及23:00原计划保持。

Firecrawl运行于 `/opt/iaa-firecrawl`，API仅127.0.0.1:3002，4核16GB；五容器，固定官方镜像摘要、readiness探针、restart策略。服务端app.env新增CAPTURE_FIRECRAWL_INTERNAL_URL与独立CAPTURE_TOOL_ENCRYPTION_KEY，Web/Worker均已加载。工作台服务配置已启用，自建内部服务无需云端Key。生产游民星空目标已发布v1，真实PREVIEW与LIVE均成功50条，暂为手动执行。

公网HTML/JS/CSS与构建摘要一致，桌面/手机交互验收通过。详情、证据与限制见[验收](docs/acceptance/2026-11-04-online-web.md)，在线操作见[工作台说明](docs/18-ONLINE-WEB-CAPTURE.md)。回滚先停Worker/报告桥，将current切回原release并恢复对应环境备份，重启服务；保留V18及采集证据，不删除表和持久卷。


## 2026-11-04 免费商店指标（当前）

当前release `/opt/iaa-radar/releases/20261104-game-metrics-r2`，JAR SHA256 `bb22f3ca2bc4b530ba2e61cf14b32466dacfaaebcdcd6b117acf18f34a8a3106`。V19扩展四张独立采集表；备份 `/opt/iaa-radar/backups/before-game-metrics-20261104.sql`（58表23180行），1483条已有冻结行核验保持。开发3306库另有本机备份，365条冻结行保持。

新增常驻 `iaa-radar-metrics`，自动发现已启用，成功缓存24h、失败退避6h；原榜单08:30/23:00、AppBrain08:40/23:10保留。海外详情 `overseas-metrics.yml` 每小时55分、最多100项，支持手动批次。新增CAPTURE_REMOTE_METRIC_TOKEN只在服务器app.env保存，桥接沿用海外TLS和外层令牌，gzip与请求大小限制已真实验证。原文详情/来源历史及在线设置见[运行说明](docs/20-GAME-METRIC-CAPTURE.md)。

回滚先停止metrics，再切回上一release；暂停海外metrics工作流，保留V19和材料。若回滚到V18应用，Flyway须通过显式target设置处理较新迁移，不删除V19历史。无需恢复整个数据库覆盖后续采集。正式生产和预览不得同时启用榜单调度。


## 2026-11-04 榜单观察指标展示（当前）

release `/opt/iaa-radar/releases/20261104-ranking-metrics`，JAR `f2efa9d53a8cc865cb510baebfd1407c280b034220b50479e348ed16f1487233`，保持V19无迁移。备份before-ranking-metrics-20261104.sql，1483条冻结行保持。只读新接口与共享前端展示通过生产验证，旧release game-metrics-r2保留。详情见[验收](docs/acceptance/2026-11-04-ranking-metrics.md)。

## 2026-11-05 公开资料补充（当前）

用户选择仅抓取无需登录的公开资料后，生产切换到 `/opt/iaa-radar/releases/20261105-public-profiles`，JAR SHA256 `9240e26ae1a39d032443284b7b44e0e0c4b5cb979cc6a53b5d18da276b13b65f`；应用 V20 新增独立 `capture_public_profile_observation` 表。切换前备份 `/opt/iaa-radar/backups/before-public-profiles-20261105.sql`，62表29778行，1587条冻结记录验证保持。榜单、报告桥和指标worker均恢复active，原08:30/23:00计划不变。公开资料入口只手动执行，不加入定时榜单任务；服务器直连七麦当前拿到未渲染页面，实际运行记录 `PUBLIC_PROFILE_FIELDS_MISSING`，没有写入伪成功数据。详情见[验收](docs/acceptance/2026-11-05-public-profiles.md)。
