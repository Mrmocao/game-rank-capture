"""Export frozen report and independently check every quoted rank against captured source JSON."""
import json, hashlib, html, re
from pathlib import Path

root=Path(__file__).resolve().parents[1]
out=root/'validation'
report=json.loads((out/'game-report.json').read_text(encoding='utf-8-sig'))['data']
sources=json.loads((out/'collection-results.json').read_text(encoding='utf-8-sig'))
by_snapshot={s['result']['snapshotId']:s for s in sources}
checks=[]; rows=[]; cards=[]
for item in report['items']:
    facts=json.loads(item['frozen_facts']); ref=json.loads(item['evidence_refs'])[0]
    source=by_snapshot[str(ref['snapshot_id'])]
    raw=(out/f"apple-games-{source['country']}.json").read_bytes()
    assert hashlib.sha256(raw).hexdigest()==source['rawSha256']==ref['raw_sha256']
    entry=json.loads(raw)['feed']['entry'][facts['rank']-1]
    assert entry['im:name']['label']==facts['productName']
    assert ref['evidence_locator']==f"feed.entry[{facts['rank']-1}]"
    assert facts['monetization']=='UNKNOWN' and item['section']=='OBSERVATION'
    checks.append({'product':facts['productName'],'snapshotId':ref['snapshot_id'],'evidenceId':ref['evidence_id'],'rankVerified':True,'hashVerified':True})
    rows.append(f"|{facts['region']} iOS|{facts['rank']}|{facts['productName']}|{facts['matchedKeywords']}|{ref['snapshot_id']} / {ref['evidence_id']}|")
    cards.append(f"### {facts['region']} · {facts['productName']}\n\n{item['content']}\n\n来源介绍摘录（发行方文字，未经独立玩法核验）：{facts['summary'][:350]}\n\n[商店详情与素材入口]({facts['storeUrl']}) · [原始榜单]({facts['sourceUrl']})\n\n证据位置 `{ref['evidence_locator']}`；快照 `{ref['snapshot_id']}`；证据 `{ref['evidence_id']}`；原始 SHA-256 `{ref['raw_sha256']}`。\n")
