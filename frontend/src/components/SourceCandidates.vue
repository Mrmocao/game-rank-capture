<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {profileRequest,newRequestKey} from '../api'
import GameIcon from './GameIcon.vue'
const props=defineProps<{date:string;initialQuery?:string;initialPlatform?:string;recordsOnly?:boolean}>()
const emit=defineEmits<{(event:'date-change',value:string):void}>()
let loadVersion=0
const rows=ref<any[]>([]),records=ref<any[]>([]),busy=ref(false),error=ref(''),q=ref(props.initialQuery||''),platform=ref(props.initialPlatform||''),page=ref(1),day=ref(props.date)
const detail=ref<any>(null),detailOpen=ref(false),selectOpen=ref(false),owner=ref(''),status=ref('SELECTED'),note=ref(''),key=ref('')
const labels:Record<string,string>={WECHAT:'微信',DOUYIN:'抖音',IOS:'iOS',GOOGLE_PLAY:'Google Play'}
const statuses:Record<string,string>={SELECTED:'已选用待研究',TESTING:'研究测试中',COMPLETED:'已完成研究',ABANDONED:'已放弃'}
const filtered=computed(()=>rows.value.filter(r=>(!platform.value||r.platform===platform.value)&&(!q.value||r.name.includes(q.value)||String(r.description).includes(q.value))))
const visible=computed(()=>filtered.value.slice((page.value-1)*20,page.value*20))
async function load(){if(!day.value)return;const version=++loadVersion;busy.value=true;error.value='';try{const result=await profileRequest(props.recordsOnly?'/api/v1/market/selections':'/api/v1/market/candidates?date='+day.value);if(version!==loadVersion)return;if(props.recordsOnly)records.value=result.items;else rows.value=result.items;page.value=1}catch(e:any){if(version===loadVersion){rows.value=[];records.value=[];error.value=e.message}}finally{if(version===loadVersion)busy.value=false}}
function changeDate(){if(day.value!==props.date)emit('date-change',day.value);else load()}
watch(()=>props.date,v=>{day.value=v;load()},{immediate:true});watch(()=>props.initialQuery,v=>{q.value=v||'';page.value=1});watch(()=>props.initialPlatform,v=>{platform.value=v||'';page.value=1})
function inspect(row:any){detail.value=row;detailOpen.value=true}
function select(row:any){detail.value=row;owner.value='';note.value='';status.value='SELECTED';key.value=newRequestKey();selectOpen.value=true}
async function save(){if(!owner.value.trim()){ElMessage.warning('请填写负责人');return}busy.value=true;try{await profileRequest('/api/v1/market/selections',{method:'POST',headers:{'Content-Type':'application/json','Idempotency-Key':key.value},body:JSON.stringify({observationId:detail.value.observationId,rank:detail.value.rank,owner:owner.value,status:status.value,note:note.value})});selectOpen.value=false;ElMessage.success('研究选用已保存，可在选用记录查看；不改变IAA资格')}catch(e:any){ElMessage.error(e.message)}finally{busy.value=false}}
</script>
<template>
 <section class="source-candidates">
  <div class="section-heading"><div><h1>{{recordsOnly?'来源候选研究记录':'产品情报库 · 来源候选'}}</h1><p>{{recordsOnly?'每位负责人独立填报，保存当时的来源与证据。':'采集到的微信、抖音和iOS游戏在这里统一查看；身份与纯IAA资格分别核验。'}}</p></div><el-button :loading="busy" @click="load">刷新</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <template v-if="!recordsOnly">
   <div class="filterbar"><el-date-picker v-model="day" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="changeDate"/><el-input v-model="q" aria-label="搜索来源候选" placeholder="游戏名称或来源描述" clearable @input="page=1"/><el-select v-model="platform" placeholder="全部平台" clearable aria-label="候选平台" @change="page=1"><el-option v-for="(label,id) in labels" :key="id" :label="label" :value="id"/></el-select><span>{{filtered.length}} 个来源候选</span></div>
   <div class="notice-band">海外缺失不阻断国内研究。同名跨平台不自动合并；来源描述不等于实机核验，免费榜和宣传数字不证明纯IAA。</div>
   <el-table v-loading="busy" :data="visible" border>
    <el-table-column label="游戏 / 来源身份" min-width="260"><template #default="{row}"><div class="product-cell"><GameIcon :url="row.thumbnail_url" :name="row.name"/><div><button class="link" @click="inspect(row)">{{row.name}}</button><p class="muted">{{labels[row.platform]}} · {{row.region}} · {{row.developer||'发行方未知'}}</p></div></div></template></el-table-column>
    <el-table-column label="各榜真实名次" min-width="180"><template #default="{row}"><div v-for="r in row.rankings" :key="r.chartId">{{r.boardName}} #{{r.rank}}</div></template></el-table-column>
    <el-table-column label="玩法与依据" min-width="240"><template #default="{row}"><b>{{row.gameplay.family}}</b><small>{{row.gameplay.label}}</small><p class="description">{{row.description||row.tags.join(' / ')||'来源未提供可用玩法描述'}}</p></template></el-table-column>
    <el-table-column label="资格" width="115"><template #default><el-tag type="warning">IAA待核验</el-tag></template></el-table-column>
    <el-table-column label="研究" width="145" fixed="right"><template #default="{row}"><el-button link type="primary" @click="inspect(row)">证据与趋势</el-button><el-button link type="primary" @click="select(row)">选用研究</el-button></template></el-table-column>
    <template #empty>该日期暂无来源候选，不以其他日期回填。</template>
   </el-table><el-pagination v-model:current-page="page" :page-size="20" :total="filtered.length" layout="total,prev,pager,next"/>
  </template>
  <el-table v-else :data="records" border><el-table-column label="游戏 / 平台" min-width="220"><template #default="{row}">{{row.source_snapshot.name}}<p>{{labels[row.source_snapshot.platform]}} · {{row.source_snapshot.boardName}} #{{row.source_snapshot.rank}}</p></template></el-table-column><el-table-column prop="owner" label="负责人" width="140"/><el-table-column label="研究状态" width="150"><template #default="{row}">{{statuses[row.status]}}</template></el-table-column><el-table-column prop="note" label="备注" min-width="220"/><el-table-column prop="created_at" label="填报时间（UTC）" width="195"/><el-table-column label="证据" width="100"><template #default="{row}"><el-button link @click="inspect({...row.source_snapshot,rankings:[row.source_snapshot]})">查看快照</el-button></template></el-table-column></el-table>
  <el-drawer v-model="detailOpen" :title="detail?.name+' · 来源证据'" size="min(920px,96vw)">
   <template v-if="detail"><p>{{labels[detail.platform]}} · {{detail.developer}} · {{detail.businessDate}}</p><el-alert title="以下为来源观察，未确认平台稳定身份或纯IAA。来源描述中的DAU、GMV等数字保留为来源声明，不计入核实市场指标。" type="warning" :closable="false"/>
    <h3>玩法描述与依据</h3><p>{{detail.gameplay?.family}} · {{detail.gameplay?.label}}</p><blockquote>{{detail.gameplay?.quote||detail.description||'待补充'}}</blockquote>
    <div v-for="r in detail.rankings" :key="r.chartId" class="source-board"><h3>{{r.boardName}} · #{{r.rank}}</h3><p>业务日 {{r.businessDate}} · 实际采集 {{r.capturedAt}} UTC · 入库 {{r.availableAt}} UTC</p><a :href="r.sourceUrl" target="_blank" rel="noopener noreferrer">查看来源页面</a><p class="muted">观察 {{r.observationId}} · {{r.completeness==='COMPLETE'?'请求范围完整':'部分覆盖'}}</p><div class="table-wrap"><table><thead><tr><th>日期</th><th>名次</th><th>证据观察</th></tr></thead><tbody><tr v-for="point in r.history||[]" :key="point.date"><td>{{point.date}}</td><td>{{point.rank??'缺失 / 身份待核'}}</td><td>{{point.observationId||'—'}}</td></tr></tbody></table></div><details><summary>材料哈希与定位</summary><p class="mono">SHA256 {{r.rawSha256}}</p><p class="mono">{{r.evidenceLocator}}</p></details></div>
    <el-button type="primary" @click="select(detail)">选用研究</el-button>
   </template>
  </el-drawer>
  <el-dialog v-model="selectOpen" title="选用研究 · 保留来源快照" width="min(560px,94vw)"><p>{{detail?.name}} · {{labels[detail?.platform]}}；此操作不认定正式IAA资格。</p><el-form label-position="top"><el-form-item label="负责人"><el-input v-model="owner" maxlength="100" aria-label="研究负责人"/></el-form-item><el-form-item label="研究状态"><el-select v-model="status"><el-option v-for="(label,id) in statuses" :key="id" :label="label" :value="id"/></el-select></el-form-item><el-form-item label="研究计划 / 备注"><el-input v-model="note" type="textarea" maxlength="2000"/></el-form-item></el-form><template #footer><el-button @click="selectOpen=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存研究记录</el-button></template></el-dialog>
 </section>
</template>
<style scoped>
.source-candidates{margin:18px 0}.source-candidates h1{font-size:21px}.source-candidates p{line-height:1.7}.source-candidates .filterbar{flex-wrap:wrap}.source-candidates .el-input{max-width:270px}.source-candidates small{display:block;color:#7b889b;margin-top:5px}.description{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;font-size:12px}.source-board{padding:18px 0;border-bottom:1px solid #e4eaf3}.source-board table{width:100%;border-collapse:collapse}.source-board td,.source-board th{border:1px solid #e4eaf3;padding:9px;text-align:left}.mono{overflow-wrap:anywhere;font-size:12px}.source-candidates blockquote{margin:15px 0;padding:14px;background:#f3f7fc}.source-candidates .el-pagination{margin-top:18px}@media(max-width:650px){.source-candidates .section-heading{display:block}.source-candidates .filterbar>*{max-width:100%}}
</style>
