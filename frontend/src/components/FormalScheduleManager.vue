<template>
  <section class="schedule-manager" v-loading="loading">
    <div class="schedule-heading">
      <div>
        <h4>调度任务</h4>
      </div>
      <div class="schedule-actions">
        <el-input v-model="search" clearable placeholder="搜索任务、模型或数据表" />
        <el-button plain @click="load">刷新</el-button>
        <el-tooltip :disabled="schedulableModels.length > 0 || schedulableFlows.length > 0" content="暂无可调度的模型或已发布流程">
          <span><el-button v-if="canManage" type="primary" :disabled="schedulableModels.length === 0 && schedulableFlows.length === 0" @click="openCreate">新建调度任务</el-button></span>
        </el-tooltip>
      </div>
    </div>

    <div class="schedule-overview">
      <article><strong>{{ tasks.length }}</strong><span>任务总数</span></article>
      <article><strong>{{ activeCount }}</strong><span>运行中计划</span></article>
      <article><strong>{{ predictionCount }}</strong><span>预测任务</span></article>
      <article><strong>{{ trainingCount }}</strong><span>重训任务</span></article>
      <article><strong>{{ failedCount }}</strong><span>最近失败</span></article>
    </div>

    <div class="schedule-table-shell">
      <el-table :data="filteredTasks" stripe size="small" row-key="id" empty-text="暂无调度任务，请点击“新建调度任务”创建">
        <el-table-column label="任务 / 模型" min-width="250" fixed="left">
          <template #default="{ row }">
            <div class="task-cell"><span>{{ row.taskType === 'FLOW' ? '流' : '调' }}</span><div><strong>{{ row.name }}</strong><small>任务 #{{ row.id }} · {{ targetName(row) }}</small></div></div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">{{ row.status === 'ACTIVE' ? '已启用' : '已暂停' }}</el-tag></template></el-table-column>
        <el-table-column label="类型" width="100"><template #default="{ row }">{{ modeLabel(row.scheduleMode) }}</template></el-table-column>
        <el-table-column label="执行周期" min-width="130"><template #default="{ row }">{{ cronLabel(row.cronExpression) }}</template></el-table-column>
        <el-table-column label="输入 / 输出" min-width="205"><template #default="{ row }"><div class="cell-stack"><span>{{ row.taskType === 'FLOW' ? '流程输入快照' : (row.inputTable || '使用模型训练数据') }}</span><small>{{ row.taskType === 'FLOW' ? '使用流程配置的多个输出目标' : (row.scheduleMode === 'PREDICT' ? (row.outputTable || '自动生成结果表') : '生成新训练制品') }}</small></div></template></el-table-column>
        <el-table-column label="最近结果" width="112"><template #default="{ row }"><div class="cell-stack"><el-tag size="small" :type="lastStatusType(row.lastStatus)" effect="plain">{{ lastStatusLabel(row.lastStatus) }}</el-tag><small v-if="row.lastError" class="error" :title="row.lastError">{{ row.lastError }}</small></div></template></el-table-column>
        <el-table-column label="上次 / 下次" min-width="185"><template #default="{ row }"><div class="cell-stack"><span>{{ formatTime(row.lastRunAt) }}</span><small>{{ row.status === 'ACTIVE' ? formatTime(row.nextRunAt) : '暂停期间不执行' }}</small></div></template></el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="emit('view-runs', row.id)">执行记录</el-button>
            <template v-if="canManage">
              <el-button link type="primary" :loading="runningTaskId === row.id" @click="runNow(row)">立即执行</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link :type="row.status === 'ACTIVE' ? 'warning' : 'success'" @click="toggle(row)">{{ row.status === 'ACTIVE' ? '暂停' : '启用' }}</el-button>
              <el-popconfirm title="删除后不会删除历史执行记录，确认删除该任务？" width="260" @confirm="remove(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <ScheduleTaskDialog v-model:visible="dialogVisible" :task="editingTask" :models="schedulableModels" :flows="schedulableFlows"
      :saving="saving" @save="save" />
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ScheduleTaskDialog from './ScheduleTaskDialog.vue'
import { fetchMiningModels } from '../api/index.js'
import { changeScheduleTaskStatus, createScheduleTask, deleteScheduleTask, fetchScheduleTasks, updateScheduleTask, fetchFlows, fetchFlowVersions, runScheduleTaskNow } from '../api/orchestration.js'
import { MODEL_STATUS } from '../constants.js'
import { useUserStore } from '../stores/user.js'

const emit = defineEmits(['view-runs'])
const userStore = useUserStore()
const tasks = ref([]), models = ref([]), flows = ref([]), loading = ref(false), saving = ref(false), search = ref('')
const dialogVisible = ref(false), editingTask = ref(null)
const runningTaskId = ref(null)
const canManage = computed(() => userStore.canManageRuntime || userStore.isAdmin)
const schedulableModels = computed(() => models.value.filter(model => [MODEL_STATUS.PUBLISHED, MODEL_STATUS.TRAINED].includes(model.status)))
const schedulableFlows = computed(() => flows.value.filter(flow => flow.status === 'PUBLISHED'))
const filteredTasks = computed(() => {
  const query = search.value.trim().toLowerCase()
  return tasks.value.filter(task => !query || [task.name, task.modelName, task.flowName, task.inputTable, task.outputTable]
    .some(value => String(value || '').toLowerCase().includes(query)))
})
const activeCount = computed(() => tasks.value.filter(task => task.status === 'ACTIVE').length)
const predictionCount = computed(() => tasks.value.filter(task => ['PREDICT', 'FLOW'].includes(task.scheduleMode)).length)
const trainingCount = computed(() => tasks.value.filter(task => task.scheduleMode === 'TRAIN').length)
const failedCount = computed(() => tasks.value.filter(task => task.lastStatus === 'FAILED').length)

