<script setup lang="ts">
defineProps<{value:any}>()
const names:Record<string,string>={WECHAT:'微信',DOUYIN:'抖音',IOS:'iOS',GOOGLE_PLAY:'Google Play'}
const types:Record<string,string>={MULTI_PLATFORM_ESTABLISHED:'多平台成熟头部',DIVERGENT:'跨平台走势分歧',CROSS_PLATFORM_EXPANSION:'跨平台扩散线索',MULTI_PLATFORM_SIGNAL:'多平台共同观察',SINGLE_PLATFORM:'单平台证据',IDENTITY_UNCONFIRMED:'产品身份待核'}
const url=(v:unknown)=>typeof v==='string'&&/^https?:\/\//.test(v)?v:undefined
</script>
<template>
 <section v-if="value" class="cross-evidence" aria-label="跨平台证据卡">
  <header><b>{{types[value.type]||value.type}}</b><span>{{value.confirmedIdentity?`${value.productName} · 统一产品 ${value.productId}`:'未确认跨平台关联'}} · 截止 {{value.asOfDate}}</span></header>
  <p>{{value.reason}}</p><p v-if="value.hasDivergence"><b>反向证据：</b>不同平台最新日涨跌方向相反，不能描述为全面增长。</p>
  <div class="table-wrap"><table><thead><tr><th>平台 / 市场</th><th>独立榜单</th><th>当日名次</th><th>最新日变化</th><th>观察与依据</th></tr></thead><tbody>
   <tr v-for="(c,i) in value.cards" :key="i"><td>{{names[c.platform]||c.platform}} / {{c.region}}</td><td>{{c.boardName}}<small>{{c.name}}</small></td><td>{{c.rank==null?'当日缺失':'#'+c.rank}}</td><td>{{c.momentum?.latestDailyGain==null?'未知':`${c.momentum.latestDailyGain>0?'+':''}${c.momentum.latestDailyGain} 名`}}</td><td>{{c.observationDays}} / 7 日 · <a v-if="url(c.sourceUrl)" :href="url(c.sourceUrl)" target="_blank" rel="noopener noreferrer">{{c.sourceKey}} ↗</a><small>{{c.associationBasis==='HUMAN_REVIEW'?'人工证据关联':c.associationBasis==='EXISTING_OFFICIAL_PLATFORM_ID'?'既有官方平台ID':'来源身份待核'}}</small></td></tr>
  </tbody></table></div>
  <p class="muted">当日 {{value.currentPlatformCount}} 个平台 · {{value.originFamilyCount}} 个来源家族。{{value.missingPlatforms?.length?`缺少当日已关联证据：${value.missingPlatforms.map((p:string)=>names[p]||p).join('、')}；状态未知。`:''}}</p>
  <details><summary>冻结的身份依据、逐日材料与判断边界</summary><p>{{value.boundary}}</p><div v-for="(c,i) in value.cards" :key="i"><b>{{names[c.platform]}} · {{c.boardName}}</b><p v-if="c.identityReview">人工记录 #{{c.identityReview.id}} / 修订 {{c.identityReview.revision}} · {{c.identityReview.effective_date}} 生效<br/>{{c.identityReview.evidence?.identityEvidence?.excerpt}}<br/>{{c.identityReview.evidence?.identityEvidence?.reason}}</p><p v-for="h in c.history" :key="h.date">{{h.date}} {{h.rank==null?'缺失':'#'+h.rank}}<small v-if="h.evidence">观察 {{h.evidence.observation_id}} · {{h.evidence.evidence_locator}} · SHA256 {{h.evidence.raw_sha256}}</small></p></div></details>
  <details v-if="value.possibleMatches?.length"><summary>同名待核 {{value.possibleMatches.length}} 项 · 未计入跨平台判断</summary><p v-for="m in value.possibleMatches" :key="m.entityKey">{{names[m.platform]}} · {{m.name}} · {{m.developer||'发行方未知'}}</p><a :href="`#/identity?date=${value.asOfDate}`">前往产品关联与核验 →</a></details>
 </section>
</template>
<style scoped>
.cross-evidence{border-left:3px solid #5484bb;background:#f6f9fd;padding:16px;margin:16px 0}.cross-evidence header{display:flex;gap:14px;flex-wrap:wrap;color:#285786}.cross-evidence header span{font-size:12px}.cross-evidence table{border-collapse:collapse;min-width:580px;width:100%;font-size:12px}.cross-evidence td,.cross-evidence th{padding:10px 8px;text-align:left;border-bottom:1px solid #dce5f0}.cross-evidence small{display:block;font-size:11px;color:#67798f;overflow-wrap:anywhere}.cross-evidence p{overflow-wrap:anywhere;line-height:1.6}.cross-evidence summary{cursor:pointer;color:#2560a0;padding:8px 0}
</style>
