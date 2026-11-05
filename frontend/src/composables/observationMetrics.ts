import { computed, onUnmounted, ref, watch } from 'vue'
import { profileRequest } from '../api'
export function useObservationMetrics(id:()=>number|undefined) {
 const data=ref<any>(null),loading=ref(false),error=ref('');let version=0
 async function refresh(){const token=++version,observation=id();data.value=null;error.value='';loading.value=false;if(!observation)return;loading.value=true
  try{const [metricsResult,profileResult]=await Promise.allSettled([profileRequest('/api/v1/admin/capture-workspace/metrics/observations/'+observation),profileRequest('/api/v1/admin/capture-workspace/supplements/observations/'+observation)]);if(token===version){const result=metricsResult.status==='fulfilled'?metricsResult.value:{rows:(profileResult.status==='fulfilled'?profileResult.value.rows:[])};const supplement=profileResult.status==='fulfilled'?profileResult.value:null;for(const row of result.rows||[])row.profiles=supplement?.rows?.find((s:any)=>s.rank_position===row.rank_position)?.profiles||[];data.value=result;error.value=[metricsResult.status==='rejected'?'商店指标加载失败':'',profileResult.status==='rejected'?'点点扩展资料加载失败，请重试':''].filter(Boolean).join('；')}}
  catch(e:any){if(token===version)error.value=e.message||'补充指标加载失败'}finally{if(token===version)loading.value=false}}
 watch(id,refresh,{immediate:true});onUnmounted(()=>version++)
 const rows=computed(()=>Object.fromEntries((data.value?.rows||[]).map((r:any)=>[r.rank_position,r])))
 return {data,rows,loading,error,refresh}
}
export const metricTime=(value:any)=>{if(!value)return '未提供';const s=String(value).replace(' ','T');return new Date(/(?:Z|[+-]\d\d:\d\d)$/.test(s)?s:s+'Z').toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false})}
export const metricSource=(key:string)=>({'apple-lookup':'Apple 官方接口','apple-detail':'App Store 详情','google-detail':'Google Play 详情'}[key]||key)
export function metric(row:any,key:string){return [...(row?.jobs||[])].filter(j=>j.snapshot?.metrics?.some((m:any)=>m.key===key)).sort((a,b)=>String(b.captured_at).localeCompare(String(a.captured_at))).map(job=>({job,value:job.snapshot.metrics.find((m:any)=>m.key===key)}))[0]}
export function metricPrice(row:any){const m=metric(row,'price');return m?`${m.value.currency} ${m.value.rawDisplay}`:row?.existing?.priceRaw?.label||'未知'}
export const metricInstalls=(row:any)=>metric(row,'installs')?.value.rawDisplay||row?.existing?.installsDisplay||'未知'
export const metricFlag=(row:any,key:string)=>metric(row,key)?'已标注':'未知'
export const metricStatus=(status:string)=>({PENDING:'等待补全',RUNNING:'补全中',SUCCEEDED:'已补全',FAILED:'补全失败',CANCELLED:'来源停用'}[status]||status)
