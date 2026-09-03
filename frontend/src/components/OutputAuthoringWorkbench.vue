<template>
  <div class="authoring-workbench">
    <section class="authoring-panel">
      <div class="section-heading">
        <div><h3>对话创建输出算子</h3><p>生成草稿后必须依次通过整形、预览和发布门禁。</p></div>
        <el-button size="small" @click="createDialog = true">新建输出算子</el-button>
      </div>

      <el-form label-position="top" class="authoring-form">
        <el-form-item label="输出算子">
          <el-select v-model="operatorId" placeholder="选择输出算子" style="width: 100%" @change="loadDrafts">
            <el-option v-for="operator in operators" :key="operator.id" :label="operator.name" :value="operator.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="展示要求（对话指令）">
          <el-input v-model="instruction" type="textarea" :rows="5"
            placeholder="例如：将预测为会逾期的客户展示为类 Excel 表格，显示客户、合同金额、预测概率；按概率降序，每行可查看预测前的完整输入。" />
        </el-form-item>
        <el-form-item label="输出运行时">
          <el-select v-model="runtimeProfileId" placeholder="使用平台默认运行时" clearable style="width:100%">
            <el-option v-for="item in runtimeProfiles" :key="item.profile.id"
              :label="`${item.profile.name} · ${item.dependencies.length} 项扩展依赖`" :value="item.profile.id" />
          </el-select>
          <div class="runtime-help">依赖中心完成构建后，在此选择新运行时并重新执行“沙箱整形”。</div>
        </el-form-item>
        <el-form-item label="预览样例 records（JSON 数组）">
          <el-input v-model="sampleText" type="textarea" :rows="8" class="json-input" />
        </el-form-item>
      </el-form>

      <div class="gate-actions">
        <el-button type="primary" :loading="busy === 'generate'" :disabled="!operatorId || !instruction.trim()" @click="generate">
          1. 对话生成草稿
        </el-button>
        <el-button :loading="busy === 'shape'" :disabled="!canShape" @click="shape">
          2. 沙箱整形
        </el-button>
        <el-button :loading="busy === 'preview'" :disabled="!canPreview" @click="preview">
          3. 预览验证
        </el-button>
        <el-button type="success" :loading="busy === 'publish'" :disabled="draft?.status !== 'PREVIEW_VALIDATED'" @click="publish">
          4. 提交人工审批
        </el-button>
      </div>

      <div v-if="draft" class="draft-status-card">
        <div class="draft-status-line">
          <strong>草稿 #{{ draft.id }}</strong>
          <el-tag :type="statusType(draft.status)" effect="plain">{{ statusLabel(draft.status) }}</el-tag>
          <span v-if="draft.candidateVersionId">候选版本 #{{ draft.candidateVersionId }}</span>
          <el-button v-if="draft.status === 'PUBLISHED' && draft.publishedVersionId" size="small" type="primary" plain @click="emit('openDag', draft.publishedVersionId)">加入 V2 DAG</el-button>
        </div>
        <p v-if="draft.explanation">{{ draft.explanation }}</p>
        <el-alert v-if="currentError" :title="currentError" type="error" :closable="false" show-icon />
        <el-alert v-else-if="currentWarnings.length" :title="currentWarnings.join('；')" type="warning" :closable="false" show-icon />
      </div>

      <el-collapse v-if="draft" class="draft-details">
        <el-collapse-item title="查看原始草稿与整形规格" name="spec">
          <div class="spec-columns">
            <div><span>LLM 原始草稿</span><pre>{{ pretty(draft.rawSpec) }}</pre></div>
            <div><span>沙箱整形规格</span><pre>{{ pretty(draft.shapedSpec) }}</pre></div>
          </div>
        </el-collapse-item>
        <el-collapse-item v-if="drafts.length > 1" title="历史草稿" name="history">
          <button v-for="item in drafts" :key="item.id" type="button" class="history-item" @click="selectDraft(item)">
            <span>#{{ item.id }} {{ item.instructionText }}</span><el-tag size="small" effect="plain">{{ statusLabel(item.status) }}</el-tag>
          </button>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="preview-panel">
      <div v-if="previewView" class="preview-content">
        <div class="preview-badge">草稿预览 · 不写入正式线索</div>
        <OutputArtifactViewer :view="previewView" />
      </div>
      <el-empty v-else description="完成预览验证后，此处显示与正式运行一致的结果视图">
        <template #image><div class="empty-preview-icon">▦</div></template>
      </el-empty>
    </section>

    <el-dialog v-model="createDialog" title="新建输出算子" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="newOperator.name" placeholder="例如：逾期预测结果表" /></el-form-item>
        <el-form-item label="唯一编码"><el-input v-model="newOperator.code" placeholder="例如：overdue_result_view" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="newOperator.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy === 'create'" @click="createOutputOperator">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import OutputArtifactViewer from './OutputArtifactViewer.vue'
