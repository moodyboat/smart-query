<template>
  <div class="policy-workbench">
    <section class="policy-panel">
      <div class="section-heading">
        <div>
          <h3>{{ props.lockOperatorType ? `${typeLabel}算子构建` : '数据加工 / 智能体策略算子' }}</h3>
          <p>权限范围先由平台固定，对话只负责生成范围内的可执行策略。</p>
        </div>
        <el-button size="small" @click="createDialog = true">新建{{ typeLabel }}算子</el-button>
      </div>

      <el-segmented v-if="!props.lockOperatorType" v-model="operatorType" :options="typeOptions" block @change="changeType" />

      <el-form label-position="top" class="policy-form">
        <el-form-item :label="`${typeLabel}算子`">
          <el-select v-model="operatorId" placeholder="选择算子" style="width:100%" @change="loadDrafts">
            <el-option v-for="item in filteredOperators" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="operatorType === 'DATA'" label="固定数据源与授权表">
          <div class="scope-row">
            <el-select v-model="dataSourceId" placeholder="选择数据源" filterable @change="loadTables">
              <el-option v-for="item in dataSources" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <el-select v-model="allowedTables" placeholder="选择允许查询的表" filterable multiple collapse-tags
              collapse-tags-tooltip :loading="loadingTables">
              <el-option v-for="item in tables" :key="tableName(item)" :label="tableLabel(item)" :value="tableName(item)" />
            </el-select>
          </div>
          <div class="scope-help">生成内容中的数据源和表名会被丢弃，以这里的选择为准。</div>
        </el-form-item>

        <template v-else>
          <el-form-item label="固定只读工具">
            <el-select v-model="allowedTools" placeholder="可不使用工具" filterable multiple style="width:100%">
              <el-option v-for="tool in tools" :key="tool.name" :value="tool.name"
                :label="`${tool.name}${tool.requireDatabase ? ' · 需数据库' : ''}`">
                <span>{{ tool.name }}</span><small class="tool-description">{{ tool.description }}</small>
              </el-option>
            </el-select>
            <div class="scope-help">目录只暴露已启用、只读且非破坏性的工具，发布后不能临时增加。</div>
          </el-form-item>
          <el-form-item v-if="needsDatabase" label="数据库工具固定范围">
            <div class="scope-row">
              <el-select v-model="dataSourceId" placeholder="选择数据源" filterable @change="loadTables">
                <el-option v-for="item in dataSources" :key="item.id" :label="item.name" :value="item.id" />
              </el-select>
              <el-select v-model="allowedTables" placeholder="选择授权表" filterable multiple collapse-tags
                collapse-tags-tooltip :loading="loadingTables">
                <el-option v-for="item in tables" :key="tableName(item)" :label="tableLabel(item)" :value="tableName(item)" />
              </el-select>
            </div>
          </el-form-item>
          <el-form-item label="生产运行模型">
            <el-input v-model="runtimeModel" placeholder="例如 glm-5.1；该值作为版本策略固定" />
          </el-form-item>
        </template>

        <el-form-item label="业务要求（对话指令）">
          <el-input v-model="instruction" type="textarea" :rows="4" :placeholder="instructionPlaceholder" />
        </el-form-item>

        <el-form-item label="固定运行时">
          <el-select v-model="runtimeProfileId" clearable placeholder="使用平台默认运行时" style="width:100%">
            <el-option v-for="item in runtimeProfiles" :key="item.profile.id"
              :label="`${item.profile.name} · ${item.profile.imageDigest.slice(0, 12)}`" :value="item.profile.id" />
          </el-select>
        </el-form-item>

        <el-form-item :label="operatorType === 'DATA' ? '预览参数（JSON 对象）' : '预览输入 records（JSON 数组）'">
          <el-input v-model="sampleText" type="textarea" :rows="7" class="json-input" />
        </el-form-item>
      </el-form>

      <div class="gate-actions">
        <el-button type="primary" :loading="busy === 'generate'" :disabled="!canGenerate" @click="generate">
          1. 对话生成草稿
        </el-button>
        <el-button :loading="busy === 'shape'" :disabled="!canShape" @click="shape">2. 策略整形</el-button>
        <el-button :loading="busy === 'preview'" :disabled="!canPreview" @click="preview">3. 真实预览</el-button>
        <el-button type="success" :loading="busy === 'publish'" :disabled="draft?.status !== 'PREVIEW_VALIDATED'" @click="publish">
          4. 提交人工审批
        </el-button>
      </div>

      <div v-if="draft" class="draft-card">
        <div class="draft-line">
          <strong>草稿 #{{ draft.id }}</strong>
          <el-tag :type="statusType(draft.status)" effect="plain">{{ statusLabel(draft.status) }}</el-tag>
          <span v-if="draft.candidateVersionId">候选版本 #{{ draft.candidateVersionId }}</span>
          <el-button v-if="draft.status === 'PUBLISHED' && draft.publishedVersionId" size="small" type="primary" plain
            @click="emit('openDag', draft.publishedVersionId)">加入 V2 DAG</el-button>
        </div>
        <p v-if="draft.explanation">{{ draft.explanation }}</p>
        <el-alert v-if="currentError" :title="currentError" type="error" :closable="false" show-icon />
        <div v-if="scopeSummary.length" class="scope-tags">
          <span>版本权限：</span><el-tag v-for="item in scopeSummary" :key="item" size="small" effect="plain">{{ item }}</el-tag>
        </div>
      </div>

      <el-collapse v-if="draft" class="draft-details">
        <el-collapse-item title="查看原始草稿与整形后策略" name="spec">
          <div class="spec-columns">
            <div><span>LLM 原始策略</span><pre>{{ pretty(draft.rawSpec) }}</pre></div>
            <div><span>服务端规范策略</span><pre>{{ pretty(draft.shapedSpec) }}</pre></div>
          </div>
        </el-collapse-item>
        <el-collapse-item v-if="drafts.length > 1" title="历史草稿" name="history">
          <button v-for="item in drafts" :key="item.id" type="button" class="history-item" @click="selectDraft(item)">
            <span>#{{ item.id }} {{ item.instructionText }}</span>
            <el-tag size="small" effect="plain">{{ statusLabel(item.status) }}</el-tag>
          </button>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="preview-panel">
      <div v-if="previewView" class="preview-content">
        <div class="preview-heading">
          <span>草稿预览 · 不写入正式线索</span>
          <el-tag v-if="previewMetrics" effect="plain">{{ previewMetrics }}</el-tag>
        </div>
        <OutputArtifactViewer :view="previewView" />
      </div>
      <el-empty v-else description="完成真实预览后，此处以 Excel 网格展示结果、原始输入和判断依据">
        <template #image><div class="empty-icon">▦</div></template>
      </el-empty>
    </section>

    <el-dialog v-model="createDialog" :title="`新建${typeLabel}算子`" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="newOperator.name" :placeholder="operatorType === 'DATA' ? '例如：重复支付订单查询' : '例如：逾期风险研判'" /></el-form-item>
        <el-form-item label="唯一编码"><el-input v-model="newOperator.code" placeholder="小写字母开头，例如 overdue_agent" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="newOperator.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="primary" :loading="busy === 'create'" @click="createPolicyOperator">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDataSources, fetchDataSourceTables } from '../api/index.js'
