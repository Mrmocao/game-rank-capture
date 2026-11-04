<script setup lang="ts">
import { metric,metricPrice,metricInstalls,metricFlag,metricSource,metricTime,metricStatus } from '../composables/observationMetrics'
const props=defineProps<{rows:Record<string,any>;loading:boolean;error:string}>()
defineEmits<{(event:'inspect',row:any):void}>()
const value=(row:any)=>props.rows[row.rank]
const missing=()=>props.loading?'加载中…':props.error?'加载失败':'未知'
</script>
<template>
 <el-table-column label="售价 / 内购" min-width="170"><template #default="{row}"><div class="metric-cell"><b>{{value(row)?metricPrice(value(row)):missing()}}</b><small>{{metric(value(row),'price')?metricSource(metric(value(row),'price').job.source_key):'原榜售价 / 未提供'}}</small><span>内购：{{metricFlag(value(row),'has_iap')}}</span><span>含广告：{{metricFlag(value(row),'contains_ads')}}</span></div></template></el-table-column>
 <el-table-column label="累计下载 / 30日估算" min-width="190"><template #default="{row}"><div class="metric-cell"><b>{{value(row)?metricInstalls(value(row)):missing()}}</b><small>{{metric(value(row),'installs')?'Google 官方 · 全球累计档位':value(row)?.existing?.installsDisplay?'AppBrain 安装展示原文':'暂无公开下载值'}}</small><span>30日估算：{{value(row)?.existing?.recentInstallsDisplay||'未知'}}</span><small v-if="value(row)?.existing?.recentInstallsDisplay">AppBrain · 地域不随榜单国家</small></div></template></el-table-column>
 <el-table-column label="评分 / 付费数据" min-width="155"><template #default="{row}"><div class="metric-cell"><span>评分：{{metric(value(row),'rating')?.value.rawDisplay||value(row)?.existing?.rating||'未知'}}</span><span>评分数：{{metric(value(row),'rating_count')?.value.rawDisplay||'未知'}}</span><small>收入 / 付费人数：未知</small></div></template></el-table-column>
 <el-table-column label="补充时间 / 来源" min-width="220"><template #default="{row}"><div class="metric-cell"><span v-if="loading">正在加载补充指标</span><span v-else-if="error">补充指标加载失败</span><template v-else><div v-for="job in value(row)?.jobs||[]" :key="job.id"><small>{{metricSource(job.source_key)}} · {{metricStatus(job.status)}}</small><small>{{job.captured_at?metricTime(job.captured_at):'尚无成功数据'}}{{job.stale&&job.captured_at?' · 已过期':''}}</small><small v-if="job.error_code">{{job.error_code}}{{job.snapshot?' · 保留旧值':''}}</small></div><small v-if="!value(row)?.jobs?.length">{{value(row)?.supported?'尚未补全商店详情':'暂无对应公开指标'}}</small><el-button link type="primary" :disabled="!value(row)" @click="$emit('inspect',value(row))">指标详情与证据</el-button></template></div></template></el-table-column>
</template>
<style scoped>.metric-cell{font-size:12px;line-height:1.7;overflow-wrap:anywhere}.metric-cell>span,.metric-cell small{display:block}.metric-cell small{color:#718096;font-size:11px}.metric-cell b{font-size:13px}</style>
