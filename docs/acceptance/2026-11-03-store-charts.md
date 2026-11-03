# 2026-11-03 商店榜单与海外修复验收

## 已交付

STORE-01～05：iOS中国免费、付费、畅销3榜；AppBrain Google Play美国/英国/德国/法国各免费、付费、畅销、新免费、新付费5榜。CaptureSources总目录30范围；国内服务器10范围，海外Actions20范围。所有范围按来源/市场/日期独立保留，不综合名次。V17 execution_mode带中文注释，API/参考DDL一致。配置、页面、规则2.2.0、Skill2.5.0、部署手册和整体架构同步更新。

部署 `20261103-store-charts-final2`，JAR SHA256 `6a3a225297a9a77688bbbb6665ab21bb8b0bbbadee2f2384dcd2eec23f31a057`。Web/国内采集/报告桥/HTTPS接收器4个systemd服务active；接收器512MiB，原Web1280/采集1536/报告桥512MiB上限不变。实测总内存7948MiB，末次检查可用3740MiB；不需要Docker、MySQL CLI或个人电脑后台。

## 真实数据

2026-11-03业务日，原始页面日期严格保留。Apple源更新时间2026-11-02（-07:00），换算北京时间业务日11月3日；AppBrain显示11月3日，时区未声明。

|来源/地区|免费|付费|畅销|新免费|新付费|合计|
|---|---:|---:|---:|---:|---:|---:|
|Apple 中国|100|100|100|不接入|不接入|300|
|AppBrain 美国|99|99|98|100|5|401|
|AppBrain 英国|100|92|99|100|3|394|
|AppBrain 德国|100|99|99|100|2|400|
|AppBrain 法国|100|42|99|100|2|343|

Google合计1538条，7榜请求范围完整、13榜部分覆盖，0来源失败；没有填补缺行或将稀疏名次重排。加微信3榜/抖音4榜各100，共30范围2538条排名（不是去重游戏数）。榜型、地区、包名、来源链接、缩略图URL、评分与安装估算原文存于来源材料；未把估算当核实日活/收入。Google图标源为googleusercontent，国内网络加载仍可能失败；本轮未扩展图标二进制离线缓存。

Apple真实批次 `555c8ee9-13dc-4c8f-b24f-9e6237d05cb0`；第一批云端 `d4975b48-3f98-4256-8ce3-2e5590ae2920`，服务器批次 `eb4dfeaa-a422-4900-ba7d-b78fd7c89ffa`。两次实际Actions成功：
- https://github.com/Mrmocao/game-rank-capture/actions/runs/41579930916
- https://github.com/Mrmocao/game-rank-capture/actions/runs/41580170509

首批通过Java直接HTTP采集，第二批验证了HTTP材料提交/Java解析路径；最终保留已实测的Java直接采集路径，Python负责计划、打包和HTTPS交付。原始材料没有上传Git或公开artifact。

服务器海外DNS/连接异常；Actions能读取AppBrain，但到生产22/18080超时。实际采用独立HTTPS443、专用证书信任和令牌，仅开放计划/材料接口。旧SSH试验公钥与sudo规则、两项GitHub SSH Secret已撤销。未调用付费数据或绕过登录/验证码。

## 实际检查

- `mvn -q package`：178项定义中152离线执行通过，26个需真实环境的用例跳过；0失败/错误。新增错榜/跨国/日期/缺行、远程调度隔离、付费风险与解析器换版去旧行测试。
- `RUN_LIVE_MYSQL_TESTS=true mvn -q -pl backend -am -Dtest=MarketCaptureMySqlTest,SourceIdentityMySqlTest -Dsurefire.failIfNoSpecifiedTests=false test`：6项真实JDBC测试通过，合成写入事务回滚；其余20项真实环境用例本轮未执行。
- `python -m unittest discover -s scripts/tests -v`：9项离线测试通过，包含坏哈希、错误范围/时间、目录穿越、全失败零材料、未授权/任意路径/超限请求拒绝。
- `npm --prefix frontend run build`：通过；保留既有约1.09MB JS包体提醒。
- `VerifyRankingCapture.java`：生产Apple3榜300条、Google20榜1538条，逐行核对JDBC、原始字节SHA、原始RSS/HTML名次、名称和来源身份，全部通过。
- 同一个云端archive重新交付，返回`reused=true`且原serverBatchId不变，不重新回放；最新周报重复生成复用报告23/修订7。
- 生产JDBC备份54表14312行，核对1375条旧冻结记录全部保留；报告18/20输入哈希不变。.env配置库独立备份54表8858行，365条冻结行不变，均完成V17。
- 真实HTTPS无令牌返回401；固定信任证书读取四国启用计划成功。旧SSH任意命令测试未执行命令，随后撤销该通道。
- Browser plugin未提供，按frontend-testing-debugging技能使用现有Playwright。生产1440×1000与390×844检查：美国免费99条、平台/榜型/国家下拉、法国新付费2条、iOS付费/畅销各100、缺失日0条、4个真实云端入口、1538条回执、旧报告20四项，应用console/API错误0，移动端无页面横向溢出。
- 发现并修复快速切换日期的迟到请求问题；另用仅浏览器响应延迟模拟验证旧日结果不会覆盖新选缺失日，未写生产假数据。截图与QA JSON在本地Temp/iaa-store-charts-20261103，证据JSON在服务器shared/store-charts-20261103。

## 当前限制与下一步

报告23/修订7仍4个微信研究项、0正式纯IAA；Google已经进入输入和筛选审计（799个需求榜候选条目），但799条缺少细玩法依据且只有本轮历史，不能强行认定高增长/新赛道。需继续按日积累完整前序榜单，补官方商店详情/玩法原句、近似竞品差异及版本级变现证据，后续才参与早期机会资格。首次采到不等于新上线。

每日08:40/23:10北京时间Actions计划已启用；排队可能延迟，自然计划长期可靠性未验证。公开源结构/权限可能变更，短榜保持部分覆盖；没有购买历史Top100或保证完整历史。证书到期前需轮换，令牌/证书、关闭来源与失败排查见docs/17。来源公开可读不等于取得商业再分发许可，不公开原始材料。
