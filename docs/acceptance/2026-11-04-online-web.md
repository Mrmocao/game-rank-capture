# 2026-11-04 在线网页采集验收

## 已完成

- 独立external-capture核心：Firecrawl v2 rawHtml、普通HTTPS、声明式CSS字段提取；宿主只写capture_web_*，保留来源URL、规则版本、原文、逐字段定位和材料SHA256。
- 本地配置数据库已备份（54表8859行）并应用V18；生产13306库切换前再次备份（54表21605行），V18迁移与新版切换均已完成。
- 全量回归193项：167通过、26按既有环境条件跳过、0失败/错误。最后HTTP/1.1兼容修复再次运行WebCaptureTest、OnlineWebCaptureTest、CaptureWorkspaceTest通过。前端类型检查和构建通过，保留原有大包提示。
- Playwright/Chrome实际界面验收：新增、保存草稿、真实试抓、发布、错误草稿不能发布、正式执行仍用v1、服务配置、桌面/手机、无控制台错误。截图位于本机临时online-web-qa目录。
- 服务器实际4核/16GB。Docker26.1.3、Compose2.27已安装；Firecrawl五容器运行，Redis/RabbitMQ/NuQ健康；API readiness探针。API只监听127.0.0.1:3002。空闲合计约2.5GiB内存，配置总上限约5.7GiB。
- 官方镜像摘要验证；服务器GHCR/DockerHub直连不佳，核心镜像通过国内镜像通道取得相同digest，Redis/RabbitMQ从官方验证后SSH导入。
- 自建Firecrawl真实抓取example.com成功，1.5秒返回HTML；游民星空资讯首页返回229655字符HTML。
- 应用完整调用链通过临时loopback SSH验证通道连接服务器Firecrawl，在开发3306库完成游民星空规则v1的PREVIEW→发布→LIVE，正式提取50条标题/链接/来源时间原文/摘要；SHA256 f679a648f72e6b7d9dc3f7edcf353e8fda40276b58951b1ed833afdf69b66881。仅保存采集观察，没有写产品/报告。
- JDK默认HTTP/2 Upgrade会被自建Node服务拒绝400；改HTTP/1.1后实际调用成功，增加无Upgrade回归断言。
- Steam公开热销页实测超时，保留失败记录，未发布或启用；Firecrawl不承诺解决所有源站网络/反爬限制。

## 生产部署完成

用户2026-11-04确认扩容重启及生产切换后，11:22执行部署。新备份 `/opt/iaa-radar/backups/before-online-web-switch-20261104-1122.sql`，54表21605行；app.env另有权限600备份。Flyway仅应用V18，原17项迁移保持校验。current现指向 `/opt/iaa-radar/releases/20261104-online-web`，JAR SHA256 `f5550f426a991e55f56062418b413c1616502c3f244d899cf5a0dc8fc1e870ac`；原20261103-report-feedback-final2保留为回滚版本。

Web、采集Worker、报告桥均active；两个Java进程均加载正确的内部Firecrawl地址和独立加密密钥，只核对布尔结果、不输出密钥。Firecrawl readiness健康。原计划每天北京时间08:30、23:00启用，Worker心跳在线，下次2026-11-04 23:00（数据库UTC15:00）。既有报告和榜单4表1482条冻结行逐行核对不变。

生产工作台已启用内部Firecrawl，无云端API Key。新增“游民星空 · 公开资讯首页”，v1完成PREVIEW→发布→LIVE。实际50条，字段title/link/source_time/summary；资讯首页不是排行榜，不补成100条。默认手动采集，用户按需勾选纳入定时范围。

- targetKey：site-ae627ccd-5b54-4b66-92e4-eebd114048d9
- previewId：b2175e6b-a162-40c3-a24b-c39ee13159f0
- liveId：b28a4bac-547a-4597-aaf1-3ae774d1ff1c
- 材料SHA256：bf38e006205b7c59631d4cf266ce437b60a91660f371b8528619c6ec831c5218
- rawLocator：web/b28a4bac-547a-4597-aaf1-3ae774d1ff1c/received.html

已从生产CAPTURE_MATERIAL_DIR重新读取文件并验证SHA256一致，结果通过正式API返回并在页面展示。没有复制开发库测试记录到生产。

## 生产界面QA

Browser plugin not available；复用已安装Java Playwright/Chrome。目标流程：正式信息抓取页→在线规则 / Firecrawl→已发布目标的校验通过记录→结果与证据抽屉。URL `http://14.103.231.1:18080/#/capture`，桌面1440×1000、手机390×844。临时脚本OnlineFirecrawlProductionUiCheck.java保存在系统TEMP，不增加业务测试依赖。

| 检查 | 结果 |
| --- | --- |
| 页面身份、有效内容 | 正式URL、正确标题、在线目标可见 |
| 空白页/框架错误覆盖层 | 无 |
| 控制台与页面运行时错误 | 0 |
| 交互 | 点击v1成功记录后显示50条、4字段、来源URL、SHA256 |
| 视觉 | 桌面和手机结果抽屉可读；宽表在手机内横向滚动 |
| 发布文件 | 公网HTML、JS、CSS逐文件SHA256与本地最终dist相同 |

截图：本机TEMP/online-web-qa/firecrawl-production.png、firecrawl-production-mobile.png。最后仅部署配置和文档改变，没有重跑无关测试。原夜间计划未人为触发，下一次自然运行仍待23:00；不把Worker在线等同于该时段已完成。

AI自动修改并发布规则未实现；现有榜单AI诊断工单不冒充通用网页自动修复。在线网页规则人工修改已可无部署生效。正式采集直接访问服务器内部Firecrawl，不依赖开发电脑或临时SSH联调连接。