assert report['domesticCount']==0 and report['overseasCount']==0 and len(checks)==10
(out/'evidence-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
text=f'''# 2026-10-27 免费游戏来源全流程验证与观察报告

最终报告 ID **{report['id']}**，修订 **{report['revision']}**。报告期 2026-10-21 至 2026-10-28（左闭右开，实际仅观察到 10 月 27 日）。

## 本次结果

- 中文注释已在真实 MySQL `iaa_radar` 落库：24 张业务表、236 个业务字段，缺失注释 0。Flyway V5 成功。迁移前后字段类型、默认、可空性、排序规则与键标记一致。详见 [实际中文数据字典](validation/database-dictionary-after.md)。
- 使用免费 Apple 官方游戏分类 RSS（genre=6014），CN/US 各100条、共200条榜单记录，两份材料均HTTP 200。两国排名独立，不合并为综合榜。覆盖100/100仅指该RSS分类榜，设备口径未细分。
- 原始JSON → Java确定性解析与字段/日期校验 → MySQL快照/产品/证据 → API按日查询 → Web生成并冻结报告 → Markdown/HTML导出，已实际执行。
- 正式精选 **国内0、海外0**；生成 **中国iOS观察5条、美国iOS补充观察5条**。这些是待验证候选，不是纯IAA推荐。美国iOS不代表用户要求的Google Play海外榜覆盖。
- AI未调用，报告由Java确定性模板生成。字段来自RSS，无需模型推断。AI供应商配置存在不等于模型调用、完整Schema校验和报告文案链路已通过验证。

## 采集与证据

|地区|快照|榜单ID|条数|来源更新时间|原始SHA-256|
|---|---|---|---|---|---|
'''
for s in sources:
    text+=f"|{s['country'].upper()}|{s['result']['snapshotId']}|{s['result']['chartId']}|100/100|{s['sourceUpdated']}|`{s['rawSha256']}`|\n"
text+='''
原始材料位于 [CN JSON](validation/apple-games-cn.json)、[US JSON](validation/apple-games-us.json)，HTTP状态、获取时间、复用返回记录见 [采集结果](validation/collection-results.json)。后端也按内容哈希留存材料，证据绑定具体快照与原始哈希；新修订不会复用旧日期/旧位置证据。记录为 submitted-material，脚本从固定Apple URL取得材料；手工提交接口本身不提供来源密码学认证。

## 本轮游戏观察

规则是：在各自地区最新有效的游戏榜内，按原始名次顺序，对来源介绍中明确包含消除/益智/解谜/拼图/收纳/合成/方块或 puzzle/match/sort/merge 的条目做可解释初筛，再按产品去重、每区最多5条。关键词可能误选大型或内购游戏；这不构成玩法确认或IAA判定。每条都需要继续人工核验。

|平台地区|原榜名次|游戏|介绍关键词|快照 / 证据|
|---|---:|---|---|---|
'''+ '\n'.join(rows)+'\n\n'+ '\n'.join(cards)
text+='''
## 实际验证

|检查|材料/环境|结果|
|---|---|---|
|24表/236字段注释|真实MySQL information_schema|0缺失；结构前后无差异|
|两个游戏榜采集|真实Apple响应+真实MySQL|各100条；快照6011/6012|
|相同材料重复提交|真实材料|相同快照ID，reused=true|
|100请求只收到40条|真实样本裁剪、MySQL回滚事务|PARTIAL，40条明细；未持久污染真实数据|
|坏JSON/空材料/重复ID/国家日期错配|契约测试+MySQL计数|拒绝；不生成成功零条快照|
|新材料修订、旧报告冻结|真实MySQL回滚事务|新快照ID；旧报告条目不变|
|报告结束边界与缺失日|真实MySQL|截止日不含；不借用窗口外数据；空日期榜单为空|
|D-7缺失|真实MySQL|delta7d=null|
|DEMO/无IAA证据隔离|真实报告|正式0，观察10，全部真实游戏来源|
|逐条复核报告|独立Python校验原始JSON|10条名称/名次/证据位置/哈希一致|
|后端测试|RUN_LIVE_MYSQL_TESTS=true; mvn -f backend/pom.xml test -q|16项通过，含3项真实MySQL事务测试|
|前端构建|npm run build --prefix frontend|通过；有大包体积警告|
|浏览器生成报告→榜单→返回报告|Chrome/Playwright，真实API；1440×1000、390×844|生成成功，榜单100行，0 JS/控制台错误；小屏表格横向滚动|

浏览器无Browser插件，使用已安装Chrome与Playwright；修复了本地127.0.0.1 CORS拒绝和MySQL rank保留字导致的500。截图：[报告桌面](validation/report-desktop.png)、[榜单桌面](validation/rankings-desktop.png)、[移动报告](validation/report-mobile.png)。

## 仍未验证或未完成

- 仅1个真实业务日，报告期其余6天缺失，不能证明持续表现、上涨、下载量、广告收益或商业成功。
- 微信、抖音、Google Play美英德法仍无已接入来源；未采购任何数据。Apple公开访问可用不等于商业再分发授权已核实。
- 所有观察版本缺少“纯广告、无内购”的正面证据，保持UNKNOWN。来源介绍出现购买等线索时也必须另做版本证据/冲突评估，不凭免费标签下结论。
- 实际调度、来源失败运行日志/告警、认证权限、规则配置试算与自动市场门槛计算、版本冲突策略、AI端到端调用仍未完成生产验收。本次不是P2～P4全量上线验收。
- 早期报告1～4为本次调试历史，含App总榜或DEMO混入问题，不作为最终验收结果；保留历史不覆盖。使用本文件指定的最终报告及JSON。

## 复现

启动 `mvn -f backend/pom.xml spring-boot:run`（不加dev）；`npm run dev --prefix frontend -- --host 127.0.0.1`。
执行 `powershell -File scripts/validate-free-source.ps1` 获取新材料并调用Java入库。采集按来源时间确定业务日，不能用当前榜单伪造历史日期。
查看 http://127.0.0.1:5173/#/reports?date=2026-10-27 ，或 GET /api/v1/reports/报告ID。
验证原始材料和导出报告：`python scripts/export-validation-report.py`。MySQL回滚测试：设置 RUN_LIVE_MYSQL_TESTS=true 后运行 Maven test；不会删除真实材料或历史快照，但可能消耗自增序号。
'''
(root/'REAL-VALIDATION-REPORT-2026-10-27.md').write_text(text,encoding='utf-8')
# Standalone compact reading view; all user/source text is escaped.
parts=['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>免费游戏来源验证报告</title><style>body{font-family:system-ui,"Microsoft YaHei";background:#f4f7fb;color:#21334d;max-width:1000px;margin:36px auto;padding:0 20px;line-height:1.8}article{background:white;border:1px solid #dce5ef;padding:24px;margin:18px 0}a{color:#1767d2}small{color:#62738a}h1{font-size:28px}h2{font-size:20px}code{overflow-wrap:anywhere}</style>']
parts += [f'<h1>真实游戏数据验证 · 2026-10-27</h1><p>报告 {report["id"]} / 修订 {report["revision"]} · CN/US iOS 各100条真实榜单</p><article><b>正式精选 国内0 · 海外0；待验证观察10条</b><p>仅首日数据，无版本级纯IAA证据。美国iOS为补充验证，不代表Google Play。AI未调用。以下理由为来源介绍关键词初筛，包含需要排除的内购/大型游戏可能性，不构成制作推荐。</p><p>MySQL：24张业务表、236字段中文注释已核验。详见同目录Markdown报告和validation内原始JSON。</p></article>']
for item in report['items']:
    f=json.loads(item['frozen_facts']);r=json.loads(item['evidence_refs'])[0]
    parts.append(f'<article><h2>{html.escape(f["region"]+" · "+f["productName"])} <small>#{f["rank"]}</small></h2><p>{html.escape(item["content"])}</p><p>发行方介绍摘录：{html.escape(f["summary"][:350])}</p><a href="{html.escape(f["storeUrl"],quote=True)}">商店详情</a> · <a href="{html.escape(f["sourceUrl"],quote=True)}">原始榜单</a><p><small>快照 {r["snapshot_id"]} · 证据 {r["evidence_id"]} · {html.escape(r["evidence_locator"])}<br>SHA-256 <code>{r["raw_sha256"]}</code></small></p></article>')
parts.append('</html>')
(root/'REAL-VALIDATION-REPORT-2026-10-27.html').write_text(''.join(parts),encoding='utf-8')
print(f'Exported report {report["id"]}; verified {len(checks)} frozen evidence references.')
