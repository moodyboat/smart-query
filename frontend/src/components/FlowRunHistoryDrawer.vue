<template>
  <el-drawer :model-value="props.modelValue" size="min(1180px, 96vw)" destroy-on-close
    class="run-history-drawer" @update:model-value="emit('update:modelValue', $event)" @open="loadRuns">
    <template #header>
      <div class="drawer-heading">
        <div><h2>运行记录</h2><p>{{ props.flowName || `流程 #${props.flowId}` }} · 从运行实例进入线索和可视化结果</p></div>
        <el-button :loading="historyLoading" @click="loadRuns">刷新</el-button>
      </div>
    </template>

    <div class="history-layout">
      <aside class="run-list" v-loading="historyLoading">
        <button v-for="run in runs" :key="run.id" type="button"
          :class="['run-item', { active: selectedRun?.id === run.id }]" @click="selectRun(run)">
          <span class="run-item-head"><strong>运行 #{{ run.id }}</strong><el-tag size="small" :type="statusType(run.status)">{{ statusLabel(run.status) }}</el-tag></span>
          <span>流程 {{ versionLabel(run.flowVersionId) }} · {{ run.runMode || 'RUN' }}</span>
          <span>{{ formatTime(run.createdAt) }}</span>
          <small v-if="summary(run).leadCount || summary(run).artifactCount">{{ summary(run).leadCount || 0 }} 条线索 · {{ summary(run).artifactCount || 0 }} 个可视化结果</small>
        </button>
        <el-empty v-if="!historyLoading && !runs.length" description="该流程暂无运行记录" :image-size="68" />
      </aside>

      <main class="run-detail" v-loading="detailLoading">
        <template v-if="selectedRun">
          <div class="run-detail-head">
            <div><strong>运行 #{{ selectedRun.id }}</strong><span>{{ versionLabel(selectedRun.flowVersionId) }} · {{ formatTime(selectedRun.createdAt) }}</span></div>
            <el-tag :type="statusType(selectedRun.status)">{{ statusLabel(selectedRun.status) }}</el-tag>
          </div>
          <el-alert v-if="selectedRun.errorMessage" type="error" :closable="false" :title="selectedRun.errorMessage" />

          <el-tabs v-model="activeTab" class="detail-tabs">
            <el-tab-pane label="执行概览" name="overview">
              <div class="run-kpis">
                <div><strong>{{ nodeRuns.length }}</strong><span>执行节点</span></div>
                <div><strong>{{ leads.length }}</strong><span>输出线索</span></div>
                <div><strong>{{ outputs.length }}</strong><span>可视化结果</span></div>
                <div><strong>{{ duration(selectedRun) }}</strong><span>运行耗时</span></div>
              </div>
              <el-table :data="nodeRuns" stripe max-height="430" empty-text="暂无节点执行记录">
                <el-table-column prop="nodeId" label="节点" min-width="180" />
                <el-table-column label="状态" width="110"><template #default="scope"><el-tag size="small" :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
                <el-table-column prop="attemptNo" label="尝试" width="80" />
                <el-table-column label="耗时" width="110"><template #default="scope">{{ scope.row.executionTimeMs == null ? '-' : `${scope.row.executionTimeMs} ms` }}</template></el-table-column>
                <el-table-column prop="errorMessage" label="异常" min-width="220" show-overflow-tooltip />
              </el-table>
              <el-collapse class="input-snapshot"><el-collapse-item title="查看本次运行输入快照" name="input"><pre>{{ pretty(selectedRun.inputSnapshot) }}</pre></el-collapse-item></el-collapse>
            </el-tab-pane>

            <el-tab-pane :label="`线索 ${leads.length}`" name="leads">
              <el-table :data="leads" stripe max-height="310" highlight-current-row
                empty-text="该运行未生成线索" @row-click="selectLead">
                <el-table-column prop="leadNo" label="线索编号" min-width="170" />
                <el-table-column prop="leadType" label="类型" width="130" />
                <el-table-column prop="subjectName" label="主体" min-width="150" />
                <el-table-column prop="decisionResult" label="判断结果" min-width="150" />
                <el-table-column prop="decisionScore" label="评分" width="90" />
                <el-table-column label="操作" width="120"><template #default="scope"><el-button link type="primary" @click.stop="selectLead(scope.row)">查看原始输入</el-button></template></el-table-column>
              </el-table>
              <div v-if="leadDetail" class="lead-detail">
                <div class="section-heading"><strong>{{ leadDetail.lead.leadNo }} · 原始输入与判断依据</strong><el-tag>{{ leadDetail.lead.status }}</el-tag></div>
                <el-descriptions :column="3" border size="small">
                  <el-descriptions-item label="主体">{{ leadDetail.lead.subjectName || leadDetail.lead.subjectId || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="判断结果">{{ leadDetail.lead.decisionResult || '-' }}</el-descriptions-item>
                  <el-descriptions-item label="决策等级">{{ leadDetail.lead.decisionLevel || '-' }}</el-descriptions-item>
                </el-descriptions>
                <div class="evidence-grid">
                  <section><strong>原始输入快照</strong><pre>{{ pretty(leadDetail.sourceSnapshot) }}</pre></section>
                  <section><strong>线索属性</strong><pre>{{ pretty(leadDetail.attributes) }}</pre></section>
                </div>
                <el-table :data="leadDetail.evidence || []" size="small" max-height="190" empty-text="暂无判断依据">
                  <el-table-column prop="evidenceName" label="依据" min-width="150" />
                  <el-table-column prop="fieldName" label="字段" min-width="120" />
                  <el-table-column prop="actualValue" label="实际值" min-width="120" />
                  <el-table-column prop="conditionExpression" label="判断条件" min-width="190" />
                </el-table>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="`可视化结果 ${outputs.length}`" name="outputs">
              <div v-if="outputs.length" class="output-toolbar">
                <el-select v-model="selectedOutputId" @change="changeOutput">
                  <el-option v-for="output in outputs" :key="output.id" :value="output.id" :label="outputLabel(output)" />
                </el-select>
                <span v-if="outputView">{{ outputView.totalRows }} 条 · 第 {{ outputPage + 1 }} 页</span>
                <el-button size="small" :disabled="outputPage === 0 || outputLoading" @click="previousOutputPage">上一页</el-button>
                <el-button size="small" type="primary" plain :disabled="!outputView?.hasMore || outputLoading" @click="nextOutputPage">下一页</el-button>
              </div>
              <div class="output-view" v-loading="outputLoading">
                <OutputArtifactViewer v-if="outputView" :key="`${selectedOutputId}-${outputPage}`" :view="outputView" server-driven :sort="outputSort" @sort-change="changeOutputSort" />
                <el-empty v-else description="该运行未生成可视化结果" />
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
        <el-empty v-else description="从左侧选择一个运行实例" />
      </main>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, watch } from 'vue'
import OutputArtifactViewer from './OutputArtifactViewer.vue'
import { fetchFlowRuns, fetchLeadDetail, fetchRunLeads, fetchRunNodes, fetchRunOutputs, queryOutputView } from '../api/orchestration.js'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  flowId: { type: Number, default: null },
  flowName: { type: String, default: '' },
  versions: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])
