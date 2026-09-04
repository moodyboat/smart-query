<template>
  <div class="authoring-workbench">
    <section class="authoring-panel">
      <div class="section-heading">
        <div><h3>设置结果去向</h3></div>
        <el-button size="small" @click="createDialog = true">新建输出算子</el-button>
      </div>

      <div class="governance-strip">
        <strong>平台自动保障</strong><span>权限、审计和来源追踪无需手工配置</span>
      </div>

      <el-form label-position="top" class="authoring-form">
        <el-form-item label="输出算子">
          <el-select v-model="operatorId" placeholder="选择输出算子" style="width: 100%" @change="loadDrafts">
            <el-option v-for="operator in operators" :key="operator.id" :label="operator.name" :value="operator.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="输出能力">
          <div class="capability-groups">
            <div v-for="group in capabilityGroups" :key="group.type" class="capability-group">
              <div class="capability-group-head"><strong>{{ capabilityTypeLabel(group.type) }}</strong><span>{{ group.items.length }}</span></div>
              <label v-for="item in group.items" :key="item.code"
                :class="['capability-card', { selected: selectedCapabilityCodes.includes(item.code) }]">
                <el-checkbox v-model="selectedCapabilityCodes" :value="item.code" />
                <span class="capability-copy"><strong>{{ item.name }}</strong></span>
              </label>
            </div>
          </div>
          <div class="selection-summary">已选 {{ selectedCapabilityCodes.length }} 项，其中 {{ selectedTargetCount }} 个结果目标</div>
        </el-form-item>
        <el-form-item label="希望如何使用结果">
          <el-input v-model="instruction" type="textarea" :rows="5"
            placeholder="例如：先按预测概率降序，保存平台制品，同时生成风险趋势图、XLSX 文件和风险线索；每条结果都保留原始输入与判断依据。" />
        </el-form-item>
        <el-collapse class="advanced-settings">
          <el-collapse-item title="高级设置（通常无需修改）" name="advanced">
            <el-form-item label="执行环境">
              <el-select v-model="runtimeProfileId" placeholder="使用平台推荐配置" clearable style="width:100%">
                <el-option v-for="item in runtimeProfiles" :key="item.profile.id"
                  :label="item.profile.name" :value="item.profile.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="试用数据">
              <el-input v-model="sampleText" type="textarea" :rows="8" class="json-input" />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>

      <div class="gate-actions">
        <el-button type="primary" :loading="busy === 'generate'" :disabled="!canGenerate" @click="generate">
          生成方案
        </el-button>
        <el-button :loading="busy === 'shape'" :disabled="!canShape" @click="shape">
          检查方案
        </el-button>
        <el-button :loading="busy === 'preview'" :disabled="!canPreview" @click="preview">
          查看效果
        </el-button>
        <el-button type="success" :loading="busy === 'publish'" :disabled="draft?.status !== 'PREVIEW_VALIDATED'" @click="publish">
          提交审批
        </el-button>
      </div>

      <div v-if="draft" class="draft-status-card">
        <div class="draft-status-line">
          <strong>方案 #{{ draft.id }}</strong>
          <el-tag :type="statusType(draft.status)" effect="plain">{{ statusLabel(draft.status) }}</el-tag>
          <span v-if="draft.candidateVersionId">候选版本 #{{ draft.candidateVersionId }}</span>
          <el-button v-if="draft.status === 'PUBLISHED' && draft.publishedVersionId" size="small" type="primary" plain @click="emit('openDag', draft.publishedVersionId)">加入流程编排</el-button>
        </div>
        <p v-if="draft.explanation">{{ draft.explanation }}</p>
        <el-alert v-if="currentError" :title="currentError" type="error" :closable="false" show-icon />
        <el-alert v-else-if="currentWarnings.length" :title="currentWarnings.join('；')" type="warning" :closable="false" show-icon />
      </div>

      <el-collapse v-if="draft" class="draft-details">
        <el-collapse-item title="技术详情（管理员查看）" name="spec">
          <div class="spec-columns">
            <div><span>原始方案</span><pre>{{ pretty(draft.rawSpec) }}</pre></div>
            <div><span>平台处理结果</span><pre>{{ pretty(draft.shapedSpec) }}</pre></div>
          </div>
        </el-collapse-item>
        <el-collapse-item v-if="drafts.length > 1" title="历史方案" name="history">
          <button v-for="item in drafts" :key="item.id" type="button" class="history-item" @click="selectDraft(item)">
            <span>#{{ item.id }} {{ item.instructionText }}</span><el-tag size="small" effect="plain">{{ statusLabel(item.status) }}</el-tag>
          </button>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="preview-panel">
      <div v-if="previewView" class="preview-content">
        <div class="preview-toolbar">
          <div class="preview-badge">试用结果 · 不保存正式数据</div>
          <el-select v-if="previewViews.length > 1" v-model="activePreviewTarget" size="small" style="width:240px">
            <el-option v-for="item in previewViews" :key="item.targetId" :value="item.targetId"
              :label="outputKindLabel(item.outputKind)" />
          </el-select>
        </div>
        <OutputArtifactViewer :view="previewView" />
      </div>
      <el-empty v-else description="暂无预览结果">
        <template #image><div class="empty-preview-icon">▦</div></template>
      </el-empty>
    </section>

    <el-dialog v-model="createDialog" title="新建输出算子" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="newOperator.name" placeholder="例如：逾期预测结果表" /></el-form-item>
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
  previewOutputDraft, publishOutputDraft, shapeOutputDraft, fetchRuntimeProfiles,
  fetchOutputCapabilities
} from '../api/orchestration.js'

