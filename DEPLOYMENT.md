# 部署与填写说明

正式环境使用 Java 21、Spring Boot、JDBC、MySQL 和 Nginx。Docker 与 MySQL CLI 都不是运行依赖；Flyway 由应用启动时通过 JDBC 执行迁移。

当前部署地址：`http://14.103.231.1:18080`。API 仅监听服务器 `127.0.0.1:18081`，Nginx 负责 `/api/` 代理；服务器专用 MySQL 监听 `127.0.0.1:13306`，不要修改机器上其他 MySQL 服务。应用目录为 `/opt/iaa-radar/current`，历史发布保留在 `/opt/iaa-radar/releases/`，systemd 服务名为 `iaa-radar`。本次 V2.2 发布应使用新的时间戳目录，保留 `20261027-1935-insights` 作为回滚版本。

## JDBC 参数

在部署服务器的 `/etc/iaa-radar/app.env` 填写：

```dotenv
DB_URL=jdbc:mysql://127.0.0.1:13306/iaa_radar?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
DB_USERNAME=...
DB_PASSWORD=...
BUSINESS_TIMEZONE=Asia/Shanghai
MATERIAL_DIR=/opt/iaa-radar/materials
APP_CONFIG_ENCRYPTION_KEY=至少32字节随机值
```

本地开发同样只需复制 `.env.example` 为 `.env`，把 `DB_URL` 改成你的 MySQL 地址。密码不写入代码、报告或聊天；服务账号文件权限为 `600`。

## AI 中转

AI 采集中心页面支持任意 OpenAI-compatible `/v1/chat/completions` 中转。已将本地 `.env` 的 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 安全同步到服务器环境文件，服务重启后页面只显示“已配置”，不会回显密钥。此次真实报告未调用模型；配置页可用不代表 AI 抽取链路已验收。

## V2.2 发布验收

本次发布包含 V8 Flyway 迁移、V2.2 推荐引擎、研究 API/页面、周报冻结和候选审计。上线后必须检查：

```text
curl http://127.0.0.1:18081/api/v1/research/profile
curl http://14.103.231.1:18080/api/v1/reports
curl http://14.103.231.1:18080/api/v1/health
```

然后在浏览器打开“研究目标与推荐”“研究材料与补证”“每周精选”，确认无 5xx、无凭据回显、无横向溢出。新报告 `researchSnapshot`、`availableCutoff`、候选审计和 `aiStatus` 必须可读；旧报告内容必须保持不变。

## 已验证版本

- Flyway V1–V5 成功；24 张业务表、236 个业务字段中文注释缺失 0。
- Apple 中国 iOS 100 条；AppBrain Google Play 美/英/德/法分别 99/100/100/100 条。美国原页面缺第90名，快照标记 `PARTIAL`，不补名次。
- 报告生成国内正式 0、海外正式 0、观察 10；纯 IAA 证据不足时保持 `UNKNOWN`。
- 部署浏览器验证：报告生成、中国榜100行、美国榜99行、移动端视图；Chrome/Playwright 无控制台错误。

证据位于 `validation/deployed/`，包括 JDBC 核验、API 反例、来源哈希、部署浏览器结果和截图。AppBrain 原始页面由开发机公开 HTTPS 获取，经 SFTP 上传后在服务器 localhost API 导入；服务器直连该站网络不可达，已如实记录运输路径。

## 更新与回滚

构建后先上传新静态资源和 JAR 到新时间戳发布目录，校验 HTTP 200，再切换 `current` 符号链接并 `systemctl restart iaa-radar`。更新前备份 `/etc/iaa-radar/app.env`、数据库和 `MATERIAL_DIR`。回滚只切换到上一个发布目录并重启，不删除历史快照。Nginx 修改前执行 `nginx -t`。

正式采集调度、权限认证、规则试算/自动判定、版本级 IAA 冲突、微信/抖音授权接入仍属于后续阶段。V2.2 的只读试算、约束登记、材料校验和历史冻结已实现，但真实授权字段协议与 AI `/v1/chat/completions` 连通性尚未宣称完成。

## 2026-10-29 V2.2 发布记录

发布目录为 `/opt/iaa-radar/releases/20261029-v22`，上线前已通过 JDBC 备份 29 张表、3061 行到 `/opt/iaa-radar/backups/db.pre-20261029-v22.sql`，并备份环境文件、图标目录。Flyway 已实际执行 V8；生产数据库表/字段中文注释核验为缺失0。服务当前指向该目录并为 `active`，公网 API `/api/v1/research/profile`、`/api/v1/reports` 和静态首页均返回 200。

正式生成报告 ID 5（周期 2026-10-26 至 2026-10-28，revision 2），保存了 V2.2 `researchSnapshot`、Skill/参数和 497 条候选审计；由于版本级 IAA 与连续历史证据不足，精选数量为0，不能把候选审计称为正式推荐。旧报告 ID 4 保持不变。

上线过程中首次尝试因缺少历史 V2 文件自动回退，未改写业务数据；补回兼容迁移后再次切换成功。


## 2026-10-27 UI 更新发布

当前发布目录 `/opt/iaa-radar/releases/20261027-1840-ui`，保留上一版 `20261027-1515`。V6 追加两张表；Java/MySQL/Nginx运行方式不变。新增 `ICON_DIR=/opt/iaa-radar/shared/icons`，图标实际PNG文件由应用 `/api/v1/media/{urlHash}.png` 提供。材料接入后需执行图标提取和缓存步骤，当前未启用定时缓存任务。

微信/抖音公开页面在开发机浏览器正常渲染后保存HTML，通过SFTP导入Java；没有解密隐藏响应或使用登录后数据。公开内容各20条，可按日期查看，缺失日不回填；图标引用和材料单独保留。

## 历史记录：2026-10-27 市场洞察发布

当前 `/opt/iaa-radar/releases/20261027-1935-insights`，上一版 `20261027-1840-ui` 保留。上线前通过 `JdbcBackup.java` 备份27张表（包含Flyway）、2138行至服务器 `/opt/iaa-radar/backups/pre-insight-20261027.sql`（600权限），不依赖MySQL CLI。环境文件已独立备份，未把凭据写入仓库。

V7已在本地配置的MySQL3306与部署专用MySQL13306执行。生产现有28张业务表、262个字段，中文注释缺失0。预览服务验证后已停止，正式服务仍由systemd和Nginx运行；不需要Docker。

实际启用 `INSIGHT_DAILY_ENABLED=true`、`INSIGHT_WEEKLY_ENABLED=true`：中国时间每天08:00采集Apple中国，周一09:00冻结上一周报告。`INSIGHT_APPBRAIN_ENABLED=false`：本次服务器探测连接失败（HTTP000、curl退出7）；已有四国快照仍可分析，不能宣称海外每日自动采集已经完成。微信/抖音仍走公开渲染材料导入。

已实际执行新采集方法：Apple中国返回98条，按来源日2026-10-27发布快照6011、PARTIAL；未补为100条。已生成本期报告4（2026-10-26至2026-10-28，不含结束日）：5条市场观察、正式IAA0；所有7日增长指标保持null。并发相同输入重试复用报告4，报告3冻结内容和证据保持不变。自然定时器下一次触发尚待到时观察，手动调用已走相同服务方法。

回滚应用切回上一个发布目录并恢复旧环境开关后重启；不要删除V7历史或修改旧迁移。备份包含业务数据，仅保存在服务器。新指标只对新快照记录附加口径，旧快照和旧报告不补写。