import {
  createOperator, fetchOperators, fetchOutputDrafts, generateOutputDraft,
  previewOutputDraft, publishOutputDraft, shapeOutputDraft, fetchRuntimeProfiles
} from '../api/orchestration.js'

const props = defineProps({
  conversationId: { type: Number, default: null },
  autoCreate: { type: Boolean, default: false }
})
const emit = defineEmits(['openDag'])

const operators = ref([])
const runtimeProfiles = ref([])
const runtimeProfileId = ref(null)
const operatorId = ref(null)
const drafts = ref([])
const draft = ref(null)
const instruction = ref('')
const busy = ref('')
const previewResult = ref(null)
const createDialog = ref(false)
const newOperator = reactive({ name: '', code: '', description: '' })
const sampleText = ref(`[
  {
    "orderId": "P-1001",
    "supplier": "甲供应商",
    "amount": 128000,
    "prediction": "DUPLICATE_PAYMENT",
    "predictionProbability": 0.92
  },
  {
    "orderId": "P-1002",
    "supplier": "乙供应商",
    "amount": 76000,
    "prediction": "NORMAL",
    "predictionProbability": 0.12
  }
]`)

const canShape = computed(() => draft.value && !['PUBLISHED', 'PENDING_APPROVAL', 'APPROVAL_REJECTED'].includes(draft.value.status))
const canPreview = computed(() => draft.value && ['SHAPED', 'PREVIEW_VALIDATED', 'PREVIEW_FAILED'].includes(draft.value.status))

function parseJson(value, fallback = {}) {
  if (!value) return fallback
  if (typeof value === 'object') return value
  try { return JSON.parse(value) } catch { return fallback }
}

const shapingReport = computed(() => parseJson(draft.value?.shapingReport))
const validationReport = computed(() => previewResult.value?.validationReport || parseJson(draft.value?.previewReport))
const currentError = computed(() => shapingReport.value?.error || validationReport.value?.errors?.[0] || '')
const currentWarnings = computed(() => validationReport.value?.warnings || shapingReport.value?.warnings || [])

const previewView = computed(() => {
  const preview = previewResult.value?.preview
  if (!preview?.rows) return null
  let columns = preview.columns || []
  if (!columns.length && preview.rows.length) {
    columns = Object.keys(preview.rows[0].display || {}).filter(key => typeof preview.rows[0].display[key] !== 'object')
      .map(field => ({ field, title: field }))
  }
  return {
    artifact: { outputKind: preview.outputKind, runId: '预览' },
    contentSpec: preview.contentSpec || {},
    summary: { renderer: preview.renderer },
    totalRows: preview.recordCount || preview.rows.length,
    page: 1,
    pageSize: preview.rows.length || 1,
    columns,
    rows: preview.rows
  }
})