const props = defineProps({
  conversationId: { type: Number, default: null },
  autoCreate: { type: Boolean, default: false }
})
const emit = defineEmits(['openDag'])

const operators = ref([])
const runtimeProfiles = ref([])
const runtimeProfileId = ref(null)
const capabilities = ref([])
const selectedCapabilityCodes = ref([])
const operatorId = ref(null)
const drafts = ref([])
const draft = ref(null)
const instruction = ref('')
const busy = ref('')
const previewResult = ref(null)
const activePreviewTarget = ref('')
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

const capabilityGroups = computed(() => ['TRANSFORM', 'PERSIST', 'VIEW', 'EXPORT', 'ACTION']
  .map(type => ({ type, items: capabilities.value.filter(item => item.capabilityType === type) }))
  .filter(group => group.items.length))
const selectedTargetCount = computed(() => capabilities.value
  .filter(item => selectedCapabilityCodes.value.includes(item.code) && item.capabilityType !== 'TRANSFORM').length)
const canGenerate = computed(() => operatorId.value && instruction.value.trim() && selectedTargetCount.value > 0)
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

const previewViews = computed(() => {
  const preview = previewResult.value?.preview
  if (!preview) return []
  const targets = preview.targetViews?.length ? preview.targetViews : [preview]
  return targets.filter(item => item.rows).map((item, index) => {
    let columns = item.columns || []
    if (!columns.length && item.rows.length) {
      columns = Object.keys(item.rows[0].display || {}).filter(key => typeof item.rows[0].display[key] !== 'object')
      .map(field => ({ field, title: field }))
    }
    return {
      targetId: item.targetId || `preview-${index}`,
      capabilityCode: item.capabilityCode || item.outputKind,
      outputKind: item.outputKind,
      artifact: { outputKind: item.outputKind, runId: '预览' },
      contentSpec: item.contentSpec || {}, summary: { renderer: item.renderer },
      totalRows: item.recordCount || item.rows.length, page: 1,
      pageSize: item.rows.length || 1, columns, rows: item.rows
    }
  })
})
const previewView = computed(() => previewViews.value.find(item => item.targetId === activePreviewTarget.value)
  || previewViews.value[0] || null)

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
      selectedCapabilityCodes: selectedCapabilityCodes.value,
      inputSchema: inferSchema(records),
      sampleFields: inferFields(records)
    })
    drafts.value.unshift(draft.value)
    runtimeProfileId.value = null
    previewResult.value = null
    ElMessage.success('输出方案已生成，请继续检查')
  } finally { busy.value = '' }
}

async function shape() {
  busy.value = 'shape'
  try {
    draft.value = await shapeOutputDraft(operatorId.value, draft.value.id, runtimeProfileId.value)
    replaceDraft(draft.value)
    previewResult.value = null
    if (draft.value.status === 'SHAPED') ElMessage.success('平台自动检查通过')
    else ElMessage.error(parseJson(draft.value.shapingReport)?.error || '方案检查未通过')
  } finally { busy.value = '' }
}

