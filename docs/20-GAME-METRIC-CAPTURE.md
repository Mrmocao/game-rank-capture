# 榜单游戏免费指标补全

入口：信息抓取 → 下载与付费信息。此模块读取启用来源的最新有效榜单，按平台商店ID、地区去重；只写capture_metric_*，不修改产品、规则或历史报告。无稳定ID的小游戏仍逐行展示，不能按同名App补值。

## 来源及口径

- Apple官方Lookup：售价、币种、评分与评分数。原接口可能返回text/javascript，仍使用JSON解析并验证trackId/游戏分类。
- App Store详情：售价、评分、内购标记及页面展示的部分内购项目/价格。只允许同Apple域名、同地区和同ID的规范化跳转。
- Google Play详情：售价、评分、全球累计下载档位、内购/广告标记。由海外执行器取得原文，服务器重新验证包名并确定性解析。
- 原AppBrain榜单：安装量展示原文、近30日安装估算；来源证据保留，不把榜单国家视为安装指标统计国家。
- 无来源数据的收入、付费人数、日下载保持未知；免费售价不意味着没有内购。iOS公开详情不提供竞品下载量。

数据商Appfigures、点点、Sensor Tower目前仅支持账号备注、Key加密保存/清除、授权声明配置。始终待授权验证、未启用；没有实现和调用未经验证的收费数据接口。供应商URL固定，不接受任意主机。已有账号后仍需真实样本核验、接口认证适配和收入口径确认。

## 执行与运维

V19新增配置、任务、不可变观察、供应商配置四表。独立进程使用RankingCaptureCommand --metrics-worker；一次处理用--metrics-tick。API与进程共享CAPTURE_TOOL_ENCRYPTION_KEY。systemd文件见scripts/systemd/iaa-radar-metrics.service。

在线设置支持自动发现开关、24/48/72小时缓存、服务器详情工具web-http/Firecrawl。自动发现每5分钟检查一次；worker每5秒处理一个iOS任务。失败退避6小时；旧成功观察保留并显示过期。关闭自动发现不撤回现有队列；关闭榜单来源后取消对应待处理任务。手动排队同样遵守缓存、退避与租约，不触发重复请求。

Google Play由.github/workflows/overseas-metrics.yml每小时55分领取最多100项（Actions可能延迟）。支持手动dispatch limit 1–100。每项间隔5秒，总时限17.5分钟，余量留待下一轮。原AppBrain榜单08:40/23:10和服务器榜单08:30/23:00互不替代。

外网桥接复用OVERSEAS_CAPTURE_TOKEN与固定CA证书，通过/overseas/metric-claim、metric-result转给loopback API；桥接到Java使用独立CAPTURE_REMOTE_METRIC_TOKEN。公网必须认证，原材料≤4MB，不能指定内网URL、运行命令或写任意业务数据。领取结果只含固定Google URL和180秒租约；提交校验租约、工作区、时间及包名，拒绝重放。页面展示海外最近领取时间，不将其称为常驻在线。

原材料及snapshot.json按UUID存于CAPTURE_MATERIAL_DIR/metrics/，数据库保存哈希、相对路径、解析版本和指标口径。来源历史保留最近30条供页面查看，数据库历史不删除。Key从不返回API，接口仅返回是否已配置。

## API

前缀/api/v1/admin/capture-workspace/metrics（工作区由服务器决定）：
- GET /：platform/region/search/page筛选，每页20个游戏，100名榜单仍完整保存。
- PUT /config：enabled、refreshHours、transport、revision，乐观锁。
- POST /enqueue：可选platform、externalId、region；只能从启用的当前榜单创建任务。
- GET /jobs/{id}：任务状态及最近30次成功来源观察。
- PUT /providers/{provider}：accountLabel、apiKey、clearKey、licensed、revision。明文Key不响应、不记日志。
- POST /remote/claim、/remote/result：额外专用令牌，只供海外桥接。

验证用离线fixture、H2、mock传输及Python unittest；真实公开材料/生产验收记录另见acceptance文档，测试不调用收费API。
