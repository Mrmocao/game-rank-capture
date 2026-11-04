# 榜单游戏下载与商业化补充来源

核查日期：2026-11-04。此文为来源调研和接入建议，未启用新定时任务、未购买订阅、未将估算写入现有产品或报告。核验方式：读取项目适配器、只读生产观察162/178、实际请求公开来源和供应商文档。材料及摘要存于本机data/research/20261104-game-metrics/，不提交原始网页。

## 已有数据与可直接补充项

| 来源 | 已核实可取字段 | 获取方式/成本 | 口径与限制 |
| --- | --- | --- | --- |
| Apple游戏RSS | 售价、币种、游戏ID、榜位 | 现有http-json已保存priceRaw | 售价不是收入，免费不是无内购 |
| Apple Lookup | price/currency/formattedPrice、bundleId、评分/评分数、版本等返回字段 | 官方公开JSON；按Apple ID和国家补详情，缓存限流 | 不返回竞品真实下载量/收入 |
| App Store公开详情 | 是否含内购、页面展示的内购项目名称及价格 | HTTPS HTML，必要时Firecrawl；按商店ID与地区 | 页面列举可能不全；没取到不是“无内购”；不含购买人数或购买次数 |
| Google Play公开详情 | 累计下载档位、售价/免费、IAP/广告标签、评分等 | HTML/Firecrawl，海外采集执行器优先 | 安装档位不是精确日下载；gl选择商店地区不等于下载指标为该国 |
| AppBrain榜单 | Installs展示原文、Recent近30天估算安装、评分 | 现有http-html/云端回传已保存 | 只有Recent明确标Estimated；Installs tooltip称Google Play install category，保留原文。榜单国家不自动赋予安装指标同一国家范围 |

实际样本：生产Apple观察162中的《潜水员戴夫》ID6755228041已有priceRaw；公开RSS与Lookup同时核到中国售价30元、币种CNY。王者荣耀ID989673964中国商店页展示“App内购买”，可见60点券6元等项目。Google Play Clash of Clans详情显示500M+ Downloads及In-app purchases。生产AppBrain观察178的Meowdoku显示Installs“32 M”、Recent“9.4 M”，后者明确为30日估算，不能宣称美国近30日下载940万。

来源：
- https://itunes.apple.com/cn/rss/toppaidapplications/limit=3/genre=6014/json
- https://itunes.apple.com/lookup?id=6755228041&country=cn
- https://apps.apple.com/cn/app/id989673964
- https://play.google.com/store/apps/details?id=com.supercell.clashofclans&hl=en&gl=US
- https://www.appbrain.com/stats/google-play-rankings/top_free/game/us

## 竞品下载量与收入估算

| 平台 | 本次核查证据 | 可作为的接入方案 | 未验证内容 |
| --- | --- | --- | --- |
| 点点数据 | 官网明确提供App Store、Google Play等应用市场预估下载收入；有API数据入口 | 中国iOS优先候选；谈授权API/导出，按商店ID匹配 | 本次没有登录/付费试用，没有取得字段级API响应，不能保证具体榜单游戏全覆盖或微信/抖音覆盖 |
| Appfigures | 已读/reports/estimates API文档，iOS和Google Play下载/收入估算，支持时间/国家；需要指定套餐，可能额外license | 接口清晰，适合自动补充并保留估算口径 | 尚无账号Token、真实接口调用、报价或中国样本精度验证 |
| Sensor Tower | 当前官网App Performance Insights明确downloads/revenue/RPD等；说明样本和模型处理 | 规模化竞品研究候选，获取商业授权接口/导出 | 本次仅官网能力，未验证授权API及具体套餐；旧/products/store-intelligence为404，不用该失效页作证据 |
| AppMagic、七麦 | 站点可访问，但这次静态返回主要为JS应用壳 | 后续登录试用候选 | 未完成真实样本及接口验证，不将熟悉品牌等同已可采集 |

Appfigures文档明确revenue为付费下载、内购、订阅的估算净收入。不要将其改名为总流水；广告收入不在该字段列举范围内。其他供应商要分别确认税、分成、退款、币种和广告收入范围，不能混用净收入、消费者支出和广告流水。

参考：
- https://www.diandian.com/ （下载/收入产品说明）
- https://app.diandian.com/service/api （API入口；本次静态内容不足以验证接口）
- https://appfigures.com/products/app-intelligence
- https://docs.appfigures.com/api/reference/v2/estimates
- https://sensortower.com/
- https://appmagic.rocks/
- https://www.qimai.cn/

## 微信与抖音小游戏

小游戏新增用户、启动/玩过人数、DAU/MAU、付费人数、IAP收入和IAA广告收入是不同指标，不能统称下载量。现有引力日榜只确认排名、变化、发行主体和标签，未核到逐游戏真实下载/流水字段。畅销排名不能反推收入，iOS同名App收入也不能填给微信版本。

DataEye官网本轮核到ADX游戏广告情报能力，可以研究投放素材/趋势；具体小游戏字段、覆盖、导出或API需账号演示后验证。投放素材数、广告消耗估算不等于游戏广告收入。阿拉丁站点本次连接失败，不标记可用。未核到支持全市场逐个小游戏精确付费率/ARPU/流水的免费公开通用接口。

自己的游戏应从微信/抖音开发者及商业化后台授权接入或导出，分别记录用户、支付与广告结算数据；竞品只能保留公开展示值、供应商估算或明确范围的厂商披露。Google Play Console也明确财务数据需要View financial data权限，只能用于有权限的应用。

参考：https://rank.gravity-engine.com/wechat/day/ 、https://www.dataeye.com/ 、https://support.google.com/googleplay/android-developer/answer/139628?hl=en 。

## 建议实施顺序

1. 先展示已保存的priceRaw/installsDisplay/recentInstallsDisplay，并补Apple Lookup与商店详情。将“售价”“含内购”“内购项目价格”“估算下载”“收入”分列，未知留空。
2. 为榜单游戏按平台稳定ID去重补详情：Apple ID、Google包名；微信/抖音没有已核验appid时保留来源ID和待核验映射，不能按名称跨平台自动合并。
3. 选一个授权估算源。中国iOS优先对点点做10–20个榜内样本PoC；接口工程评估并行比较Appfigures。确认覆盖、价格和API权利后再全量。
4. 小游戏先补公开用户/热度与商业化类型证据。付费人数、ARPU、付费率、IAA收入不能用榜位或模型猜测补齐。

建议采集记录包含platform/externalId、metricKey、rawDisplay、数值或上下界、单位/币种、商店地区、指标地域、统计起止日、实值/档位/第三方估算/厂商声明、收入范围、sourceUrl、capturedAt、材料哈希、规则版本与身份核验状态。榜单所属国家和下载指标覆盖国家独立存储。

现有Firecrawl在线规则可做单个详情页字段PoC，但尚没有“榜单ID驱动批量详情补全→稳定ID关联→指标时间序列”通用流水线。该能力应继续在独立采集模块实现；JSON源走API工具，HTML源走web-http/Firecrawl，授权供应商走单独凭据适配器。Firecrawl不提供数据供应商订阅，也不能凭空得到源站未公开的收入。
