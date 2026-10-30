<script setup lang="ts">
import {ref,watch} from 'vue'
const props=defineProps<{url?:string|null;name:string;size?:number}>()
const failed=ref(false)
watch(()=>props.url,()=>failed.value=false)
</script>
<template><span class="game-icon" :style="{width:`${size||38}px`,height:`${size||38}px`}"><img v-if="url&&!failed" :src="url" :alt="`${name}图标`" referrerpolicy="no-referrer" loading="lazy" @error="failed=true"/><span v-else class="icon-empty" :title="url?'来源图标暂不可用':'来源未提供图标'">{{name.slice(0,1)}}</span></span></template>
