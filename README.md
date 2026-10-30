# 游探 / 锦鲤云 IAA 产品情报系统

从可追溯的市场材料发现游戏机会，保留独立平台榜单、证据、缺口、研究行动与每周历史。当前工程统一采用 **V2.7 采集与门户设计、V2.3 游戏档案设计，推荐算法保持 2.2.0**，不提供旧版/新版切换。整体设计与后续开发维护入口是 [ARCHITECTURE-DESIGN.md](ARCHITECTURE-DESIGN.md)。

仓库：[Mrmocao/game-rank-capture](https://github.com/Mrmocao/game-rank-capture)。源码、迁移、测试、契约与设计文档纳入 Git；`.env`、访问口令、备份、原始材料及 `validation/` 现场记录不上传。

## 当前实现（2026-10-30）

- 产品库保留跨平台统一产品行，各平台/市场名次独立；无当日榜单仍保留已知档案，明确显示缺失。
- 游戏档案接入主页面：12 信息域、81 字段、8 个分区，支持平台/市场切换、指定知识日、深链接、证据历史、资料分母、媒体引用与 JSON 导出。
- 材料提交进入待复核，复核保留审计；AI 草案与来源事实分开。应用更新、过期、冲突和显式替代分别处理，AI 不能证明纯 IAA。
- 补全任务按范围去重并校验修订；多人选用相互独立，记录参考平台/市场；当前协作进度与历史市场事实分开。
- 授权材料可提交至已配置的 OpenAI-compatible 模型；输出通过 JSON Schema、字段类型、引用与范围校验，仅生成待复核 AI 材料。失败/弃权留痕并形成补证任务。
- 每周精选沿用 V2.2 六维范围模型，冻结候选、规则、研究方案和证据输入；新配置不改旧报告。最新设计默认目标 8 款、允许 5～10 款，资格不足就少出。
- 单组织工作区登录：生产配置访问口令，浏览器使用 8 小时 HttpOnly 会话；API 可用 Bearer。尚无企业 SSO 和人员级角色管理。
- V2.7 来源中心：42 个来源登记、12 个小游戏门户和 28 个门户字段模板；来源登记、真实运行、执行日志、证据血缘分开显示。所有新增来源默认关闭，未执行不显示成功率。
- 采集运维导航：信息范围与字段、数据源管理、工具与接入、采集任务、执行日志、异常与补采、信息来源与证据。V12 持久化 `source_definition` 和任务定义，已有运行表继续保存真实尝试。

后端 Java 21 / Spring Boot 3.5.6 / MySQL 8 / Flyway；前端 Vue 3.5.13 / TypeScript 5.7.3 / Vite 6.0.7 / Element Plus 2.9.1。依赖由 Maven 配置及 npm lock 文件固定。AI Schema 校验使用 `json-schema-validator:1.5.9`，已按 Maven Central 元数据核对并在 Java 21 构建验证。

## 启动与配置

1. 安装 Java 21、Maven 3.9+、Node 20+、MySQL 8。**不需要 Docker 或 MySQL CLI**。
2. 复制 `.env.example` 为 `.env`，填写已创建数据库的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`，通过 JDBC 连接。
3. 填写 `APP_CONFIG_ENCRYPTION_KEY` 以保护 AI 密钥。当前生产按业务要求关闭登录，`PROFILE_ACCESS_TOKEN` 留空即可；如以后需要公网共享口令，再填写独立值。`PROFILE_TENANT_ID` 由服务端固定。
4. 根目录执行 `mvn -f backend/pom.xml spring-boot:run`，Flyway 自动执行至 V11。现有库先备份；全新库不要先手工执行参考 DDL 再运行 Flyway。
5. 执行 `npm ci --prefix frontend`、`npm run dev --prefix frontend`，访问 `http://localhost:5173`，开发 API 默认 `http://localhost:8080/api/v1`。

开发模拟数据仅在明确需要时使用 `dev` profile。生产不得启用 `dev`；已执行过的历史 V2 seed 迁移必须保留以满足 Flyway 校验，开发另使用可重复 seed。DEMO/SYNTHETIC 材料不是市场证据。

AI 在“配置管理 → AI采集中心”填写 Base URL（通常以 `/v1` 结尾）、模型、Key、超时和温度；客户端追加 `/chat/completions`。首次读取可使用 `.env` 配置，页面保存后以数据库加密配置为准。档案“证据与补全”可选择已获权限的摘录、1～10 个字段并确认可发送至模型。真实模型效果尚未验证。

## 验证记录

本轮实际执行：

```powershell
$env:RUN_LIVE_MYSQL_TESTS='true'
mvn -q -f backend/pom.xml test
npm --prefix frontend run build
mvn -q -f backend/pom.xml -DskipTests package
```

后端汇总 **78 项，0 失败、0 错误、0 跳过**；包含真实 JDBC 回滚集成和独立并发事务检查。AI 传输采用本地 mock HTTP 服务，模型编排使用合成材料回滚测试，未调用真实供应商。前端构建通过，保留现有约 1 MB 大包提示。

Chrome/Playwright 已验证登录、产品库、完整 8 分区档案、历史、81 字段导出、刷新/返回、390px 移动宽度；无控制台错误与移动横向溢出。任务/复核/双负责人 UI 写流程用明确合成 API 响应验证，数据库事务另由 JDBC 测试验证。Browser plugin 不可用，因此使用已有 Playwright。临时脚本与截图保留本地，不进入仓库。

JDBC/Flyway 已在配置数据库与生产数据库执行 V9～V12；两者是不同实例/端口，不混用报告 ID。生产迁移前备份 33 表、3372 行，已逐行对比确认迁移前的 957 行报告及榜单冻结记录内容不变。发布与公网验收见 [DEPLOYMENT.md](DEPLOYMENT.md)，完整验收矩阵见 [traceability.md](traceability.md)。

## 真实数据与边界

已接入免费 Apple 中国游戏榜材料、AppBrain 四国公开材料，以及引力引擎微信/抖音公开前 20 观察；保留来源、日期、原文哈希和部分覆盖。AppBrain 来源为第三方，服务器直连不可达，既有材料经开发机获取后导入；无完整商业再分发授权结论。微信/抖音无稳定平台 appid 的观察独立展示，不按名称强行归并产品，不冒充完整 Top100。

当前仍缺版本级真实纯 IAA、连续可比历史、实机与授权玩家样本，不能宣称发现真实增长或输出合格精选。真实供应商 AI 调用质量、Firecrawl/门户自动采集、媒体上传/视频缓存、企业 SSO、长期定时器运行和备份恢复演练尚未验证。媒体当前保存外部来源引用，未下载不能显示为已缓存。免费来源可访问不等于获得批量采集、长期保存和再分发授权。

## 开发入口

- [整体架构与设计](ARCHITECTURE-DESIGN.md)：唯一持续维护基线；后续新增功能先更新此文。
- [迁移说明](MIGRATION-NOTES.md)、[部署说明](DEPLOYMENT.md)、[JDBC 配置](MYSQL-CONFIG.md)。
- [追踪与验收](traceability.md)、[采购建议](PURCHASE-PLAN.md)、[来源接入模板](SOURCE-ONBOARDING.md)。
- `backend/`、`frontend/`：当前 Java/Vue 工程；`contracts/`：DDL/API/AI 契约。
- `Game-Market-Insight-V2-Design-Pack/`：用户提供的设计依据与独立原型夹具；原型测试结果不代替工程验收。
- [独立采集模块](external-capture/README.md)：尚未整体替换后端现有采集层。