import OutputArtifactViewer from './OutputArtifactViewer.vue'
import {
  createOperator, fetchAgentTools, fetchOperators, fetchPolicyDrafts, fetchRuntimeProfiles,
  generatePolicyDraft, previewPolicyDraft, publishPolicyDraft, shapePolicyDraft
} from '../api/orchestration.js'

const props = defineProps({
  conversationId: { type: Number, default: null },
  initialOperatorType: { type: String, default: 'DATA' },
  lockOperatorType: { type: Boolean, default: false },
  autoCreate: { type: Boolean, default: false }
})
const emit = defineEmits(['openDag'])
const typeOptions = [{ label: '数据加工算子', value: 'DATA' }, { label: '受控智能体', value: 'AGENT' }]
const operatorType = ref(props.initialOperatorType === 'AGENT' ? 'AGENT' : 'DATA')
const operators = ref([])
const operatorId = ref(null)
const dataSources = ref([])
const dataSourceId = ref(null)
const tables = ref([])
const allowedTables = ref([])
const tools = ref([])
const allowedTools = ref([])
const runtimeModel = ref('glm-5.1')
const runtimeProfiles = ref([])
const runtimeProfileId = ref(null)
const instruction = ref('')
const drafts = ref([])
const draft = ref(null)
const previewResult = ref(null)
const loadingTables = ref(false)
const busy = ref('')
const createDialog = ref(false)
const newOperator = reactive({ name: '', code: '', description: '' })
const sampleText = ref('{}')

