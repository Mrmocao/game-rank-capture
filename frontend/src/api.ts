export type Ranking = { versionId: string; platform: string; region: string; versionName: string; chartId: string|null; chartName: string|null; kind: string|null; rank: number|null; delta7d: number|null; snapshotId: string|null; dataState: string; completeness: string|null; monetization: string }
export type Product = { id: string; name: string; poolState: string; firstObservedDate: string; stateAsOf: string; thumbnailUrl?: string|null; rankings: Ranking[]; selectionSummary: { total: number; inProgress: number; completed: number } }
const json = async (url: string, options?: RequestInit) => { const r = await fetch(url, options); const body = await r.json().catch(()=>null); if(r.status===401&&!url.endsWith('/access'))window.dispatchEvent(new Event('workspace-login-required')); if (!r.ok) throw new Error(body?.error?.message || `请求失败 ${r.status}，请检查工作区登录和服务连接`); if(!body)throw new Error('服务返回了无法解析的响应'); return body.data }
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
export const getLatestReport = () => json('/api/v1/reports/latest')
export const getReport = (id:string) => json(`/api/v1/reports/${encodeURIComponent(id)}`)
export const getReportHistory = (page=1) => json(`/api/v1/reports?page=${page}&pageSize=20`)
export const getReportSkill = () => json('/api/v1/reports/skill')
export const getInsightCollection = (date:string) => json(`/api/v1/admin/insight-collection?date=${date}`)
export const runInsightCollection = () => json('/api/v1/admin/insight-collection/run',{method:'POST'})
export const getCollectionSources = (params:Record<string,string|undefined>={}) => {const q=new URLSearchParams();Object.entries(params).forEach(([k,v])=>v&&q.set(k,v));return json(`/api/v1/collection/sources?${q}`)}
export const getCollectionSource = (id:string) => json(`/api/v1/collection/sources/${encodeURIComponent(id)}`)
export const getCollectionRuns = (params:Record<string,string|undefined>={}) => {const q=new URLSearchParams();Object.entries(params).forEach(([k,v])=>v&&q.set(k,v));return json(`/api/v1/collection/runs?${q}`)}
export const getPortalFields = () => json('/api/v1/collection/portal-fields')
export const getCoverage = (date:string) => json(`/api/v1/meta/coverage?date=${encodeURIComponent(date)}`)
export const generateReport = (periodStart:string,periodEnd:string) => json(`/api/v1/admin/reports/generate?periodStart=${periodStart}&periodEnd=${periodEnd}`, {method:'POST',headers:{'Idempotency-Key':requestId()}})
export const getCurrentRule = () => json('/api/v1/admin/rules/current')
export const publishRule = (rulesJson:string) => json('/api/v1/admin/rules/publish',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({rulesJson})})

export const getStats = (date:string) => json(`/api/v1/products/stats?date=${encodeURIComponent(date)}`)
export const getPublicRankings = (platform:string,date:string) => json(`/api/v1/public-rankings?platform=${platform}&date=${encodeURIComponent(date)}`)
export const getProfileProducts = (params:Record<string,string|number|undefined>) => {const q=new URLSearchParams();Object.entries(params).forEach(([k,v])=>v!==undefined&&v!==''&&q.set(k,String(v)));return json(`/api/v1/profile/products?${q}`)}
export const getGameProfile = (product:string,listing:string,date:string,version?:string,market?:string) => {const q=new URLSearchParams({asOf:date,timezone:'Asia/Shanghai'});if(listing)q.set('listingId',listing);if(version)q.set('versionId',version);if(market)q.set('market',market);return json(`/api/v1/products/${product}/profile?${q}`)}
export const updateSelection = (id:string,payload:unknown) => json(`/api/v1/selections/${id}`,{method:'PATCH',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)})
