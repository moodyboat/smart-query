<template>
  <section class="penetrating-monitor" v-loading="loading">
    <button v-if="props.showSidebarToggle" type="button" class="module-menu-floating" aria-label="打开导航" @click="emit('toggleSidebar')">☰</button>

    <div class="summary-grid">
      <article><strong>{{ data.summary?.formalTasks || 0 }}</strong><span>正式执行记录</span></article>
      <article><strong>{{ data.summary?.successTasks || 0 }}</strong><span>执行成功</span></article>
      <article><strong>{{ data.summary?.failedTasks || 0 }}</strong><span>执行失败</span></article>
      <article><strong>{{ data.summary?.scheduledTraining || 0 }}</strong><span>定时训练</span></article>
      <article><strong>{{ data.summary?.scheduledPrediction || 0 }}</strong><span>定时预测</span></article>
      <article><strong>{{ data.summary?.outputArtifacts || 0 }}</strong><span>流程输出产物</span></article>
    </div>

    <el-tabs v-if="section !== 'view'" v-model="tab" class="monitor-tabs">
      <template #extra><el-button class="monitor-refresh" @click="load">刷新</el-button></template>
      <el-tab-pane label="调度任务" name="schedule">
        <FormalScheduleManager @view-runs="showTaskRuns" />
      </el-tab-pane>
      <el-tab-pane label="执行记录" name="tasks">
        <div class="section-title">
          <h4>正式运行实例</h4>
          <div class="title-actions"><el-tag v-if="selectedTaskId" closable effect="plain" @close="selectedTaskId = null">任务 #{{ selectedTaskId }}</el-tag><el-tag effect="plain">{{ executionRows.length }} 条</el-tag></div>
        </div>
        <el-table :data="executionRows" stripe size="small" max-height="540" row-key="key" empty-text="暂无正式执行记录">
          <el-table-column label="任务" min-width="230" fixed="left">
            <template #default="{ row }"><div class="cell-stack"><div><el-tag size="small" :type="taskType(row.taskType)" effect="plain">{{ row.taskTypeLabel }}</el-tag><strong>{{ row.scheduleTaskName || row.title }}</strong></div><small>{{ taskIdentity(row) }}</small></div></template>
          </el-table-column>
          <el-table-column label="模型 / 算法" min-width="190">
            <template #default="{ row }"><div class="cell-stack"><strong>{{ row.modelId ? `模型 #${row.modelId} · 版本 ${row.version || '-'}` : '非模型任务' }}</strong><small>{{ row.algorithm || '—' }}</small></div></template>
          </el-table-column>
          <el-table-column label="输入来源" min-width="170"><template #default="{ row }"><span>{{ row.inputSource || '—' }}</span></template></el-table-column>
          <el-table-column label="输出位置" min-width="210"><template #default="{ row }"><div class="cell-stack"><strong>{{ row.outputLocation || '未生成持久化输出' }}</strong><small v-if="row.recordCount != null">{{ row.recordCount }} 条记录</small><small v-else-if="row.outputCount">{{ row.outputCount }} 个输出目标</small></div></template></el-table-column>
          <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="归属用户" width="100"><template #default="{ row }">{{ row.ownerUserId || '系统调度' }}</template></el-table-column>
          <el-table-column label="开始 / 完成" min-width="190"><template #default="{ row }"><div class="cell-stack"><span>{{ dateTime(row.startedAt || row.createdAt) }}</span><small>{{ dateTime(row.finishedAt) }}</small></div></template></el-table-column>
          <el-table-column label="执行信息" min-width="180"><template #default="{ row }"><span :class="{ error: row.errorMessage }">{{ row.errorMessage || row.message || '—' }}</span></template></el-table-column>
          <el-table-column label="操作" width="82" fixed="right"><template #default="{ row }"><el-button v-if="canCancelExecution(row)" link type="danger" @click="cancelExecution(row)">取消</el-button><span v-else class="muted">—</span></template></el-table-column>
        </el-table>
      </el-tab-pane>

    </el-tabs>

    <section v-else class="monitor-view-panel">
      <div class="section-title">
        <h4>正式输出卡片</h4>
        <div class="title-actions"><el-tag effect="plain">{{ data.outputs?.length || 0 }} 个</el-tag><el-button class="monitor-refresh" @click="load">刷新</el-button></div>
      </div>
      <div v-if="data.outputs?.length" class="output-card-grid">
        <article v-for="row in data.outputs" :key="row.id" class="output-card">
          <header>
            <div><span class="output-card-icon">果</span><div><small>任务名称</small><strong>{{ row.scheduleTaskName || row.flowName || `正式运行 #${row.runId}` }}</strong></div></div>
            <el-tag :type="statusType(row.runStatus)" effect="plain">{{ statusLabel(row.runStatus) }}</el-tag>
          </header>
          <div class="output-card-title"><strong>{{ row.title }}</strong><span>#{{ row.id }}</span></div>
          <div class="output-card-tags"><el-tag size="small" :type="kindTag(row.outputKind)" effect="plain">{{ row.kindLabel }}</el-tag><code v-if="row.capabilityCode">{{ row.capabilityCode }}</code><span v-if="row.recordCount != null">{{ row.recordCount }} 条</span></div>
          <dl>
            <div><dt>正式运行</dt><dd>#{{ row.runId }} · 流程版本 {{ row.flowVersionNo || '-' }}</dd></div>
            <div><dt>经过模型</dt><dd>{{ modelSummary(row) }}</dd></div>
            <div><dt>所有者</dt><dd>{{ row.ownerDisplayName || row.ownerUsername || `用户 #${row.ownerUserId}` }}</dd></div>
            <div><dt>读取权限</dt><dd>{{ permissionLabel(row) }}</dd></div>
            <div><dt>容量</dt><dd>{{ bytes(row.payloadBytes) }}</dd></div>
            <div><dt>生成时间</dt><dd>{{ dateTime(row.createdAt) }}</dd></div>
          </dl>
          <footer><el-button type="primary" plain @click="openOutputDetail(row)">查看穿透详情</el-button><el-button v-if="row.canArchive" plain type="warning" @click="archiveOutput(row)">归档</el-button></footer>
        </article>
      </div>
      <el-empty v-else description="暂无正式调度输出；试运行和预览结果不会显示在这里" :image-size="76" />
    </section>

    <el-drawer v-model="detailVisible" :title="selectedOutput?.title || '输出详情'" size="min(1180px, 94vw)" destroy-on-close>
      <div v-if="selectedOutput" class="detail-shell">
        <el-descriptions :column="detailColumns" border size="small">
          <el-descriptions-item label="输出目标">{{ selectedOutput.kindLabel }}<span v-if="selectedOutput.targetId"> · {{ selectedOutput.targetId }}</span></el-descriptions-item>
          <el-descriptions-item label="能力编码"><code>{{ selectedOutput.capabilityCode || '-' }}</code></el-descriptions-item>
          <el-descriptions-item label="容量 / 记录">{{ bytes(selectedOutput.payloadBytes) }} · {{ selectedOutput.recordCount ?? '-' }} 条</el-descriptions-item>
          <el-descriptions-item label="流程">{{ selectedOutput.flowName || '-' }} · 版本 {{ selectedOutput.flowVersionNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="正式运行">#{{ selectedOutput.runId }} · {{ selectedOutput.runStatus || '-' }}</el-descriptions-item>
          <el-descriptions-item label="所有者">{{ selectedOutput.ownerDisplayName || selectedOutput.ownerUsername || selectedOutput.ownerUserId }}</el-descriptions-item>
          <el-descriptions-item label="明细权限" :span="detailColumns">{{ selectedOutput.permissionDescription }}</el-descriptions-item>
        </el-descriptions>

        <section>
          <div class="section-title"><h4>执行与模型血缘</h4><el-tag effect="plain">{{ selectedOutput.executionPath?.length || 0 }} 个节点</el-tag></div>
          <ExecutionLineageGraph v-if="selectedOutput.executionPath?.length" :nodes="selectedOutput.executionPath" :edges="selectedOutput.executionEdges || []" :selected-node-run-id="selectedStep?.nodeRunId" @select="selectExecutionStep" />
          <el-empty v-else description="该正式输出没有可用的节点运行快照" :image-size="64" />
          <div v-if="selectedStep" v-loading="nodeSnapshotLoading" class="node-snapshot-panel">
            <div class="snapshot-heading"><div><strong>{{ selectedStep.operatorName }}</strong><small>{{ operatorLabel(selectedStep.operatorType) }} · {{ statusLabel(selectedStep.status) }} · 节点 {{ selectedStep.nodeId }}</small></div><el-tag effect="plain">{{ nodeSnapshot?.snapshotBytes || 0 }} 字节</el-tag></div>
            <el-tabs v-if="nodeSnapshot" v-model="snapshotTab">
              <el-tab-pane label="节点输入" name="input"><pre>{{ formatJson(nodeInput) }}</pre></el-tab-pane>
              <el-tab-pane label="节点输出" name="output"><pre>{{ formatJson(nodeSnapshot.output) }}</pre></el-tab-pane>
              <el-tab-pane label="节点配置" name="config"><pre>{{ formatJson(nodeSnapshot.nodeConfig) }}</pre></el-tab-pane>
            </el-tabs>
          </div>
        </section>

        <section>
          <div class="section-title"><h4>输出内容</h4></div>
          <el-alert v-if="!selectedOutput.canViewDetails" type="warning" :closable="false" show-icon title="当前账号只能查看治理元数据，不能跨用户读取业务明细。" :description="selectedOutput.permissionDescription" />
          <div v-else v-loading="detailLoading" class="result-view-wrap">
            <div v-if="outputView?.queryIndexReady" class="result-filter-bar">
              <el-select v-model="filterField" clearable placeholder="选择筛选字段" @change="syncFilterOperator"><el-option v-for="field in filterableFields" :key="field.field" :label="field.field" :value="field.field" /></el-select>
              <el-select v-model="filterOperator" :disabled="!filterField"><el-option v-for="option in filterOperators" :key="option.value" :label="option.label" :value="option.value" /></el-select>
              <el-input v-model="filterValue" :disabled="!filterField" clearable placeholder="输入筛选值" @keyup.enter="applyResultFilter" />
              <el-button type="primary" :disabled="!filterField" @click="applyResultFilter">筛选</el-button><el-button @click="resetResultFilter">重置</el-button>
            </div>
            <OutputArtifactViewer v-if="outputView" :view="outputView" server-driven :sort="outputSort" @sort-change="changeOutputSort" />
            <div v-if="outputView" class="result-pagination"><span>共 {{ outputView.totalRows }} 条 · 第 {{ detailPage }} 页</span><el-select v-model="detailPageSize" @change="changeDetailPageSize"><el-option v-for="size in [20,50,100,200]" :key="size" :label="`${size} 条/页`" :value="size" /></el-select><el-button :disabled="detailPage <= 1" @click="previousDetailPage">上一页</el-button><el-button :disabled="!outputView.hasMore" @click="nextDetailPage">下一页</el-button></div>
          </div>
        </section>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import OutputArtifactViewer from './OutputArtifactViewer.vue'
import ExecutionLineageGraph from './ExecutionLineageGraph.vue'
import FormalScheduleManager from './FormalScheduleManager.vue'
import { cancelRun, archiveOutputArtifact, fetchFormalTaskMonitor, queryOutputView, fetchNodeRunSnapshot } from '../api/orchestration.js'
import { cancelTrainingExecution } from '../api/index.js'

const props = defineProps({ section: { type: String, default: 'operations' }, showSidebarToggle: { type: Boolean, default: false } })
const emit = defineEmits(['toggleSidebar'])
const section = computed(() => props.section)
const data = ref({}), loading = ref(false), tab = ref('schedule')
const detailVisible = ref(false), detailLoading = ref(false), selectedOutput = ref(null), outputView = ref(null)
const detailPage = ref(1), detailPageSize = ref(50)
const detailCursors = ref([null]), outputSort = ref(null), activeFilters = ref([])
const filterField = ref(''), filterOperator = ref('CONTAINS'), filterValue = ref('')
const selectedStep = ref(null), nodeSnapshot = ref(null), nodeSnapshotLoading = ref(false), snapshotTab = ref('input')
const selectedTaskId = ref(null)
const detailColumns = computed(() => window.innerWidth < 760 ? 1 : window.innerWidth < 1120 ? 2 : 3)
const executionRows = computed(() => (data.value.tasks || []).filter(row => !selectedTaskId.value || row.scheduleTaskId === selectedTaskId.value))

async function load() { loading.value = true; try { data.value = await fetchFormalTaskMonitor() || {} } finally { loading.value = false } }
async function openOutputDetail(row) { selectedOutput.value = row; outputView.value = null; selectedStep.value = null; nodeSnapshot.value = null; resetQueryState(); detailVisible.value = true; if (row.canViewDetails) await loadOutputDetail() }
async function loadOutputDetail() { if (!selectedOutput.value?.canViewDetails) return; detailLoading.value = true; try { outputView.value = await queryOutputView(selectedOutput.value.id, { pageSize: detailPageSize.value, cursor: detailCursors.value[detailPage.value - 1] || null, filters: activeFilters.value, sort: outputSort.value }) } finally { detailLoading.value = false } }
function resetQueryState() { detailPage.value = 1; detailCursors.value = [null]; outputSort.value = null; activeFilters.value = []; filterField.value = ''; filterOperator.value = 'CONTAINS'; filterValue.value = '' }
async function changeDetailPageSize() { detailPage.value = 1; detailCursors.value = [null]; await loadOutputDetail() }
async function nextDetailPage() { if (!outputView.value?.nextCursor) return; detailCursors.value[detailPage.value] = outputView.value.nextCursor; detailPage.value += 1; await loadOutputDetail() }
async function previousDetailPage() { if (detailPage.value <= 1) return; detailPage.value -= 1; await loadOutputDetail() }
const filterableFields = computed(() => (outputView.value?.queryFields || []).filter(field => field.filterable))
const selectedFilterField = computed(() => filterableFields.value.find(field => field.field === filterField.value))
const filterOperators = computed(() => selectedFilterField.value?.valueType === 'NUMBER' ? [{label:'等于',value:'EQ'},{label:'大于等于',value:'GTE'},{label:'小于等于',value:'LTE'}] : selectedFilterField.value?.valueType === 'BOOLEAN' ? [{label:'等于',value:'EQ'}] : [{label:'包含',value:'CONTAINS'},{label:'等于',value:'EQ'},{label:'开头是',value:'STARTS_WITH'}])
function syncFilterOperator() { filterOperator.value = filterOperators.value[0]?.value || 'EQ' }
async function applyResultFilter() { if (!filterField.value) return; let value = filterValue.value; if (selectedFilterField.value?.valueType === 'NUMBER') value = Number(value); if (selectedFilterField.value?.valueType === 'BOOLEAN') value = String(value).toLowerCase() === 'true'; activeFilters.value = [{ field: filterField.value, operator: filterOperator.value, value }]; detailPage.value = 1; detailCursors.value = [null]; await loadOutputDetail() }
async function resetResultFilter() { activeFilters.value = []; filterField.value = ''; filterValue.value = ''; detailPage.value = 1; detailCursors.value = [null]; await loadOutputDetail() }
async function changeOutputSort(event) { outputSort.value = event?.prop ? { field: event.prop, direction: event.order === 'descending' ? 'DESC' : 'ASC' } : null; detailPage.value = 1; detailCursors.value = [null]; await loadOutputDetail() }
async function selectExecutionStep(step) { selectedStep.value = step; nodeSnapshot.value = null; snapshotTab.value = 'input'; nodeSnapshotLoading.value = true; try { nodeSnapshot.value = await fetchNodeRunSnapshot(selectedOutput.value.runId, step.nodeRunId) } finally { nodeSnapshotLoading.value = false } }
const nodeInput = computed(() => nodeSnapshot.value ? { runInput: nodeSnapshot.value.runInput, upstreamOutputs: nodeSnapshot.value.upstreamOutputs } : {})
const formatJson = value => JSON.stringify(value || {}, null, 2)
async function archiveOutput(row) { try { await ElMessageBox.confirm(`归档正式输出 #${row.id}？明细会迁移到压缩归档。`, '归档确认', { type: 'warning', confirmButtonText: '确认归档', cancelButtonText: '取消' }) } catch { return } await archiveOutputArtifact(row.id, '穿透式监控模型手动归档'); ElMessage.success('输出已归档'); await load() }
function showTaskRuns(taskId) { selectedTaskId.value = taskId; tab.value = 'tasks' }
const canCancelExecution = row => ['RUNNING', 'QUEUED', 'running', 'queued'].includes(row.status)
  && ((row.taskType === 'MODEL_TRAIN' && row.modelId && row.executionId) || (row.taskType === 'FLOW' && row.flowRunId))
async function cancelExecution(row) {
  try { await ElMessageBox.confirm('确认取消这个正在运行的正式实例？调度任务本身不会被暂停。', '取消执行', { type: 'warning', confirmButtonText: '取消执行', cancelButtonText: '返回' }) }
  catch { return }
  if (row.taskType === 'FLOW') await cancelRun(row.flowRunId)
  else await cancelTrainingExecution(row.modelId, row.executionId)
  ElMessage.success('已提交取消请求')
  await load()
}

const taskIdentity = row => row.taskType === 'FLOW' ? `正式流程运行 #${row.flowRunId}` : `模型执行 #${row.executionId}${row.scheduleTaskId ? ` · 调度任务 #${row.scheduleTaskId}` : ''}${row.batchId ? ` · 批次 ${row.batchId}` : ''}`
const taskType = type => ({ MODEL_TRAIN:'primary', MODEL_PREDICT:'warning', FLOW:'info' }[type] || 'info')
const statusLabel = status => ({ success:'成功', SUCCESS:'成功', completed:'完成', COMPLETED:'完成', running:'运行中', RUNNING:'运行中', queued:'排队中', QUEUED:'排队中', failed:'失败', FAILED:'失败', canceled:'已取消', CANCELED:'已取消', TIMED_OUT:'超时' }[status] || status || '未知')
const statusType = status => ({ success:'success', SUCCESS:'success', completed:'success', COMPLETED:'success', running:'warning', RUNNING:'warning', queued:'info', QUEUED:'info', failed:'danger', FAILED:'danger', canceled:'info', CANCELED:'info', TIMED_OUT:'danger' }[status] || 'info')
const permissionLabel = row => row.permissionBasis === 'OWNER' ? '本人数据' : row.permissionBasis === 'RESOURCE_ACCESS_ALL' ? '全局读取' : '仅治理元数据'
const kindTag = kind => String(kind).startsWith('EXPORT_') ? 'primary' : ({ LEAD:'danger', CHART:'primary', DASHBOARD:'primary', TABLE:'info', ARTIFACT:'warning', TEMP_RESULT:'warning' }[kind] || 'info')
const operatorTag = type => ({ DATA:'primary', ML:'warning', RULE:'info', AGENT:'danger', OUTPUT:'info' }[type] || 'info')
const operatorLabel = type => ({ DATA:'数据', ML:'机器学习', RULE:'规则', AGENT:'智能体', OUTPUT:'输出' }[type] || type || '未知')
const modelSummary = row => row.models?.length ? row.models.map(model => `${model.name} / ${model.algorithm || '未知算法'}`).join('、') : '未经过机器学习模型'
function bytes(value) { let size = Number(value || 0), unit = 0; const units = ['B','KB','MB','GB','TB']; while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++ } return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}` }
const dateTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
onMounted(load)
</script>

<style scoped>
.penetrating-monitor{position:relative;width:100%;height:auto;min-height:100%;min-width:0;flex:1 0 100%;overflow:visible;padding:18px 22px;background:#f5f7fa}.module-menu-floating{position:absolute;z-index:5;top:10px;left:10px;width:34px;height:34px;display:grid;place-items:center;padding:0;border:1px solid #d9dee7;border-radius:8px;color:#4e5969;background:#fff;cursor:pointer}.section-title h4{margin:0;color:#1d1d1f}.monitor-refresh{color:#fff!important;border-color:#2468f2!important;background:#2468f2!important}.monitor-refresh:hover,.monitor-refresh:focus{border-color:#1e5ed8!important;background:#1e5ed8!important}.summary-grid{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:9px;margin-bottom:12px}.summary-grid article{padding:12px 13px;border:1px solid #e4e8ef;border-radius:9px;background:#fff}.summary-grid strong,.summary-grid span{display:block}.summary-grid strong{color:#2468f2;font-size:21px;line-height:1}.summary-grid span{margin-top:6px;color:#86909c;font-size:10px}.monitor-tabs,.monitor-view-panel{padding:0 14px 14px;border:1px solid #e4e8ef;border-radius:10px;background:#fff}.monitor-view-panel{padding-top:14px}.section-title{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:4px 0 10px}.title-actions{display:flex;align-items:center;gap:7px}.cell-stack,.output-identity{display:flex;min-width:0;flex-direction:column;align-items:flex-start;gap:4px}.cell-stack>div{display:flex;align-items:center;gap:7px}.cell-stack strong,.output-identity strong{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.cell-stack small,.output-identity small,.muted{color:#86909c;font-size:10px}.output-identity>div,.model-list{display:flex;flex-wrap:wrap;gap:5px}.output-identity code{color:#86909c;font-size:10px}.output-card-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(330px,1fr));gap:12px}.output-card{display:flex;min-width:0;flex-direction:column;gap:12px;padding:15px;border:1px solid #e2e7ef;border-radius:12px;background:linear-gradient(150deg,#fff,#f9fbff);box-shadow:0 4px 14px rgba(35,74,130,.05)}.output-card header,.output-card header>div,.output-card footer,.output-card-title,.output-card-tags{display:flex;align-items:center}.output-card header{justify-content:space-between;gap:8px}.output-card header>div{min-width:0;gap:9px}.output-card header strong,.output-card header small{display:block}.output-card header strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.output-card header small{margin-bottom:2px;color:#8a94a3;font-size:9px}.output-card-icon{width:32px;height:32px;display:grid;flex:none;place-items:center;border-radius:9px;background:#eaf2ff;color:#2468f2;font-weight:700}.output-card-title{justify-content:space-between;gap:8px}.output-card-title span{color:#96a0ae;font-size:10px}.output-card-tags{flex-wrap:wrap;gap:6px}.output-card-tags code,.output-card-tags span{color:#758398;font-size:10px}.output-card dl{display:grid;grid-template-columns:1fr 1fr;margin:0;border:1px solid #e8edf4;border-radius:9px;background:#fff}.output-card dl>div{min-width:0;padding:9px 10px;border-right:1px solid #edf0f5;border-bottom:1px solid #edf0f5}.output-card dl>div:nth-child(2n){border-right:0}.output-card dl>div:nth-last-child(-n+2){border-bottom:0}.output-card dt{color:#8a95a5;font-size:9px}.output-card dd{overflow:hidden;margin:4px 0 0;color:#3f4d61;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.output-card footer{justify-content:flex-end;margin-top:auto}.error{color:#d03050}.detail-shell{display:flex;flex-direction:column;gap:18px}.execution-path{display:flex;align-items:stretch;gap:8px;overflow-x:auto;padding:5px 2px 12px}.step-card{flex:0 0 210px;padding:12px;border:1px solid #e4e8ef;border-radius:11px;background:#fff}.step-card>strong,.step-card>small{display:block;margin-top:7px}.step-card>small,.model-card span,.model-card small{color:#86909c;font-size:10px}.step-head{display:flex;justify-content:space-between;gap:6px}.path-arrow{align-self:center;color:#2468f2;font-size:22px}.model-card{display:flex;flex-direction:column;gap:3px;margin-top:10px;padding:8px;border-radius:8px;background:#edf3ff;font-size:11px}.result-view-wrap{min-height:300px;height:min(680px,65vh);display:flex;flex-direction:column;gap:12px}.result-view-wrap :deep(.artifact-viewer){min-height:260px}.result-view-wrap .el-pagination{justify-content:flex-end;flex-shrink:0}
.output-card-grid{gap:10px}.output-card{padding:13px;border-color:#e4e8ef;border-radius:9px;background:#fff;box-shadow:none}.output-card-icon{border-radius:8px;background:#edf3ff}.output-card dl{border-radius:8px;background:#fafbfc}.step-card{border-radius:9px}
.node-snapshot-panel{margin-top:10px;padding:12px;border:1px solid #e4e8ef;border-radius:9px;background:#fff}.snapshot-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.snapshot-heading strong,.snapshot-heading small{display:block}.snapshot-heading small{margin-top:3px;color:#86909c;font-size:10px}.node-snapshot-panel pre{max-height:360px;margin:0;padding:12px;overflow:auto;border-radius:8px;background:#18212f;color:#dce7f5;font:11px/1.6 Consolas,monospace}.result-view-wrap{height:min(720px,68vh);gap:10px}.result-filter-bar{display:grid;grid-template-columns:minmax(150px,1fr) 120px minmax(180px,1.2fr) auto auto;gap:7px}.result-pagination{display:flex;align-items:center;justify-content:flex-end;gap:7px;flex-shrink:0}.result-pagination>span{margin-right:auto;color:#86909c;font-size:10px}.result-pagination .el-select{width:110px}
@media(max-width:1180px){.summary-grid{grid-template-columns:repeat(3,1fr)}}
@media(max-width:760px){.penetrating-monitor{padding:54px 12px 12px}.summary-grid{grid-template-columns:repeat(2,1fr)}.output-card-grid{grid-template-columns:1fr}.output-card dl{grid-template-columns:1fr}.output-card dl>div{border-right:0}.output-card dl>div:nth-last-child(-n+2){border-bottom:1px solid #edf0f5}.output-card dl>div:last-child{border-bottom:0}.execution-path{flex-direction:column}.step-card{flex-basis:auto}.path-arrow{transform:rotate(90deg)}}
@media(max-width:760px){.result-filter-bar{grid-template-columns:1fr 1fr}.result-filter-bar .el-input{grid-column:1/-1}.result-pagination{flex-wrap:wrap}.result-pagination>span{width:100%}}
</style>
