<script setup lang="ts">
import { computed } from 'vue'
import GameIcon from './GameIcon.vue'
const props=defineProps<{ report:any; group:string; reportId:string }>()
const labels:Record<string,string>={WECHAT:'微信',DOUYIN:'抖音',IOS:'iOS',GOOGLE_PLAY:'Google Play'}
const items=computed(()=>(props.report.items||[]).filter((r:any)=>props.group==='ALL'||(props.group==='DOMESTIC'?r.region==='CN':r.region!=='CN')))
const safeUrl=(v:unknown)=>typeof v==='string'&&/^https?:\/\//.test(v)?v:undefined
function exportList(){
 const text=`# 本周游戏研究线索标准列表\n\n报告 ${props.reportId}；观察窗 ${props.report.windowStart} — ${props.report.windowEnd}\n\n以下为待核研究线索，不是已通过纯IAA资格的正式推荐。来源名次变化不等于用户量增长。\n\n`+items.value.map((r:any)=>`## ${r.displayOrder}. ${r.name}\n\n- 平台 / 地区：${labels[r.platform]||r.platform} / ${r.region}\n- 来源：${r.sourceKey} / ${r.boardName} / ${r.authority}\n- 发行方：${r.developer||'来源未提供'}\n- 来源玩法标签：${r.family}（待实机核验）\n- 研究类型：${r.signalLabel||'待核研究'}\n- 入选研究理由：${r.whyNow}\n- 资格：纯IAA未知；${r.identityStatus}\n- 缺口：${r.gaps.join('；')}\n- 下一步：${r.nextAction}\n- 原始来源：${r.sourceUrl}\n\n| 日期 | 名次 | 观察ID | 材料SHA256 | 定位 |\n| --- | --- | --- | --- | --- |\n`+r.history.map((p:any)=>`| ${p.date} | ${p.rank==null?'缺失':p.rank} | ${p.evidence?.observation_id||'—'} | ${p.evidence?.raw_sha256||'—'} | ${(p.evidence?.evidence_locator||'—').replace(/\|/g,'\\|')} |`).join('\n')).join('\n\n')+`\n\n规则：${props.report.policy}\n\n规则版本：${props.report.algorithmVersion}\n`;
 const u=URL.createObjectURL(new Blob([text],{type:'text/markdown;charset=utf-8'}));const a=document.createElement('a');a.href=u;a.download=`本周研究线索-${props.reportId}.md`;a.click();URL.revokeObjectURL(u)
}
</script>
<template>
 <section class="research-leads insight-panel" aria-label="本周研究线索标准列表">
  <div class="section-heading"><div><h2>本周研究线索 · 待核验</h2><p>{{report.windowStart}} — {{report.windowEnd}} · {{report.items?.length||0}} 项；与正式精选合计最多 5 项</p></div><el-button :disabled="!items.length" @click="exportList">导出标准列表</el-button></div>
  <el-alert title="以下是有榜单依据的研究顺序，不是纯IAA正式推荐。身份、实机和变现证据核验通过后，再决定是否选用。" type="warning" :closable="false"/>
  <p>{{report.diagnostic}}</p>
  <div v-if="!items.length" class="empty">当前范围暂无符合条件的研究线索。</div>
  <article v-for="row in items" :key="row.leadKey" class="lead-item">
   <div class="lead-head"><GameIcon :url="row.thumbnailUrl" :name="row.name" :size="48"/><div><h3>{{row.displayOrder}}. {{row.name}}</h3><p>{{labels[row.platform]||row.platform}} · {{row.region}} · {{row.family}}（来源标签） · {{row.developer||'发行方未提供'}}</p></div><el-tag type="warning">待核研究线索</el-tag></div>
   <div class="lead-facts"><span>{{row.signalLabel||'待核研究'}}</span><span>{{row.boardName}} · 当前 #{{row.latestRank}}</span><span>观察 {{row.observationDays}} / 7 日</span><span>{{row.rankChange==null?'单日记录，增长未知':`首末名次 ${row.firstRank} → ${row.latestRank}`}}</span><span>纯IAA：未知</span></div>
   <p><b>为什么现在研究：</b>{{row.whyNow}}</p><p><b>缺口与反对理由：</b>{{row.gaps.join('；')}}</p><p><b>下一步：</b>{{row.nextAction}}</p>
   <details><summary>7日记录与证据 · {{row.authority==='THIRD_PARTY'?'第三方来源':'官方来源'}}</summary><p>{{row.identityStatus==='SOURCE_LABEL_MATCH_UNVERIFIED'?'名称与发行方仅用于同来源内待核观察匹配；未建立平台身份、未跨平台合并。':'来源提供稳定ID；仍须核验当前版本与玩法。'}}</p><div class="table-wrap"><table><thead><tr><th>业务日</th><th>来源名次</th><th>观察 / 来源</th><th>材料定位</th></tr></thead><tbody><tr v-for="p in row.history" :key="p.date"><td>{{p.date}}</td><td>{{p.rank==null?'缺失 / 身份未确认':'#'+p.rank}}</td><td>{{p.evidence?`观察 ${p.evidence.observation_id}`:'无证据'}}<br/><a v-if="safeUrl(p.evidence?.source_url)" :href="safeUrl(p.evidence.source_url)" target="_blank" rel="noopener noreferrer">来源页面 ↗</a></td><td><template v-if="p.evidence"><code>{{p.evidence.evidence_locator}}</code><small>SHA256 {{p.evidence.raw_sha256}}</small><small>可用时间（UTC）{{p.evidence.available_at}} · {{p.evidence.completeness}}</small></template><span v-else>不以其他日期回填</span></td></tr></tbody></table></div><p v-if="row.contextualSignals.length">同来源其他榜单仅作背景（不重复计独立证据）：{{row.contextualSignals.map((s:any)=>`${s.boardName} #${s.rank}`).join('；')}}</p></details>
  </article>
  <details><summary>透明选取规则与未选原因</summary><p>{{report.policy}}</p><div class="table-wrap"><el-table :data="(report.candidateAudit||[]).filter((r:any)=>!r.selected)" max-height="360" size="small"><el-table-column prop="name" label="候选" min-width="180"/><el-table-column prop="boardName" label="来源榜单" width="140"/><el-table-column prop="exclusionReason" label="本期未选原因" min-width="320"/></el-table></div><small>规则版本 {{report.algorithmVersion}}；有效来源快照 {{report.snapshotCount}} 份。记录随本期报告冻结。</small></details>
 </section>