async function preview() {
  const records = sampleRecords(true)
  busy.value = 'preview'
  try {
    previewResult.value = await previewOutputDraft(operatorId.value, draft.value.id, records)
    activePreviewTarget.value = previewResult.value?.preview?.targetViews?.[0]?.targetId || 'preview-0'
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
  if (!newOperator.name.trim()) { ElMessage.warning('请填写算子名称'); return }
  busy.value = 'create'
  try {
    const created = await createOperator({ ...newOperator, code: `output_${Date.now().toString(36)}`, operatorType: 'OUTPUT' })
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
function statusLabel(status) { return ({ GENERATED: '方案已生成', DEPENDENCY_MISSING: '平台配置待完善', SHAPED: '检查通过', SHAPING_FAILED: '检查未通过', PREVIEW_VALIDATED: '试用通过', PREVIEW_FAILED: '试用未通过', PENDING_APPROVAL: '待人工审批', APPROVAL_REJECTED: '审批驳回', PUBLISHED: '已发布' }[status] || status) }
function statusType(status) { return ({ GENERATED: 'info', DEPENDENCY_MISSING: 'warning', SHAPED: 'primary', SHAPING_FAILED: 'danger', PREVIEW_VALIDATED: 'success', PREVIEW_FAILED: 'danger', PENDING_APPROVAL: 'warning', APPROVAL_REJECTED: 'danger', PUBLISHED: 'success' }[status] || 'info') }
function capabilityTypeLabel(type) { return ({ TRANSFORM: '可选转换', PERSIST: '持久化保存', VIEW: '前端展示', EXPORT: '固定格式导出', ACTION: '业务动作' }[type] || type) }
function implementationLabel(type) { return ({
  PROJECT: '字段处理', RUN_ARTIFACT: '运行制品', TEMP_ARTIFACT: '临时结果',
  COMPOSED_PAGE: '组合页面', ECHARTS: '通用图表', TABLE: '数据表',
  CSV: '表格文件', JSON: '结构数据', PDF: '文档', PNG: '图片', XLSX: '工作簿', LEAD: '业务线索'
}[type] || '平台能力') }
function outputKindLabel(type) { return ({
  RUN_ARTIFACT: '平台运行制品', TEMP_ARTIFACT: '临时结果', COMPOSED_PAGE: '组合页面',
  ECHARTS: '通用图表', TABLE: '数据表', CSV: '表格文件', JSON: '结构数据',
  PDF: '文档', PNG: '图片', XLSX: '工作簿', LEAD: '业务线索'
}[type] || '预览结果') }

onMounted(async () => {
  const [profiles, registry] = await Promise.all([
    fetchRuntimeProfiles('OUTPUT_RENDERER'), fetchOutputCapabilities(false)
  ])
  runtimeProfiles.value = profiles
  capabilities.value = registry
  const defaultTarget = registry.find(item => item.code === 'view.table') || registry.find(item => item.capabilityType !== 'TRANSFORM')
  if (defaultTarget) selectedCapabilityCodes.value = [defaultTarget.code]
  await loadOperators()
  if (props.autoCreate) createDialog.value = true
})
</script>

<style scoped>
.authoring-workbench { flex:1;min-height:0;display:grid;grid-template-columns:minmax(410px,45%) minmax(0,1fr);gap:12px;padding:12px;background:#f5f7fa; }
.authoring-panel,
.preview-panel { min-width:0;border:1px solid #e4e8ef;border-radius:10px;box-shadow:none; }
.authoring-panel { overflow-y:auto;padding:18px 20px 28px;background:#fff; }
.preview-panel { overflow:hidden;padding:18px 20px;background:#fff; }
.section-heading { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; margin-bottom: 16px; }
.section-heading h3 { margin: 0; color:#26364a; font-size: 17px; font-weight: 670; letter-spacing: -.02em; }
.section-heading p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.governance-strip { display:flex;align-items:center;gap:6px;margin:-2px 0 16px;padding:7px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc;color:#526b8c;font-size:10.5px; }
.governance-strip span { color:#758398; }
.governance-strip strong { padding:4px 8px;border-radius:6px;color:#175cb5;background:#edf3ff;font-size:10.5px; }
.authoring-form :deep(.el-form-item) { margin-bottom: 14px; }
.advanced-settings { margin: 2px 0 14px; padding: 0 10px; border: 1px solid #e4e8ef; border-radius: 9px; background: #fafbfc; }
.advanced-settings :deep(.el-collapse-item__header) { height: 42px; color: #586579; font-size: 12px; }
.advanced-settings :deep(.el-collapse-item__wrap) { background: transparent; }
.capability-groups { width:100%;display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px; }
.capability-group { min-width:0;padding:9px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc; }
.capability-group-head { display:flex;align-items:center;justify-content:space-between;margin:0 3px 7px;color:#58677b;font-size:11px; }
.capability-group-head strong { font-weight:650; }
.capability-group-head span { min-width:19px;height:19px;display:grid;place-items:center;border-radius:10px;color:#718096;background:#edf1f6;font-size:9px; }
.capability-card { position:relative;min-width:0;display:flex;align-items:center;gap:7px;padding:8px 7px;border:1px solid transparent;border-radius:9px;cursor:pointer;transition:border-color .15s ease,background .15s ease,box-shadow .15s ease,transform .15s ease; }
.capability-card:hover { border-color:#c8d8ee;background:#fff;transform:none; }
.capability-card.selected { border-color:#a9c9f2;background:#fff;box-shadow:none; }
.capability-card.selected::before { content:'';position:absolute;inset:7px auto 7px -1px;width:3px;border-radius:3px;background:#2468f2; }
.capability-card :deep(.el-checkbox__label) { display:none; }
.capability-copy { min-width:0;flex:1;display:flex;flex-direction:column;gap:1px; }
.capability-copy strong { overflow:hidden;color:#2e4058;font-size:11px;text-overflow:ellipsis;white-space:nowrap; }
.capability-copy small { overflow:hidden;color:#8492a6;font-size:9px;text-overflow:ellipsis;white-space:nowrap; }
.capability-card .el-tag { max-width:82px;overflow:hidden;color:#5b7090;background:#edf3fb;font-size:9px; }
.selection-summary { width:100%;margin-top:7px;padding:7px 9px;border-radius:8px;color:#46658c;background:#f2f6fc;font-size:10.5px; }
.json-input :deep(textarea), pre { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 12px; }
.gate-actions { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:7px;padding:8px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc; }
.gate-actions .el-button { min-width:0;margin: 0;padding-inline:7px;font-size:10.5px; }
.draft-status-card { margin-top:14px;padding:12px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc; }
.draft-status-line { display: flex; align-items: center; gap: 8px; }
.draft-status-line span { color: var(--text-muted); font-size: var(--font-sm); }
.draft-status-card p { margin: 8px 0 0; color: var(--text-secondary); font-size: var(--font-sm); }
.draft-status-card .el-alert { margin-top: 10px; }
.draft-details { margin-top:12px;padding:0 10px;border:1px solid var(--border-lighter);border-radius:9px;background:#fff; }
.spec-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.spec-columns span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: var(--font-xs); }
pre { max-height: 300px; overflow: auto; margin: 0; padding: 9px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; }
.history-item { width: 100%; display: flex; justify-content: space-between; gap: 10px; padding: 8px; border: 0; border-bottom: 1px solid var(--border-lighter); background: transparent; cursor: pointer; text-align: left; }
.history-item span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.preview-content { height: 100%; display: flex; flex-direction: column; }
.preview-toolbar { display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px; }
.preview-badge { align-self: flex-start; margin-bottom: 8px; padding: 5px 10px; border:1px solid #dbeafe;border-radius: 999px; color: #175cd3; background: #eff8ff; font-size: var(--font-xs); }
.empty-preview-icon { width:64px;height:64px;display:grid;place-items:center;margin:auto;border:1px solid #e4e8ef;border-radius:10px;color:#8ba3bf;background:#fafbfc;box-shadow:none;font-size:30px; }
@media (max-width: 1360px) { .gate-actions{grid-template-columns:repeat(2,minmax(0,1fr));} }
@media (max-width: 1180px) { .authoring-workbench { grid-template-columns:minmax(360px,48%) minmax(0,1fr); }.capability-groups{grid-template-columns:1fr;} }
@media (max-width: 900px) { .authoring-workbench { grid-template-columns: 1fr; overflow-y: auto; } .authoring-panel { overflow: visible; } .preview-panel { min-height: 520px; }.capability-groups{grid-template-columns:repeat(2,minmax(0,1fr));} }
@media (max-width: 560px) { .authoring-workbench{padding:8px}.authoring-panel,.preview-panel{padding:16px}.capability-groups{grid-template-columns:1fr}.gate-actions{grid-template-columns:1fr}.preview-toolbar{align-items:stretch;flex-direction:column}.preview-toolbar .el-select{width:100%!important}.governance-strip{align-items:flex-start;flex-direction:column} }
</style>
