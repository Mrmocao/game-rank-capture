<script setup lang="ts">
import { metricSource,metricTime,metricStatus } from '../composables/observationMetrics'
import ProfileDocumentView from './ProfileDocumentView.vue'
defineProps<{modelValue:boolean;row:any}>();defineEmits<{(event:'update:modelValue',value:boolean):void}>()
const label=(v:string)=>({price:'售价',installs:'累计下载档位',rating:'评分',rating_count:'评分数量',has_iap:'含内购',contains_ads:'含广告'}[v]||v)
const scope=(v:string)=>({GLOBAL:'全球',cn:'中国',us:'美国',gb:'英国',de:'德国',fr:'法国'}[v]||v)
const method=(v:string)=>({OFFICIAL_DISPLAY:'官方展示',STORE_RANGE:'官方下载档位'}[v]||v)
</script>
<template>
 <el-drawer :model-value="modelValue" @update:model-value="$emit('update:modelValue',$event)" title="榜单游戏 · 指标详情与证据" size="min(1040px,96vw)" append-to-body>
  <div v-if="row" class="ranking-metric-evidence"><h2>{{row.name}}</h2><p>{{row.platform}} · {{scope(row.region)}} · {{row.external_id||'平台身份未提供'}}</p><el-alert title="以下商店指标为最新补充数据，不代表历史榜单当天的值。售价不是收入，内购项目不代表购买人数。" type="info" :closable="false"/>
   <h3>原榜单字段</h3><p>榜单采集时间：{{metricTime(row.capturedAt)}}（北京）</p><a :href="row.sourceUrl" target="_blank" rel="noopener noreferrer">原榜来源 ↗</a><p>售价：{{row.existing?.priceRaw?.label||'未知'}} · 安装展示：{{row.existing?.installsDisplay||'未知'}} · 近30日估算：{{row.existing?.recentInstallsDisplay||'未知'}}</p><p class="muted">AppBrain近30日数据为估算，其指标地域不自动继承榜单国家。</p><details><summary>原榜证据与字段</summary><p>SHA256 {{row.rawSha256}}</p><p>{{row.evidence_locator}}</p><pre>{{JSON.stringify(row.existing,null,2)}}</pre></details>
   <section v-for="job in row.jobs" :key="job.id"><h3>{{metricSource(job.source_key)}} · {{metricStatus(job.status)}}</h3><p v-if="job.captured_at">补充采集：{{metricTime(job.captured_at)}}（北京）{{job.stale?' · 已超过缓存期':''}}</p><el-alert v-if="job.error_code" :title="`${job.error_code}${job.snapshot?'；展示上次成功数据':'；尚无成功数据'}`" type="warning" :closable="false"/>
    <template v-if="job.snapshot"><a :href="job.snapshot.sourceUrl" target="_blank" rel="noopener noreferrer">商店详情来源 ↗</a><el-table :data="job.snapshot.metrics" stripe><el-table-column label="指标" min-width="115"><template #default="{row:m}">{{label(m.key)}}</template></el-table-column><el-table-column prop="rawDisplay" label="来源原文" min-width="130"/><el-table-column prop="currency" label="币种" width="80"/><el-table-column label="口径 / 地域 / 周期" min-width="200"><template #default="{row:m}">{{method(m.method)}} · {{scope(m.geographicScope)}} · {{m.period==='LIFETIME'?'历史累计':'抓取时展示值'}}</template></el-table-column></el-table>
    <template v-if="job.snapshot.purchases.length"><h4>页面展示的内购商品（不保证完整）</h4><el-table :data="job.snapshot.purchases" stripe><el-table-column prop="name" label="商品"/><el-table-column prop="priceDisplay" label="价格"/></el-table></template><p v-for="note in job.snapshot.limitations" :key="note" class="muted">{{note}}</p><details><summary>原文哈希与解析定位</summary><p>SHA256 {{job.snapshot.rawSha256}}</p><p>解析版本：{{job.snapshot.parserVersion}}</p><pre>{{JSON.stringify(job.snapshot.metrics,null,2)}}</pre></details></template>
   </section><el-empty v-if="!row.jobs?.length" :description="row.supported?'商店详情尚未补全，原榜字段如上':'该平台尚无可核实的公开下载、收入与付费人数，保持未知'"/>
   <ProfileDocumentView v-for="p in row.profiles||[]" :key="p.id" :document="p.document"/>
  </div>
 </el-drawer>
</template>
<style scoped>.ranking-metric-evidence{line-height:1.8}.ranking-metric-evidence section{margin-top:24px;border-top:1px solid #e3e8ef;padding-top:10px}.ranking-metric-evidence p,.ranking-metric-evidence pre{overflow-wrap:anywhere}.ranking-metric-evidence pre{white-space:pre-wrap;font-size:12px;background:#f5f7fa;padding:12px}.muted{font-size:12px;color:#718096}a,summary{color:#337ecc}summary{cursor:pointer}</style>
