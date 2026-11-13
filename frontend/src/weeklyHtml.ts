// Export only saved workflow snapshots: viewing/downloading never regenerates a report.
type Fields = Record<string, any>
const labels: Record<string, string> = {
  goal: '推荐目标', quota: '席位分配', evidence: '证据要求', crossPlatform: '跨平台判断', gates: '入选门槛', output: '输出约定',
  WECHAT: '微信', DOUYIN: '抖音', IOS: 'iOS', GOOGLE_PLAY: 'Google Play',
  CN: '中国', US: '美国', GB: '英国', UK: '英国', DE: '德国', FR: '法国',
  OBSERVED: '当日已观察', MISSING_ON_DATE: '截止日缺失', READY: '待研发确认', ACKNOWLEDGED: '研发已接收', PUBLISHED: '已生成', UNKNOWN: '待核',
}
const value = (v: unknown): string => v == null || v === '' ? '未记录' : typeof v === 'object' ? JSON.stringify(v) : String(v)
const escape = (v: unknown): string => value(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!))
const translated = (v: unknown) => escape(labels[String(v)] || v)
const list = (v: unknown): unknown[] => Array.isArray(v) ? v : v == null || v === '' ? [] : [v]
const points = (v: unknown, empty = '暂无记录') => list(v).length ? `<ul>${list(v).map(x => `<li>${escape(x)}</li>`).join('')}</ul>` : `<p class="muted">${escape(empty)}</p>`
const mechanism = (v: Fields = {}) => `<div class="mechanism">${Object.entries(v).map(([k, x]) => `<div><h3>${translated(k)}</h3><p>${escape(x)}</p></div>`).join('') || '<p class="muted">未保存机制摘要</p>'}</div>`
const section = (id: string, n: string, title: string, content: string) => `<section id="${id}"><div class="section-title"><span>${n}</span><h2>${title}</h2></div>${content}</section>`
const metric = (label: string, v: unknown) => `<div class="metric"><strong>${escape(v)}</strong><span>${label}</span></div>`