async function loadOperators() {
  const all = await fetchOperators()
  operators.value = all.filter(item => item.operatorType === 'OUTPUT' && item.ownerUserId !== 'SYSTEM')
  if (!operatorId.value && operators.value.length) operatorId.value = operators.value[0].id
  if (operatorId.value) await loadDrafts()
}

async function loadDrafts() {
  previewResult.value = null
  if (!operatorId.value) { drafts.value = []; draft.value = null; return }
  drafts.value = await fetchOutputDrafts(operatorId.value)
  draft.value = drafts.value[0] || null
  if (draft.value) selectDraft(draft.value)
}

async function generate() {
  busy.value = 'generate'
  try {
    const records = sampleRecords(false)
    draft.value = await generateOutputDraft(operatorId.value, {
      instruction: instruction.value,
      conversationId: props.conversationId || undefined,
      inputSchema: inferSchema(records),
      sampleFields: inferFields(records)
    })
    drafts.value.unshift(draft.value)
    runtimeProfileId.value = null
    previewResult.value = null
    ElMessage.success('输出草稿已生成，请继续执行沙箱整形')
  } finally { busy.value = '' }
}

async function shape() {
  busy.value = 'shape'
  try {
    draft.value = await shapeOutputDraft(operatorId.value, draft.value.id, runtimeProfileId.value)
    replaceDraft(draft.value)
    previewResult.value = null
    if (draft.value.status === 'SHAPED') ElMessage.success('声明式整形通过')
    else ElMessage.error(parseJson(draft.value.shapingReport)?.error || '整形失败')
  } finally { busy.value = '' }
}

async function preview() {
  const records = sampleRecords(true)
  busy.value = 'preview'
  try {
    previewResult.value = await previewOutputDraft(operatorId.value, draft.value.id, records)
    draft.value = previewResult.value.draft
    replaceDraft(draft.value)
    if (previewResult.value.validationReport?.valid) ElMessage.success('预览验证通过，可以提交人工审批')
    else ElMessage.error('预览验证未通过，请检查字段和样例')
  } finally { busy.value = '' }
}

async function publish() {
  busy.value = 'publish'
  try {
    const version = await publishOutputDraft(operatorId.value, draft.value.id)
    draft.value = { ...draft.value, status: version.status === 'PUBLISHED' ? 'PUBLISHED' : 'PENDING_APPROVAL',
      candidateVersionId: version.id, publishedVersionId: version.status === 'PUBLISHED' ? version.id : null }
    replaceDraft(draft.value)
    ElMessage.success(version.status === 'PUBLISHED' ? `已复用发布版本 #${version.id}` : `候选版本 #${version.id} 已提交人工审批`)
  } finally { busy.value = '' }
}

async function createOutputOperator() {
  if (!newOperator.name.trim() || !/^[a-z][a-z0-9_-]{2,99}$/.test(newOperator.code.trim())) {
    ElMessage.warning('请填写名称，并使用合法的小写唯一编码')
    return
  }
  busy.value = 'create'
  try {
    const created = await createOperator({ ...newOperator, code: newOperator.code.trim(), operatorType: 'OUTPUT' })
    operators.value.unshift(created)
    operatorId.value = created.id
    drafts.value = []
    draft.value = null
    createDialog.value = false
    Object.assign(newOperator, { name: '', code: '', description: '' })
    ElMessage.success('输出算子已创建')
  } finally { busy.value = '' }
}

function sampleRecords(required) {
  try {
    const value = JSON.parse(sampleText.value)
    const records = Array.isArray(value) ? value : value?.records
    if (!Array.isArray(records) || (required && !records.length)) throw new Error('必须是非空JSON数组')
    return records || []
  } catch (error) {
    if (required) ElMessage.error('预览样例格式错误：' + error.message)
    if (required) throw error
    return []
  }
}

