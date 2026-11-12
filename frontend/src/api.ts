export type Ranking = { versionId: string; platform: string; region: string; versionName: string; chartId: string|null; chartName: string|null; kind: string|null; rank: number|null; delta7d: number|null; snapshotId: string|null; dataState: string; completeness: string|null; monetization: string }
export type Product = { id: string; name: string; poolState: string; firstObservedDate: string; stateAsOf: string; thumbnailUrl?: string|null; rankings: Ranking[]; selectionSummary: { total: number; inProgress: number; completed: number } }
const json = async (url: string, options?: RequestInit) => { const r = await fetch(url, options); const body = await r.json().catch(()=>null); if(r.status===401&&!url.endsWith('/access'))window.dispatchEvent(new Event('workspace-login-required')); if (!r.ok) throw new Error(body?.error?.message || `请求失败 ${r.status}，请检查工作区登录和服务连接`); if(!body)throw new Error('服务返回了无法解析的响应'); return body.data }
const reportJson = async(url:string, options?:RequestInit, timeout=45000) => {
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout)
 try{return await json(url,{...options,signal:controller.signal})}
 catch(error:any){if(controller.signal.aborted)throw new Error('请求等待超时，服务端可能仍在处理');throw error}
 finally{clearTimeout(timer)}
}
export const workspaceAccess = () => json('/api/v1/access')
export const loginWorkspace = (token:string) => json('/api/v1/access',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({token})})
export const profileRequest = json
export const newRequestKey = () => requestId()
// getRandomValues also works on the current HTTP deployment; randomUUID requires HTTPS.
const requestId = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6]! & 0x0f) | 0x40; bytes[8] = (bytes[8]! & 0x3f) | 0x80
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
}
export const getProducts = (params: Record<string,string|number|undefined>) => { const q = new URLSearchParams(); Object.entries(params).forEach(([k,v]) => v !== undefined && v !== '' && q.set(k,String(v))); return json(`/api/v1/products?${q}`) as Promise<{date:string;coverageState:string;page:number;pageSize:number;total:number;items:Product[]}> }
export const getProduct = (id:string,date:string) => json(`/api/v1/products/${id}?date=${encodeURIComponent(date)}`)
export const createSelection = (id:string,payload:unknown) => json(`/api/v1/products/${id}/selections`, { method:'POST', headers:{'Content-Type':'application/json','Idempotency-Key':requestId()}, body:JSON.stringify(payload) })
export const getRankings = (chartId:string,date:string) => json(`/api/v1/rankings?chartId=${encodeURIComponent(chartId)}&date=${encodeURIComponent(date)}`)
export const getSelections = (params:Record<string,string|number|undefined>) => { const q=new URLSearchParams(); Object.entries(params).forEach(([k,v])=>v!==undefined&&v!==''&&q.set(k,String(v))); return json(`/api/v1/selections?${q}`) }
export const getAiConfig = () => json('/api/v1/admin/ai-config')
export const saveAiConfig = (payload:unknown) => json('/api/v1/admin/ai-config', {method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
export const getLatestReport = () => reportJson('/api/v1/reports/latest?pageView=true')
export const getReport = (id:string, full=false) => reportJson(`/api/v1/reports/${encodeURIComponent(id)}${full?'':'?pageView=true'}`,undefined,full?240000:45000)
export const getReportHistory = (page=1) => reportJson(`/api/v1/reports?page=${page}&pageSize=20`)
export const getReportSkill = () => json('/api/v1/reports/skill')
export const getWeeklyDelivery = (id:string) => reportJson(`/api/v1/reports/${encodeURIComponent(id)}/delivery`)
export const createWeeklyDelivery = (id:string) => reportJson(`/api/v1/admin/reports/${encodeURIComponent(id)}/delivery`,{method:'POST'})
export const acknowledgeWeeklyDelivery = (id:string,actor='研发成员',note='') => reportJson(`/api/v1/admin/reports/${encodeURIComponent(id)}/delivery/ack`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({actor,note})})
export const getWeeklyReview = (id:string) => reportJson(`/api/v1/reports/${encodeURIComponent(id)}/review`)
export const createWeeklyReview = (id:string) => reportJson(`/api/v1/admin/reports/${encodeURIComponent(id)}/review`,{method:'POST'})
export const getInsightCollection = (date:string) => json(`/api/v1/admin/insight-collection?date=${date}`)
export const runInsightCollection = () => json('/api/v1/admin/insight-collection/run',{method:'POST'})
export const getCollectionSources = (params:Record<string,string|undefined>={}) => {const q=new URLSearchParams();Object.entries(params).forEach(([k,v])=>v&&q.set(k,v));return json(`/api/v1/collection/sources?${q}`)}
export const getCollectionSource = (id:string) => json(`/api/v1/collection/sources/${encodeURIComponent(id)}`)
export const getCollectionRuns = (params:Record<string,string|undefined>={}) => {const q=new URLSearchParams();Object.entries(params).forEach(([k,v])=>v&&q.set(k,v));return json(`/api/v1/collection/runs?${q}`)}
export const getPortalFields = () => json('/api/v1/collection/portal-fields')
export const getMarketCoverage = (date:string) => json(`/api/v1/market/coverage?date=${encodeURIComponent(date)}`)
export const getMarketRankings = (chartId:string,date:string) => json(`/api/v1/market/rankings?chartId=${encodeURIComponent(chartId)}&date=${encodeURIComponent(date)}`)
export const getCoverage = async(date:string) => {
 const market=await getMarketCoverage(date)
 const legacy=await json(`/api/v1/meta/coverage?date=${encodeURIComponent(date||market.date)}`)
 const external=market.charts||[]
 const old=legacy.charts.filter((l:any)=>l.actual_count>0&&!external.some((c:any)=>c.platform===l.platform&&c.region===l.region&&c.chartKey==='top-free'&&c.actual_count>0))
 const charts=[...external,...old].sort((a:any,b:any)=>['WECHAT','DOUYIN','IOS','GOOGLE_PLAY'].indexOf(a.platform)-['WECHAT','DOUYIN','IOS','GOOGLE_PLAY'].indexOf(b.platform))
 return {...legacy,charts,date:date||market.date,latestAvailableDate:market.latestAvailableDate||market.date,market,coverageState:charts.every((c:any)=>c.data_state==='COMPLETE')?'COMPLETE':charts.some((c:any)=>c.actual_count>0)?'PARTIAL':'NONE'}
}
export const generateReport = (periodStart:string,periodEnd:string) => reportJson(`/api/v1/admin/reports/generate?periodStart=${periodStart}&periodEnd=${periodEnd}&pageView=true`, {method:'POST',headers:{'Idempotency-Key':requestId()}}, 240000)
export const getCurrentRule = () => json('/api/v1/admin/rules/current')
export const publishRule = (rulesJson:string) => json('/api/v1/admin/rules/publish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({rulesJson})})

export const getStats = (date:string) => json(`/api/v1/products/stats?date=${encodeURIComponent(date)}`)
export const getPublicRankings = (platform:string,date:string) => json(`/api/v1/public-rankings?platform=${platform}&date=${encodeURIComponent(date)}`)
export const getProfileProducts = (params:Record<string,string|number|undefined>) => {const q=new URLSearchParams();Object.entries(params).forEach(([k,v])=>v!==undefined&&v!==''&&q.set(k,String(v)));return json(`/api/v1/profile/products?${q}`)}
export const getGameProfile = (product:string,listing:string,date:string,version?:string,market?:string) => {const q=new URLSearchParams({asOf:date,timezone:'Asia/Shanghai'});if(listing)q.set('listingId',listing);if(version)q.set('versionId',version);if(market)q.set('market',market);return json(`/api/v1/products/${product}/profile?${q}`)}
export const updateSelection = (id:string,payload:unknown) => json(`/api/v1/selections/${id}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
