# 在线网页采集与Firecrawl

信息抓取 → 在线规则 / Firecrawl。Firecrawl为获取工具；实际数据来源始终是目标网址。普通HTML使用web-http，动态渲染可使用firecrawl。现有Apple、引力引擎、AppBrain专用榜单保持各自来源校验，通用网页结果不写入产品、榜单或报告表。

## 操作流程

1. 自建服务首次部署后，在Firecrawl服务配置中启用内部入口；云端服务需填写自己的Key。后端只返回是否配置，Key不会返回页面。
2. 新增在线网站，填写公开HTTPS网址与工具。设置每条记录的CSS、字段名/相对CSS/属性/是否必填、最低和最多记录数。字段选择器留空代表记录本身。
3. 保存新草稿，再点击试抓。页面显示实际字段、数量、错误和材料SHA256；可下载JSON，并查看每个字段定位证据。
4. 同一版本24小时内校验成功、供应商配置未变更才可发布。发布后下一次执行立即使用该版本。保存新草稿不影响已发布版本。
5. 纳入定时范围使用现有北京时间08:30、23:00计划。最多启用5个通用网页目标，每次顺序执行；不影响固定榜单的100名校验。
6. 回退时选择历史版本，重新试抓后发布。失败记录保留，旧成功数据不被覆盖。

## 边界与故障

单条规则一次获取一个页面，不自动翻页、登录、破解验证码或无限滚动；每页1–1000条、最多20字段、渲染等待最多10秒、输入4MB、结果总计100万字符。数量超出、必填缺失、字段匹配多个节点均不标为成功。URL最多1000字符，禁止敏感查询参数、私网目标；普通HTTPS不跟随重定向，目标跳到其它域名需显式修改规则。

FIRECRAWL_AUTH_REQUIRED为供应商凭据问题；FIRECRAWL_CREDITS_REQUIRED为云端额度问题；WEB_TRANSPORT_FAILED为连接/超时/响应过大；PREVIEW_REQUIRED须重新试抓；CONFIG_CONFLICT须刷新后重试。网站反爬或服务器外网不通不会因部署Firecrawl自动消失。

当前已有榜单AI诊断工单保持独立；本轮网页规则支持人工在线修复，尚未实现AI自动生成并发布CSS。后续Agent应走同一草稿→真实试抓→发布接口，不能直接执行模型返回的代码或把模型猜测当来源数据。

## API

前缀 `/api/v1/admin/capture-workspace/web`；工作区由服务器配置决定，不接受请求自行指定租户。

| 方法与路径 | 用途 |
| --- | --- |
| GET/PUT /provider | 获取脱敏服务配置/按revision更新 |
| GET/PUT /targets/{key}/rules | 版本列表/保存新草稿，body为revision和rule |
| POST /targets/{key}/preview | body为version，真实抓取并保存证据 |
| POST /targets/{key}/publish | body为version、revision、previewId |
| POST /targets/{key}/run | 执行当前发布版本 |
| GET /runs?target=... | 最新100次运行 |
| GET /runs/{id} | 字段结果、校验问题与证据 |

目标登记和启停复用父级 `/targets`；已有规则的工具绑定固定，URL只能经规则发布更新。

## 运维与数据库

V18仅增加4张capture_web_*表。材料写到CAPTURE_MATERIAL_DIR/web/{runId}/received.html与bundle.json。需同时备份数据库、材料和加密密钥。服务进程中断后，下一次执行会将过期租约记为INTERRUPTED；同目标未过期任务不能并行。

自建镜像、资源、启动和回滚见 [Firecrawl部署包](../scripts/firecrawl/README.md)。应用升级一次引入此引擎后，普通来源URL、CSS字段、数量、等待和发布/回退均在线生效；新引擎类型、复杂登录流程和软件升级仍需要部署。