</template>
<style scoped>
.research-leads{margin-top:20px}.research-leads h2{font-size:18px;margin:0}.research-leads p{line-height:1.8}.lead-item{border-top:1px solid #e3eaf4;padding:22px 0}.lead-head{display:flex;align-items:center;gap:12px}.lead-head h3{margin:0;font-size:18px}.lead-head p{margin:5px 0;color:#63748a;font-size:12px}.lead-head .el-tag{margin-left:auto}.lead-facts{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0}.lead-facts span{background:#f0f5fc;padding:6px 10px;font-size:12px;color:#375c8a}.research-leads summary{cursor:pointer;color:#296ac0;font-size:13px;padding:8px 0}.research-leads table{width:100%;border-collapse:collapse;font-size:12px;min-width:700px}.research-leads th,.research-leads td{text-align:left;padding:10px;border:1px solid #e1e8f2;vertical-align:top}.research-leads th{background:#f4f7fb;white-space:nowrap}.research-leads td code{overflow-wrap:anywhere}.research-leads small{display:block;color:#7d8ba0;margin-top:6px;overflow-wrap:anywhere}.research-leads .section-heading{gap:12px;flex-wrap:wrap}@media(max-width:650px){.lead-head{flex-wrap:wrap}.lead-head .el-tag{margin-left:0}.lead-head h3{font-size:16px}}
</style>
