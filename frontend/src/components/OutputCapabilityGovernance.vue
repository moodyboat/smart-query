<template>
  <div class="capability-governance">
    <aside class="capability-list">
      <div class="capability-toolbar"><strong>输出能力注册中心</strong><el-button size="small" type="primary" @click="definitionDialog=true">新增</el-button></div>
      <button v-for="item in capabilities" :key="item.id" type="button"
        :class="['registry-item',{active:item.id===selectedId}]" @click="select(item.id)">
        <span><strong>{{ item.name }}</strong><small>{{ item.code }} · {{ typeLabel(item.capabilityType) }}</small></span>
        <el-tag size="small" :type="item.status==='ENABLED'?'success':'info'" effect="plain">{{ item.status }}</el-tag>
      </button>
    </aside>
    <main class="capability-detail" v-loading="loading">
      <template v-if="selected">
        <header class="detail-head">
          <div><span>{{ selected.capabilityType }}</span><h3>{{ selected.name }}</h3><p>{{ selected.description }}</p></div>
          <div class="detail-actions">
            <el-button size="small" @click="versionDialog=true">登记新版本</el-button>
            <el-button size="small" :type="selected.status==='ENABLED'?'danger':'success'" plain @click="toggleStatus">
              {{ selected.status==='ENABLED'?'停用能力':'启用能力' }}
            </el-button>
          </div>
        </header>
        <div class="snapshot-grid">
          <div><span>当前版本</span><strong>{{ selected.versionNo || '—' }}</strong></div>
          <div><span>执行实现</span><strong>{{ selected.implementationType || '未发布' }}</strong></div>
          <div><span>运行时</span><strong>{{ selected.runtimeType || '—' }}</strong></div>
          <div><span>制品摘要</span><strong>{{ shortHash(selected.artifactSha256) }}</strong></div>
        </div>
        <section class="security-card"><strong>安全策略</strong><pre>{{ pretty(selected.securityPolicy) }}</pre></section>
        <section class="version-section">
          <div class="section-title"><strong>不可变版本</strong><span>候选版本必须由其他治理人员审批</span></div>
          <el-table :data="versions" border>
            <el-table-column prop="versionNo" label="版本" width="86"><template #default="{row}">版本 {{ row.versionNo }}</template></el-table-column>
            <el-table-column prop="implementationType" label="实现" min-width="130" />
            <el-table-column prop="runtimeType" label="运行时" min-width="130" />
            <el-table-column label="摘要" min-width="150"><template #default="{row}"><code>{{ shortHash(row.contentHash) }}</code></template></el-table-column>
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column label="操作" width="170">
              <template #default="{row}"><template v-if="row.status==='CANDIDATE'">
                <el-button size="small" type="success" text @click="review(row.id,'APPROVE')">通过</el-button>
                <el-button size="small" type="danger" text @click="review(row.id,'REJECT')">驳回</el-button>
              </template><span v-else class="muted">{{ row.approvedByUserId || '—' }}</span></template>
            </el-table-column>
          </el-table>
        </section>
      </template>
      <el-empty v-else description="选择一个输出能力" />
    </main>

    <el-dialog v-model="definitionDialog" title="新增输出能力定义" width="480px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="definition.name" /></el-form-item>
        <el-form-item label="能力编码"><el-input v-model="definition.code" placeholder="例如 view.finance-card" /></el-form-item>
        <el-form-item label="能力类型"><el-select v-model="definition.capabilityType" style="width:100%"><el-option v-for="type in types" :key="type" :value="type" :label="typeLabel(type)" /></el-select></el-form-item>
        <el-form-item label="说明"><el-input v-model="definition.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="definitionDialog=false">取消</el-button><el-button type="primary" @click="saveDefinition">创建定义</el-button></template>
    </el-dialog>

    <el-dialog v-model="versionDialog" title="登记候选能力版本" width="680px">
      <el-alert type="info" :closable="false" title="登记只创建候选版本；适配器代码、依赖和制品必须先经过平台构建与扫描。" />
      <el-form label-position="top" class="version-form">
        <el-form-item label="实现类型"><el-input v-model="version.implementationType" placeholder="例如 COMPOSED_PAGE" /></el-form-item>
        <el-form-item label="实现引用"><el-input v-model="version.implementationRef" placeholder="builtin://、adapter:// 或 sandbox://" /></el-form-item>
        <el-form-item label="运行时"><el-input v-model="version.runtimeType" value="OUTPUT_RENDERER" /></el-form-item>
        <el-form-item label="制品 SHA256"><el-input v-model="version.artifactSha256" placeholder="sha256:..." /></el-form-item>
        <el-form-item label="配置 Schema（JSON）"><el-input v-model="version.configSchema" type="textarea" :rows="4" class="json-field" /></el-form-item>
        <el-form-item label="安全策略（JSON）"><el-input v-model="version.securityPolicy" type="textarea" :rows="4" class="json-field" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="versionDialog=false">取消</el-button><el-button type="primary" @click="saveVersion">提交候选版本</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changeOutputCapabilityStatus, createOutputCapability, createOutputCapabilityVersion,
  fetchOutputCapabilities, fetchOutputCapabilityVersions, reviewOutputCapabilityVersion } from '../api/orchestration.js'

const types=['TRANSFORM','PERSIST','VIEW','EXPORT','ACTION']
const capabilities=ref([]), versions=ref([]), selectedId=ref(null), loading=ref(false)
const definitionDialog=ref(false), versionDialog=ref(false)
const definition=reactive({name:'',code:'',capabilityType:'VIEW',description:''})
const defaultSecurity=JSON.stringify({network:'DENY',filesystem:'DENY',lineage:'REQUIRED',audit:'REQUIRED'},null,2)
const version=reactive({implementationType:'',implementationRef:'',runtimeType:'OUTPUT_RENDERER',artifactSha256:'',configSchema:'{"type":"object"}',securityPolicy:defaultSecurity})
const selected=computed(()=>capabilities.value.find(item=>item.id===selectedId.value))

