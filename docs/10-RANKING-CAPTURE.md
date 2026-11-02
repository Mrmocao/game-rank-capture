# 微信、抖音与 iOS 榜单实际采集

> **每榜连续前100名已完成真实抓取及数据库核验：8榜800条。** 专用浏览器会话由用户手动登录一次，后续复用并滚动取满100名；登录失效明确报错，不退回20条成功。配置、登录与重登方法见 [浏览器采集模块](../browser-capture/README.md)。首轮匿名240条作为历史部分覆盖结果保留。

2026-11-02 已完成真实网络取材、MySQL 入库、材料回查与重复执行验证。采集核心位于 `external-capture/`，不引用 Spring、JDBC、产品、规则或周报；后端 `collection.capture` 负责运行编排和独立采集表存储。依赖方向只有后端适配层 → 采集核心。

## 已取得的数据

| 平台 | sourceKey / chartKey | 来源 | 实际条数 |
| --- | --- | --- | ---: |
| iOS 中国 | apple-games / top-free | Apple 官方游戏分类 6014 免费 RSS | 100 |
| 微信 | gravity-wechat / popularity | 引力引擎第三方人气榜 | 100 |
| 微信 | gravity-wechat / bestseller | 引力引擎第三方畅销榜 | 100 |
| 微信 | gravity-wechat / most_played | 引力引擎第三方畅玩榜 | 100 |
| 抖音 | gravity-douyin / popularity | 引力引擎第三方热门榜 | 100 |
| 抖音 | gravity-douyin / bestseller | 引力引擎第三方畅销榜 | 100 |
| 抖音 | gravity-douyin / fresh_game | 引力引擎第三方新游榜 | 100 |
| 抖音 | gravity-douyin / publisher_heat | 引力引擎第三方发行人热度榜 | 100 |

合计 8 份榜单、800 条排名记录，非800款去重游戏，均为 `REQUESTED_RANGE_COMPLETE`。匿名页仅提供前20；登录后通过滚动加载取得连续1–100名。微信/抖音结果明确标记 `THIRD_PARTY`，未提供平台 appid 时 `externalId=null`，不按名称构造 ID 或合并产品。微信官方客户端是另一个来源，本实现没有将网页数据标为官方客户端数据。

真实入口：

