<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getCollectionSources, getCollectionSource, getCollectionRuns, getPortalFields } from '../api'

const props = defineProps<{ mode: string }>()
const loading = ref(false)
const catalog = ref<any>({ items: [], sourceCount: 0, portalFieldCount: 0 })
const runs = ref<any>({ items: [] })
const fields = ref<any>({ fields: [] })
const search = ref('')
const state = ref('')
const platform = ref('')
const selected = ref<any>(null)
const title = computed(() => ({ sources:'数据源管理', scope:'信息范围与字段', runs:'执行日志', tasks:'采集任务', alerts:'异常与补采', provenance:'信息来源与证据' } as any)[props.mode])
const filteredRuns = computed(() => runs.value.items || [])
const visibleSources = computed(() => catalog.value.items || [])

async function load() {
  loading.value = true
  try {
    if (props.mode === 'sources' || props.mode === 'scope' || props.mode === 'provenance') catalog.value = await getCollectionSources({ q: search.value, state: state.value, platform: platform.value })
    if (props.mode === 'scope') fields.value = await getPortalFields()
    if (props.mode === 'runs' || props.mode === 'tasks' || props.mode === 'alerts') runs.value = await getCollectionRuns({})
  } catch (e:any) { ElMessage.error(e.message) } finally { loading.value = false }
}
async function openSource(id:string) { try { selected.value = await getCollectionSource(id) } catch (e:any) { ElMessage.error(e.message) } }
function statusLabel(s:string) { return ({'未启用':'未启用','样页可见':'样页可见','文档已核对':'文档已核对','PoC通过':'PoC通过','试运行通过':'试运行通过','SUCCESS':'成功','FAILED':'失败','RUNNING':'运行中','INTERRUPTED':'中断'} as any)[s] || s || '尚未执行' }
function levelType(s:string) { return s === 'SUCCESS' || s === '试运行通过' ? 'success' : s === 'FAILED' ? 'danger' : 'warning' }
onMounted(load)
</script>

<template>
  <section class="page collection-catalog" v-loading="loading">
    <div class="page-head"><div><h1>{{title}}</h1><p>来源目录、字段口径、执行日志和证据血缘分开展示；配置登记不等于真实采集成功。</p></div><el-tag type="warning">未授权来源默认关闭</el-tag></div>
    <div v-if="mode==='sources'||mode==='provenance'" class="data-panel">
      <div class="filterbar"><el-input v-model="search" placeholder="搜索来源、网站或字段" clearable @keyup.enter="load"/><el-select v-model="platform" placeholder="平台 / 来源族" clearable><el-option label="WEB_PORTAL 小游戏门户" value="WEB_PORTAL"/><el-option label="微信 / 抖音 / 商店" value="wechat"/></el-select><el-select v-model="state" placeholder="核验状态" clearable><el-option label="未启用" value="未启用"/><el-option label="样页可见" value="样页可见"/><el-option label="文档已核对" value="文档已核对"/></el-select><el-button type="primary" @click="load">查询</el-button></div>
      <div class="table-caption"><span>{{catalog.sourceCount}} 个来源登记 · 当前显示 {{visibleSources.length}}</span><span class="muted">成功数只来自实际 run_id</span></div>
      <div class="table-wrap"><el-table :data="visibleSources" border><el-table-column prop="sourceId" label="ID" width="76"/><el-table-column label="来源 / 网站" min-width="240"><template #default="{row}"><button class="link" @click="openSource(row.sourceId)">{{row.name}}</button><div class="muted">{{row.site}} · {{row.role}}</div></template></el-table-column><el-table-column label="平台 / 市场" width="150"><template #default="{row}">{{row.platform}} · {{row.region}}</template></el-table-column><el-table-column prop="verificationLevel" label="核验级别" min-width="140"/><el-table-column prop="depth" label="实际深度 / 限制" min-width="280" show-overflow-tooltip/><el-table-column label="运行状态" width="130"><template #default="{row}"><el-tag :type="levelType(row.state)">{{statusLabel(row.state)}}</el-tag><div class="muted">{{row.enabled?'已启用配置':'未启用'}}</div></template></el-table-column></el-table></div>
    </div>
    <div v-else-if="mode==='scope'" class="data-panel"><div class="overview"><div><label>原子来源登记</label><strong>{{catalog.sourceCount}}</strong><small>42项，包含门户候选</small></div><div><label>门户字段模板</label><strong>{{fields.fieldCount||28}}</strong><small>不把站点语境当玩家市场</small></div><div><label>生产自动采集</label><strong>0</strong><small>所有新门户默认关闭</small></div><div><label>当前口径</label><strong>未知保留</strong><small>不将广告或游玩计数当DAU</small></div></div><div class="table-wrap"><el-table :data="fields.fields||[]" border><el-table-column prop="field_key" label="字段键" width="190"/><el-table-column prop="字段名称" label="字段名称" width="180"/><el-table-column prop="采集与口径" label="采集与口径" min-width="580"/></el-table></div></div>
    <div v-else class="data-panel"><div class="notice-band">{{mode==='tasks'?'计划模板与实际运行分开；当前没有为门户自动创建任务。':mode==='alerts'?'失败、部分覆盖和权限缺口保留原记录，补采会产生新run。':'执行日志来自真实免费来源尝试；空列表、登录页或HTTP200解析失败都不会标完整成功。'}}</div><div class="table-wrap"><el-table :data="filteredRuns" border><el-table-column prop="id" label="运行ID" width="90"/><el-table-column label="任务 / 来源" min-width="220"><template #default="{row}">{{row.sourceName}}<div class="muted">{{row.source_key}}</div></template></el-table-column><el-table-column prop="requested_date" label="计划业务日" width="130"/><el-table-column label="状态" width="120"><template #default="{row}"><el-tag :type="levelType(row.status)">{{statusLabel(row.status)}}</el-tag></template></el-table-column><el-table-column prop="started_at" label="开始时间" width="190"/><el-table-column label="结果 / 缺口" min-width="320"><template #default="{row}"><span>{{row.resultJson?.notice||row.resultJson?.code||'未提供结果'}}</span></template></el-table-column></el-table><el-empty v-if="!filteredRuns.length" description="尚未执行；没有可伪造的成功率"/></div></div>
    <el-drawer v-model="selected" :title="selected?.name||'来源详情'" size="min(720px,94vw)"><p><b>{{selected?.sourceId}}</b> · {{selected?.state}} · {{selected?.verificationLevel}}</p><p>{{selected?.limitations}}</p><p>字段范围：{{selected?.fields}}</p><p>入口：<a :href="selected?.url" target="_blank" rel="noreferrer">{{selected?.url}}</a></p><p>默认策略：{{selected?.collectionMethod}}；{{selected?.policy||'逐源准入后再启用'}}</p><el-alert title="此目录记录能力和边界，不会因为页面可打开就形成事实或纯IAA资格。" type="info" :closable="false"/></el-drawer>
  </section>
</template>
