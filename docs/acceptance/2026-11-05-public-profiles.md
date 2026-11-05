# 公开游戏资料补充采集验收

2026-11-05，生产切换到 `/opt/iaa-radar/releases/20261105-public-profiles`，JAR SHA256 为 `9240e26ae1a39d032443284b7b44e0e0c4b5cb979cc6a53b5d18da276b13b65f`。原版本 `20261104-ranking-metrics` 保留，可回滚。发布前生产备份为 `/opt/iaa-radar/backups/before-public-profiles-20261105.sql`，备份完成时 62 张表、29,778 行；V20 迁移成功，冻结记录 1,587 条保持不变。

## 功能

- `GET /api/v1/admin/capture-workspace/public-profiles/candidates?observationId=165&limit=100`：从微信/抖音榜单观察逐行读取名称，并在现有中国 iOS 观察索引中寻找候选 App ID。
- `POST /api/v1/admin/capture-workspace/public-profiles/capture`：读取七麦公开基础详情页，保存独立的公开资料观察、原始材料路径、SHA256、来源 URL 和失败码。
- 信息抓取工作台增加“公开资料补充”页签，手动输入榜单观察 ID 后查看候选并执行抓取。
- 新表 `capture_public_profile_observation` 只保存公开资料候选，不更新 `external_ranking_*`、产品档案、下载/收入指标或历史报告。

## 实测

生产观察 165 的前三名候选已正确解析：

| 微信名称 | iOS候选 | 匹配结果 |
| --- | --- | --- |
| 向僵尸开炮 | 6474293468，向僵尸开炮-尸潮来袭 | 名称相近，身份未核验 |
| 三国：冰河时代 | 6503432459，三国：冰河时代 | 名称相同，身份未核验 |
| 疯狂水世界 | 6757172630，疯狂水世界 | 名称相同，身份未核验 |

生产手动触发前三项后返回 `succeeded=0, failed=3`，错误为 `PUBLIC_PROFILE_FIELDS_MISSING`。原因是服务器普通 HTTP 请求拿到七麦的未渲染/拦截页面，缺少公开详情 DOM；这类失败已保存，不被标记为成功。此前在受控浏览器中已读取三条公开详情的可见字段，摘录保存在 `docs/samples/2026-11-04-qimai-public-profiles.json`，这些摘录不冒充服务器自动采集结果。

点点名称搜索和详情页仍跳转登录，未接入登录后资料。七麦名称搜索结果也显示登录提示；当前仅使用未登录状态下可见的公开详情 DOM。Firecrawl 对七麦详情曾返回 `/404`，因此没有把 Firecrawl HTTP 200 当作成功。

## 边界

候选状态固定为 `UNVERIFIED_CROSS_PLATFORM_CANDIDATE`。iOS 公开页的评分、昨日下载预估、价格和内购条目不能直接填入微信/抖音小游戏数据；名称、开发商、地区、统计日期和来源口径分别保留。当前公开资料补充为手动触发，不加入 08:30/23:00 榜单计划。
