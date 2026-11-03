# 统一产品关联、多平台证据与机会判断

## 已确认与建议默认值

已确认：统一产品关联 → 多平台证据卡 → 跨平台机会判断；总计最多5项，身份、玩法、IAA证据边界独立，保留历史。平台名次不平均，不建立“综合第几名”。海外缺失不阻断国内。

建议默认值（版本冻结，待时间外回测）：两个平台当日Top20、各5/7日和最近3日连续视作多平台成熟；跨平台扩散需要本平台满足既有新增/加速门槛，另一平台早至少3天出现且有5/7日和最近3日连续记录。最新日正负方向相反视作分歧。在既有配额内按扩散、多平台信号、单平台、分歧安排研究顺序，不增加入选资格。成熟头部只能占基准席。

## 实现与数据流

1. `SourceEntity`：来源、平台、市场、来源稳定ID（无ID用名称+发行方）的身份键。无ID且无发行方的记录只能按观察单独存储，不允许建立跨日人工关联。
2. `/market/candidates` 返回 associationKey、unifiedProductId/Name、associationBasis、identityReview、reviewedGameplay，保留原candidateKey及原始gameplay。
3. `SourceIdentityService` 校验租户、真实观察引用、日期、证据和修订号；LINK可指向已有可见产品或新建待识别产品。新建不虚构平台ID。显式UNLINK阻止官方ID自动重新关联；再次LINK可恢复。CLASSIFY只改变该来源平台的玩法依据，其他/重度不进入轻度候选。
4. `CaptureResearchLeadService` 先按截止读取已生效身份/玩法，再计算单榜资格；`OpportunityPortfolio` 调用 `CrossPlatformAssessment` 进行跨平台评估和全产品去重，再分配席位。正式产品ID占用同一去重空间；旧版同平台同名保守避重继续保留。
5. `WeeklyReportService` 冻结身份事件、独立名次、7日材料/哈希/定位、缺失、来源家族、分歧、候选审计及版本。相同输入复用，审核/撤销后生成新修订，不改旧报告。
6. UI：产品研究 → 产品关联与核验 → 筛选同名待核 / 选择1～20项 → 关联；逐项玩法、撤销、历史；然后每周精选重新生成。正式卡与研究卡共用多平台证据组件，Markdown导出同一冻结字段。

## API 契约

统一前缀 `/api/v1/market/identity`，响应沿用 `{data: ...}`。

- GET `/products?q=`：最多50个当前组织可见产品的id/name，关联已有产品使用。
- GET `/history?observationId=&rank=`：校验组织可访问后返回按修订倒序的不可变事件。
- POST `/reviews`：必填 `Idempotency-Key`（16～64位字母/数字/连字符）。相同正文重试复用，不同正文冲突。

请求示例（只是契约，不可作为真实身份材料提交）：

```json
{"action":"LINK","actor":"核对人","effectiveDate":"2026-11-03","productId":123,"confirmedSameProduct":true,"evidenceUrl":"https://publisher.example/game","excerpt":"发行方在官方说明中列出的对应平台游戏信息","reason":"已核对发行方与官方平台详情为同一产品","members":[{"observationId":82,"rank":3,"revision":0}]}
```

`action` 为 LINK / UNLINK / CLASSIFY / CLEAR_GAMEPLAY。LINK二选一：productId已有产品，或productName新待识别产品；必须confirmedSameProduct=true。CLASSIFY必须family属于消除/合成/塔防/跑酷/解谜/益智/模拟经营/其他/重度之一。所有操作需actor、effectiveDate、http(s)evidenceUrl、至少10字excerpt、至少5字reason和1～20个不重复members。生效日不得早于引用观察或上一修订，不得晚于今天。revision不符返回409，字段/租户/证据无效返回422。旧修订不删除。

`researchLeads` 新增platformDiagnostics（候选/已关联/缺玩法/入选，按榜单条目统计）、productEvidence（已确认统一产品的冻结证据）；每个audit/item新增crossPlatform。cards中rank只允许当日值；过往最新记录保留latestObservedDate但rank=null。possibleMatches仅同名提示，不参与计数、排序或身份去重。正式report_item.frozen_facts追加crossPlatform（无已关联采集证据可为空）。

## 迁移与运行

V16新增两张中文注释表，只追加结构；现有V1～V15不修改。contracts/schema.sql追加同一DDL。无需新环境变量、MySQL CLI或Docker，无新增常驻进程。

```powershell
mvn -q -DskipTests package
java '-Dloader.main=com.youkia.iaa.collection.capture.RankingCaptureCommand' -cp backend/target/iaa-radar-api-0.1.0-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher --migrate --env-file .env
npm --prefix frontend run build
```

执行前用scripts/JdbcBackup.java备份指定目标库。生产用服务器app.env，不能将本地.env不同端口的库混同。应用回滚保留新增表和已登记审计历史，不回滚数据、不覆盖冻结报告。

## 仍缺的真实数据

微信/抖音稳定appid、官方跨平台产品对应关系或发行方声明、实机核心循环与首发日期、版本级广告/内购证据。iOS官方榜单的分类6014不足以判断消除/合成等细玩法；抖音无明确描述标签时同样保持未知。人工证据核验入口可补齐，不能凭游戏名称让AI猜测。新增来源或官方详情采集需独立验证，不在本轮虚构自动补全结果。排名变化不是用户增长，扩散线索不是新赛道或因果证明。

知识回放额外边界：人工LINK保存当时统一产品名称；官方ID复用只在产品/平台创建和更新时间均不晚于知识截止时成立。关系之后变化而无旧关系快照时保持未知，不使用当前关系假装历史已知。
