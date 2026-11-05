<script setup lang="ts">
import { metricTime } from '../composables/observationMetrics'
defineProps<{document:any}>()
</script>
<template>
 <section class="profile-document" v-if="document">
  <h3>{{document.name}} · 中国 iOS 候选资料</h3>
  <p>{{document.developer}} · App ID {{document.externalId}}</p>
  <p><a :href="document.sourceUrl" target="_blank" rel="noopener noreferrer">点点详情来源 ↗</a> · {{metricTime(document.capturedAt)}}（北京）</p>
  <p v-for="note in document.limitations" :key="note" class="muted">{{note}}</p>
  <dl><template v-for="(value,key) in document.fields" :key="key"><dt>{{key}}</dt><dd>{{value}}</dd></template></dl>
  <details><summary>来源证据</summary><p>可见材料 SHA256：{{document.rawSha256}}</p><p>资料平台 {{document.platform}} · 商店地区 {{document.region}} · 来源 {{document.source}}</p></details>
 </section>
</template>
<style scoped>
.profile-document{line-height:1.7;border-top:1px solid #e2e8ef;margin-top:20px;padding-top:10px}.profile-document p{overflow-wrap:anywhere}.muted{color:#718096;font-size:12px}dl{display:grid;grid-template-columns:130px minmax(0,1fr);gap:0}dt,dd{margin:0;padding:10px 4px;border-bottom:1px solid #edf0f4;white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px}dt{color:#58667b}a,summary{color:#337ecc}summary{cursor:pointer}@media(max-width:600px){dl{grid-template-columns:1fr}dt{font-weight:600;border-bottom:0;padding-bottom:0}}
</style>
