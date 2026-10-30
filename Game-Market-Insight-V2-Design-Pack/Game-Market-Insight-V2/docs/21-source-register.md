# 数据源登记册与当前核验边界

日期：2026-10-30；V2.7补充12个门户候选，详见第29章。采集方案1.0的既有登记继续保留。完整42项登记见21-source-register.csv。本轮进行了公开网页/官方文档核对，没有在生产环境完成采集器联调、权限验收或7日稳定性测试。

| 标识 | 含义 | 可以得出的结论 |
| --- | --- | --- |
| V0_UNVERIFIED / V0_PER_SITE / V0_VENDOR | 候选来源或逐站待核 | 可以安排验证，不能承诺字段或覆盖 |
| V1_DOC | 已核对来源方文档/服务说明 | 文档描述能力存在；凭证、用途、字段、配额仍待接入 |
| V1_ENTRY | 已找到入口或可检索到入口 | 不能据此说已取得榜项、完整分页、目标国家数据 |
| V2_PAGE | 本轮已读取至少一张公开页面的相关内容 | 仅证明当次研究可见；不能替代部署环境HTTP、许可与连续采集验收 |
| V3_POC | 预留：部署环境端到端样本通过 | 逐源证据、接口响应、字段校验、限制处理通过 |
| V4_PILOT | 预留：连续7日试运行通过 | 达到该源承诺范围的试运行标准，仍不保证以后不变 |
| INTERNAL_PLANNED | 内部人工/计算能力待开发 | 不是外部网站可以抓到的信息 |

权威性、技术可访问性、使用许可、统计可比性是四个独立维度。官方网页也可能仅展示部分榜；第三方公开页面也需要追溯原始出处。所有连接器默认production_enabled=false，只有逐源准入后开启。

## 已核实的关键依据

1. Apple元数据接口文档：https://performance-partners.apple.com/search-api 。支持搜索/ID查找和国家参数；本轮只做文档核对。
2. Apple中国区游戏免费榜：https://apps.apple.com/cn/iphone/charts/6014?chart=top-free 。本次网页读取显示1–25名；不能把期望Top100写成实际完整覆盖。
3. Apple公开详情样例：https://apps.apple.com/cn/app/id791532221 。可见评分、评分计数、部分评论、内购标签和版本信息。示例是字段验证，不是纯IAA推荐。
4. Google Play公开详情样例：https://play.google.com/store/apps/details?id=com.block.juggle&hl=en&gl=US 。本次显示广告与内购同时存在；“含广告”不足以证明纯IAA。
5. Google自有应用评论接口：https://developers.google.com/android-publisher/reply-to-reviews 。开发者权限范围不能自动延伸到竞品。
6. 抖音小游戏官方入口：https://game.open-douyin.com/ 。核到热门、畅销、新游榜入口；榜项、日期、ID、TopN待部署环境PoC。自有数据助手说明：https://developer.open-douyin.com/docs/resource/zh-CN/mini-game/operation1/about-data/data-assistant 。
7. TapTap公开评论样例：https://www.taptap.cn/app/531593/review 。核到可见评价文本与评价标签，未验证全量分页或自动化稳定性。
8. TikTok Top Ads说明：https://ads.tiktok.com/resources/help/article/how-to-use-the-top-ads-dashboard 。未登录可见数量有限，精选广告不是完整投放库。
9. Google广告透明说明：https://support.google.com/My-Ad-Center-Help/answer/12155361?hl=EN 。公开检索支持广告主/网站及地区和日期过滤；自动API能力未核实。
10. DataEye服务：https://www.dataeye.com/api.html 与 https://www.dataeye.com/adxray.html 。供应商自述API/广告情报能力，目标小游戏字段、许可和SLA需样本验收；不据营销覆盖比例承诺本项目覆盖率。
11. Google Trends导出：https://support.google.com/trends/answer/4365538?hl=en 。先走官方CSV导入；其他接入另做评估。
12. YouTube文档：https://developers.google.com/youtube/data ，配额 https://developers.google.com/youtube/data/determine_quota_cost ，使用政策 https://developers.google.com/youtube/terms/developer-policies ，额外获批分析政策 https://developers.google.com/youtube/terms/derived-metrics-policy 。默认仅参考入口与允许的原始展示，不直接纳入AI舆情、综合热度或长期竞品统计库；相应用途获批后才单独启用。对非授权统计不承诺超过30日保留；源策略要实施刷新/删除，不能用“不可变历史”绕过。

以上URL用于开发核对；不能把搜索引擎索引时间当网页数据日期。来源页面可能变化，实施时需再次确认。没有核到文档不等于证明接口不存在；仅表示目前不应把它写成已具备能力。
