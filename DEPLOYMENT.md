# 部署与填写说明

Java 21、Spring Boot、JDBC、MySQL 8、Nginx；Docker 与 MySQL CLI 均不是依赖。迁移由应用启动时通过 Flyway/JDBC 执行。当前代码只维护最新设计，旧发布目录仅供事故回滚，旧周报作为历史事实保留。

## 当前部署

| 项目 | 当前值 |
| --- | --- |
| 网页 | http://14.103.231.1:18080 |
| 发布 | `/opt/iaa-radar/releases/20261030-v23` |
| 当前链接 | `/opt/iaa-radar/current` |
| 应用服务 | `iaa-radar`，API `127.0.0.1:18081` |
| 专用MySQL | `127.0.0.1:13306/iaa_radar` |
| 环境文件 | `/etc/iaa-radar/app.env`，权限600 |
| 原始材料 / 图标 | `/opt/iaa-radar/shared/materials` / `/opt/iaa-radar/shared/icons` |
| 回滚发布 | `/opt/iaa-radar/releases/20261029-v22` |

开发机 `.env` 指向3306配置库，生产应用使用13306专用库，两个实例不得混淆报告ID。旧材料仍有引用指向历史发布目录，清理目录前须检查raw_locator，不能一并删除。

生产已启用工作区访问口令。首次登录使用部署操作者本地 `data/deployment/workspace-access.txt` 中的口令；文件被Git忽略，不通过公开仓库分发。口令来自服务器环境变量 `PROFILE_ACCESS_TOKEN`；丢失时由部署管理员读取或轮换。浏览器持有8小时HttpOnly SameSite=Strict会话，重启后重新登录；脚本使用Bearer。当前为单组织共享访问，姓名为填报字段，尚无人员级认证/权限。

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

本地78项测试通过，包含实际MySQL回滚、独立并发事务和本地mock HTTP模型；前端构建通过（现有大包提示保留）。生产先备份33表、3372行到 `/opt/iaa-radar/backups/before-v23-20261030.sql`，预览端口18082成功执行V9～V11。比对957行历史报告/榜单冻结记录内容保持不变。预览关闭后正式服务运行最新包。

应用身份、81字段定义、指定日期产品和报告接口实际验证；Nginx使用公网Host/Origin的Cookie登录通过，开发机Chrome/Playwright直接访问公网验证登录、8分区、81字段导出、刷新、390px无溢出、榜单跳转iOS中国对应平台实体、历史报告4和未登录401，控制台错误0。服务器回访自身公网IP超时，改用本机Nginx加真实Host/Origin检查，公网浏览器从开发机独立验证。生产切换后再次核验957行冻结记录不变。UI写流程使用明确合成API响应，数据库写入则用独立JDBC测试；未向真实模型服务发起请求。

## 调度与来源边界

本次更新保留原生产配置：Apple每日采集/每周报告定时器按 `INSIGHT_DAILY_ENABLED` / `INSIGHT_WEEKLY_ENABLED` 控制；AppBrain服务器网络不可达仍关闭自动采集；微信/抖音公开前20仍为材料导入，不称为完整Top100。预览服务不执行调度。自然定时器长期稳定性未完成持续观察。

既有免费来源验证包括Apple中国和AppBrain美英德法，缺失行保持PARTIAL，版本级IAA不足则无正式精选。旧报告不可被新字段、新模型或新配置改写。真实来源权限/保存协议、实机媒体、玩家样本与模型效果仍有缺口。

## 回滚

停止新服务，将current切回上一发布目录，恢复对应备份环境文件后启动；新增V9～V11表保留，不drop、不清空历史、不改Flyway校验和。回滚到旧应用后如需继续保护旧接口，应由反向代理保留访问限制。历史V2 seed迁移已发布，不能从构建中删除。仅当需要完整灾难恢复时才使用数据库和材料备份；应先在独立库演练，本轮未执行全量恢复演练。

核验备份已有冻结行：`java --class-path <mysql-connector-j.jar> scripts/JdbcVerifyPreserved.java <env-file> <backup.sql>`。只打印统计，不打印业务正文、密码或访问口令。
