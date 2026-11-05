<script setup lang="ts">
import {computed,onMounted,ref} from 'vue'
import {profileRequest} from '../api'
import {metricTime} from '../composables/observationMetrics'
import ProfileDocumentView from './ProfileDocumentView.vue'
const data=ref<any>(null),error=ref(''),busy=ref(false),search=ref(''),onlyMissing=ref(false),page=ref(1),selected=ref<any>(null),open=ref(false)
const api='/api/v1/admin/capture-workspace/supplements'
async function refresh(){busy.value=true;try{data.value=await profileRequest(api);error.value=''}catch(e:any){error.value=e.message}finally{busy.value=false}}
async function enqueue(){busy.value=true;try{await profileRequest(api+'/enqueue',{method:'POST'});await refresh()}catch(e:any){error.value=e.message}finally{busy.value=false}}
const rows=computed(()=>{const out=new Map<string,any>();for(const seed of data.value?.seeds||[]){const key=seed.platform+':'+seed.name;if(!out.has(key))out.set(key,seed)}return [...out.values()].filter(r=>(!onlyMissing.value||!r.profiles.length)&&r.name.includes(search.value))})
const visible=computed(()=>rows.value.slice((page.value-1)*20,page.value*20))
const job=(row:any)=>data.value?.jobs.find((j:any)=>j.seed_name===row.name)
const latestAttempt=computed(()=>data.value?.jobs.find((j:any)=>j.attempts>0&&['SUCCEEDED','NO_CANDIDATE','FAILED'].includes(j.status)))
const status=(row:any)=>({PENDING:'等待搜索',RUNNING:'搜索中',SUCCEEDED:'已取得资料',NO_CANDIDATE:'可见搜索结果无同名候选',FAILED:'采集失败',CANCELLED:'来源已停用 / 游戏已出榜'}[job(row)?.status as string]||(row.profiles.length?'浏览器取材已导入':'未排队'))
function inspect(row:any){selected.value=row;open.value=true}
onMounted(refresh)
</script>
<template>
 <div class="supplements">
  <div class="toolbar"><div><h2>榜内游戏 · 扩展资料</h2><p>按现有榜单游戏名称搜索点点，保留来源详情与采集时间。</p></div><el-button :loading="busy" @click="refresh">刷新资料</el-button><el-button type="primary" :disabled="busy" @click="enqueue">补全全部榜内游戏</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <template v-if="data">
   <el-alert :title="data.notice" type="info" :closable="false"/>
   <p>已保存 {{data.documents.length}} 个 App 的最新资料 · 按平台名称去重 {{rows.length}} 项 · {{data.browserConfigured?'服务器专用浏览器已配置，执行结果见下表':'当前为浏览器取材导入；服务器尚未配置点点会话'}} </p>
   <p v-if="latestAttempt?.error_code==='SOURCE_AUTH_REQUIRED'" class="login">最近一次执行提示点点服务器会话需要手动登录。已有资料继续保留，登录后恢复排队采集。</p>
   <div class="filters"><el-input v-model="search" placeholder="搜索榜内游戏" clearable @input="page=1"/><el-checkbox v-model="onlyMissing" @change="page=1">仅看资料缺失</el-checkbox></div>
   <el-table :data="visible" stripe>
    <el-table-column label="原榜游戏" min-width="170"><template #default="{row}"><b>{{row.name}}</b><p>{{row.platform}} · #{{row.rank_position}}</p></template></el-table-column>
    <el-table-column label="iOS候选 / 开发者" min-width="220"><template #default="{row}"><div v-for="p in row.profiles" :key="p.id"><b>{{p.document.name}}</b><p>{{p.document.developer}}</p><small>{{p.external_id}} · {{row.platform==='IOS'?'商店ID一致':'跨平台身份未核验'}}</small></div><span v-if="!row.profiles.length">尚无可用资料</span></template></el-table-column>
    <el-table-column label="评分 / 售价 / 版本" min-width="200"><template #default="{row}"><div v-for="p in row.profiles" :key="p.id"><p>评分 {{p.document.fields['评分']||'未知'}} · {{p.document.fields['评分数量']||'数量未知'}}</p><p>售价 {{p.document.fields['价格']||'未知'}} · 内购 {{p.document.fields['内购']||'未知'}}</p><p>版本 {{p.document.fields['版本']||'未知'}} · {{p.document.fields['更新日期']||'更新未知'}}</p></div></template></el-table-column>
    <el-table-column label="采集 / 来源" min-width="190"><template #default="{row}"><p>{{status(row)}}</p><small v-if="job(row)?.error_code">{{job(row).error_code}}</small><p v-for="p in row.profiles" :key="p.id">点点 · {{metricTime(p.captured_at)}}</p></template></el-table-column>
    <el-table-column label="资料" width="120"><template #default="{row}"><el-button link type="primary" :disabled="!row.profiles.length" @click="inspect(row)">查看扩展字段</el-button></template></el-table-column>
   </el-table>
   <el-pagination v-model:current-page="page" :page-size="20" :total="rows.length" layout="prev, pager, next, total"/>
  </template>
  <el-drawer v-model="open" title="扩展资料与来源证据" size="min(1000px,96vw)" append-to-body><ProfileDocumentView v-for="p in selected?.profiles||[]" :key="p.id" :document="p.document"/></el-drawer>
 </div>
</template>
<style scoped>.toolbar,.filters{display:flex;gap:14px;align-items:center;flex-wrap:wrap;margin:18px 0}.toolbar>div{flex:1}h2{font-size:20px;margin:0}p,small{font-size:12px;color:#718096;line-height:1.7;margin:5px 0}.filters .el-input{width:260px}.login{color:#a36612}.el-pagination{margin-top:20px}.supplements :deep(.cell){overflow-wrap:anywhere}</style>