- [Apple 中国游戏免费 RSS](https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json)
- [引力引擎微信日榜](https://rank.gravity-engine.com/wechat/day/)
- [引力引擎抖音日榜](https://rank.gravity-engine.com/douyin/day/)

匿名前20可通过 Java HTTP + Jsoup 直接解析；完整Top100由独立 `browser-capture` 模块使用 Playwright 和专用持久Chrome会话滚动取材，无需Firecrawl。浏览器在登录UI就绪且各榜初始行返回后开始滚动，读取日期控件当前值，再输出排除登录表单、脚本与凭证的榜单DOM快照。核心来源语义、覆盖校验和存储契约不变。

## 运行方式

要求 Java 21、Maven、可连接的 MySQL。项目根目录 `.env` 配置 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`；推荐固定 `CAPTURE_MATERIAL_DIR` 为绝对路径。系统环境变量优先于 `.env`；显式 `-Output` 优先于材料目录配置。

完整小游戏榜还需安装Chrome，配置 `CAPTURE_BROWSER_PROFILE_DIR` 为本机专用绝对路径。首次运行 `./scripts/login-ranking-capture.ps1` 手动登录并关闭专用窗口保存会话；之后 `-Run` 默认复用会话。`-SessionStatus`只读查询最近实际采集是否提示重登，不发起网络会话检查。当前会话已设置每小时检查运行日志的失效提醒，只有新的 `SOURCE_AUTH_REQUIRED` 事件才提醒，不反复通知同一事件。

```powershell
# 查看当前配置库结构，不写数据
./scripts/run-ranking-capture.ps1 -Inspect

# 备份后，在尚未迁移的目标库增量执行迁移并抓取
./scripts/run-ranking-capture.ps1 -Migrate -Run

# 已迁移数据库：实际抓取八张榜并入库
./scripts/run-ranking-capture.ps1 -Run

# 从单一批次的材料目录重放，校验哈希，保留原采集时间
./scripts/run-ranking-capture.ps1 -Run -ReplayDir data/external-capture-verified-20261102
```

脚本先从根 reactor 构建，然后通过 Boot JAR 的 `PropertiesLauncher` 执行普通 main；不启动 Web 服务、seed、业务调度或周报。`-Migrate` 执行该数据库全部待执行 Flyway 迁移，并非仅运行 V13，因此迁移前必须确认目标库并备份。本次配置库实际从 V11 增量执行了 V12/V13。

CLI 有失败尝试时返回退出码 2；命令级异常为 1；只有部分覆盖且无失败时为 0。不要用退出码 0 推断拿到了全部 Top100，必须读取输出 JSON 中每个 `attempts` 的 `status`、`freshness`、`coverage`。

只采集匿名文件、不连接数据库（以下核心CLI不使用浏览器会话，微信/抖音仍为公开前20）：

```powershell
mvn -q package -DskipTests
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source all --region cn --output data/captures/external
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source gravity-wechat --chart popularity --region cn --top 100 --output data/captures/external
```

根 `pom.xml` 统一构建 `external-capture`、`browser-capture` 与 `backend`。无分类器JAR是依赖库，`-cli.jar`是带依赖的可执行程序。单独执行 `mvn -f backend/pom.xml spring-boot:run` 前，先在根目录 `mvn install -DskipTests` 安装依赖；完整打包使用根目录Maven命令。登录脚本运行专用目录中的独立JAR副本，避免登录期间重新构建工程破坏正在运行的类加载。

## 后端接口

沿用现有工作区认证和服务端 `PROFILE_TENANT_ID`；不接受客户端指定租户。前缀 `/api/v1/admin/ranking-capture`：

| 方法 / 路径 | 用途 |
| --- | --- |
| GET /sources | 默认八榜范围、真实 URL、来源权威性、公开数量上限 |
| POST /run | 同步执行；无 body 时执行默认八榜 |
| GET /runs?batchId= | 查询运行日志；无 batchId 返回最近 200 条 |
| GET /observations?platform=&date=&source=&chart= | 按业务日等条件查询最近 200 份有效观察 |
| GET /observations/{id} | 完整 bundle、原材料相对路径与首次保存时间 |

单榜请求示例：

```json
{
  "requests": [{
    "sourceKey": "gravity-douyin",
    "chartKey": "fresh_game",
    "region": "cn",
    "requestedTopN": 100,
    "expectedSourceDate": "2026-11-02"
  }]
}
```

`expectedSourceDate` 可省略；传入时严格校验来源显示日，不覆盖原始日期。单批 1～20 个不同范围的请求，范围错误返回 422。执行返回 HTTP 200 仍需检查逐项结果：某源失败不抹掉其他成功观察。查询已撤销验证的观察不会将它作为有效结果返回，原始运行和材料仍保留供审计。

`status` 表示执行及请求范围覆盖；`freshness=CURRENT/STALE/UNKNOWN` 独立表示相对运行请求日的新鲜度。Apple 本批来源时间的当地日期为 11 月 1 日，换算上海业务日为 11 月 2 日，因此 `CURRENT`；不能只比较 `sourceDate` 字符串判断过期。回放保留原采集时间，旧材料不会被重标为今日榜单。

## 存储、幂等与证据

V13 新增三张表，全部具中文表/列注释：

- `external_capture_run`：每次执行记录、来源范围、状态、错误码、材料哈希及路径。
- `external_ranking_observation`：不可变来源观察、原日期、解析器版本、覆盖、完整 bundle。
- `external_ranking_entry`：真实名次、名称、来源身份、开发商、逐行定位与原始字段。

这些表没有产品/周报外键。完成观察与明细写入使用事务；失败回滚不清空旧观察。同租户、来源、榜单、地区有 10 分钟租约互斥；中断后下次同范围运行可回收过期租约。同范围、解析器版本、来源日期和材料哈希重跑复用观察；新材料或新解析器追加版本，仍保留每次运行日志。

每次运行先保存 `received.bin` / `received.json`，解析失败也有已收到的材料证据；网络请求未取得材料时只留错误码。通过验证后导出 `raw.bin`、`bundle.json`、`entries.csv`、`coverage.md`。数据库路径均相对 `CAPTURE_MATERIAL_DIR`，切换工作目录时保持同一根目录，数据库和材料必须一起备份。

引力引擎排名来自 NO.n 标签，前三名图形通过已核验图像标记哈希映射；不以行序或分类榜排名代替总榜名次。每一行的 CSS 定位在解析时验证唯一命中实际行。未知图形、页面范围变化、重复名次、空榜、错误日期被拒绝。HTTP 限固定 HTTPS 榜单路由、拒绝重定向、4 MB 上限、连接/请求/完整响应超时；IO 与少数暂时 HTTP 错误最多三次尝试，不重试登录或解析失败。

## 验收与运行边界

完整Top100在线批次 `7682f820-92d1-4051-a395-c5fff8c3cd3a`：观察19–26，每榜100行，共800行；0失败、0部分覆盖，全部 `CURRENT`，来源业务日2026-11-02。

- 批次：`data/external-captures/batch-7682f820-92d1-4051-a395-c5fff8c3cd3a.json`。
- 严格Top100逐行核验：`data/external-captures/verification-top100-20261102.json`，数据库、材料哈希、DOM定位及连续名次一致。
- 重放批次 `7a5aafd1-b1bb-45f0-a107-142a30a0c0ed` 全部复用观察19–26，未重复插入800条明细；核验文件 `verification-top100-replay-20261102.json`。
- 交付汇总 `data/external-captures/README-Top100-20261102.md`；完整导出 `rankings-top100-20261102.csv` / `rankings-top100-20261102.json`，与同目录批次、核验结果配套。
- 自动测试累计114项：99通过、15项既有可选数据库测试跳过、0失败。已实际执行两个离线Chrome测试，验证动态日期属性、异步榜单行加载和排除登录秘密；真实登录与在线采集单独验证。
- 专用会话在多次浏览器启动后可复用，未要求用户再次登录。浏览器会话失效、网络错误及未取满100分别处理，不混报。

以下为首轮匿名模式的历史验证，不能替代上述完整Top100结果：

2026-11-02 首轮匿名在线批次 `c12931c1-0612-4896-a997-3d0600aebe9b`：观察 9～16，8 榜 240 行，0 失败、7 部分覆盖、8 个 `CURRENT`。

- 批次文件：`data/external-captures/batch-c12931c1-0612-4896-a997-3d0600aebe9b.json`。
- 数据库/材料/DOM 逐行核验：`data/external-captures/verification-20261102.json`。
- 重放批次：`a63dc634-bf77-44b4-b951-2d1e35b28210`，8 份均 `reused=true`，没有重复插入明细。
- 最终解析器 `gravity-ssr-v2` 修正了嵌套行容器的定位。初期两批中 14 条 Gravity v1 运行结果已记为 `EVIDENCE_REJECTED / EVIDENCE_LOCATOR_INVALID`，原材料和不可变观察保留，不作为有效结果提供。
- 迁移前备份：`data/backups/before-external-capture-v13-20261102.sql`（43 表、1304 行）；旧榜单/周报的 365 条冻结记录回查未变化。
- 自动测试汇总108项，93项通过、15项既有可选数据库测试跳过、0失败；最终针对采集的28项测试及打包通过。覆盖材料解析、日期、缺口、事务回滚、幂等、租户隔离、租约、逐源失败和 REST；实际 MySQL 与网络验证单独执行，不以模拟测试代替。

V14后独立调度改由数据库计划控制，旧`RANKING_CAPTURE_ENABLED/CRON`不再读取。当前Windows已注册登录自启动`GameRankCapture-Worker.lnk`，按用户要求每天北京时间08:30和23:00执行；本机须开机且用户已登录。新增`#/capture`展示这些真实记录、完整榜单、目标和工具，不混入旧业务快照。详情见 [工作台与定时运维](11-CAPTURE-WORKSPACE.md)。2026-11-02公网已同步该模块与7日真实材料，后续运行边界见[交付说明](12-WEEKLY-CAPTURE-DELIVERY.md)。

微信/抖音第三方Top100已通过用户登录后取得；官方客户端口径和其他商业来源仍需各自的可用来源。本模块不绕过登录，亦不将第三方完整榜等同官方客户端榜。会话仅保存在本机，换机器或失效需手动重登。
