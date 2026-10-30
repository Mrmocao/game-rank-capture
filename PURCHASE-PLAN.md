# 数据源采购清单（按你的目标）

官网核对时间：2026-10-27。价格、接口权限和具体覆盖必须以供应商报价单为准；下面是应该购买的“产品模块/数据端内容”，不是无条件推荐买整站套餐。

## 推荐的最小采购组合

| 优先级 | 供应商/产品 | 解决什么 | 购买时必须写进合同/报价单 | 不能替代什么 |
|---|---|---|---|---|
| 1 | [Sensor Tower App Performance Insights](https://sensortower.com/product/app-intelligence) | iOS 中国、Google Play US/GB/DE/FR 的商店排名、下载/收入估算、国家/分类趋势和历史 | 游戏分类；5 个国家；免费榜/热门榜原名；每日历史快照；API 或 CSV 导出；App Store ID/package；调用额度；原始数据保存/内部展示授权 | 不能单独证明 PURE_IAA；估算值要保留 estimated、单位和期间 |
| 2 | [DataEye ADXray](https://www.dataeye.com/) | 国内/海外买量和广告创意信号；DataEye 官网明确提供国内与海外游戏广告情报 | 微信/抖音相关媒体覆盖；产品 ID；媒体/投放日期；素材 URL；投放估算字段及单位；API/导出；历史回补；内部保存授权 | 不能当作热度榜，也不能单独证明纯 IAA |
| 3 | [42matters API / Data Dumps](https://www.42matters.com/app-market-explorer) | Google Play 和 App Store 的包名、商店 ID、开发商、SDK/权限/IAP 等身份和版本辅助证据 | Google Play 4 国和 iOS 中国；包名/商店 ID 稳定性；SDK/IAP 字段；版本历史；API 或 data dump；IAA 相关字段是否存在；保存授权 | 官网公开产品说明重点是 App Intelligence/SDK/应用数据，不能假定有目标 Top100 或 PURE_IAA |

这套组合的意义是：Sensor Tower 提供海外与 iOS 的市场热度，DataEye 提供买量线索，42matters 做身份、SDK/IAP 和版本辅助核验。热度与买量仍在系统中分开保存。

## 中国 iOS 可选增强

| 供应商/产品 | 适合购买的内容 | 采购限制 |
|---|---|---|
| [七麦数据 Qimai](https://www.qimai.cn/) | 中国 App Store 排名、ASO、关键词、榜单历史 | 购买前必须确认游戏免费榜原名、按日历史、API/导出和内部保存授权；网页会员不等于 API 授权 |
| [data.ai](https://www.data.ai/en/) | App Store/Google Play 的市场规模、排名、下载和收入估算 | 通常价格较高；必须确认 API/批量导出、5 个目标国家、游戏分类和历史范围 |

Sensor Tower 与七麦二选一即可先做中国 iOS；预算有限时优先 Sensor Tower，因为它同时覆盖海外 Google Play。

## 不建议单独购买的内容

- 只卖广告素材库的套餐：可以支持买量观察，不能替代榜单热度。
- 只卖 ASO 关键词的套餐：不能替代每日排名快照。
- 只返回“有广告/无内购”的套餐：不能直接作为 PURE_IAA 证据，必须有版本范围、来源定位和观察时间。
- 只提供网页会员、不提供 API/合法导出的套餐：不适合自动每日采集。

## 发给供应商的统一询价文字

```text
我们需要游戏市场情报的 API 或授权导出，目标平台为：微信小游戏、抖音小游戏、中国区 iOS、Google Play 美国/英国/德国/法国。

请确认：
1. 是否覆盖游戏免费/热门/热度榜，原榜单名称、分类、设备口径和每日更新时间；
2. 是否能返回 Top100 及指定历史日期，缺失日如何表示；
3. 稳定身份字段是 appid、App Store ID 还是 package name；
4. 是否返回版本级广告/IAP/变现分类；若有 PURE_IAA 字段，请提供来源方法、版本范围、更新时间和证据定位；
5. 是否提供 API、CSV/JSON 导出或仅网页；配额、限流、历史回补和失败重试规则；
6. 是否允许内部保存原始 URL、记录 ID、字段快照和证据摘要；
7. 试用价格、正式价格、调用量、国家/平台加购费用；
8. 请提供一份脱敏真实响应和字段字典，不要发送密钥。
```

## 最实际的购买顺序

1. 先向 Sensor Tower 询价，要求 `App Performance Insights/API`，并明确 5 个国家与游戏榜历史。
2. 同时向 DataEye 询价 `ADXray 游戏情报（国内&海外）`，明确微信/抖音媒体覆盖，不要只买素材库。
3. 用 42matters 做身份和 IAP/SDK 辅助核验；如果报价过高，可先延后。
4. 每家先要脱敏样本和一周试用，样本通过字段检查后再购买正式额度。

系统会保留每个来源的覆盖范围、授权备注、字段缺口和试采状态；没有真实试采通过的来源不会标记为“已接入”。
