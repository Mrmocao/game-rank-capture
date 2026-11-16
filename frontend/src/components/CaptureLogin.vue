<script setup lang="ts">
import {ref,onMounted,onUnmounted,computed} from 'vue'
import {profileRequest} from '../api'
const props=defineProps<{compact?:boolean}>()
const state=ref<any>(null),error=ref(''),open=ref(false),busy=ref(false),code=ref(''),viewer=ref(false)
const api='/api/v1/admin/capture-login'
let timer:ReturnType<typeof setTimeout>|undefined,disposed=false
const needsLogin=computed(()=>state.value?.source.requiresLogin)
const at=(v:any)=>v?new Date(String(v).replace(' ','T')+(/(?:Z|[+-]\d\d:\d\d)$/.test(String(v))?'':'Z')).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false}):'—'
const windowStatus=computed(()=>({OPENING:'正在打开服务器浏览器',READY:'请在下方窗口手动登录引力引擎',VERIFYING:'会话已保存，正在验证实际榜单',CLOSED:'登录窗口已关闭',FAILED:'登录窗口操作失败，请重试'}[state.value?.window.state as string]||'尚未打开登录窗口'))
const correctOrigin=computed(()=>state.value?.publicOrigin===location.origin)
async function refresh(){try{const before=state.value?.window.state;state.value=await profileRequest(api+'/status');error.value='';if(state.value.window.state!=='READY')viewer.value=false;else if(before!=='READY'&&open.value)viewer.value=true}catch(e:any){error.value='登录状态检查失败：'+e.message}}
async function poll(){await refresh();if(!disposed)timer=setTimeout(poll,open.value?4000:60000)}
async function action(name:string){busy.value=true;error.value='';if(name!=='start')viewer.value=false;try{await profileRequest(api+'/'+name,{method:'POST'});state.value.window.state=name==='start'?'OPENING':name==='finish'?'VERIFYING':'CLOSED'}catch(e:any){error.value=e.message}finally{busy.value=false}}
async function unlock(){busy.value=true;error.value='';try{await profileRequest(api+'/access',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:code.value})});code.value='';await refresh()}catch(e:any){error.value=e.message}finally{busy.value=false}}
function show(){open.value=true;if(timer)clearTimeout(timer);poll()}
onMounted(poll);onUnmounted(()=>{disposed=true;if(timer)clearTimeout(timer)})
</script>
<template>
 <div v-if="!props.compact||needsLogin||error" class="capture-login-card" :class="{urgent:needsLogin}" role="status">
  <div><b>{{needsLogin?'引力引擎登录已失效，采集需要续登':'引力引擎 · 网页续登'}}</b>
   <p v-if="needsLogin">本次未恢复事件首次记录：{{at(state.source.event.started_at)}}（北京）。旧榜单保留，无法取得的最新数据不补写。</p>
   <p v-else>登录状态依据服务器实际采集记录；保存后会重新抓取验证。</p>
   <p v-if="error" class="error">{{error}}</p>
  </div><el-button :type="needsLogin?'danger':'primary'" @click="show">打开续登页面</el-button>
 </div>
 <el-dialog v-model="open" title="引力引擎 · 服务器网页续登" width="min(1320px,96vw)" :close-on-click-modal="false" destroy-on-close @closed="viewer=false">
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <template v-if="state">
   <p>在服务器专用浏览器中完成短信或扫码登录，再点击「已登录，保存并验证」。源站账号、密码和验证码只在窗口内输入。</p>
   <el-alert v-if="!state.configured" title="网页续登尚未启用，请联系维护人员配置服务。" type="warning" :closable="false"/>
   <div v-else-if="!correctOrigin"><p>请从 HTTPS 管理入口打开，以保护续登会话。</p><a :href="state.publicOrigin+'/#/capture'" target="_blank" rel="noopener noreferrer">打开 HTTPS 信息抓取工作台 ↗</a></div>
   <form v-else-if="!state.authenticated" class="unlock" @submit.prevent="unlock"><p>输入独立的续登管理口令（不是引力引擎密码），授权有效 30 分钟。</p><el-input v-model="code" type="password" autocomplete="current-password" placeholder="续登管理口令" aria-label="续登管理口令"/><el-button type="primary" native-type="submit" :loading="busy">解锁续登</el-button></form>
   <template v-else>
    <p><b>{{windowStatus}}</b><span v-if="state.window.state==='READY'"> · 窗口到期 {{at(state.window.expiresAt)}}（北京）</span></p>
    <div class="login-actions">
     <el-button v-if="!['READY','OPENING','VERIFYING'].includes(state.window.state)" type="primary" :loading="busy" @click="action('start')">打开服务器登录窗口</el-button>
     <template v-if="state.window.state==='READY'"><el-button v-if="!viewer" @click="viewer=true">显示登录窗口</el-button><el-button type="primary" :loading="busy" @click="action('finish')">已登录，保存并验证</el-button><el-button :disabled="busy" @click="action('cancel')">关闭窗口并恢复采集</el-button></template>
     <el-button :disabled="busy" @click="refresh">刷新验证状态</el-button>
    </div>
    <iframe v-if="viewer&&state.window.state==='READY'" title="引力引擎服务器专用登录浏览器" src="/capture-login/vnc.html?autoconnect=true&amp;resize=scale&amp;path=capture-login/websockify" referrerpolicy="no-referrer" allow="clipboard-read; clipboard-write"/>
    <el-alert v-if="state.window.state==='CLOSED'&&needsLogin" title="采集记录仍提示登录失效，请完成续登并通过实际采集验证。" type="warning" :closable="false"/>
    <p>关闭此对话框不会提前终止登录；临时窗口到期会自动关闭并恢复定时采集。成功验证后，失效提醒自动解除。</p>
   </template>
  </template>
 </el-dialog>
</template>
<style scoped>
.capture-login-card{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:16px 20px;margin:16px 0;border:1px solid #d6e4ef;border-radius:8px;background:#f5f9fd}.urgent{border-color:#e8c3b1;background:#fff5ed}.capture-login-card p{margin:6px 0 0;font-size:13px;color:#58687b}.error{color:#a52222!important}.unlock{max-width:560px}.unlock .el-button{margin-top:12px}.login-actions{display:flex;gap:10px;flex-wrap:wrap;margin:16px 0}iframe{width:100%;height:min(72vh,820px);min-height:420px;border:1px solid #d9e1ea;border-radius:6px}@media(max-width:650px){.capture-login-card{align-items:flex-start;flex-direction:column}}
</style>