const css = `
:root{color-scheme:light;--ink:#172e49;--muted:#62748a;--blue:#155bc2;--line:#dce5ef}
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#f2f5f9;color:var(--ink);font:15px/1.75 "Microsoft YaHei","PingFang SC",system-ui,sans-serif;overflow-wrap:anywhere}
.page{max-width:1080px;margin:32px auto;background:#fff;border:1px solid var(--line)}header{padding:40px 48px 32px;border-top:6px solid var(--blue)}.brand{font-size:12px;letter-spacing:2px;color:var(--blue);font-weight:700}.eyebrow{font-size:13px;color:var(--muted);margin-top:30px}h1{font-size:36px;line-height:1.3;margin:8px 0 12px;letter-spacing:1px}.intro{color:var(--muted);max-width:760px;margin:0}nav{display:flex;flex-wrap:wrap;gap:12px 28px;padding:14px 48px;border-block:1px solid var(--line);background:#f8faff}a{color:var(--blue);text-decoration:none}a:hover{text-decoration:underline}a:focus-visible{outline:2px solid var(--blue);outline-offset:4px}
main{padding:0 48px 36px}section{padding-top:32px;scroll-margin-top:16px}.section-title{display:flex;align-items:center;gap:12px;margin-bottom:18px}.section-title>span{font-size:13px;color:var(--blue);font-weight:700;border-bottom:2px solid var(--blue)}h2{font-size:21px;margin:0}h3{font-size:16px;margin:0 0 8px}p{margin:8px 0}.muted{color:var(--muted)}.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:12px}.metric{border-left:3px solid #b6d0f5;background:#f4f8fe;padding:16px 18px}.metric strong{display:block;font-size:28px;line-height:1.3;color:var(--blue)}.metric span{font-size:13px;color:var(--muted)}
.game{border:1px solid var(--line);padding:24px;margin:16px 0}.game-head{display:flex;gap:16px;align-items:flex-start}.number{color:var(--blue);font-size:28px;line-height:1.2;font-weight:700}.game h3{font-size:21px;line-height:1.4;margin:0 0 6px}.tags{display:flex;gap:6px;flex-wrap:wrap;font-size:12px}.tag{padding:2px 9px;background:#edf3fc;color:#28588b}.tag.warn{background:#fff4de;color:#82591d}.game-body{margin-top:20px;display:grid;grid-template-columns:1.4fr 1fr;gap:24px}.game-body h4{font-size:13px;margin:0;color:var(--muted)}.reason{white-space:pre-wrap}.gaps{background:#fffbf1;padding:14px 18px}.gaps ul{margin:8px 0}.mechanism{display:grid;grid-template-columns:1fr 1fr;column-gap:28px}.mechanism>div{padding:18px 0;border-top:1px solid var(--line)}.mechanism p{margin:0;white-space:pre-wrap}ul,ol{padding-left:24px;margin:10px 0}li{padding-left:4px;margin:8px 0;white-space:pre-wrap}.notice{border-left:3px solid #c5933f;padding:14px 20px;background:#fffbf1}.meta{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));margin:0;gap:0 28px}.meta>div{border-bottom:1px solid var(--line);padding:12px 0}dt{font-size:12px;color:var(--muted)}dd{margin:2px 0 0;white-space:pre-wrap}footer{padding:20px 48px;background:#f8faff;color:var(--muted);border-top:1px solid var(--line);font-size:12px}.priority{display:flex;gap:14px;border-top:1px solid var(--line);padding:16px 0}.priority b{color:var(--blue);flex:0 0 32px}.priority p{margin:0;white-space:pre-wrap}.checklist li::marker{color:var(--blue);font-weight:700}
@media(max-width:640px){.page{margin:0;border:0}header{padding:28px 22px}h1{font-size:28px}.eyebrow{margin-top:22px}nav{padding:12px 22px;gap:8px 18px}main{padding:0 22px 28px}.game{padding:18px}.game-body,.mechanism,.meta{grid-template-columns:1fr}.game h3{font-size:18px}footer{padding:20px 22px}.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media print{@page{size:A4;margin:16mm}body{background:white;font-size:11pt}.page{margin:0;max-width:none;border:0}header{padding:16px 0 24px}h1{font-size:28pt}nav{display:none}main{padding:0}footer{padding:16px 0;background:white}.game,.metric,.mechanism>div,.priority,.meta>div{break-inside:avoid}section{padding-top:24px}h2,h3,h4,.section-title{break-after:avoid}.metric,.gaps,.notice{-webkit-print-color-adjust:exact;print-color-adjust:exact}}
`