async function load() {
  loading.value = true
  try {
    const [taskRows, modelRows, flowDefinitions] = await Promise.all([fetchScheduleTasks(), fetchMiningModels(), fetchFlows()])
    tasks.value = taskRows; models.value = modelRows
    const versionGroups = await Promise.all(flowDefinitions.map(async flow => (await fetchFlowVersions(flow.id)).map(version => ({ ...version, name: flow.name, flowId: flow.id }))))
    flows.value = versionGroups.flat()
  }
  catch (error) { ElMessage.error(error.response?.data?.message || error.message || '加载调度任务失败') }
  finally { loading.value = false }
}
function openCreate() { editingTask.value = null; dialogVisible.value = true }
function openEdit(task) { editingTask.value = task; dialogVisible.value = true }
async function save(payload) {
  saving.value = true
  try {
    if (editingTask.value) await updateScheduleTask(editingTask.value.id, payload)
    else await createScheduleTask(payload)
    dialogVisible.value = false
    ElMessage.success(editingTask.value ? '调度任务已更新' : '调度任务已创建')
    await load()
  } finally { saving.value = false }
}
async function toggle(task) {
  const status = task.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
  await changeScheduleTaskStatus(task.id, status)
  ElMessage.success(status === 'ACTIVE' ? '调度任务已启用' : '调度任务已暂停')
  await load()
}
async function runNow(task) {
  runningTaskId.value = task.id
  try {
    const result = await runScheduleTaskNow(task.id)
    if (!result.success) throw new Error(result.errorMessage || '执行失败')
    ElMessage.success('调度任务已提交执行')
    await load()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || error.message || '执行失败')
  } finally {
    runningTaskId.value = null
  }
}
async function remove(task) {
  await deleteScheduleTask(task.id)
  ElMessage.success('调度任务已删除，历史执行记录予以保留')
  await load()
}

const modeLabel = mode => mode === 'TRAIN' ? '定期重训' : mode === 'FLOW' ? '完整流程' : '定期预测'
const targetName = row => row.taskType === 'FLOW' ? `${row.flowName || '模型流程'} · 版本 ${row.flowVersionNo || '-'}` : `${row.modelName} · 版本 ${row.modelVersion || 1}`
const cronLabel = cron => ({ '*/30 * * * *': '每 30 分钟', '0 * * * *': '每小时', '0 6 * * *': '每天 06:00', '0 8 * * *': '每天 08:00', '0 0 * * *': '每天 00:00', '0 8 * * 1': '每周一 08:00', '0 0 1 * *': '每月 1 日' }[cron] || cron || '未配置')
const lastStatusLabel = status => ({ SUCCESS: '成功', FAILED: '失败', RUNNING: '执行中' }[status] || '尚未执行')
const lastStatusType = status => ({ SUCCESS: 'success', FAILED: 'danger', RUNNING: 'warning' }[status] || 'info')
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
onMounted(load)
</script>

<style scoped>
.schedule-manager{min-height:360px}.schedule-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;margin:4px 0 11px}.schedule-heading h4{margin:0;color:#1d1d1f}.schedule-heading p{margin:5px 0 0;color:#7d7d82;font-size:11px}.schedule-actions{display:flex;gap:8px}.schedule-actions .el-input{width:240px}.schedule-overview{display:grid;grid-template-columns:repeat(5,minmax(0,140px));gap:8px;margin-bottom:10px}.schedule-overview article{padding:10px 12px;border:1px solid #e4e8ef;border-radius:8px;background:#f8fafc}.schedule-overview strong,.schedule-overview span{display:block}.schedule-overview strong{color:#2468f2;font-size:19px}.schedule-overview span{margin-top:3px;color:#86909c;font-size:10px}.schedule-table-shell{overflow:hidden;border:1px solid #e4e8ef;border-radius:9px}.task-cell{display:flex;align-items:center;gap:9px}.task-cell>span{width:31px;height:31px;display:grid;place-items:center;flex-shrink:0;border-radius:7px;color:#2468f2;background:#edf3ff;font-size:11px;font-weight:700}.task-cell strong,.task-cell small,.cell-stack span,.cell-stack small{display:block}.task-cell small,.cell-stack small{margin-top:3px;color:#86909c;font-size:10px}.cell-stack .error{max-width:95px;overflow:hidden;color:#d03050;text-overflow:ellipsis;white-space:nowrap}
@media(max-width:1100px){.schedule-heading{flex-direction:column}.schedule-actions{width:100%;flex-wrap:wrap}.schedule-actions .el-input{min-width:220px;flex:1}.schedule-overview{grid-template-columns:repeat(3,1fr)}}
@media(max-width:680px){.schedule-overview{grid-template-columns:repeat(2,1fr)}.schedule-actions .el-input{width:100%;flex-basis:100%}}
</style>
