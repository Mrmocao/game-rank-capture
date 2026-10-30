# 真实免费来源验证（2026-10-27更新）

本次最终使用 Apple 官方游戏分类 RSS，非早期免费App总榜：

- https://itunes.apple.com/cn/rss/topfreeapplications/limit=100/genre=6014/json
- https://itunes.apple.com/us/rss/topfreeapplications/limit=100/genre=6014/json

HTTP200，各100条，source updated为2026-10-27。快照6011/6012，游戏观察报告12；原始JSON、证据位置/哈希、幂等返回均已保存。完整数据和论据见 [本次报告](REAL-VALIDATION-REPORT-2026-10-27.md)。

`powershell -File scripts/validate-free-source.ps1` 从固定Apple地址获取材料，再交给 `POST /api/v1/admin/sources/apple-games/collect-material` 校验与入库。默认只接受来源日期，禁止把当前材料改成过去日期。接口自带材料的来源声明不是密码学认证，真实来源证明为脚本HTTP获取记录及留存原文。

免费游戏榜仍不提供IAA正面证据；不自动确认纯IAA，不称为完整商用授权。AppBrain公开页面可按Google Play国家展示Top Free Games、包名、开发商、评分、安装量区间和近30日估算安装量；US页面显示更新时间2026-10-27且名次存在缺口，因此实际覆盖保留为PARTIAL。公开页面没有为本项目提供历史API或商业再分发授权。引力引擎公开页未登录展示微信/抖音日榜前20，页面明示完整100条和历史需要登录；浏览器记录到其公开请求是加密响应，不能虚构字段协议或保存授权。GameLook与微信合集用于行业/IAA背景文章，不能当逐产品排名数据：微信合集当前跳到“此账号已被屏蔽”。没有调用AI、没有付费或自动购买。早期apple-rss App总榜保留为调试历史，不再作为游戏报告的候选源。

部署验证中，开发机 HTTPS 获取 AppBrain 美、英、德、法页面后经 SFTP 上传服务器，再调用服务器 localhost API 导入；服务器直连 AppBrain 报 `Network is unreachable`，因此没有宣称服务器直采。部署快照为 CN iOS 100 条、Google Play US/GB/DE/FR 99/100/100/100 条。US 原页面缺第90名，保留名次并标记 `PARTIAL`；其余为 `COMPLETE`。每份材料保存 SHA-256，重复导入返回同一 snapshot 且 `reused=true`。AppBrain 安装量为页面显示区间，Recent 为近30日估算，不能换算为收入；商业再分发授权尚未核实。

来源调研原始页面和截图在 `validation/source-research/`；脚本：`powershell -File scripts/validate-public-web.ps1 -Countries us,gb,de,fr`。部署证据在 `validation/deployed/`，包括 JDBC、API 反例和浏览器结果。