const typeLabel = computed(() => operatorType.value === 'DATA' ? '数据加工' : '智能体')
const filteredOperators = computed(() => operators.value.filter(item => item.operatorType === operatorType.value && item.ownerUserId !== 'SYSTEM'))
const selectedToolViews = computed(() => tools.value.filter(tool => allowedTools.value.includes(tool.name)))
const needsDatabase = computed(() => selectedToolViews.value.some(tool => tool.requireDatabase))
const canGenerate = computed(() => {
  if (!operatorId.value || !instruction.value.trim()) return false
  if (operatorType.value === 'DATA') return dataSourceId.value && allowedTables.value.length
  return runtimeModel.value.trim() && (!needsDatabase.value || (dataSourceId.value && allowedTables.value.length))
})
const canShape = computed(() => draft.value && !['PUBLISHED', 'PENDING_APPROVAL', 'APPROVAL_REJECTED'].includes(draft.value.status))
const canPreview = computed(() => draft.value && ['SHAPED', 'PREVIEW_VALIDATED', 'PREVIEW_FAILED'].includes(draft.value.status))
const instructionPlaceholder = computed(() => operatorType.value === 'DATA'
  ? '例如：查询同一供应商、相同金额且 24 小时内重复出现的支付订单，返回订单号、供应商、金额和时间。'
  : '例如：结合输入记录和授权查询工具判断逾期风险，在 agentDecision 字段给出结论与原因。')
const shapingReport = computed(() => parseJson(draft.value?.shapingReport))
const validationReport = computed(() => previewResult.value?.validationReport || parseJson(draft.value?.previewReport))
const currentError = computed(() => shapingReport.value?.error || validationReport.value?.errors?.[0] || '')
const scopeSummary = computed(() => {
  const spec = parseJson(draft.value?.shapedSpec)
  if (operatorType.value === 'DATA') return [spec.dataSourceId && `数据源 ${spec.dataSourceId}`, ...(spec.allowedTables || [])].filter(Boolean)
  return [spec.model && `模型 ${spec.model}`, ...(spec.allowedTools || []).map(item => `工具 ${item}`), ...(spec.allowedTables || []).map(item => `表 ${item}`)].filter(Boolean)
})
const previewMetrics = computed(() => {
  const metrics = previewResult.value?.preview?.metrics
  if (!metrics) return ''
  if (operatorType.value === 'DATA') return `${metrics.recordCount || 0} 行 · ${metrics.tables?.join(', ') || 'SQL'}`
  return `${metrics.recordCount || 0} 条 · ${metrics.toolCallCount || 0} 次工具调用 · ${metrics.tokenCount || 0} tokens`
})
const previewView = computed(() => {
  const preview = previewResult.value?.preview
  if (!preview?.rows) return null
  return {
    artifact: { outputKind: 'EXCEL', runId: '预览' },
    contentSpec: { ...(preview.contentSpec || {}), title: operatorType.value === 'DATA' ? 'SQL 查询预览' : '智能体研判预览' },
    summary: { renderer: 'excel-grid' }, totalRows: preview.recordCount || preview.rows.length,
    page: 1, pageSize: preview.rows.length || 1, columns: preview.columns || [], rows: preview.rows
  }
})

watch(needsDatabase, value => {
  if (!value && operatorType.value === 'AGENT') { dataSourceId.value = null; allowedTables.value = []; tables.value = [] }
})

async function changeType() {
  operatorId.value = filteredOperators.value[0]?.id || null
  dataSourceId.value = null; tables.value = []; allowedTables.value = []; allowedTools.value = []
  runtimeProfileId.value = null; draft.value = null; drafts.value = []; previewResult.value = null
  sampleText.value = operatorType.value === 'DATA' ? '{}' : defaultRecords()
  await Promise.all([loadProfiles(), loadDrafts()])
}

async function loadDrafts() {
  previewResult.value = null
  if (!operatorId.value) { drafts.value = []; draft.value = null; return }
  drafts.value = await fetchPolicyDrafts(operatorId.value)
  selectDraft(drafts.value[0] || null)
}

async function loadProfiles() {
  runtimeProfiles.value = await fetchRuntimeProfiles(operatorType.value === 'DATA' ? 'DATA_CONNECTOR' : 'AGENT_GATEWAY')
}

async function loadTables() {
  allowedTables.value = []
  if (!dataSourceId.value) { tables.value = []; return }
  loadingTables.value = true
  try { tables.value = await fetchDataSourceTables(dataSourceId.value) || [] }
  catch { tables.value = [] }
  finally { loadingTables.value = false }
}

