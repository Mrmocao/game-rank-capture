# 信息抓取工作台与运行维护

入口：数据采集 → 信息抓取（`#/capture`）。本次本地验收地址 `http://127.0.0.1:5173/#/capture`，API位于127.0.0.1:18081；公网部署未更新。页面仅访问采集模块的配置和观察记录，不自动关联产品或触发周报。

## 来源与工具

| 网站 / 来源 | 当前能力 | 工具键 |
| --- | --- | --- |
| Apple中国游戏免费RSS | 官方前100名、名称、ID、开发者、图标、更新时间；已真实取得 | http-json |
| 引力引擎微信 | 第三方人气、畅销、畅玩3榜各100；名次、名称、发行方、标签、变化原文；已真实取得 | browser-scroll |
| 引力引擎抖音 | 第三方热门、畅销、新游、发行人热度4榜各100；已真实取得 | browser-scroll |
| AppBrain美/英/德/法 | HTML适配器已实现；当前网络可达性和完整Top100待验证，默认不纳入计划 | http-html |
| 已获取的HTML/JSON材料 | 保留原时间、哈希和范围进行本地重放 | material-import |
| Firecrawl | 候选工具，未接入、不执行 | firecrawl |

来源目录另展示42项研究入口，包括TapTap、B站、YouTube、Google Play、广告透明库、Google Trends、百度指数、DataEye、小红书，以及4399、Poki、CrazyGames等门户。逐项显示可取字段、建议方法、验证级别和限制，目录条目不等于可执行目标。Google Play不冒充AppBrain，引力引擎不冒充微信/抖音官方。

已实现目标的固定入口和工具绑定由适配器确定，可在页面修改名称、范围说明、备注、是否纳入计划。新增HTTPS网站登记为候选，不能直接启用。独立工具源码位置与回归命令在工具页列出；接入新网站时实现SourceAdapter、注册来源与受控URL、补真实证据校验，再加入可执行目标。

## 定时计划与执行器

当前计划：每天 **08:30和23:00，Asia/Shanghai**。晚上抓取追加新的来源观察，同材料复用观察并保留新运行，不覆盖早间历史。`status`与`freshness`独立；完整100条不表示一定是今日来源。

V14默认计划关闭，用户在页面管理计划。CLI也支持`--schedule daily|twice|off`和`--schedule-status`，其中twice固定为08:30、23:00。`next_due_at`等数据库时间按UTC存储，页面统一显示北京时间。

Windows安装：

```powershell
mvn package
./scripts/install-capture-worker.ps1
Get-Content data/worker-logs/worker-*.log -Tail 3
```

安装脚本复制按哈希命名的JAR到被Git忽略的`data/runtime/`，在当前用户的Startup目录写入`GameRankCapture-Worker.lnk`，通过Java 21的`javaw.exe`隐藏启动独立CLI `--worker`。Worker每分钟检查计划，并用文件锁防止同项目重复启动。登录Windows后自动恢复，不依赖前端、Spring服务或Codex窗口。电脑须开机、联网，Windows用户须处于登录状态（锁屏可用）；不存Windows密码。数据库暂时不可用时记录错误并在下一分钟重试。网站登录失效需要手工重登。

本机Task Scheduler曾返回`0x800710e0`拒绝启动，因此已切换为用户登录自启动，并移除本次创建的不可靠旧任务；不需要提升系统权限。更新工具前先在页面关闭计划，等正在执行的采集结束，再构建并运行安装脚本，切换到新版本副本；最后重新启用计划。不要覆盖运行中的JAR。

执行器仅在计划到期时抓取，平时刷新心跳。超过3分钟无心跳显示离线；DB原子领取及30分钟租约防重复。离线错过多个时段后，恢复时合并为一次补跑；开始时前移下次时间，进程中断不会在当前时段无限重试，下个计划或人工重试可再次抓取。单榜抓取自身另有10分钟租约。失败保留旧数据，并形成修复工单。

`CAPTURE_WORKER_ENABLED=true`允许Spring每分钟轮询相同计划，默认true；独立CLI不读取这个开关，关闭Spring不会停止独立Worker。停采应在页面关闭计划，或CLI设置`--schedule off`。取消登录自启动可移除当前用户Startup目录中的`GameRankCapture-Worker.lnk`；当前进程仍须按本项目命令行精确定位后停止，不能批量杀所有Java进程。旧`RANKING_CAPTURE_ENABLED/CRON`已弃用。日志在`data/worker-logs/worker-yyyy-MM-dd.log`，`executed:false`且没有errorCode表示正常心跳、尚未到点。单次诊断可运行`--tick`；`capture-worker-tick.ps1`保留作手工单次检查。

