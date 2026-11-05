# 榜内游戏扩展资料

## 边界与数据解释

读取已启用来源的最新成功/部分成功中国区微信、抖音、iOS 榜单，以游戏名称搜索点点中国 iOS 详情。发行商热度榜不是游戏，排除。每个名称只进入一个任务，多个榜单复用同一来源资料。

独立 `external-capture` 模块负责确定性解析，`browser-capture` 负责浏览器搜索，后端只写 `capture_profile_document` / `capture_profile_job` 和材料文件。不写产品、规则、周报或原始榜单数值。

App ID 与详情可见 Apple 中国商店链接相互验证。iOS 榜单按 ID 关联，小游戏仅展示名称候选，不能据此确认跨平台身份。全球速览只保留原文，可能是应用合集/全球口径，不能转换成中国小游戏下载量或收入。采集时间不代表历史榜单当天的值。

## 执行与登录

- `RankingCaptureCommand --profile-import DIRECTORY`：接收可见 DOM 资料信封，校验来源、时间、名称与既有榜单。保留原始 JSON 和 SHA256；重复材料复用，不重复入库。
- `--profile-tick`：排队并串行执行一个到期名称。
- `--profiles-worker`：独立常驻执行器，每 15 秒检查；完成/无候选 24 小时缓存，失败 6 小时退避，登录/安全验证失败暂停取材一小时。重启执行器可在手动登录后立即恢复其他待处理任务。
- `CAPTURE_DIANDIAN_PROFILE_DIR`：点点专用浏览器目录；空值只允许资料导入。不要指向普通个人 Chrome 或引力引擎目录。
- 生产服务：`scripts/systemd/iaa-radar-profiles.service`。原榜单 08:30、23:00 计划独立运行；资料执行器每 5 分钟发现新增榜内名称。
- 服务器登录：`scripts/server-diandian-login.sh start` 暂停资料执行器并打开服务器专用 Chrome。通过 `scripts/server-login-tunnel.py --config <本机SSH配置> --remote-port 6082 --port 29882` 查看 `http://127.0.0.1:29882/vnc.html?autoconnect=true&resize=scale`。用户手动登录点点后运行 `scripts/server-diandian-login.sh finish`，正常关闭浏览器保存会话并恢复服务。
- 登录查看器只监听回环地址，通过校验主机指纹的 SSH 隧道访问，35 分钟自动清理。不复制本机浏览器 Cookie，不输出账号密码，不代过验证码。

## 展示与定位

信息抓取 → 扩展资料 / 点点：游戏、候选开发者、评分、售价、内购、版本、任务状态、采集时间、全部字段与原文来源。榜单观察复用该资料，显示最新补充的时间和候选状态。页面上的“服务器专用浏览器已配置”不等于会话已验证。

常见错误：`SOURCE_AUTH_REQUIRED` 手动重登；`SOURCE_VERIFICATION_REQUIRED` 人工处理验证；`PROFILE_SEARCH_STRUCTURE_CHANGED` 检查页面结构；`PROFILE_BROWSER_FAILED` 检查站点网络与页面；`BROWSER_PROFILE_BUSY` 关闭同目录登录浏览器；`PROFILE_NOT_RANKED` 拒绝与当前榜单无关的导入。

修改抓取逻辑分别查看 `DiandianBrowserFetcher.PROJECTION`（可见字段）、`DiandianProfileParser`（确定性解析与身份校验）、`ProfileSupplementService`（排队与独立保存）。这条登录浏览器适配器暂为代码规则；已有在线网页规则/Firecrawl 配置不替代登录会话。

## 验证

离线解析测试包含可见页面材料、错误地区、身份冲突；后端测试覆盖去重、租户隔离、停用来源、登录失败退避及不修改既有榜单/指标。生产上线前备份数据库，V22 只添加两张独立资料表。