async function generate() {
  let sample
  try { sample = parseSample() } catch (error) { ElMessage.error(error.message); return }
  busy.value = 'generate'
  try {
    const records = operatorType.value === 'AGENT' ? sample : []
    const request = {
      instruction: instruction.value, conversationId: props.conversationId || undefined,
      dataSourceId: dataSourceId.value || undefined, allowedTables: allowedTables.value,
      allowedTools: allowedTools.value, runtimeModel: runtimeModel.value,
      inputSchema: operatorType.value === 'AGENT' ? inferSchema(records) : { type: 'object' },
      sampleFields: inferFields(records)
    }
    draft.value = await generatePolicyDraft(operatorId.value, request)
    drafts.value.unshift(draft.value); runtimeProfileId.value = null; previewResult.value = null
    ElMessage.success('策略草稿已生成，权限范围已由服务端固定')
  } finally { busy.value = '' }
}

async function shape() {
  busy.value = 'shape'
  try {
    draft.value = await shapePolicyDraft(operatorId.value, draft.value.id, runtimeProfileId.value)
    replaceDraft(draft.value); previewResult.value = null
    if (draft.value.status === 'SHAPED') ElMessage.success('策略整形与运行时校验通过')
    else ElMessage.error(parseJson(draft.value.shapingReport)?.error || '策略整形失败')
  } finally { busy.value = '' }
}

async function preview() {
  let sample
  try { sample = parseSample() } catch (error) { ElMessage.error(error.message); return }
  busy.value = 'preview'
  try {
    const request = operatorType.value === 'DATA' ? { parameters: sample } : { records: sample }
    previewResult.value = await previewPolicyDraft(operatorId.value, draft.value.id, request)
    draft.value = previewResult.value.draft; replaceDraft(draft.value)
    if (previewResult.value.validationReport?.valid) ElMessage.success('真实运行时预览通过，可以提交人工审批')
    else ElMessage.error(previewResult.value.validationReport?.errors?.[0] || '预览失败')
  } finally { busy.value = '' }
}

async function publish() {
  busy.value = 'publish'
  try {
    const version = await publishPolicyDraft(operatorId.value, draft.value.id)
    draft.value = { ...draft.value, status: version.status === 'PUBLISHED' ? 'PUBLISHED' : 'PENDING_APPROVAL',
      candidateVersionId: version.id, publishedVersionId: version.status === 'PUBLISHED' ? version.id : null }
    replaceDraft(draft.value)
    ElMessage.success(version.status === 'PUBLISHED' ? `已复用发布版本 #${version.id}` : `候选版本 #${version.id} 已提交人工审批`)
  } finally { busy.value = '' }
}

async function createPolicyOperator() {
  if (!newOperator.name.trim() || !/^[a-z][a-z0-9_-]{2,99}$/.test(newOperator.code.trim())) {
    ElMessage.warning('请填写名称，并使用合法的小写唯一编码'); return
  }
  busy.value = 'create'
  try {
    const created = await createOperator({ ...newOperator, code: newOperator.code.trim(), operatorType: operatorType.value })
    operators.value.unshift(created); operatorId.value = created.id; drafts.value = []; draft.value = null
    createDialog.value = false; Object.assign(newOperator, { name: '', code: '', description: '' })
    ElMessage.success(`${typeLabel.value}算子已创建`)
  } finally { busy.value = '' }
}

function parseSample() {
  try {
    const value = JSON.parse(sampleText.value)
    if (operatorType.value === 'DATA' && (!value || Array.isArray(value) || typeof value !== 'object')) throw new Error('SQL 预览参数必须是 JSON 对象')
    if (operatorType.value === 'AGENT' && (!Array.isArray(value) || !value.length || value.some(item => !item || Array.isArray(item) || typeof item !== 'object'))) {
      throw new Error('智能体预览输入必须是非空 JSON 对象数组')
    }
    return value
  } catch (error) { throw new Error('预览样例格式错误：' + error.message) }
}

