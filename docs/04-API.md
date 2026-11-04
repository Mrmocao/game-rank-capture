# 04 API 开发契约

建议统一前缀 `/api/v1`；Java后台为事实源；前端只展示与提交，不在浏览器计算入库结论。

## 独立外部榜单采集（2026-11-02 已实现）

前缀 `/api/v1/admin/ranking-capture`，沿用工作区认证，租户由服务端决定。此接口保存外部观察，不写产品快照或触发周报。

| 方法/路径 | 语义 |
| --- | --- |
| GET /sources | 默认八榜来源、榜单键、URL、权威性与公开范围 |
| POST /run | 无 body 执行八榜；可传 requests 数组指定来源范围，同步返回逐项结果 |
| GET /runs?batchId= | 每次执行及失败日志，无 batchId 时最近200条 |
| GET /observations?platform=&date=&source=&chart= | 有效观察列表，date 为来源业务日，最近200份 |
| GET /observations/{id} | 完整来源 bundle 与原材料相对路径 |

范围校验错误返回422；HTTP200不表示各源均成功，须检查 `attempts[].status/freshness/coverage`。请求与实际验证示例见 [采集说明](10-RANKING-CAPTURE.md)。

## 1. 通用约定
- 请求/返回JSON，日期YYYY-MM-DD，时间ISO8601；分页page从1开始，pageSize默认20、最大100。
- 成功：`{"data":...,"requestId":"..."}`；列表data包含items/total/page/pageSize。
- 失败：`{"error":{"code":"...","message":"...","fieldErrors":[]},"requestId":"..."}`。
- HTTP 400参数错误，401未登录，403越权，404不存在，409乐观锁/幂等冲突，422规则校验失败，429限流，503来源暂不可用。
- 服务器校验date不得晚于业务今天；date缺省取元数据返回的最新可用日。列表排序字段白名单，禁止直接拼SQL。
- 新增选用、重试和周报生成接受Idempotency-Key。相同键与相同请求返回同结果，不同负载同键409。
- 并发编辑提交version，不匹配409；权限和身份由会话决定，不能信任前端传的createdBy。

## 2. 只读页面接口
| 方法/路径 | 语义 |
| --- | --- |
| GET /meta/coverage?date= | 平台、国家、榜单、最新日期、各源完整性及缺口 |
| GET /products | 统一产品分页，date/q/platform/region/chartId/tag/signal/poolState/selectionState/sort/order |
| GET /products/{id}?date= | 截至date市场信息＋明确标记current的制作信息 |
| GET /products/{id}/versions?date= | 平台版本、当日各榜名次、IAA判定 |
| GET /versions/{id}/rankings?chartId=&from=&to= | 单榜历史，缺失原因和具体快照ID |
| GET /products/{id}/evidence?date= | 证据、规则命中、人工覆盖、状态变化 |
| GET /rankings?chartId=&date= | 一张榜当日名次，不混榜 |
| GET /selections | 按productId/owner/status分页，当前状态 |
| GET /reports | 周报历史列表，可选periodStart/version |
| GET /reports/{id} | 冻结事实条目＋独立当前选用摘要 |
| GET /exports/products | 复用列表条件，CSV含date、chart/source、数据状态；大任务返回导出job |
| GET /reports/{id}/export?format=markdown | 导出指定报告版本 |

## 3. 产品列表响应样例（非真实产品）
```json
{
  "data": {
    "date": "2026-10-25", "businessTimezone": "Asia/Shanghai",
    "coverageState": "PARTIAL", "page": 1, "pageSize": 20, "total": 1,
    "items": [{
      "id": "101", "name": "示例产品", "poolState": "FORMAL",
      "stateAsOf": "2026-10-25", "firstObservedDate": "2026-10-19",
      "rankings": [
        {"versionId":"201","platform":"WECHAT","region":"CN","chartId":"301","chartName":"示例人气榜","kind":"HEAT","rank":12,"delta7d":8,"snapshotId":"401","dataState":"OBSERVED","monetization":"PURE_IAA"},
        {"versionId":"202","platform":"IOS","region":"CN","chartId":"302","chartName":"示例游戏免费榜","kind":"HEAT","rank":null,"delta7d":null,"snapshotId":null,"dataState":"MISSING_SNAPSHOT","monetization":"MIXED"}
      ],
      "signals":[{"type":"RAPID_RISE","versionId":"201","chartId":"301","ruleVersionId":"501","date":"2026-10-25"}],
      "selectionSummary":{"scope":"CURRENT","total":2,"inProgress":1,"completed":0}
    }]
  }, "requestId":"example-request"
}
```
其他dataState：NOT_IN_COVERAGE（完整TopN未出现）、NOT_COLLECTED（未配置覆盖）、INSUFFICIENT_HISTORY（变化不可算）。榜单整体失败记录在coverage，产品不是“未上榜”。

