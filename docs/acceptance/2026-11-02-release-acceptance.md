# 2026-11-02 真实采集与每周研究交付验收

## 完成矩阵

| 要求 | 实际结果 | 验收证据 |
| --- | --- | --- |
| 最新采集工程与数据同步到服务器 | Java独立来源模块、浏览器传输、采集工作台、V13/V14及研究桥接已部署 | 发布目录`/opt/iaa-radar/releases/20261102-capture-weekly-r3`，systemd active，前端3个发布文件与本地逐字节一致 |
| 最近一周初始化 | 10月27日—11月2日，49份引力榜单＋当日Apple榜单，共50份、5,000条，均连续Top100 | 生产JDBC逐行比对数据库、bundle、原材料SHA256和DOM定位全部一致；同榜各日真实日期经UI读取，未重标RSS日期 |
| 服务器执行真实抓取 | 服务器另从Apple公开RSS成功取得100条，当前累计51份观察、5,100条记录 | `server-real-apple-capture.json`及`production-final-verification.json`；不是5,100款去重游戏 |
| 全流程验证 | 原材料→解析→校验→MySQL→冻结报告→页面→导出与历史通过 | 真实MySQL、真实HTTP/Chrome和浏览器验收；故障/边界以明确合成测试补充 |
| 本周标准列表 | 报告9、修订2，正式0项＋待核研究线索5项，均7日记录 | [标准列表](2026-11-02-weekly-shortlist.md)及线上冻结报告 |
| 独立大模型评估与优化 | 独立子agent首轮要求修改；修订后PASS_WITH_LIMITATIONS | [两轮独立评估](2026-11-02-shortlist-independent-review.md)；35个主榜点＋35个旁证点逐项核对 |
| 发布与追溯 | 最新程序已运行；报告8和旧版本记录保持不变，报告9重复生成复用 | 报告输入哈希、1,261行旧报告/榜单逐行保持、免登录接口及页面实际验证 |

最终JAR SHA256：`6188f39a00ffb322801761a0724593c5275c6017aed3fb418986155468c6cd7c`。

最终报告输入SHA256：`57be0d1e9edfb2e2f02ccedfc23b26d4d2a2b6dd0766fefc4054c2cf4ececfbd`。

## 实际执行的验证

```powershell
$env:RUN_CAPTURE_BROWSER_TESTS='true'
$env:RUN_LIVE_MYSQL_TESTS='true'
mvn -q test

# 历史接口大JSON排序修复后单独执行相关真实JDBC回归
mvn -q test -pl backend -am '-Dtest=ResearchPipelineMySqlTest' '-Dsurefire.failIfNoSpecifiedTests=false'

mvn -q package -DskipTests
npm run build --prefix frontend
node data/weekly-validation-20261102/browser-qa.cjs
```

完整测试146项通过，0失败、0错误、0跳过；新增历史分页回归后相关3项真实JDBC测试全部通过，累计147项已验证测试。数据库测试中的合成事实在事务结束回滚，不当作真实市场材料。浏览器离线样本测试与线上真实采集分开记录。

生产按7个日期逐批运行`VerifyRankingCapture.java --require-top100 --expected-count=7|8`，验证50份/5,000行及采集列中文注释。重复重放10月27日产生7条`reused=true`运行，观察仍为7份，未重复造快照。请求Apple当前RSS冒充10月27日时明确失败`SOURCE_DATE_MISMATCH`，观察总数不增加；10月26日查询为空，无回填。**这条失败是验收时主动验证历史日期防线的真实请求，保留在执行日志，不删除失败记录或伪装成功。**

生产迁移前通过JDBC备份45表，迁移和发布后再逐行核对旧`weekly_report`、`report_item`、`ranking_snapshot`、`ranking_entry`共1,261行，保持一致。报告8修订1保留原名单；规则1.1.0生成报告9修订2，同输入再次生成复用9，旧报告输入哈希不变。

页面验收采用`frontend-testing-debugging`技能；Browser插件未提供，使用Playwright Chrome。验证1440px桌面和390px手机视口，手机页面宽度恰为390px，无横向溢出；实际测试国内5项/海外0项筛选、7日证据展开、Markdown导出、打开历史修订1再回修订2、采集详情100行、免口令访问。没有页面运行异常或业务API HTTP错误。模拟访问状态接口503后显示连接错误，解除模拟后“重新连接”成功，无误显示口令页。

真实浏览器验收发现历史周报接口因MySQL排序携带大JSON报错，已改为先分页排序标量ID、再读取摘要。在真实MySQL会话中将排序缓冲设为32KB、冻结JSON扩至2MB后验证分页顺序与研究线索计数正确；会话配置还原、合成写入回滚。生产再次点击历史周报成功。验证脚本也移除无必要的JSON行排序，没有通过调大服务器全局配置掩盖问题。

构建仍有既有前端bundle超过500kB的提示，不影响本轮功能通过。截图及原始日志位于本地忽略目录`data/weekly-validation-20261102/`；仓库仅保留可公开的标准列表、设计与验收结论，不上传会话、数据库备份、密钥或原始材料。

## 运行边界与后续动作

生产保持`PROFILE_ACCESS_TOKEN=`，未开启用户体系。原有Apple每日和周报定时器仍为启用状态；本轮验证的是显式执行、实际发布及定时配置，没有把未来长期运行称为已验收。生产没有本机引力登录会话，`CAPTURE_WORKER_ENABLED=false`；完整Top100由Windows专用Chrome执行。**本轮材料同步已经完成，持续跨机器自动同步尚未实现**，后续需按[运行说明](../12-WEEKLY-CAPTURE-DELIVERY.md)传输/重放新材料。

五项是微信第三方榜单研究线索，非官方推荐、非全市场前5，纯IAA证据尚不足。抖音真实榜单缺玩法标签，下一步可对已有描述做有引用的分类并安排实机核验；海外需连续可比来源。还需逐项确认平台appid、版本/市场、广告及全部内购入口、核心玩法和工期。缺口不能由模型推断补齐。

本轮模型评估为独立Codex子agent对实际冻结名单进行两轮审查；未宣称调用了系统配置中的AI中转供应商。后续AI提取仍须遵守Schema、证据引用及业务复核边界。