function selectDraft(value) {
  draft.value = value
  if (!value) return
  instruction.value = value.instructionText || ''
  runtimeProfileId.value = parseJson(value.shapingReport)?.runtimeProfileId || null
  const stored = parseJson(value.previewData)
  previewResult.value = stored?.rows ? { preview: stored, validationReport: parseJson(value.previewReport) } : null
}
function replaceDraft(value) { const index = drafts.value.findIndex(item => item.id === value.id); if (index >= 0) drafts.value.splice(index, 1, value) }
function parseJson(value) { if (!value) return {}; if (typeof value === 'object') return value; try { return JSON.parse(value) } catch { return {} } }
function pretty(value) { return JSON.stringify(parseJson(value), null, 2) }
function tableName(item) { return typeof item === 'string' ? item : item.name || item.tableName || item.TABLE_NAME || '' }
function tableLabel(item) { return typeof item === 'string' ? item : item.comment ? `${tableName(item)} · ${item.comment}` : tableName(item) }
function inferFields(records) { const first = records?.[0] || {}; return Object.fromEntries(Object.entries(first).map(([key, value]) => [key, Array.isArray(value) ? 'array' : typeof value])) }
function inferSchema(records) { return { type: 'object', properties: { records: { type: 'array', items: { type: 'object', properties: Object.fromEntries(Object.entries(inferFields(records)).map(([key, type]) => [key, { type }])) } } } } }
function statusLabel(status) { return ({ GENERATED: '草稿已生成', DEPENDENCY_MISSING: '缺少依赖', SHAPED: '整形通过', SHAPING_FAILED: '整形失败', PREVIEW_VALIDATED: '预览通过', PREVIEW_FAILED: '预览失败', PENDING_APPROVAL: '待人工审批', APPROVAL_REJECTED: '审批驳回', PUBLISHED: '已发布' }[status] || status) }
function statusType(status) { return ({ GENERATED: 'info', DEPENDENCY_MISSING: 'warning', SHAPED: 'primary', SHAPING_FAILED: 'danger', PREVIEW_VALIDATED: 'success', PREVIEW_FAILED: 'danger', PENDING_APPROVAL: 'warning', APPROVAL_REJECTED: 'danger', PUBLISHED: 'success' }[status] || 'info') }
function defaultRecords() { return `[\n  {\n    "customerId": "C-1001",\n    "contractAmount": 128000,\n    "overdueProbability": 0.86,\n    "paymentHistory": "近三期有两期延迟"\n  }\n]` }

onMounted(async () => {
  const [allOperators, allSources, allTools] = await Promise.all([fetchOperators(), fetchDataSources(), fetchAgentTools()])
  operators.value = allOperators; dataSources.value = (allSources || []).filter(item => item.forQuestionAnswering !== false && item.status !== 0)
  tools.value = allTools; operatorId.value = filteredOperators.value[0]?.id || null
  await Promise.all([loadProfiles(), loadDrafts()])
  if (props.autoCreate) createDialog.value = true
})
</script>

<style scoped>
.policy-workbench { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(410px, 46%) minmax(0, 1fr); }
.policy-panel { overflow-y: auto; padding: 18px 20px 28px; border-right: 1px solid var(--border-light); background: var(--surface); }
.preview-panel { min-width: 0; overflow: hidden; padding: 18px 20px; background: var(--bg); }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.section-heading h3 { margin: 0 0 4px; font-size: var(--font-lg); }
.section-heading p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.policy-form { margin-top: 16px; }
.policy-form :deep(.el-form-item) { margin-bottom: 14px; }
.scope-row { width: 100%; display: grid; grid-template-columns: minmax(120px, .8fr) minmax(180px, 1.2fr); gap: 8px; }
.scope-help { margin-top: 5px; color: var(--text-muted); font-size: 12px; }
.tool-description { float: right; max-width: 220px; margin-left: 20px; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; }
.json-input :deep(textarea), pre { font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.gate-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.gate-actions .el-button { margin: 0; }
.draft-card { margin-top: 14px; padding: 12px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--bg); }
.draft-line { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.draft-line span, .draft-card p { color: var(--text-muted); font-size: var(--font-sm); }
.draft-card p { margin: 8px 0 0; }
.draft-card .el-alert { margin-top: 10px; }
.scope-tags { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; margin-top: 10px; font-size: 12px; color: var(--text-muted); }
.draft-details { margin-top: 12px; }
.spec-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.spec-columns span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: var(--font-xs); }
pre { max-height: 300px; overflow: auto; margin: 0; padding: 9px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; }
.history-item { width: 100%; display: flex; justify-content: space-between; gap: 10px; padding: 8px; border: 0; border-bottom: 1px solid var(--border-lighter); background: transparent; cursor: pointer; text-align: left; }
.history-item span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.preview-content { height: 100%; display: flex; flex-direction: column; }
.preview-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; color: #175cd3; font-size: var(--font-xs); }
.preview-heading > span { padding: 4px 9px; border-radius: 999px; background: #eff8ff; }
.empty-icon { font-size: 64px; color: var(--border); }
@media (max-width: 1100px) { .policy-workbench { grid-template-columns: 1fr; overflow-y: auto; } .policy-panel { overflow: visible; border-right: 0; } .preview-panel { min-height: 620px; } }
</style>
