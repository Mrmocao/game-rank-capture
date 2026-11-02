# 外部信息采集

独立 Java 21 模块，职责为「来源获取 → 材料校验 → 结构化观察 → 证据导出」。无 Spring、数据库、后端业务模块依赖。既可独立 CLI 运行，也可由其他进程通过 JSON 文件接收结果。2026-11-02 已增加微信/抖音第三方公开日榜与宿主数据库适配层，真实执行记录和接口见 [实际采集说明](../docs/10-RANKING-CAPTURE.md)。

## 构建与运行

在项目根目录运行：

```powershell
mvn -f external-capture/pom.xml clean package
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --help
```

公开端点实时抓取（执行时才联网，不自动调度）：

```powershell
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source all --region cn --output data/captures/external
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source apple-games --region cn --top 100 --output data/captures/external
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source gravity-wechat --chart popularity --region cn --top 100 --output data/captures/external
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source appbrain --region us --top 100 --output data/captures/external
```

已有材料离线解析，必须传入材料实际获取时间，不能用本次导入时间冒充采集时间：

```powershell
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT-cli.jar --source appbrain --region us --top 100 --material validation/source-research/appbrain-us.html --captured-at 2026-10-27T07:25:59.186504Z --output data/captures/external
```

`--expected-source-date YYYY-MM-DD` 仅约束来源显示日，不修改材料日期；Apple 的来源日期与上海业务日可能不同。CLI 失败退出码为 1。`PARTIAL` 是有效但不完整的观察，退出码为 0，调用方必须检查覆盖状态，不能仅检查退出码。

## 接口边界

| 接口 | 职责 | 禁止承担 |
| --- | --- | --- |
| `SourceAdapter` | 声明来源 URL、校验范围、解析原材料 | 网络请求、数据库、业务判断 |
| `MaterialFetcher` | 获取原始字节与真实采集时间 | 来源解析、产品合并 |
| `CapturePipeline` | 来源/日期/排名/身份/证据校验、覆盖计算 | 快照发布、精选、周报 |
| `CaptureSink` | 接收已验证观察与原始材料 | 反向依赖业务服务 |

调用方需要业务入库时，由调用方读取 `bundle.json` 或在自己的模块实现 `CaptureSink`；本模块不导入调用方代码，不自动调用现有 `/collect-material` 接口。

## 来源与能力

| sourceKey | 来源口径 | 地区 | 已实现 |
| --- | --- | --- | --- |
| `apple-games` | Apple RSS 游戏分类 6014 免费榜 | cn/us/gb/de/fr | 固定公开 URL、JSON 解析、材料导入 |
| `appbrain` | 第三方 Google Play 游戏免费榜 | us/gb/de/fr | 固定公开 URL、公开 HTML 解析、材料导入 |
| `gravity-wechat` | 第三方微信人气/畅销/畅玩日榜 | cn | 直接 HTTP 获取 SSR HTML，真实名次、逐行证据定位 |
| `gravity-douyin` | 第三方抖音热门/畅销/新游/发行人热度日榜 | cn | 直接 HTTP 获取 SSR HTML，真实名次、逐行证据定位 |

Apple Marketing Tools 全 App 榜与 Apple 游戏分类 RSS 不共用身份。引力引擎网页当前匿名公开各榜前20，完整榜要求登录；请求Top100时保留PARTIAL，不能冒充微信/抖音官方榜单。微信真实客户端尚未迁入本模块，现有 UI 采集继续输出到 `data/captures/`。后续新增来源须提供自己独立的 `sourceKey`、解析器与样本。

需要完整Top100时使用宿主的 [browser-capture](../browser-capture/README.md) 持久浏览器传输，并运行 `scripts/run-ranking-capture.ps1 -Run`。2026-11-02已真实取得微信三榜、抖音四榜各100条；此处的核心CLI仍是匿名HTTP模式。浏览器库不进入本模块依赖。

`--source all` 默认执行 Apple 中国游戏免费榜及微信/抖音七榜，同批复用同 URL 已取得的材料。微信 `--chart` 支持 `popularity/bestseller/most_played`，抖音支持 `popularity/bestseller/fresh_game/publisher_heat`；Apple/AppBrain 使用 `top-free`。未指定 chart 时小游戏默认 `popularity`。

## 输出契约 1.1

每次输出新的 `capture-*` 子目录，不覆盖前次材料：

- `raw.bin`：来源原始字节，保持 BOM 等原样，与 SHA-256 对应。
- `bundle.json`：完整结构化交付，最后写入，存在才表示本轮文件保存完成。
- `entries.csv`：便于人工核查，UTF-8 BOM，文本公式前缀转义；JSON 保留原文。
- `coverage.md`：实际条数、连续覆盖范围、缺失名次和材料哈希。

`bundle.json` 包含 `schemaVersion`、`request`、`observation`、`coverage`、`evidence`；request 新增来源内 `chartKey`。观察包含来源、解析器版本、平台、地区、原榜名、来源日、业务日、日期策略、条目和警告。每行包含 `rank/externalId/name/developer/evidenceLocator/sourceFields`，没有内部产品或快照 ID。`externalId=null` 表示小游戏来源未提供身份，禁止按名称补 ID；Apple/AppBrain 要求来源 ID 有效。

`coverage.status` 为 `PARTIAL` 或 `REQUESTED_RANGE_COMPLETE`，后者仅证明请求的前 N 名齐全，不证明榜尾。`maxObservedRank` 与 `contiguousTopN` 分开，不把第100名可见写成连续100条。缺口不补造，空行集合拒绝导出。

来源日未知时应为 null；采集时间仅在 `evidence.capturedAt`。来源字段保留原口径，AppBrain 近30日安装量保留估算原文；没有 IAA、收入、增长或跨平台产品合并结论。

## 验证范围

测试不联网、不依赖 MySQL。覆盖时区日期、来源范围、重复名次/身份、缺口、页面结构变化、逐行唯一证据定位、原始字节哈希、材料上限、CSV 转义和输出不覆盖。HTTP 适配器只接受固定 HTTPS 榜单路由，拒绝重定向，设置连接/请求/完整响应超时和4MB上限；IO与429/502/503/504最多三次尝试。没有实现登录或验证码处理。公开端点的实时连通性和商业再分发权限不由离线测试证明。

后端新增 `collection.capture` 数据库存储/运行适配层和默认关闭的日调度，核心仍可独立运行。现有业务采集 Service 保持原运行方式，不表示全部业务采集已经迁移。系统审阅见 [模块抽象说明](../docs/09-EXTERNAL-CAPTURE.md)，当前实际入库与验收见 [运行说明](../docs/10-RANKING-CAPTURE.md)。
