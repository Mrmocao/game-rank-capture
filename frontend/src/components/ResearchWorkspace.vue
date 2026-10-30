<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
const props=defineProps<{mode:string;date:string}>()
const loading=ref(false),error=ref(''),rows=ref<any[]>([]),q=ref(''),domain=ref(''),profile=ref<any>(null),params=ref<any>({}),actor=ref(''),versionId=ref(''),packetText=ref(''),simulationText=ref(''),simulation=ref<any>(null)
const evidenceOpen=ref(false),selected=ref<any>(null),constraintRows=ref<any[]>([]),selectionId=ref(''),outcomeText=ref(''),outcomeRows=ref<any[]>([])
async function request(path:string,body?:unknown,headers?:Record<string,string>){const r=await fetch('/api/v1/research'+path,body===undefined?undefined:{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(body)});const b=await r.json();if(!r.ok)throw new Error(b.error?.message||'请求失败');return b.data}
const names:Record<string,string>={catalog:'信息采集范围',research:'研究目标与推荐',opinions:'玩家评价与舆情',families:'玩法与赛道',materials:'研究材料与补证',planning:'采集接入规划',experiments:'验证反馈'}
const dimNames=['市场持续性 D','近期增势 M','玩家认可 P','制作适配 F','素材验证 A','改进空间 O']
const filtered=computed(()=>rows.value.filter(r=>(!domain.value||r.domain===domain.value)&&JSON.stringify(r).toLowerCase().includes(q.value.toLowerCase())))
const domains=computed(()=>[...new Set(rows.value.map(r=>r['信息域']))])
const families=computed(()=>{const m=new Map<string,any>();for(const r of rows.value){const f=r.material?.family||'未知';if(!m.has(f))m.set(f,{name:f,products:new Set(),versions:0,markets:new Set()});const x=m.get(f);x.products.add(r.product_id);x.versions++;x.markets.add(r.market)}return [...m.values()].map(x=>({...x,products:x.products.size,markets:[...x.markets].join(' / ')}))})
const weightTotal=computed(()=>params.value.weights?.reduce((s:number,v:number)=>s+Number(v||0),0)||0)
async function load(){loading.value=true;error.value='';try{
 if(props.mode==='catalog')rows.value=await request('/catalog')
 else if(props.mode==='research'){profile.value=await request('/profile');params.value=structuredClone(profile.value.parameters)}
 else if(props.mode==='opinions')rows.value=await request('/opinions?date='+props.date)
 else if(['families','materials'].includes(props.mode)){rows.value=await request('/packets?date='+props.date);if(props.mode==='materials')constraintRows.value=await request('/constraints')}
 }catch(e:any){error.value=e.message}finally{loading.value=false}}