function documentHtml(title: string, subtitle: string, snapshot: Fields, meta: Fields, nav: string, body: string): string {
  const metadata = { '所属周（周一）': snapshot.weekStart, '报告编号': snapshot.reportId, '报告修订号（生成时）': snapshot.revision, '工作流版本': snapshot.workflowVersion, ...meta, '导出时间（北京时间）': new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) }
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><title>${escape(title)} · ${escape(snapshot.weekStart)}</title><style>${css}</style></head><body><div class="page"><header><div class="brand">锦鲤云 / 游戏市场情报</div><div class="eyebrow">WEEKLY INSIGHT · ${escape(snapshot.weekStart)} 所属周</div><h1>${escape(title)}</h1><p class="intro">${escape(subtitle)}</p></header><nav aria-label="报告目录">${nav}<a href="#archive">归档信息</a></nav><main>${body}${section('archive','05','归档与追溯',`<dl class="meta">${Object.entries(metadata).map(([k,v])=>`<div><dt>${escape(k)}</dt><dd>${escape(v)}</dd></div>`).join('')}</dl><p class="muted">本文件是已保存内容的离线副本，不会随系统更新。请以所属周、报告编号和修订号区分不同导出；未记录的字段保持缺失。</p>`)}</main><footer>锦鲤云 · 研究决策资料 ｜ 单文件离线阅读，无外部资源 ｜ 浏览器 Ctrl / ⌘ + P 可打印或保存为 PDF</footer></div></body></html>`
}

export function deliveryHtml(delivery: Fields): string {
  const p = delivery.package
  if (!p) throw new Error('请先生成研发交付包')
  const items: Fields[] = Array.isArray(p.items) ? p.items : []
  const cards = items.map((x, i) => `<article class="game"><div class="game-head"><span class="number">${String(i + 1).padStart(2, '0')}</span><div><h3>${escape(x.name)}</h3><div class="tags"><span class="tag">${translated(x.platform)} / ${translated(x.region)}</span><span class="tag warn">${escape(x.type)}</span><span class="tag">${escape(x.route)}</span></div></div></div><p class="muted">来源日期：${escape(x.sourceDate)} · 当前状态：${translated(x.currentState)}</p><div class="game-body"><div><h4>为什么现在关注</h4><p class="reason">${escape(x.whyNow)}</p></div><div class="gaps"><h4>核验缺口</h4>${points(x.gaps, '未记录缺口；不代表已完成核验')}</div></div></article>`).join('') || '<p class="notice">本交付包尚无推荐条目，不以示例游戏补齐。</p>'
  return documentHtml('每周精选 · 研发交付', '先读推荐清单，再核验机会依据与证据缺口。待核研究仅供验证，不等同于正式立项结论。', p,
    { '交付对象': p.recipientTeam, '交付状态（导出时）': labels[delivery.status] || delivery.status, '交付生成时间（原记录）': delivery.deliveredAt, '接收时间（原记录）': delivery.acknowledgedAt, '接收人': delivery.acknowledgedBy, '接收备注': delivery.note },
    '<a href="#picks">推荐清单</a><a href="#mechanism">推荐机制</a><a href="#actions">研发动作</a><a href="#boundary">证据边界</a>',
    section('picks','01','本周研究清单', `<div class="metrics">${metric('交付条目', items.length)}${metric('正式候选', items.filter(x=>x.type==='正式候选').length)}${metric('待核研究', items.filter(x=>x.type==='待核研究').length)}</div>${cards}`)
    + section('mechanism','02','交付包保存的推荐机制', mechanism(p.mechanismSummary))
    + section('actions','03','研发下一步', `<div class="checklist">${points(p.rAndDAction)}</div>`)
    + section('boundary','04','证据与判断边界', `<p class="notice">${escape(p.sourceBoundary)}</p>`))
}

export function reviewHtml(review: Fields): string {
  const p = review.review
  if (!p) throw new Error('请先生成规则复盘')
  const r = p.resultSummary || {}
  const suggestions = list(p.iterationSuggestions).map((x, i) => `<div class="priority"><b>${String(i+1).padStart(2,'0')}</b><p>${escape(x)}</p></div>`).join('') || '<p class="muted">暂无迭代建议</p>'
  return documentHtml('每周精选 · 规则复盘', '回顾本期推荐结果与证据质量，明确下一轮的数据补齐和规则优化动作。复盘所属周以归档信息为准。', p,
    { '复盘状态': labels[review.status] || review.status, '复盘生成时间（原记录）': review.generatedAt },
    '<a href="#results">结果与发现</a><a href="#mechanism">推荐机制</a><a href="#iterations">迭代建议</a><a href="#next">下周检查</a>',
    section('results','01','结果与证据质量', `<div class="metrics">${metric('正式候选',r.formalCount)}${metric('待核研究',r.researchCount)}${metric('玩法未知',r.unknownGameplay)}${metric('截止日缺失',r.missingCurrent)}${metric('观察不足',r.insufficientObservation)}</div><p class="muted">以上按复盘保存的统计展示，缺口可能重叠，不应相加作为推荐总数。</p>${points(p.findings)}`)
    + section('mechanism','02','复盘保存的推荐机制', mechanism(p.currentMechanism))
    + section('iterations','03','下一轮迭代建议', suggestions)
    + section('next','04','下周一检查清单', `<div class="checklist">${points(p.nextMondayChecklist)}</div><p class="notice">${escape(p.limitations)}</p>`))
}

export function downloadWeeklyHtml(kind: 'delivery' | 'review', workflow: Fields): void {
  const snapshot = kind === 'delivery' ? workflow.package : workflow.review
  const html = kind === 'delivery' ? deliveryHtml(workflow) : reviewHtml(workflow)
  const name = `${kind === 'delivery' ? '研发交付' : '规则复盘'}-${value(snapshot.weekStart)}-报告${value(snapshot.reportId)}-修订${value(snapshot.revision)}.html`.replace(/[<>:"/\\|?*\x00-\x1f]/g, '_')
  const url = URL.createObjectURL(new Blob([html], { type: 'text/html;charset=utf-8' }))
  const a = document.createElement('a')
  a.href = url; a.download = name; document.body.append(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