function inferFields(records) {
  const first = records[0] || {}
  return Object.fromEntries(Object.entries(first).map(([field, value]) => [field, Array.isArray(value) ? 'array' : typeof value]))
}
function inferSchema(records) {
  return { type: 'object', properties: Object.fromEntries(Object.entries(inferFields(records)).map(([field, type]) => [field, { type }])) }
}
function replaceDraft(value) {
  const index = drafts.value.findIndex(item => item.id === value.id)
  if (index >= 0) drafts.value.splice(index, 1, value)
}
function selectDraft(value) { draft.value = value; instruction.value = value.instructionText || ''; runtimeProfileId.value = parseJson(value.shapingReport)?.runtimeProfileId || null; previewResult.value = null }
function pretty(value) { return JSON.stringify(parseJson(value), null, 2) }
function statusLabel(status) { return ({ GENERATED: '草稿已生成', DEPENDENCY_MISSING: '缺少依赖', SHAPED: '整形通过', SHAPING_FAILED: '整形失败', PREVIEW_VALIDATED: '预览通过', PREVIEW_FAILED: '预览失败', PENDING_APPROVAL: '待人工审批', APPROVAL_REJECTED: '审批驳回', PUBLISHED: '已发布' }[status] || status) }
function statusType(status) { return ({ GENERATED: 'info', DEPENDENCY_MISSING: 'warning', SHAPED: 'primary', SHAPING_FAILED: 'danger', PREVIEW_VALIDATED: 'success', PREVIEW_FAILED: 'danger', PENDING_APPROVAL: 'warning', APPROVAL_REJECTED: 'danger', PUBLISHED: 'success' }[status] || 'info') }

onMounted(async () => {
  runtimeProfiles.value = await fetchRuntimeProfiles('OUTPUT_RENDERER')
  await loadOperators()
  if (props.autoCreate) createDialog.value = true
})
</script>

<style scoped>
.authoring-workbench { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(390px, 44%) minmax(0, 1fr); }
.authoring-panel { overflow-y: auto; padding: 18px 20px 28px; border-right: 1px solid var(--border-light); background: var(--surface); }
.preview-panel { min-width: 0; overflow: hidden; padding: 18px 20px; background: var(--bg); }
.section-heading { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; margin-bottom: 16px; }
.section-heading h3 { margin: 0 0 4px; font-size: var(--font-lg); }
.section-heading p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.authoring-form :deep(.el-form-item) { margin-bottom: 14px; }
.runtime-help { margin-top:5px; color:var(--text-muted); font-size:12px; }
.json-input :deep(textarea), pre { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.gate-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.gate-actions .el-button { margin: 0; }
.draft-status-card { margin-top: 14px; padding: 12px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--bg); }
.draft-status-line { display: flex; align-items: center; gap: 8px; }
.draft-status-line span { color: var(--text-muted); font-size: var(--font-sm); }
.draft-status-card p { margin: 8px 0 0; color: var(--text-secondary); font-size: var(--font-sm); }
.draft-status-card .el-alert { margin-top: 10px; }
.draft-details { margin-top: 12px; }
.spec-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.spec-columns span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: var(--font-xs); }
pre { max-height: 300px; overflow: auto; margin: 0; padding: 9px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; }
.history-item { width: 100%; display: flex; justify-content: space-between; gap: 10px; padding: 8px; border: 0; border-bottom: 1px solid var(--border-lighter); background: transparent; cursor: pointer; text-align: left; }
.history-item span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.preview-content { height: 100%; display: flex; flex-direction: column; }
.preview-badge { align-self: flex-start; margin-bottom: 8px; padding: 4px 9px; border-radius: 999px; color: #175cd3; background: #eff8ff; font-size: var(--font-xs); }
.empty-preview-icon { font-size: 64px; color: var(--border); }
@media (max-width: 1050px) { .authoring-workbench { grid-template-columns: 1fr; overflow-y: auto; } .authoring-panel { overflow: visible; border-right: 0; } .preview-panel { min-height: 620px; } }
</style>