watch(()=>[props.mode,props.date],load,{immediate:true})
async function publish(){try{profile.value=await request('/profile',{actor:actor.value,parameters:params.value});ElMessage.success('研究方案已发布，旧报告保持冻结')}catch(e:any){ElMessage.error(e.message)}}
async function simulate(){try{simulation.value=await request('/simulations',{isSimulation:true,parameters:params.value,candidates:JSON.parse(simulationText.value)});ElMessage.success('只读试算完成，未写入事实和报告')}catch(e:any){ElMessage.error(e.message)}}
async function importPacket(){try{if(!versionId.value)throw Error('请填写平台版本ID');const b=JSON.parse(packetText.value);const result=await request('/versions/'+encodeURIComponent(versionId.value)+'/packets',b);ElMessage.success(result.reused?'同材料已存在，复用记录':'材料已登记并保留引用');await load()}catch(e:any){ElMessage.error(e.message)}}
function template(){packetText.value=JSON.stringify({actor:'',market:'CN',effectiveDate:props.date,validUntil:props.date,family:'未知',assessment:{mode:'unknown'},evidence:[{id:'E1',kind:'source_excerpt',locator:'',excerpt:'',originFamily:'',permission:'public_excerpt',observedDate:props.date}],reviews:[]},null,2)}
async function fromFile(e:Event){try{const file=(e.target as HTMLInputElement).files?.[0];if(file){if(file.size>2000000)throw Error('文件超过2MB');packetText.value=await file.text()}}catch(e:any){ElMessage.error(e.message)}}
function show(r:any){selected.value=r;evidenceOpen.value=true}
async function loadOutcomes(){try{outcomeRows.value=await request('/selections/'+encodeURIComponent(selectionId.value)+'/outcomes')}catch(e:any){ElMessage.error(e.message)}}
function outcomeTemplate(){outcomeText.value=JSON.stringify({actor:'',hypothesis:'',protocol:'',context:'',from:props.date,to:props.date,sample:0,result:'unknown',unknownReason:'尚未开展测试',evidence:'',actualPersonDays:null},null,2)}
async function saveOutcome(){try{if(!selectionId.value)throw Error('填写选用记录ID');const key=Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');await request('/selections/'+selectionId.value+'/outcomes',JSON.parse(outcomeText.value),{'Idempotency-Key':key});ElMessage.success('实验反馈已追加，未覆盖旧结果');await loadOutcomes()}catch(e:any){ElMessage.error(e.message)}}
const resolution=ref({id:'',packetId:'',actor:'',reason:'',evidenceIds:''})
async function resolve(){try{await request('/constraints/'+resolution.value.id+'/resolve',{...resolution.value,packetId:Number(resolution.value.packetId),evidenceIds:resolution.value.evidenceIds.split(',').map(s=>s.trim()).filter(Boolean)});ElMessage.success('已登记解除依据，旧报告仍保留原约束');await load()}catch(e:any){ElMessage.error(e.message)}}
</script>
<template>
<section class="page research-workspace" v-loading="loading">
 <div class="page-head"><div><h1>{{names[mode]}}</h1><p>研究截止 {{date}} · 事实、人工判断与未知项分别留存。</p></div><el-button @click="load">刷新</el-button></div>
 <el-alert v-if="error" :title="error" type="error" :closable="false"/>
 <template v-if="mode==='catalog'">
  <div class="notice-band">十个信息域、120个原子字段是需求目录。可得性未核实的字段不代表已接入。</div>
  <div class="filterbar"><el-input v-model="q" clearable placeholder="搜索字段、口径、用途"/><span>{{filtered.length}} 个字段</span></div>
  <el-table :data="filtered" border><el-table-column prop="信息域" label="信息域" width="170"/><el-table-column prop="字段名称" label="字段" width="180"/><el-table-column prop="field_key" label="标识" min-width="180"/><el-table-column prop="口径与约束" label="口径与约束" min-width="270"/><el-table-column prop="优先级" label="优先级" width="90"/><el-table-column prop="可得性状态" label="可得性状态" min-width="230"/></el-table>
 </template>
 <template v-else-if="mode==='research'">
  <div class="notice-band">当前方案修订 {{profile?.revision}}。参数为待校准启动值；修改仅影响后续生成。试算结果不写入真实材料。</div>
  <div v-if="params.weights" class="insight-panel">
   <h3>六维权重 · 合计 {{weightTotal}}%</h3><div class="research-grid"><el-form-item v-for="(name,i) in dimNames" :key="name" :label="name"><el-input-number v-model="params.weights[i]" :min="0" :max="100"/></el-form-item></div>
   <div class="research-grid"><el-form-item label="每期目标"><el-input-number v-model="params.target" :min="5" :max="10"/></el-form-item><el-form-item label="同玩法上限"><el-input-number v-model="params.familyCap" :min="1" :max="10"/></el-form-item><el-form-item label="探索名额"><el-input-number v-model="params.explorationSlots" :min="0" :max="params.target"/></el-form-item><el-form-item label="原型预算（人日）"><el-input-number v-model="params.budget" :min="1"/></el-form-item><el-form-item label="需求在榜日数"><el-input-number v-model="params.minTopDays" :min="1" :max="14"/></el-form-item><el-form-item label="重点覆盖率"><el-input-number v-model="params.minCoverage" :min="0" :max="1" :step="0.05"/></el-form-item><el-form-item label="重点范围宽度"><el-input-number v-model="params.maxUtilityWidth" :min="0" :max="100"/></el-form-item><el-form-item label="玩家区间最大宽度"><el-input-number v-model="params.maxReviewWidth" :min="0.01" :max="1" :step="0.05"/></el-form-item></div>
   <el-input v-model="actor" placeholder="方案登记人" style="max-width:260px"/><el-button type="primary" :disabled="weightTotal!==100" @click="publish">发布研究方案</el-button>
  </div>
  <details class="insight-panel"><summary>只读缺失数据试算 · 使用结构化候选JSON</summary><p>输入为领域计算候选数组。未知用null，states可声明missing/stale/conflict/not_authorized；试算不会创建产品、资格或报告。</p><el-input v-model="simulationText" type="textarea" :rows="9" placeholder="粘贴候选数组；设计包weekly-v22-cases.json可用于虚构测试"/><el-button :disabled="weightTotal!==100" @click="simulate">执行模拟试算</el-button><p v-if="simulation?.isSimulation">模拟结果：{{simulation.diagnostic}}，入选 {{simulation.selectedIds.join('、')||'无'}}</p><el-table v-if="simulation" :data="simulation.rows"><el-table-column prop="name" label="候选"/><el-table-column prop="route" label="层级"/><el-table-column label="范围"><template #default="{row}">{{row.utilityRange.lower.toFixed(2)}}～{{row.utilityRange.upper.toFixed(2)}}</template></el-table-column><el-table-column prop="action" label="行动" min-width="250"/></el-table></details>
 </template>
 <template v-else-if="mode==='materials'">
  <div class="notice-band">导入人工核查后的结构化材料。必须提供版本ID、来源摘录、引用定位、保存依据与登记人；服务端记录实际可用时间。可在产品详情查看平台版本ID。</div>
  <div class="insight-panel"><div class="filterbar"><el-input v-model="versionId" placeholder="平台版本ID"/><el-button @click="template">生成空白填写模板</el-button><label class="link">读取JSON文件 <input type="file" accept=".json" @change="fromFile"/></label></div><el-input v-model="packetText" type="textarea" :rows="14" placeholder="填写或导入研究材料JSON"/><el-button type="primary" @click="importPacket">校验并登记材料</el-button></div>
  <el-table :data="rows" border><el-table-column prop="id" label="修订ID" width="90"/><el-table-column prop="name" label="产品"/><el-table-column prop="platform_version_id" label="版本ID" width="90"/><el-table-column prop="market" label="市场" width="80"/><el-table-column prop="state" label="适用状态" width="100"/><el-table-column prop="available_at" label="实际获知时间"/><el-table-column label="证据"><template #default="{row}"><el-button link @click="show(row)">查看材料与引用</el-button></template></el-table-column></el-table>
  <details class="insight-panel"><summary>已知约束与解除 · 来源掉线不撤销</summary><el-table :data="constraintRows"><el-table-column prop="id" label="约束ID"/><el-table-column prop="platform_version_id" label="版本"/><el-table-column prop="code" label="约束"/><el-table-column prop="resolved_at" label="解除时间"/><el-table-column prop="detail_json" label="依据" show-overflow-tooltip/></el-table><div class="research-grid"><el-input v-model="resolution.id" placeholder="约束ID"/><el-input v-model="resolution.packetId" placeholder="新材料修订ID"/><el-input v-model="resolution.actor" placeholder="解除登记人"/><el-input v-model="resolution.reason" placeholder="解除原因"/><el-input v-model="resolution.evidenceIds" placeholder="新材料证据ID（逗号分隔）"/></div><el-button @click="resolve">登记解除与依据</el-button></details>
 </template>
 <template v-else-if="mode==='opinions'">
  <div class="notice-band">关注度、玩家倾向、证据质量分开看。数据来自已登记材料；未接入评论源不表示零讨论。分母不包含混合、未知、开发者回复和重复转载。</div>
  <div v-if="!rows.length" class="empty">尚无可追溯玩家材料。请在“研究材料与补证”登记授权摘录及抽样范围。</div>
  <article v-for="r in rows" :key="r.id" class="insight-panel"><div class="section-heading"><h2>{{r.name}}</h2><span>{{r.platform}} / {{r.market}} · {{r.state}}</span><el-button @click="show(r)">原文与证据</el-button></div><p>14日窗口：原始 {{r.summary.rawCount}} 条 / 去重 {{r.summary.clusters}} 簇；正向 {{r.summary.positive}} / 可判断 {{r.summary.denominator}}；混合或未知 {{r.summary.ambiguous}}。</p><p>{{r.summary.comparable?'抽样声明可比，仍需评估代表性':'仅定性展示：跨来源/抽样范围不一致或缺可比声明'}}</p><div class="card-tags"><span v-for="(n,topic) in r.summary.topics" :key="topic">{{topic}} · {{n}}</span></div><p class="muted">{{r.summary.notice}}</p></article>
 </template>
 <template v-else-if="mode==='families'">
  <div class="notice-band">玩法相似只建立家族关系，不合并产品身份。这里统计已登记研究范围，不代表全市场供给或蓝海结论。</div>
  <el-table :data="families" border><el-table-column prop="name" label="玩法家族"/><el-table-column prop="products" label="独立产品数"/><el-table-column prop="versions" label="材料版本数"/><el-table-column prop="markets" label="覆盖市场"/></el-table><p v-if="!families.length" class="empty">玩法尚未核验，请先登记实机材料与机制分类。</p>
 </template>
 <template v-else-if="mode==='experiments'">
  <div class="notice-band">每条反馈绑定独立选用记录。完成制作不等于测试成功；样本不足保存unknown及原因。</div>
  <div class="filterbar"><el-input v-model="selectionId" placeholder="选用记录ID"/><el-button @click="loadOutcomes">查看反馈</el-button><el-button @click="outcomeTemplate">生成填写模板</el-button></div>
  <div class="insight-panel"><el-input v-model="outcomeText" type="textarea" :rows="12"/><el-button type="primary" @click="saveOutcome">追加验证反馈</el-button></div>
  <article v-for="r in outcomeRows" :key="r.id" class="insight-panel"><h3>#{{r.id}} · {{r.created_by}} · {{r.created_at}}</h3><pre class="skill-text">{{JSON.stringify(r.payload,null,2)}}</pre></article>
 </template>
 <template v-else-if="mode==='planning'">
  <div class="insight-panel"><h3>先保证可追溯的输入，再扩展真实采集</h3><el-table :data="[{name:'Apple游戏分类RSS',state:'已接入免费公开材料',gap:'持续积累14日；免费不等于纯IAA'},{name:'AppBrain / Google Play四国',state:'已有材料导入适配',gap:'服务器网络可达性及保存/使用条款待核实'},{name:'微信 / 抖音公开榜单',state:'部分公开观察；独立保存',gap:'稳定身份和完整覆盖待核验，不冒充官方全榜'},{name:'玩家评价 / 实机 / 工期',state:'本期支持人工结构化材料',gap:'授权范围、抽样方案、同版本引用、责任人'},{name:'替代需求行为协议',state:'尚未启用生产通过判定',gap:'需事先登记市场、基线、原始量、阈值及校准结果'}]" border><el-table-column prop="name" label="信息源" min-width="220"/><el-table-column prop="state" label="当前状态" min-width="250"/><el-table-column prop="gap" label="下一步验收" min-width="320"/></el-table><p>AI配置保留独立入口。当前规则报告不调用模型，输出明确标识NOT_CALLED；来源失败不会删除历史证据。</p></div>
 </template>
 <el-drawer v-model="evidenceOpen" title="材料、原文和证据引用" size="min(800px,96vw)"><template v-if="selected"><p>版本 {{selected.platform_version_id}} · 市场 {{selected.market}} · 登记人 {{selected.created_by}}</p><article v-for="e in selected.material?.evidence||[]" :key="e.id" class="insight-panel"><h3>{{e.id}} · {{e.kind}}</h3><p>{{e.excerpt}}</p><p>来源族 {{e.originFamily}} · 观察日 {{e.observedDate}} · 保存依据 {{e.permission}}</p><a v-if="/^https?:\/\//.test(e.locator)" :href="e.locator" target="_blank" rel="noopener noreferrer">{{e.locator}}</a><p v-else>{{e.locator}}</p></article><details><summary>完整材料JSON</summary><pre class="skill-text">{{JSON.stringify(selected.material,null,2)}}</pre></details></template></el-drawer>
</section>
</template>
<style scoped>
.research-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:15px;margin:18px 0}.research-workspace .el-button{margin-top:8px}.research-workspace input[type=file]{max-width:210px}.research-workspace pre{white-space:pre-wrap}.research-workspace .insight-panel{overflow:auto}@media(max-width:1050px){.research-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:650px){.research-grid{grid-template-columns:1fr}}
</style>