首次真实定时批次需到23:00产生；当前验证了后台Worker连续心跳和下次时间，并用测试验证原子领取及到期执行路径。实际重启/重新登录Windows的场景尚未验收。旧Codex每小时登录失效提醒只检查最新失败日志，不执行采集。

## AI与Agent修复

失败或不完整运行自动创建幂等修复工单；历史失败也可在执行记录中手动创建。登录失效直接进入`NEEDS_LOGIN`，用户运行`./scripts/login-ranking-capture.ps1`后点实际重试。解析、结构、网络和覆盖错误分别保留稳定错误码。

AI诊断复用现有供应商配置，但使用独立请求协议。用户点击AI诊断后，仅发送来源范围、固定公开URL、错误码、材料哈希及相对路径、工具文件和验收要求；不发送原始网页、Cookie或浏览器profile。服务使用HTTPS、不跟随重定向、限制响应256KB及总超时，严格校验四字段JSON。`AI_SUGGESTED`只是建议，不能标记修复完成。当前真实模型配置可读取，实际供应商调用未作为自动验收执行。

下载Agent交接包后，有本地代码权限的Agent可按包中`tool.files`、`validation`和`acceptance`读取留存证据、复现失败、修改独立工具、补回归测试并部署经过验证的运行副本。上下文把网页内容视为不可信输入。当前**不自动执行模型脚本、不自动修改/发布代码**；在线自主Agent执行器是后续接入点。本次已落地诊断、持久工单、交接协议和真实重试闭环。

只有对工单目标实际重新抓取、所有请求均为`SUCCEEDED`后，工单才是`RECOVERED`；部分覆盖、网络错误仍是`STILL_FAILING`，保留验证批次。运行中工单不重复领取；AI请求中断超过5分钟、采集验证中断超过30分钟可恢复为可重试状态。

## API

统一前缀`/api/v1/admin/capture-workspace`，沿用工作区鉴权与服务端tenant。所有响应为`{data:...}`，采集异常为422 +稳定错误码。操作同步完成，HTTP200还需检查业务status。

| 方法 / 路径 | 内容 |
| --- | --- |
| GET / | 目标、工具、42项目录、计划、最新完整覆盖、AI配置就绪状态 |
| POST /targets | 登记候选网站，不能自动执行未知URL |
| PUT /targets/{key} | 目标配置，revision乐观锁；固定适配器不能换URL/tool |
| PUT /schedule | `{enabled,frequency:"daily"或"twice",revision}` |
| POST /run?target= | 指定目标或所有启用目标；Top100；失败自动入工单 |
| GET /repairs | 最近100个修复工单及脱敏上下文 |
| POST /repairs | `{runId}`，只允许本工作区失败/不完整运行，重复创建复用 |
| GET /repairs/{id} | 工单、诊断建议与验证批次，可导出作为Agent交接包 |
| POST /repairs/{id}/diagnose | 调用配置AI服务；建议不执行 |
| POST /repairs/{id}/retry | 真实重试目标并更新验证结果 |

运行记录和榜单明细复用`/api/v1/admin/ranking-capture/runs`与`/observations/{id}`。低层ranking-capture API/旧CLI独立运行不自动创建工作台工单，可通过POST repairs补建。

## 验证

新增14项测试覆盖目标可执行范围、URL凭证拒绝、配置并发、北京时间两时段、并发领取、调度异常释放、租户隔离、工单幂等、登录人工处理、只有完整重试才能恢复、AI响应结构及错误脱敏。本地mock供应商验证实际HTTP协议，不把它当作真实供应商调用成功。

V14迁移前备份48表2884行，迁移通过。工作台连接真实配置库，最近8榜800条；前端构建和Chrome桌面/窄屏交互验收独立执行。当前独立Worker已在线，长期运行及网站后续结构变化需继续按日志观察。

本次验收使用已有Java Playwright与本机Chrome（Browser plugin not available），视口1440×1000及390×844。检查页面身份、非空内容、无Vite错误遮罩、控制台/页面错误0、桌面与窄屏截图；实际操作包括微信榜详情翻到第5页第100名、打开并核对定时配置、查看工具源码位置、42项来源搜索TapTap、失败运行创建工单和下载脱敏Agent交接包。手机视口无文档横向溢出。截图和临时QA脚本留在系统临时目录，不纳入仓库。

执行了`mvn test`（128项，111通过、17项可选测试跳过、0失败），修复MySQL DATETIME返回类型差异后重新通过新增14项测试；`mvn package -DskipTests`与`npm run build`通过。前端仍有既有整体bundle偏大的构建提示。实际验证独立Worker连续分钟心跳；第二个`--worker`实例立即正常退出，未重复建立工作循环。真实AI供应商、Windows重启登录和首个23:00定时批次未作为本次已通过结果。
