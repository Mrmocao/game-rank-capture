# 迁移与开发 seed

> 当前最新迁移为 V12。V9～V12 已在配置库与生产库通过 Flyway/JDBC 执行；历史说明按当时状态保留，不代表当前待办。不修改任何已执行迁移的校验和。

`backend/src/main/resources/db/migration/V1__initial_schema.sql` 来自交接 DDL，并保留追加式历史和快照 revision 约束，同时包含 AI 中转配置表。`backend/src/main/resources/db/demo/V2__demo_seed.sql` 只在 `dev` profile 执行，通过固定 `demo-` 外部 ID 和 `DEMO` 来源标记，重复执行不会产生重复版本。`V3__ai_provider_config.sql` 为旧数据库补齐 AI 配置表，使用 `IF NOT EXISTS`。

生产启动必须先备份数据库，再执行 Flyway。回滚使用应用兼容回滚或恢复备份；不删除历史快照。部署和检查均通过JDBC，不需要Docker或MySQL CLI。

2026-10-27 已通过JDBC在真实MySQL执行并验证V4/V5。V4补表注释和关键列；V5补全剩余列（177条MODIFY，含3条原英文说明中文化），现为24表、236字段中文说明，缺失0。不改已执行迁移校验和。V5前后字段类型、默认值、可空、字符排序、键标记相同；未删表或业务记录。

- 现有库：启动后端由Flyway增量执行，不重跑CREATE。
- 全新空库的手工安装参考：`contracts/schema.sql` 已包含完整注释；不要先手工安装再直接运行Flyway，以免非空库基线冲突。
- JDBC核验：`java --class-path <mysql-connector-j.jar> scripts/DbInspect.java after`（在根目录执行，从.env读取凭据，只输出统计和数据字典）。
- 真实材料存储：`MATERIAL_DIR`，默认后端工作目录 `./data/materials`；哈希文件与数据库一起备份，迁移不能代替备份恢复演练。
- `RUN_LIVE_MYSQL_TESTS=true` 下执行Maven测试，模拟PARTIAL/坏材料/修订全部回滚；自增序号可能有间隙，这是正常现象。
- 备份恢复演练、并发迁移和生产部署尚未验证。


## V6 国内公开观察与图标

追加 `V6__domestic_chart_catalog.sql`，新增公开小游戏材料表（10列）和快照图标引用表（5列），均含中文注释，不修改V1～V5。已有历史排名、报告内容、证据引用不变；从哈希匹配的原始材料补提图标到新表。V6 已通过 JDBC/Flyway 在原开发库和部署库执行。两库端口分别3306与13306，不能混淆报告ID。

缩略图文件使用 `ICON_DIR`（服务器 `/opt/iaa-radar/shared/icons`），原始材料使用 `MATERIAL_DIR`（新材料 `/opt/iaa-radar/shared/materials`）。旧材料仍在原发布目录，不能删除仍被 raw_locator 引用的目录。数据库与这些文件必须一同备份。

## V7 市场洞察

新增 weekly_report.skill_snapshot / candidate_audit / input_sha256，保留旧行NULL；新增 insight_generation_lock 与 insight_collection_attempt。迁移由应用Flyway/JDBC执行，无需MySQL CLI。V1–V6不修改；旧报告事实及条目不会更新。应用迁移失败不得继续切换线上版本。

发布前可用 `scripts/JdbcBackup.java` 通过JDBC在服务器创建一致性备份（需新文件路径，不能覆盖）；输出仅表数/行数，不打印凭据或数据。备份含业务数据，服务器权限600。回滚应用仅切回旧发布目录，V7新增结构可保留，禁止删除历史报告或回改Flyway校验和。

# V8 V2.2 研究工作区（2026-10-29）

V8 通过应用启动时的 Flyway/JDBC 执行，新增 `research_profile`、`research_packet`、`research_constraint`、`research_outcome`，并为 `weekly_report` 增加研究方案、冻结研究快照和材料截止时间。所有新表和字段均带中文注释；研究方案、材料、约束和实验反馈只能追加，重复材料由内容哈希幂等，旧周报不按新材料或新规则重算。