const runs = ref([]), selectedRun = ref(null), nodeRuns = ref([]), leads = ref([]), outputs = ref([])
const historyLoading = ref(false), detailLoading = ref(false), activeTab = ref('overview')
const leadDetail = ref(null), selectedOutputId = ref(null), outputView = ref(null), outputLoading = ref(false)
const outputPage = ref(0), outputCursors = ref([null]), outputSort = ref(null)

function parse(value, fallback = {}) { if (value == null || value === '') return fallback; if (typeof value === 'object') return value; try { return JSON.parse(value) } catch { return fallback } }
function pretty(value) { return JSON.stringify(parse(value, value ?? {}), null, 2) }
function summary(run) { return parse(run?.outputSummary, {}) }
function versionLabel(id) { const version = props.versions.find(item => Number(item.id) === Number(id)); return version ? `v${version.versionNo}` : `版本 #${id}` }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
function statusLabel(status) { return ({ QUEUED:'排队中', PENDING:'等待中', RUNNING:'运行中', COMMITTING:'提交中', SUCCESS:'成功', FAILED:'失败', CANCELED:'已取消', TIMED_OUT:'超时' }[status] || status || '-') }
function statusType(status) { return ({ SUCCESS:'success', FAILED:'danger', TIMED_OUT:'danger', CANCELED:'info', RUNNING:'warning', COMMITTING:'warning', QUEUED:'info', PENDING:'info' }[status] || 'info') }
function duration(run) { const ms = summary(run).executionTimeMs; if (ms != null) return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`; if (!run.startedAt) return '-'; const end = run.finishedAt ? new Date(run.finishedAt) : new Date(); return `${Math.max(0, end - new Date(run.startedAt)) / 1000}s` }
function outputLabel(output) { const spec = parse(output.contentSpec, {}); return `${spec.title || spec.sheetName || output.outputKind} · #${output.id}` }

async function loadRuns() {
  if (!props.flowId) return
  historyLoading.value = true
  try {
    runs.value = await fetchFlowRuns(props.flowId, 100)
    if (selectedRun.value && runs.value.some(run => run.id === selectedRun.value.id)) await selectRun(runs.value.find(run => run.id === selectedRun.value.id))
    else if (runs.value.length) await selectRun(runs.value[0])
    else resetDetail()
  } finally { historyLoading.value = false }
}
async function selectRun(run) {
  selectedRun.value = run
  detailLoading.value = true
  activeTab.value = 'overview'
  leadDetail.value = null
  resetOutputQuery()
  try {
    ;[nodeRuns.value, leads.value, outputs.value] = await Promise.all([
      fetchRunNodes(run.id), fetchRunLeads(run.id), fetchRunOutputs(run.id)
    ])
    selectedOutputId.value = outputs.value[0]?.id || null
    if (selectedOutputId.value) await loadOutputView()
    else outputView.value = null
  } finally { detailLoading.value = false }
}
async function selectLead(lead) { leadDetail.value = await fetchLeadDetail(lead.id); activeTab.value = 'leads' }
function resetDetail() { selectedRun.value = null; nodeRuns.value = []; leads.value = []; outputs.value = []; leadDetail.value = null; selectedOutputId.value = null; outputView.value = null }
function resetOutputQuery() { outputPage.value = 0; outputCursors.value = [null]; outputSort.value = null }
async function loadOutputView() { if (!selectedOutputId.value) return; outputLoading.value = true; try { outputView.value = await queryOutputView(selectedOutputId.value, { pageSize: 50, cursor: outputCursors.value[outputPage.value], filters: [], sort: outputSort.value }) } finally { outputLoading.value = false } }
async function changeOutput() { resetOutputQuery(); await loadOutputView() }
async function changeOutputSort(event) { outputSort.value = event.order ? { field: event.prop, direction: event.order === 'descending' ? 'DESC' : 'ASC' } : null; outputPage.value = 0; outputCursors.value = [null]; await loadOutputView() }
async function nextOutputPage() { if (!outputView.value?.nextCursor) return; outputCursors.value = [...outputCursors.value.slice(0, outputPage.value + 1), outputView.value.nextCursor]; outputPage.value += 1; await loadOutputView() }
async function previousOutputPage() { if (outputPage.value === 0) return; outputPage.value -= 1; await loadOutputView() }

watch(() => props.flowId, () => { if (props.modelValue) loadRuns() })
</script>

<style scoped>
.drawer-heading{width:100%;display:flex;align-items:center;justify-content:space-between;gap:16px}.drawer-heading h2{margin:0 0 4px;color:#102a4c;font-size:20px}.drawer-heading p{margin:0;color:#758197;font-size:12px}.history-layout{height:calc(100vh - 92px);display:grid;grid-template-columns:310px 1fr;border-top:1px solid #e1e8f1}.run-list{min-height:0;padding:12px;overflow:auto;border-right:1px solid #e1e8f1;background:#f7f9fc}.run-item{width:100%;display:flex;flex-direction:column;gap:6px;margin-bottom:8px;padding:12px;border:1px solid #dfe7f1;border-radius:9px;background:white;color:#65758b;text-align:left;cursor:pointer}.run-item:hover,.run-item.active{border-color:#7eb0ed;background:#f5f9ff}.run-item.active{box-shadow:inset 3px 0 #2563eb}.run-item-head{display:flex;align-items:center;justify-content:space-between;color:#183153}.run-item>span,.run-item>small{font-size:11px}.run-detail{min-width:0;min-height:0;padding:0 18px 18px;overflow:auto}.run-detail-head{display:flex;align-items:center;justify-content:space-between;padding:12px 0}.run-detail-head>div{display:flex;flex-direction:column;gap:4px}.run-detail-head strong{color:#17365d;font-size:17px}.run-detail-head span{color:#758197;font-size:11px}.detail-tabs{min-height:0}.run-kpis{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:4px 0 14px}.run-kpis>div{display:flex;flex-direction:column;gap:5px;padding:13px;border:1px solid #e1e8f1;border-radius:9px;background:#f8fafc}.run-kpis strong{color:#175cb5;font-size:20px}.run-kpis span{color:#758197;font-size:11px}.input-snapshot{margin-top:14px}.input-snapshot pre,.evidence-grid pre{max-height:260px;margin:8px 0 0;padding:12px;overflow:auto;border-radius:8px;background:#111827;color:#d1fae5;font:12px/1.5 ui-monospace,Consolas,monospace;white-space:pre-wrap}.lead-detail{margin-top:14px;padding-top:14px;border-top:1px solid #e1e8f1}.section-heading,.output-toolbar{display:flex;align-items:center;gap:10px;margin-bottom:12px}.section-heading{justify-content:space-between}.evidence-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:12px 0}.evidence-grid section{min-width:0}.output-toolbar>.el-select{width:300px;margin-right:auto}.output-toolbar>span{color:#758197;font-size:12px}.output-view{min-height:360px}@media(max-width:900px){.history-layout{grid-template-columns:240px 1fr}.run-kpis{grid-template-columns:repeat(2,1fr)}.evidence-grid{grid-template-columns:1fr}}
</style>
