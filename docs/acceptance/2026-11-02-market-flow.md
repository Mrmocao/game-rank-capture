# 2026-11-02 市场数据完整流程验收

## 本次完成

FLOW-01～06：市场概览、微信/抖音各榜、来源候选库（含图标与描述）、证据/日期抽屉、多人研究记录、来源描述玩法识别、Windows采集到服务器自动交付、冻结周报和历史追溯。核心读桥MarketCaptureService，V15迁移与contracts/schema.sql一致且有中文注释。来源核心仍不依赖业务模块。

- 实际入口：[微信日榜](https://rank.gravity-engine.com/wechat/day/)、[抖音2026-11-02日榜](https://rank.gravity-engine.com/douyin/day/?date=2026-11-02)。第三方来源，非官方客户端榜。
- 新采批次166b0c92-b6f7-43fc-990a-04ea7d2f0f2e；服务器重放批次bfc3f268-2ec0-4757-adc3-5e89dfbdff35。
- 微信3榜、抖音4榜各100；Apple中国96且PARTIAL；合计796排名记录、620来源候选。96不被历史100补齐。
- 56个抖音候选有明确描述玩法依据；其余保持未知。DAU/GMV等宣传原句保留，不转成核实指标。来源玩法均verified=false，IAA保持UNKNOWN。
- [周报10 / 修订3](http://14.103.231.1:18080/#/reports?date=2026-11-02&reportId=10)：正式0、研究线索5。名单仍为打个螺丝、不停找找找、对子消消乐、毛线通关、赵云与阿斗；理由、反面趋势与全部候选未选原因冻结。
- 抖音当前100个热门榜候选的未选原因：76玩法依据不足、5连续观察不足、4热度/增势未达门槛、15畅销风险。180条抖音审计还包括80条截止日未观察的历史候选，不能把它们当当前机会。
- 报告10输入SHA256：3e027cf659d4714dcf29bb43a1a4fcee7664381c5f479bb654addd07d6d024d8；报告9仍为57be0d1e9edfb2e2f02ccedfc23b26d4d2a2b6dd0766fefc4054c2cf4ececfbd。

## 实际执行的验证

| 验证 | 命令 / 方法 | 结果与性质 |
|---|---|---|
| Java回归 | RUN_LIVE_MYSQL_TESTS=true，根目录mvn -q test；修改后补跑RankingCaptureTest/MarketCaptureMySqlTest/CaptureResearchLeadTest/SourceGameplayTest | 最终156项、0失败/错误/跳过；含真实JDBC事务回滚，合成材料不充当市场证据 |
| 浏览器传输契约 | RUN_CAPTURE_BROWSER_TESTS=true，mvn test -pl browser-capture -am -Dtest=BrowserDomTest -Dsurefire.failIfNoSpecifiedTests=false | 3项本地模拟网页测试，计入156；不冒充源站实采 |
| 交付边界 | python scripts/test_capture_sync.py | 6项通过：仅传材料、哈希篡改拒绝、ZIP越界拒绝、回执幂等、源失败不造快照或报告、同批不同清单冲突 |
| 构建 | npm run build --prefix frontend；mvn package | 通过；保留约1.06MB前端大包提示 |
| 真实采集与交付 | RankingCaptureCommand --run；sync-capture-server.py --batch | 8榜796条，原始来源日期与实际采集时间保留；回执PARTIAL、周报10 |
| 重复同批 | 同一--batch再次发送 | reused=true；不重复入库/生成报告 |
| 自动发现 | 常驻同步进程自动发现44ac3e59-467a-42f7-a566-4a5ac539d2a1 | 真实材料重放（非新抓取），796条幂等观察；后台自动送达并复用报告10 |
| 数据逐行审计 | 服务器VerifyRankingCapture.java，--expected-count=8 | 数据库、raw SHA256、bundle、DOM定位逐行一致；未使用require-top100掩盖Apple缺4条 |
| 迁移与冻结历史 | Flyway V15，JdbcBackup / JdbcVerifyPreserved | 备份51表10,086行；1,263旧冻结行不变；生产现59观察5,896行，重复重放未再增加 |
| 公网页面 | 现有Chrome/Playwright，1440px与390px | 概览796、微信/抖音各100、候选玩法/证据、7日历史、旧报告、移动无页面横向溢出、无pageerror、仍免登录 |
| 研究记录 | UI显式SYNTHETIC响应 + 独立真实JDBC回滚测试 | 双负责人、幂等冲突、组织隔离、引用已被新抓取替代的旧观察、保存快照不被改名覆盖；生产无测试选用残留 |

Browser plugin未提供，因此使用现有Playwright。现场脚本/截图：本机TEMP/iaa-flow-20261102；原始材料、日志及JSON核验：data/weekly-validation-20261102、data/external-captures，均不提交凭据和真实原文到Git。

## 发布与持续运行

current指向/opt/iaa-radar/releases/20261102-market-flow-r3；JAR SHA256 786ab07abc4c6125df23ac86d08db3833459200de079227c1b55921c43e93b73。已安装当前用户登录启动的Java采集执行器与Python同步执行器；计划08:30/23:00，发现清单每60秒。服务器只重放来源材料，不接收本机Cookie/Chrome目录/.env。生产不需要Docker或MySQL CLI，不启用用户登录。

## 尚未真实验证与下一步

海外新的连续来源缺失不阻断上述国内闭环。尚缺实机玩法、稳定平台身份、版本级纯IAA证据、授权玩家样本与供应商长期保存/再分发协议；因此5项是待核研究线索，不是已确认IAA推荐。真实供应商AI未参与结论生成。

自然08:30/23:00调度的长期稳定性、电脑重启后的持续运行、灾难恢复演练尚未实测；当前验证为手动新采+常驻自动发现真实重放。电脑开机、用户登录、来源会话有效是实际依赖。下一步观察自然计划回执，给短名单逐项补实机/变现与稳定身份依据；有可访问海外来源后按同一原始材料契约扩展，不改旧报告。
