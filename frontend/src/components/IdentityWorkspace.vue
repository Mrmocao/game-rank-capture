<script setup lang="ts">
import {computed,ref,reactive,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {profileRequest,newRequestKey} from '../api'
import GameIcon from './GameIcon.vue'
const props=defineProps<{date:string}>()
const rows=ref<any[]>([]),chosen=ref<any[]>([]),busy=ref(false),error=ref(''),q=ref(''),platform=ref(''),onlyMatches=ref(false),open=ref(false),saving=ref(false),products=ref<any[]>([]),history=ref<any[]>([]),historyOpen=ref(false)
const names:Record<string,string>={WECHAT:'微信',DOUYIN:'抖音',IOS:'iOS',GOOGLE_PLAY:'Google Play'}
const actions:Record<string,string>={LINK:'关联统一产品',UNLINK:'撤销关联',CLASSIFY:'核验本平台玩法',CLEAR_GAMEPLAY:'撤销玩法核验'}
const families=['消除','合成','塔防','跑酷','解谜','益智','模拟经营','其他/重度']
const form=reactive({action:'LINK',productId:null as number|null,productName:'',family:'',actor:'',effectiveDate:'',evidenceUrl:'',excerpt:'',reason:'',confirmedSameProduct:false})
const normalized=(v:string)=>v.replace(/\s+/g,'').toLowerCase()
const sourceUrl=(v:unknown)=>typeof v==='string'&&/^https?:\/\//.test(v)?v:undefined
const matches=computed(()=>{const map=new Map<string,Set<string>>();for(const r of rows.value){const key=normalized(r.name);if(!map.has(key))map.set(key,new Set());map.get(key)!.add(r.platform)}return map})
const visible=computed(()=>rows.value.filter(r=>(!q.value||r.name.includes(q.value))&&(!platform.value||r.platform===platform.value)&&(!onlyMatches.value||(matches.value.get(normalized(r.name))?.size||0)>1)))
const members=ref<any[]>([])
async function load(){if(!props.date)return;busy.value=true;error.value='';chosen.value=[];try{rows.value=(await profileRequest(`/api/v1/market/candidates?date=${props.date}`)).items}catch(e:any){error.value=e.message}finally{busy.value=false}}
async function searchProducts(query:string){try{products.value=await profileRequest(`/api/v1/market/identity/products?q=${encodeURIComponent(query)}`)}catch(e:any){ElMessage.error(e.message)}}
function begin(action:string,items:any[]){members.value=[...items];Object.assign(form,{action,productId:null,productName:'',family:'',effectiveDate:props.date,evidenceUrl:'',excerpt:'',reason:'',confirmedSameProduct:false});open.value=true;if(action==='LINK')searchProducts('')}
async function showHistory(row:any){try{history.value=await profileRequest(`/api/v1/market/identity/history?observationId=${row.observationId}&rank=${row.rank}`);historyOpen.value=true}catch(e:any){ElMessage.error(e.message)}}
async function submit(){saving.value=true;try{await profileRequest('/api/v1/market/identity/reviews',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':newRequestKey()},body:JSON.stringify({...form,members:members.value.map(r=>({observationId:r.observationId,rank:r.rank,revision:r.identityReview?.revision||0}))})});open.value=false;ElMessage.success('已追加核验记录；重新生成周报将创建修订，旧报告保持不变');await load()}catch(e:any){ElMessage.error(e.message)}finally{saving.value=false}}
watch(()=>props.date,load,{immediate:true})
</script>
<template><section class="identity-workspace page" v-loading="busy">
 <div class="page-head"><div><h1>产品关联与核验</h1><p>{{date}} · {{rows.length}} 个来源身份。确认同一产品后跨平台只占一席；玩法与IAA依据保留各自平台范围。</p></div><el-button @click="load">刷新</el-button></div>
 <el-alert title="同名不代表同一游戏。请核对官方应用信息、发行方或实机证据后关联；当前操作不会把研究线索变为纯IAA正式推荐。" type="warning" :closable="false"/>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <div class="identity-toolbar"><el-input v-model="q" placeholder="筛选游戏名称" clearable aria-label="筛选游戏名称"/><el-select v-model="platform" clearable placeholder="全部平台" aria-label="筛选平台"><el-option v-for="(name,key) in names" :key="key" :label="name" :value="key"/></el-select><el-checkbox v-model="onlyMatches">只看跨平台同名待核</el-checkbox><el-button type="primary" :disabled="!chosen.length||chosen.length>20" @click="begin('LINK',chosen)">关联所选 {{chosen.length}} 项</el-button></div>
 <div class="table-wrap"><el-table :data="visible" row-key="associationKey" @selection-change="chosen=$event" max-height="650" border>
  <el-table-column type="selection" width="42"/><el-table-column label="来源游戏" min-width="210"><template #default="{row}"><div class="game-cell"><GameIcon :url="row.thumbnail_url" :name="row.name" :size="34"/><span>{{row.name}}<small>{{names[row.platform]}} · {{row.developer||'发行方未知'}}</small></span></div></template></el-table-column>
  <el-table-column label="榜单" min-width="160"><template #default="{row}">{{row.rankings.map((r:any)=>`${r.boardName} #${r.rank}`).join('；')}}</template></el-table-column>
  <el-table-column label="统一产品" min-width="160"><template #default="{row}">{{row.unifiedProductId?`${row.unifiedProductName}（${row.unifiedProductId}）`:'未关联'}}<small v-if="(matches.get(normalized(row.name))?.size||0)>1">存在跨平台同名 · 需核对</small></template></el-table-column>
  <el-table-column label="玩法依据" min-width="160"><template #default="{row}">{{row.reviewedGameplay?.family||row.gameplay?.family||'未知'}}<small>{{row.reviewedGameplay?'人工证据核验':row.gameplay?.label}}</small></template></el-table-column>
  <el-table-column label="核验操作" min-width="270"><template #default="{row}"><el-button size="small" @click="begin('CLASSIFY',[row])">玩法</el-button><el-button size="small" @click="showHistory(row)">历史</el-button><el-button v-if="row.unifiedProductId" size="small" @click="begin('UNLINK',[row])">撤销关联</el-button><el-button v-if="row.reviewedGameplay" size="small" @click="begin('CLEAR_GAMEPLAY',[row])">撤销玩法</el-button></template></el-table-column>
 </el-table></div><p class="muted">核验后在“每周精选”重新生成，才能得到采用新结论的冻结修订。历史报告与原始榜单不被修改。</p>
 <el-dialog v-model="open" :title="actions[form.action]" width="min(680px, 94vw)" :close-on-click-modal="false">
  <p>{{members.map(r=>`${names[r.platform]} · ${r.name}`).join('；')}}</p>
  <details><summary>查看所选来源原文与定位（辅助核对，不自动认定同一产品）</summary><article v-for="r in members" :key="r.associationKey"><b>{{names[r.platform]}} · {{r.name}}</b><p>{{r.developer||'发行方未知'}} · {{r.businessDate}} · {{r.boardName}} #{{r.rank}}</p><p>来源标签：{{r.tags?.join('、')||'缺失'}}<br/>来源描述：{{r.description||'缺失；需补充官方详情或实机材料'}}</p><p>来源ID：{{r.externalId||'未提供'}} · 观察 {{r.observationId}}<small>{{r.evidenceLocator}}<br/>SHA256 {{r.rawSha256}}</small></p><a v-if="sourceUrl(r.sourceUrl)" :href="sourceUrl(r.sourceUrl)" target="_blank" rel="noopener noreferrer">查看原始来源 ↗</a></article></details>
  <el-form label-position="top" @submit.prevent="submit">
   <template v-if="form.action==='LINK'"><el-form-item label="关联已有产品（搜索）"><el-select v-model="form.productId" filterable remote clearable :remote-method="searchProducts" placeholder="留空则创建待识别产品"><el-option v-for="p in products" :key="p.id" :value="p.id" :label="`${p.name}（${p.id}）`"/></el-select></el-form-item><el-form-item v-if="!form.productId" label="新建统一产品名称"><el-input v-model="form.productName" maxlength="200"/></el-form-item><el-checkbox v-model="form.confirmedSameProduct">我已核对证据，确认所选记录属于同一产品；并非仅依据同名</el-checkbox></template>
   <el-form-item v-if="form.action==='CLASSIFY'" label="本平台玩法分类"><el-select v-model="form.family"><el-option v-for="f in families" :key="f" :label="f" :value="f"/></el-select></el-form-item>
   <el-form-item label="核对人"><el-input v-model="form.actor" maxlength="100"/></el-form-item><el-form-item label="生效业务日（不早于所引观察日，不接受未来日期）"><el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD"/></el-form-item>
   <el-form-item label="证据网址（官方详情、发行方声明或可追溯实机材料）"><el-input v-model="form.evidenceUrl" placeholder="https://…"/></el-form-item><el-form-item label="证据原文摘录（至少10字）"><el-input v-model="form.excerpt" type="textarea" :rows="3" maxlength="4000"/></el-form-item><el-form-item label="判断 / 撤销原因（至少5字）"><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="2000"/></el-form-item>
  </el-form><template #footer><el-button @click="open=false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">提交证据并保存</el-button></template>
 </el-dialog>
 <el-dialog v-model="historyOpen" title="不可变核验历史" width="min(760px, 94vw)"><el-empty v-if="!history.length" description="暂无人工核验记录"/><article v-for="h in history" :key="h.id"><b>修订 {{h.revision}} · {{actions[h.action]}} · {{h.actor}}</b><p>{{h.effective_date}} 生效 · UTC {{h.recorded_at}} 登记</p><p>{{h.evidence?.operationEvidence?.excerpt}}</p><p>{{h.evidence?.operationEvidence?.reason}}</p><a v-if="/^https?:\/\//.test(h.evidence?.operationEvidence?.url||'')" :href="h.evidence.operationEvidence.url" target="_blank" rel="noopener noreferrer">引用证据 ↗</a></article></el-dialog>
</section></template>
<style scoped>
.identity-toolbar{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:18px 0}.identity-toolbar .el-input{width:220px}.identity-toolbar .el-select{width:150px}.game-cell{display:flex;gap:10px;align-items:center}.identity-workspace small{display:block;color:#75859a;font-size:11px}.identity-workspace article{border-bottom:1px solid #dce5f0;padding:12px 0}.identity-workspace .el-dialog p{overflow-wrap:anywhere}.identity-workspace .el-form-item .el-select{width:100%}
</style>