async function load(){loading.value=true;try{capabilities.value=await fetchOutputCapabilities(true);if(!selectedId.value&&capabilities.value.length)selectedId.value=capabilities.value[0].id;if(selectedId.value)versions.value=await fetchOutputCapabilityVersions(selectedId.value)}finally{loading.value=false}}
async function select(id){selectedId.value=id;versions.value=await fetchOutputCapabilityVersions(id)}
async function toggleStatus(){const next=selected.value.status==='ENABLED'?'DISABLED':'ENABLED';await ElMessageBox.confirm(`确认${next==='ENABLED'?'启用':'停用'} ${selected.value.name}？`,'输出能力治理',{type:'warning'});await changeOutputCapabilityStatus(selected.value.id,next);ElMessage.success('能力状态已更新');await load()}
async function saveDefinition(){const created=await createOutputCapability(definition);definitionDialog.value=false;Object.assign(definition,{name:'',code:'',capabilityType:'VIEW',description:''});await load();selectedId.value=created.id;await select(created.id);ElMessage.success('能力定义已创建，登记并审批版本后才能启用')}
function parse(value,name){try{return JSON.parse(value)}catch{throw new Error(`${name}不是有效JSON`)}}
async function saveVersion(){try{await createOutputCapabilityVersion(selectedId.value,{implementationType:version.implementationType,implementationRef:version.implementationRef,runtimeType:version.runtimeType,artifactSha256:version.artifactSha256,configSchema:parse(version.configSchema,'配置Schema'),inputSchema:{type:'object'},outputSchema:{type:'object'},dependencies:[],interactionEvents:[],securityPolicy:parse(version.securityPolicy,'安全策略')});versionDialog.value=false;Object.assign(version,{implementationType:'',implementationRef:'',runtimeType:'OUTPUT_RENDERER',artifactSha256:'',configSchema:'{"type":"object"}',securityPolicy:defaultSecurity});await select(selectedId.value);ElMessage.success('候选版本已登记，请由其他治理人员审批')}catch(error){ElMessage.error(error.message)}}
async function review(id,decision){await reviewOutputCapabilityVersion(id,{decision,comment:decision==='APPROVE'?'治理审批通过':'治理审批驳回'});await load();ElMessage.success(decision==='APPROVE'?'版本已发布':'版本已驳回')}
function typeLabel(type){return({TRANSFORM:'数据转换',PERSIST:'持久化',VIEW:'前端展示',EXPORT:'文件导出',ACTION:'业务动作'}[type]||type)}
function shortHash(value){return value?`${String(value).slice(0,14)}…${String(value).slice(-8)}`:'—'}
function pretty(value){return JSON.stringify(value||{},null,2)}
onMounted(load)
</script>

<style scoped>
.capability-governance{flex:1;min-height:0;display:grid;grid-template-columns:300px minmax(0,1fr)}.capability-list{min-height:0;padding:12px;overflow:auto;border-right:1px solid #e1e8f1;background:#f7f9fc}.capability-toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px;color:#183153}.registry-item{width:100%;display:flex;align-items:center;gap:8px;margin-bottom:7px;padding:10px;border:1px solid transparent;border-radius:8px;background:transparent;text-align:left;cursor:pointer}.registry-item:hover,.registry-item.active{border-color:#a8c8ef;background:white}.registry-item>span:first-child{min-width:0;flex:1;display:flex;flex-direction:column;gap:3px}.registry-item strong,.registry-item small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.registry-item strong{color:#254360;font-size:12px}.registry-item small{color:#8492a6;font-size:9px}.capability-detail{min-width:0;min-height:0;padding:17px 20px;overflow:auto}.detail-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.detail-head span{color:#3d78bd;font-size:10px;font-weight:700}.detail-head h3{margin:4px 0;color:#16395f;font-size:20px}.detail-head p{margin:0;color:#73839a;font-size:12px}.detail-actions{display:flex;gap:8px}.snapshot-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin:16px 0}.snapshot-grid>div{display:flex;min-width:0;flex-direction:column;gap:5px;padding:12px;border:1px solid #e1e8f1;border-radius:9px;background:#f9fbfd}.snapshot-grid span{color:#8592a4;font-size:9px}.snapshot-grid strong{overflow:hidden;color:#244a72;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.security-card,.version-section{margin-top:13px;padding:13px;border:1px solid #e1e8f1;border-radius:10px;background:white}.security-card pre{max-height:170px;overflow:auto;margin:8px 0 0;padding:9px;border-radius:7px;background:#101828;color:#d0d5dd;font:11px/1.5 ui-monospace,Consolas,monospace}.section-title{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}.section-title span,.muted{color:#8492a6;font-size:10px}.version-form{display:grid;grid-template-columns:1fr 1fr;gap:0 12px;margin-top:13px}.version-form .el-form-item:nth-last-child(-n+2){grid-column:1/-1}.json-field :deep(textarea){font-family:ui-monospace,Consolas,monospace;font-size:11px}@media(max-width:900px){.capability-governance{grid-template-columns:230px minmax(0,1fr)}.snapshot-grid{grid-template-columns:repeat(2,1fr)}.detail-head{flex-direction:column}}@media(max-width:640px){.capability-governance{display:block;overflow:auto}.capability-list{max-height:260px;border-right:0;border-bottom:1px solid #e1e8f1}.snapshot-grid,.version-form{grid-template-columns:1fr}.version-form .el-form-item{grid-column:1}.detail-actions{flex-wrap:wrap}}
</style>
