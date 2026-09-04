<template>
  <el-dialog :model-value="visible" :title="task ? '编辑调度任务' : '新建调度任务'" width="540px"
    destroy-on-close @update:model-value="emit('update:visible', $event)">
    <el-form label-width="96px" @submit.prevent>
      <el-form-item label="任务名称" required>
        <el-input v-model="form.name" maxlength="200" show-word-limit placeholder="例如：每日客户风险预测" />
      </el-form-item>
      <el-form-item label="调度目标" required>
        <el-radio-group v-model="form.taskType" :disabled="!!task">
          <el-radio value="MODEL">单个机器学习算子</el-radio>
          <el-radio value="FLOW">完整模型流程</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.taskType === 'MODEL'" label="生产模型" required>
        <el-select v-model="form.modelId" filterable placeholder="选择已发布模型" style="width:100%" @change="loadTables">
          <el-option v-for="model in models" :key="model.id"
            :label="`${model.name} · 版本 ${model.version || 1}`" :value="model.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-else label="模型流程" required>
        <el-select v-model="form.flowVersionId" filterable placeholder="选择审批通过的模型流程版本" style="width:100%">
          <el-option v-for="flow in flows" :key="flow.id" :label="`${flow.name} · 版本 ${flow.versionNo}`" :value="flow.id" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.taskType === 'MODEL'" label="任务类型" required>
        <el-radio-group v-model="form.scheduleMode">
          <el-radio value="PREDICT">定期预测</el-radio>
          <el-radio value="TRAIN">定期重训</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="执行周期" required>
        <el-select v-model="form.cronExpression" style="width:100%">
          <el-option v-for="option in cronOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <template v-if="form.taskType === 'MODEL' && form.scheduleMode === 'PREDICT'">
        <el-form-item label="输入表" required>
          <el-select v-model="form.inputTable" filterable allow-create default-first-option
            placeholder="选择或输入预测数据表" style="width:100%" :loading="tableLoading">
            <el-option v-for="table in tables" :key="table.name" :label="table.name" :value="table.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="输出表">
          <el-input v-model="form.outputTable" placeholder="留空时按模型生成默认结果表" />
        </el-form-item>
      </template>
      <el-form-item v-if="form.taskType === 'MODEL'" label="数据过滤">
        <el-input v-model="form.inputFilter" type="textarea" :rows="2"
          placeholder="可选，例如：etl_date = '${today}'" />
        <div class="variable-list">
          <code v-for="variable in variables" :key="variable" @click="appendVariable(variable)">{{ variable }}</code>
        </div>
      </el-form-item>
      <el-form-item v-else label="流程输入">
        <el-input v-model="form.inputPayload" type="textarea" :rows="6" placeholder='例如：{"records":[{"paymentId":"P001"}]}' />
        <div class="field-help">使用 JSON 对象；SQL 数据入口流程可保留默认空记录。</div>
      </el-form-item>
      <el-form-item label="创建后">
        <el-radio-group v-model="form.status">
          <el-radio value="ACTIVE">立即启用</el-radio>
          <el-radio value="PAUSED">保持暂停</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ task ? '保存修改' : '创建任务' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDataSourceTables } from '../api/index.js'

const props = defineProps({
  visible: { type: Boolean, default: false },
  task: { type: Object, default: null },
  models: { type: Array, default: () => [] },
  flows: { type: Array, default: () => [] },
  saving: { type: Boolean, default: false },
})
const emit = defineEmits(['update:visible', 'save'])
const cronOptions = [
  { label: '每 30 分钟', value: '*/30 * * * *' },
  { label: '每小时', value: '0 * * * *' },
  { label: '每天 06:00', value: '0 6 * * *' },
  { label: '每天 08:00', value: '0 8 * * *' },
  { label: '每天 00:00', value: '0 0 * * *' },
  { label: '每周一 08:00', value: '0 8 * * 1' },
  { label: '每月 1 日 00:00', value: '0 0 1 * *' },
]
const variables = ['${etl_date}', '${today}', '${yesterday}', '${today-N}']
const tables = ref([])
const tableLoading = ref(false)
const form = reactive(emptyForm())

function emptyForm() {
  return { name: '', taskType: 'MODEL', modelId: null, flowVersionId: null, scheduleMode: 'PREDICT', cronExpression: '0 6 * * *', inputTable: '', inputFilter: '', outputTable: '', inputPayload: '{"records":[]}', status: 'ACTIVE' }
}

watch(() => props.visible, visible => {
  if (!visible) return
  Object.assign(form, emptyForm(), props.task ? {
    name: props.task.name || '', taskType: props.task.taskType || 'MODEL', modelId: props.task.modelId,
    flowVersionId: props.task.flowVersionId,
    scheduleMode: String(props.task.scheduleMode || 'PREDICT').toUpperCase(),
    cronExpression: props.task.cronExpression || '0 6 * * *', inputTable: props.task.inputTable || '',
    inputFilter: props.task.inputFilter || '', outputTable: props.task.outputTable || '', inputPayload: props.task.inputPayload || '{"records":[]}', status: props.task.status || 'PAUSED',
  } : {})
  loadTables(form.modelId)
})

async function loadTables(modelId) {
  tables.value = []
  const model = props.models.find(item => item.id === modelId)
  if (!model?.dataSourceId) return
  tableLoading.value = true
  try { tables.value = await fetchDataSourceTables(model.dataSourceId) || [] }
  catch { tables.value = [] }
  finally { tableLoading.value = false }
}

function appendVariable(variable) { form.inputFilter = `${form.inputFilter || ''}${variable}` }
function submit() {
  if (!form.name.trim()) return ElMessage.warning('请输入任务名称')
  if (form.taskType === 'MODEL' && !form.modelId) return ElMessage.warning('请选择生产模型')
  if (form.taskType === 'FLOW' && !form.flowVersionId) return ElMessage.warning('请选择模型流程版本')
  if (!form.cronExpression) return ElMessage.warning('请选择执行周期')
  if (form.taskType === 'MODEL' && form.scheduleMode === 'PREDICT' && !form.inputTable) return ElMessage.warning('定期预测必须选择输入表')
  if (form.taskType === 'FLOW') {
    try { const value = JSON.parse(form.inputPayload || '{}'); if (!value || Array.isArray(value) || typeof value !== 'object') throw new Error() }
    catch { return ElMessage.warning('流程输入必须是 JSON 对象') }
  }
  emit('save', { ...form, scheduleMode: form.taskType === 'FLOW' ? 'FLOW' : form.scheduleMode,
    modelId: form.taskType === 'MODEL' ? form.modelId : null, flowVersionId: form.taskType === 'FLOW' ? form.flowVersionId : null,
    name: form.name.trim(), inputTable: form.inputTable || null,
    inputFilter: form.inputFilter || null, outputTable: form.outputTable || null })
}
</script>

<style scoped>
.variable-list{display:flex;flex-wrap:wrap;gap:5px;margin-top:6px}.variable-list code{padding:2px 7px;border-radius:5px;background:#edf3ff;color:#2468f2;font-size:10px;cursor:pointer}.variable-list code:hover{background:#2468f2;color:#fff}.field-help{margin-top:5px;color:#86909c;font-size:10px}
</style>