部署前先使用 `scripts/JdbcBackup.java` 备份数据库、材料目录和图标目录。新版本启动失败时不得切换 `current`；切换成功后使用 `/api/v1/reports`、`/api/v1/research/profile` 和前端研究页面做验收。V8 回滚只切换应用发布目录，保留 V8 结构和历史报告，禁止删除迁移或业务数据。

V2.2 测试命令：

```text
mvn -q -f backend/pom.xml test
RUN_LIVE_MYSQL_TESTS=true mvn -f backend/pom.xml -Dtest=V22RecommendationEngineTest,ResearchPipelineMySqlTest test
npm run build --prefix frontend
```

真实来源、AI 模型和连续历史不足时，系统保留候选审计和补证任务，状态必须如实标为 `LEAD`、`PAUSED` 或空精选，不得用模拟材料宣称真实增长。

## 生产兼容修复（2026-10-29）

生产库历史中已存在 `V2 demo seed`，而整理后的源码曾只保留 `db/demo/V2__demo_seed.sql`，导致新 JAR 启动时 Flyway 报“applied migration not resolved locally”。已从上一发布包恢复原始 `db/migration/V2__demo_seed.sql`（不修改其内容/校验和），并将开发重复 seed 重命名为 `db/demo/R__demo_seed.sql`，避免同一版本号冲突。生产只扫描 `db/migration`，开发 profile 额外执行可重复的演示 seed。


## V9 游戏档案（已执行，2026-10-30）

新增市场条目、应用发布版本、档案证据、字段观察、AI草案、材料提交与复核、补全任务和媒体引用9张表；新字段有中文注释。V1～V8 历史迁移不修改。配置库和生产库已由应用Flyway/JDBC实际执行V9，真实材料复核、历史回放、任务和权限通过JDBC回滚集成验证。稳定平台实体 `platform_version` 与实际应用发布 `release_record` 严格区分。

PROFILE_TENANT_ID 由服务器固定组织范围；当前生产按业务要求将 PROFILE_ACCESS_TOKEN 留空，`GET /api/v1/access` 返回 `enabled=false`，浏览器直接进入单组织工作区。前端仍保留可选登录表单，只有以后配置共享口令时才会显示；脚本使用Bearer。旧API在启用口令后也会受认证保护，自动化调用应同步配置。

## V10 选用范围 / V11 AI运行审计 / V12 来源登记（已执行）

V10在 `selection_record` 增加 `tenant_id`、`reference_listing_id`、索引和外键。旧选用记录归default工作区，无法确认的市场范围保留NULL，不自动补写。新写入验证组织、产品、平台实体和listing匹配；并发重试/修订使用事务与行锁。V11新增 `profile_ai_run`，保存输入证据、字段、模型/提示/Schema版本、hash、状态和安全失败代码，不保存供应商密钥或原始错误响应。V12新增带中文注释的 `source_definition`、`collection_task_definition`，由设计包42项来源登记同步，默认生产关闭；真实尝试仍记录在既有 `insight_collection_attempt`，没有为未执行来源制造运行行。

开发配置库连接3306；生产应用专用库连接127.0.0.1:13306，两者独立。生产迁移前 `JdbcBackup.java` 输出33表、3372行，备份 `/opt/iaa-radar/backups/before-v23-20261030.sql` 权限600。预览应用在禁用调度的独立端口执行V9～V11后，通过 `JdbcVerifyPreserved.java` 将迁移前的957行weekly_report/report_item/ranking_snapshot/ranking_entry逐行对比：内容保持不变。此检查确认备份中已有行，没有把新追加行认作篡改；不替代完整恢复演练。

```text
java --class-path <mysql-connector-j.jar> scripts/JdbcBackup.java <env-file> <new-backup.sql>
java --class-path <mysql-connector-j.jar> scripts/JdbcVerifyPreserved.java <env-file> <backup.sql>
```

DDL参考 `contracts/schema.sql` 已与V9～V11对齐；实际安装由Flyway负责，勿对已安装库重复执行CREATE/ALTER。升级失败切回旧应用和原环境文件，保留新增结构及历史，禁止drop表、清空历史、Flyway repair或修改旧SQL。备份恢复须在独立库演练，目前未执行全量恢复演练。
