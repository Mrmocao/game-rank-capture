# 最近一周采集、研究短名单与发布

本轮业务约定为**正式精选与待核研究线索合计最多5项**。资料不足时少出；不能通过补造纯IAA证据、平台ID、历史排名满足名额。

## 数据流与边界

1. Windows专用Chrome复用用户已有会话，读取引力引擎可见日期控件和连续Top100。历史请求只对提供日期控件的引力来源生效。
2. `external-capture`校验来源、日期、名次、覆盖及证据定位，生成`bundle.json`和去除会话信息的`raw.bin`。
3. Java通过JDBC写入`external_capture_run`、`external_ranking_observation`、`external_ranking_entry`。来源日期、实际采集时间、数据库可用时间分别保存。
4. 服务器通过相同解析和校验链重放已核验材料，不转移Chrome配置、Cookie、账号或本机`.env`。重放保留原始采集时间，在生产登记新的可用时间。
5. 正式产品走稳定身份、版本级资格和六维研究模型；外部榜单走`CaptureResearchLeadService`，只产生独立研究线索，`formalEligible=false`、变现`UNKNOWN`。缺少appid不能以名称构造ID或跨平台合并产品。
6. `WeeklyReportService`冻结实际参数、正式候选、研究线索、逐日证据、未选原因和哈希。相同输入复用报告；规则变化生成修订，不改旧报告。

引力引擎是**第三方来源**，不等于微信/抖音官方客户端榜单。两个平台和各榜单名次不能合成一个市场排名；发行人热度不当作玩家需求，畅销匹配只作为需核查变现的风险信号。

## 研究线索规则 1.1.0

- 先要求明确的来源轻度玩法标签，截止日有记录，7日中至少5个观察日，且最近3日连续。空发行方不建立跨日名称匹配趋势。
- 当前同榜Top20进入“头部热度 / 基准观察”；或首末提升至少10名且当前进入Top50，进入“榜单增势研究”。不满足的留在观察池。
- 同来源同平台同名同发行方的畅销信号在整个7日窗口内检查，存在时先补变现证据，不入短名单。
- 各来源榜队列内按上升记录、观察天数和当前名次排序，队列轮转；同来源实体不重复占位，玩法标签最多2项。跨平台身份未核实不能合并为一款产品。
- 最新名次回落、其他需求榜同期走弱必须披露；原始定位、SHA256、业务日、采集/可用时间随报告保存。
- 这些是可解释、保守的研究默认值，**不是统计显著性、用户增长率、买量ROI或成功概率**。短名单不足5项不补齐。

抖音本轮原始榜单的玩法标签为空，有产品描述字段，但尚未经过独立玩法核验。未入短名单表示字段不足，不能解释为抖音市场没有机会。海外本轮没有新增连续7日来源，不以国内名单冒充海外覆盖。

## 本地采集与服务器重放

```powershell
# 最多7个自然日，不能选未来；历史日只采引力，今日含Apple。
./scripts/run-ranking-capture.ps1 -Run -From 2026-10-27 -To 2026-11-02
```

命令读取本地`.env`的JDBC配置。它与生产`/etc/iaa-radar/app.env`可能连接不同数据库；必须核对目标库。Apple公开RSS没有历史选择功能，不能给它重标日期补历史。

```bash
# 先备份生产数据库；无需MySQL CLI或Docker。
java -Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand \
  -cp iaa-radar-api-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --migrate --env-file /etc/iaa-radar/app.env

# 每个目录只含同一天、每个来源URL的一份bundle.json/raw.bin。
java -Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand \
  -cp iaa-radar-api-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --run --replay-dir /opt/iaa-radar/shared/intake-20261102/evidence/2026-10-27 \
  --env-file /etc/iaa-radar/app.env \
  --output /opt/iaa-radar/shared/external-captures
```

服务器`CAPTURE_MATERIAL_DIR`指向持久目录；无Chrome登录会话的服务器设置`CAPTURE_WORKER_ENABLED=false`、`CAPTURE_BROWSER_PROFILE_DIR=`。完整引力Top100仍由已登录Windows执行器采集。**本轮初始化材料已同步不等于建立了持续跨机器同步任务**；后续批次须按此流程传输/重放。原有Apple每日采集与周报定时器独立运行。

## 发布与回滚

根目录执行`mvn package`和`npm ci --prefix frontend`、`npm run build --prefix frontend`。Java进程必须使用独立发布目录，不能覆盖正在运行的JAR。先在独立预验收端口启动，关闭全部定时器，确认迁移、重放、报告与历史后再切换`/opt/iaa-radar/current`并重启`iaa-radar`，Nginx静态目录跟随该链接。

生产保留`PROFILE_ACCESS_TOKEN=`，无需口令。网络失败页面显示连接错误和“重新连接”；只有服务端明确启用口令且未认证时才出现登录页。

保留旧发布目录和迁移前JDBC备份。应用回滚可切回旧目录；V13/V14为追加表，旧应用不读取，不自动执行破坏性降级。不要在新写入后直接用旧备份覆盖数据库。

## 验收入口

- `/api/v1/admin/ranking-capture/observations?date=YYYY-MM-DD`：指定日期、来源和榜单观察。
- `/api/v1/admin/ranking-capture/observations/{id}`：原始bundle、证据定位。
- `/api/v1/admin/reports/generate?periodStart=2026-11-02&periodEnd=2026-11-03`：本周截至11月2日；区间左闭右开，研究线索回看10月27日—11月2日。
- `/api/v1/reports/{id}`与`/api/v1/reports`：冻结报告和历史修订；`researchLeads`与`items`分别表示待核线索与正式推荐。
- `scripts/VerifyRankingCapture.java`：真实JDBC逐行比对数据库、bundle、材料哈希、DOM定位及列注释；历史批次使用`--expected-count=7`，今日8榜使用`--expected-count=8`。

验证记录和本周标准列表见`docs/acceptance/`。本地`data/weekly-validation-20261102/`包含实际原材料和运行证据，按`.gitignore`留在本地，不上传凭据或会话到GitHub。
