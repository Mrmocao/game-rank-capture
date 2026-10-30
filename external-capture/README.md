# 外部信息采集

独立 Java 21 模块，职责为「来源获取 → 材料校验 → 结构化观察 → 证据导出」。无 Spring、数据库、后端业务模块依赖。既可独立 CLI 运行，也可由其他进程通过 JSON 文件接收结果。

## 构建与运行

在项目根目录运行：

```powershell
mvn -f external-capture/pom.xml clean package
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT.jar --help
```

公开端点实时抓取（执行时才联网，不自动调度）：

```powershell
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT.jar --source apple-games --region cn --top 100 --output data/captures/external
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT.jar --source appbrain --region us --top 100 --output data/captures/external
```

已有材料离线解析，必须传入材料实际获取时间，不能用本次导入时间冒充采集时间：

```powershell
java -jar external-capture/target/external-capture-0.1.0-SNAPSHOT.jar --source appbrain --region us --top 100 --material validation/source-research/appbrain-us.html --captured-at 2026-10-27T07:25:59.186504Z --output data/captures/external
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

Apple Marketing Tools 全 App 榜与 Apple 游戏分类 RSS 不共用身份。引力引擎网页、微信真实客户端暂未迁入本模块；现有 UI 采集继续输出到 `data/captures/`，不能伪装为 `appbrain` 或 Apple 材料。后续新增来源须提供自己独立的 `sourceKey`、解析器与样本。

## 输出契约 1.0

每次输出新的 `capture-*` 子目录，不覆盖前次材料：

- `raw.bin`：来源原始字节，保持 BOM 等原样，与 SHA-256 对应。
- `bundle.json`：完整结构化交付，最后写入，存在才表示本轮文件保存完成。
- `entries.csv`：便于人工核查，UTF-8 BOM，文本公式前缀转义；JSON 保留原文。
- `coverage.md`：实际条数、连续覆盖范围、缺失名次和材料哈希。

`bundle.json` 包含 `schemaVersion`、`request`、`observation`、`coverage`、`evidence`。观察包含来源、解析器版本、平台、地区、原榜名、来源日、业务日、日期策略、条目和警告。每行包含 `rank/externalId/name/developer/evidenceLocator/sourceFields`，没有内部产品或快照 ID。`externalId=null` 可表示来源未提供身份，禁止按名称补 ID；当前两个适配器要求来源 ID 有效。

`coverage.status` 为 `PARTIAL` 或 `REQUESTED_RANGE_COMPLETE`，后者仅证明请求的前 N 名齐全，不证明榜尾。`maxObservedRank` 与 `contiguousTopN` 分开，不把第100名可见写成连续100条。缺口不补造，空行集合拒绝导出。

来源日未知时应为 null；采集时间仅在 `evidence.capturedAt`。来源字段保留原口径，AppBrain 近30日安装量保留估算原文；没有 IAA、收入、增长或跨平台产品合并结论。

## 验证范围

测试不联网、不依赖 MySQL。覆盖时区日期、来源范围、重复名次/身份、缺口、页面结构变化、原始字节哈希、材料上限、CSV 转义和输出不覆盖。HTTP 适配器只接受固定 HTTPS 主机、拒绝重定向、设置连接/请求超时、限制读取大小；没有实现登录、验证码、自动重试或后台调度。公开端点的实时连通性和商业再分发权限不由离线测试证明。

现有系统的采集 Service 保持原运行方式；本次交付是独立模块，不表示原后端已经完成迁移。系统审阅与后续依赖方向见 [模块抽象说明](../docs/09-EXTERNAL-CAPTURE.md)。
