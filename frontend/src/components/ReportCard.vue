<script setup lang="ts">
import {computed} from 'vue'
import GameIcon from './GameIcon.vue'
import CrossPlatformEvidence from './CrossPlatformEvidence.vue'
const props=defineProps<{row:any}>();const emit=defineEmits(['detail','select'])
const f=computed(()=>{try{return typeof props.row.frozen_facts==='string'?JSON.parse(props.row.frozen_facts):props.row.frozen_facts||{}}catch{return {}}})
const v=computed(()=>f.value.v22)
const routes:Record<string,string>={ready:'重点验证',conditional:'条件推荐',lead:'线索推荐',paused:'暂缓验证',excluded:'范围不匹配'}
const platform:Record<string,string>={IOS:'iOS',GOOGLE_PLAY:'Google Play',WECHAT:'微信',DOUYIN:'抖音'}
const dimensions:Record<string,string>={D:'市场持续性',M:'近期增势',P:'玩家认可',F:'制作适配',A:'素材可验证性',O:'改进空间'}
const fmt=(n:unknown)=>typeof n==='number'?n.toFixed(1):'—'
</script>
<template><article class="report-card">
 <div class="card-heading"><GameIcon :url="row.thumbnailUrl||f.thumbnailUrl" :name="f.productName||'游戏'" :size="54"/><div><h2>{{f.productName}}</h2><p>{{platform[f.platform]}} · {{f.region}} <span class="status-tag">{{v?routes[v.route]:'历史冻结记录'}}</span></p></div></div>
 <template v-if="v">
  <div class="card-tags"><span>{{v.slot}}</span><span>{{v.family}}</span><span>当前榜单 {{f.rank==null?'缺失 / 榜外':'#'+f.rank}}</span></div>
  <div class="v22-route"><b>研究范围 {{fmt(v.utilityRange.lower)}}～{{fmt(v.utilityRange.upper)}}</b><span>输入覆盖 {{fmt(v.inputCoverage*100)}}%</span></div>
  <p class="muted">范围不是成功概率，覆盖不是准确率。{{v.topDays}} / 14 日实际在榜。</p>
  <div class="v22-dimensions"><span v-for="(r,key) in v.dimensions" :key="key" :title="r.boundMethod"><b>{{dimensions[String(key)]}}</b>{{fmt(r.lower)}}～{{fmt(r.upper)}}</span></div>
  <div class="card-reason">{{f.whyNow}}<p><b>行动：</b>{{v.action}}</p><p v-if="v.reasons?.length">反对 / 未知：{{v.reasons.join('；')}}</p></div>
  <details class="insight-detail"><summary>逐维计算、补证与来源窗口</summary><p v-for="(r,key) in v.dimensions" :key="key"><b>{{dimensions[String(key)]}}</b> · {{r.status}} · {{r.boundMethod}}</p><p v-for="t in v.evidenceTasks" :key="t.key">{{t.label}}：{{t.impact==null?'前提优先':'最多缩小 '+fmt(t.impact)+' 分'}}。{{t.reason}}</p><div class="history-points"><span v-for="h in f.history" :key="h.date" :title="h.state">{{h.date.slice(5)}}<b>{{h.rank==null?'—':'#'+h.rank}}</b></span></div></details>
 </template>
 <template v-else><div class="card-reason">{{row.content}}</div><p class="muted">历史报告保留当时事实与算法结果，不按新方案重算。</p></template>
 <CrossPlatformEvidence :value="f.crossPlatform"/>
 <p class="card-source">截止 {{f.businessDate}} · {{f.chartName}}</p>
 <div class="card-actions"><button class="link" @click="emit('detail',row.product_id)">查看产品与依据 →</button><a v-if="f.sourceUrl&&/^https?:\/\//.test(f.sourceUrl)" :href="f.sourceUrl" target="_blank" rel="noopener noreferrer">原始榜单</a><el-button size="small" @click="emit('select',row.product_id)">选用</el-button></div>
</article></template>