## 4. 填报及人工修正
| 方法/路径 | 要求 |
| --- | --- |
| POST /products/{id}/selections | owner、可空referenceVersionId、status默认SELECTED、selectedOn、startedOn/finishedOn可空、note；引用版本必须属于产品 |
| PATCH /selections/{id} | 字段＋version；日期一致性校验；审计 |
| PUT /products/{id}/overrides/{field} | 管理权限；允许字段白名单、value/reason/version，不修改来源值 |
| POST /versions/{id}/assessments | 有权限人员录入实测依据和结论，需evidenceIds和reason |
| POST /products/{id}/exclude | reason、version；停止推荐，保留历史 |
| POST /products/{id}/restore | 恢复后重算资格，不直接强制FORMAL |
| POST /identity-links | versionId、targetProductId、evidenceIds、reason；关联事件 |
| POST /identity-links/{id}/revert | 冲突校验、撤销事件、不删除历史 |

## 5. 配置与采集接口
GET/POST/PATCH /admin/sources，GET/PATCH /admin/charts，GET/PATCH /admin/jobs；敏感字段只接收不返回，用secretRef配置。
GET /admin/runs、GET /admin/runs/{id}：状态QUEUED/RUNNING/SUCCEEDED/PARTIAL/FAILED、统计日、真实/模拟、任务阶段、数量、错误分类、可重试性。
POST /admin/jobs/{id}/run：明确date、mode(LIVE/DRY_RUN)；返回202及runId，不同步等待完整AI流程。
POST /admin/runs/{id}/retry：仅重试失败单元、保留父runId，幂等。
GET /admin/rules/versions；POST /admin/rules/drafts；POST /admin/rules/{id}/simulate；POST /admin/rules/{id}/publish。
试算返回新增正式/降级/待识别数量和样本解释；发布不修改旧rule_version。
POST /admin/reports/generate：periodStart、periodEnd、ruleVersionId，返回202+jobId；同周期不同内容新增报告版本。

## 6. 最小权限建议
READER：读产品、榜单、周报及选用记录；EDITOR：增加选用、编辑本人记录；ADMIN：配置、采集重跑、人工修正和编辑所有记录。
这是建议默认值，优先对接锦鲤现有权限，不建设复杂RBAC。负责人姓名与登录账号分开，owner可填别人姓名，修改权限由createdBy而不是姓名决定。
导出相同鉴权；原始证据访问按数据授权限制；日志不记录密钥、完整token、敏感请求头。

## 7. 已落地的市场洞察API（2026-10-27覆盖前述建议契约）

当前报告生成是同步HTTP 200，不返回202任务ID：`POST /api/v1/admin/reports/generate?periodStart=...&periodEnd=...`，使用当前已发布规则，最多7天；返回冻结报告、reused与inputSha256。相同内容重试复用，改变数据追加revision。报告历史分页 `GET /api/v1/reports?page=1&pageSize=20`；详情 `/reports/{id}`；当前Skill `/reports/skill`。每期合计最多5款（含市场观察）。旧报告原数量保留。

免费来源运行 `POST /api/v1/admin/insight-collection/run` 同步返回每源attempt结果（SUCCESS/FAILED）；`GET /api/v1/admin/insight-collection?date=...` 返回覆盖与近期任务。HTTP 200不等于所有来源成功，必须逐项检查status。当前未实现完整的来源配置CRUD、通用任务异步队列或文章AI抽取；不能把本节接口当作前述全套设计已完成。


## 8. 独立信息抓取工作台（2026-11-02）

新增管理接口 /api/v1/admin/capture-workspace：目标登记/配置、定时计划、Top100运行、AI诊断工单和真实重试。读取独立external_capture与capture配置表，不触发产品或周报。完整路径、请求体和状态见 [工作台API](11-CAPTURE-WORKSPACE.md#api)。旧低层榜单接口保持兼容。

## 2026-11-02 抓取研究短名单扩展

`GET /reports/{id}`、`GET /reports/latest`新增`researchLeads`，仅从本报告冻结的`research_snapshot.captureResearchLeads`读取，旧报告没有该字段时不实时补算。`items`仍只表示正式推荐，不能将线索显示为已验证纯IAA。`researchLeads.items`含signalType/signalLabel、逐日history及evidence、counterEvidence、gaps、formalEligible=false、monetization=UNKNOWN；thresholds和algorithmVersion随期冻结。

`GET /reports`历史分页增加research_lead_count；先对标量ID排序分页，再读取摘要，避免对多MB冻结JSON执行filesort。正式与线索合计最多5；新研究目标发布不得超过5，生成时effectiveParameters冻结实际生效配置。报告时间范围左闭右开，本周截至11月2日使用2026-11-02至2026-11-03；研究线索独立回看10月27日至11月2日。

`GET /access`返回enabled=false、authenticated=true时直接进入工作区；网络错误是连接状态，不代表用户必须输入口令。

## V18 在线网页采集

服务配置、规则版本、真实试抓、发布/回退与网页证据接口位于 `/api/v1/admin/capture-workspace/web`，详细请求流程见 [在线采集API](18-ONLINE-WEB-CAPTURE.md)。通用网页结果独立于榜单观察。


商店指标独立接口、权限、字段和口径见[20-GAME-METRIC-CAPTURE.md](20-GAME-METRIC-CAPTURE.md)。
