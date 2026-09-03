<template>
  <div class="rule-workbench">
    <section class="rule-panel">
      <div class="section-heading">
        <div><h3>对话创建规则算子</h3><p>不是拼装固定规则，而是在隔离 Python 运行时中生成和验证新规则能力。</p></div>
        <el-button size="small" @click="createDialog = true">新建规则算子</el-button>
      </div>

      <el-form label-position="top" class="rule-form">
        <el-form-item label="规则算子">
          <el-select v-model="operatorId" placeholder="选择规则算子" style="width:100%" @change="loadDrafts">
            <el-option v-for="item in operators" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="规则要求（对话指令）">
          <el-input v-model="instruction" type="textarea" :rows="5"
            placeholder="例如：按供应商、金额和付款账户识别 24 小时内的重复支付；输出所有可疑订单并追加命中原因、重复次数和风险等级。" />
        </el-form-item>
        <el-form-item label="Python 规则运行时">
          <el-select v-model="runtimeProfileId" clearable placeholder="使用平台默认运行时" style="width:100%">
            <el-option v-for="item in runtimeProfiles" :key="item.profile.id"
              :label="`${item.profile.name} · ${item.dependencies.length} 项扩展依赖`" :value="item.profile.id" />
          </el-select>
          <div class="runtime-help">缺少 Python 包时草稿会关闭运行；依赖审批和镜像构建完成后可选择新运行时重新测试。</div>
        </el-form-item>
        <el-form-item label="输入样例 records（用于推导 Schema，不在生成阶段执行）">
          <el-input v-model="sampleText" type="textarea" :rows="8" class="json-input" />
        </el-form-item>
      </el-form>

      <div class="gate-actions">
        <el-button type="primary" :loading="busy === 'generate'" :disabled="!operatorId || !instruction.trim()" @click="generate">
          1. 对话生成规则
        </el-button>
        <el-button :loading="busy === 'validate'" :disabled="!canValidate" @click="validate">
          2. 隔离沙箱测试
        </el-button>
        <el-button type="success" :loading="busy === 'submit'" :disabled="draft?.status !== 'VALIDATED'" @click="submitApproval">
          3. 创建版本并提交审批
        </el-button>
      </div>

      <div v-if="draft" class="draft-card">
        <div class="draft-line">
          <strong>草稿 #{{ draft.id }}</strong>
          <el-tag :type="statusType(draft.status)" effect="plain">{{ statusLabel(draft.status) }}</el-tag>
          <span v-if="draft.candidateVersionId">候选版本 #{{ draft.candidateVersionId }}</span>
          <el-button v-if="draft.status === 'PUBLISHED' && draft.candidateVersionId" size="small" type="primary" plain
            @click="emit('openDag', draft.candidateVersionId)">加入 V2 DAG</el-button>
        </div>
        <p v-if="draft.explanation">{{ draft.explanation }}</p>
        <el-alert v-if="validationError" :title="validationError" type="error" :closable="false" show-icon />
        <div v-if="validationSummary" class="validation-summary">{{ validationSummary }}</div>
      </div>

      <el-collapse v-if="draft" class="draft-details">
        <el-collapse-item title="生成的完整规则源码" name="source"><pre>{{ draft.sourceCode }}</pre></el-collapse-item>
        <el-collapse-item title="生成的正常与边界测试" name="tests"><pre>{{ pretty(draft.testCases) }}</pre></el-collapse-item>
        <el-collapse-item title="沙箱验证报告" name="report"><pre>{{ pretty(draft.validationReport) }}</pre></el-collapse-item>
        <el-collapse-item v-if="drafts.length > 1" title="历史草稿" name="history">
          <button v-for="item in drafts" :key="item.id" type="button" class="history-item" @click="selectDraft(item)">
            <span>#{{ item.id }} {{ item.instructionText }}</span>
            <el-tag size="small" effect="plain">{{ statusLabel(item.status) }}</el-tag>
          </button>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="contract-panel">
      <div class="contract-heading"><h3>规则契约</h3><el-tag type="warning" effect="plain">纯函数沙箱</el-tag></div>
      <div class="contract-flow">
        <div><strong>输入</strong><span>records + parameters</span></div><b>→</b>
        <div><strong>evaluate()</strong><span>隔离 Python 容器</span></div><b>→</b>
        <div><strong>输出</strong><span>records + 完整血缘</span></div>
      </div>
      <el-alert title="任何输出记录都必须保留 __sourceRefs 与 __sourceSnapshots；丢失血缘会导致测试失败。" type="info" :closable="false" show-icon />
      <div v-if="draft" class="schema-grid">
        <div><span>输入 Schema</span><pre>{{ pretty(draft.inputSchema) }}</pre></div>
        <div><span>输出 Schema</span><pre>{{ pretty(draft.outputSchema) }}</pre></div>
        <div><span>参数 Schema</span><pre>{{ pretty(draft.parameterSchema) }}</pre></div>
      </div>
      <el-empty v-else description="生成草稿后在此检查源码、测试与输入输出契约" />
    </section>

    <el-dialog v-model="createDialog" title="新建规则算子" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="newOperator.name" placeholder="例如：重复支付识别规则" /></el-form-item>
        <el-form-item label="唯一编码"><el-input v-model="newOperator.code" placeholder="例如 duplicate_payment_rule" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="newOperator.description" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createDialog = false">取消</el-button><el-button type="primary" :loading="busy === 'create'" @click="createRuleOperator">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createOperator, createRuleCandidate, fetchOperators, fetchRuleDrafts, fetchRuntimeProfiles,
  generateRuleDraft, validateRuleDraft
} from '../api/orchestration.js'

const props = defineProps({
  conversationId: { type: Number, default: null },
  autoCreate: { type: Boolean, default: false }
})
const emit = defineEmits(['openDag'])
const operators = ref([])
const operatorId = ref(null)
const drafts = ref([])
const draft = ref(null)
const runtimeProfiles = ref([])
const runtimeProfileId = ref(null)
const instruction = ref('')
const busy = ref('')
const createDialog = ref(false)
const newOperator = reactive({ name: '', code: '', description: '' })
const sampleText = ref(`[\n  {\n    "orderId": "P-1001",\n    "supplierId": "S-01",\n    "amount": 128000,\n    "paymentAccount": "6222...1001",\n    "paidAt": "2026-09-01T08:30:00Z"\n  }\n]`)

const report = computed(() => parseJson(draft.value?.validationReport))
const validationError = computed(() => report.value?.error || report.value?.errors?.[0] || '')
const validationSummary = computed(() => {
  if (!draft.value || !report.value?.valid) return ''
  const count = report.value.testCount ?? report.value.testsPassed ?? report.value.passedTests
  const time = report.value.executionTimeMs
  return ['隔离沙箱测试通过', count != null ? `${count} 个测试` : '', time != null ? `${time} ms` : ''].filter(Boolean).join(' · ')
})
const canValidate = computed(() => draft.value && ['GENERATED', 'VALIDATED', 'VALIDATION_FAILED', 'DEPENDENCY_MISSING'].includes(draft.value.status))

async function loadOperators() {
  const all = await fetchOperators()
  operators.value = all.filter(item => item.operatorType === 'RULE' && item.ownerUserId !== 'SYSTEM')
  if (!operatorId.value && operators.value.length) operatorId.value = operators.value[0].id
  await loadDrafts()
}

async function loadDrafts() {
  if (!operatorId.value) { drafts.value = []; draft.value = null; return }
  drafts.value = await fetchRuleDrafts(operatorId.value)
  selectDraft(drafts.value[0] || null)
}

async function generate() {
  let records
  try { records = sampleRecords() } catch (error) { ElMessage.error(error.message); return }
  busy.value = 'generate'
  try {
    const recordSchema = inferRecordSchema(records)
    draft.value = await generateRuleDraft(operatorId.value, {
      instruction: instruction.value, conversationId: props.conversationId || undefined,
      inputSchema: envelopeSchema(recordSchema), outputSchema: envelopeSchema({ type: 'object' })
    })
    drafts.value.unshift(draft.value); runtimeProfileId.value = null
    ElMessage.success('完整规则草稿已生成，源码尚未执行')
  } finally { busy.value = '' }
}

async function validate() {
  busy.value = 'validate'
  try {
    draft.value = await validateRuleDraft(operatorId.value, draft.value.id, runtimeProfileId.value)
    replaceDraft(draft.value)
    if (draft.value.status === 'VALIDATED') ElMessage.success('隔离沙箱与血缘测试通过')
    else ElMessage.error(parseJson(draft.value.validationReport)?.error || '沙箱测试未通过')
  } finally { busy.value = '' }
}

async function submitApproval() {
  busy.value = 'submit'
  try {
    const version = await createRuleCandidate(operatorId.value, draft.value.id)
    draft.value = { ...draft.value, candidateVersionId: version.id,
      status: version.status === 'PUBLISHED' ? 'PUBLISHED' : 'PENDING_APPROVAL' }
    replaceDraft(draft.value)
    ElMessage.success(version.status === 'PUBLISHED' ? `已复用发布版本 #${version.id}` : `候选版本 #${version.id} 已提交人工审批`)
  } finally { busy.value = '' }
}

async function createRuleOperator() {
  if (!newOperator.name.trim() || !/^[a-z][a-z0-9_-]{2,99}$/.test(newOperator.code.trim())) {
    ElMessage.warning('请填写名称，并使用合法的小写唯一编码'); return
  }
  busy.value = 'create'
  try {
    const created = await createOperator({ ...newOperator, code: newOperator.code.trim(), operatorType: 'RULE' })
    operators.value.unshift(created); operatorId.value = created.id; drafts.value = []; draft.value = null
    createDialog.value = false; Object.assign(newOperator, { name: '', code: '', description: '' })
    ElMessage.success('规则算子已创建')
  } finally { busy.value = '' }
}

function sampleRecords() {
  try {
    const value = JSON.parse(sampleText.value)
    if (!Array.isArray(value) || !value.length || value.some(item => !item || Array.isArray(item) || typeof item !== 'object')) {
      throw new Error('必须是非空 JSON 对象数组')
    }
    return value
  } catch (error) { throw new Error('输入样例格式错误：' + error.message) }
}
function inferRecordSchema(records) {
  const first = records[0] || {}
  return { type: 'object', properties: Object.fromEntries(Object.entries(first).map(([field, value]) => [field, { type: jsonType(value) }])) }
}
function envelopeSchema(itemSchema) { return { type: 'object', required: ['records'], properties: { records: { type: 'array', items: itemSchema } } } }
function jsonType(value) { if (value === null) return 'null'; if (Array.isArray(value)) return 'array'; if (Number.isInteger(value)) return 'integer'; if (typeof value === 'number') return 'number'; return typeof value }
function selectDraft(value) { draft.value = value; if (!value) return; instruction.value = value.instructionText || ''; runtimeProfileId.value = parseJson(value.validationReport)?.runtimeProfileId || null }
function replaceDraft(value) { const index = drafts.value.findIndex(item => item.id === value.id); if (index >= 0) drafts.value.splice(index, 1, value) }
function parseJson(value) { if (!value) return {}; if (typeof value === 'object') return value; try { return JSON.parse(value) } catch { return {} } }
function pretty(value) { return JSON.stringify(parseJson(value), null, 2) }
function statusLabel(status) { return ({ GENERATED: '草稿已生成', DEPENDENCY_MISSING: '缺少依赖', VALIDATED: '沙箱通过', VALIDATION_FAILED: '沙箱失败', PENDING_APPROVAL: '待人工审批', APPROVAL_REJECTED: '审批驳回', PUBLISHED: '已发布' }[status] || status) }
function statusType(status) { return ({ GENERATED: 'info', DEPENDENCY_MISSING: 'warning', VALIDATED: 'success', VALIDATION_FAILED: 'danger', PENDING_APPROVAL: 'warning', APPROVAL_REJECTED: 'danger', PUBLISHED: 'success' }[status] || 'info') }

onMounted(async () => {
  runtimeProfiles.value = await fetchRuntimeProfiles('RULE_PYTHON')
  await loadOperators()
  if (props.autoCreate) createDialog.value = true
})
</script>

<style scoped>
.rule-workbench { flex: 1; min-height: 0; display: grid; grid-template-columns: minmax(410px, 46%) minmax(0, 1fr); }
.rule-panel { overflow-y: auto; padding: 18px 20px 28px; border-right: 1px solid var(--border-light); background: var(--surface); }
.contract-panel { min-width: 0; overflow-y: auto; padding: 20px; background: var(--bg); }
.section-heading, .contract-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-heading h3, .contract-heading h3 { margin: 0 0 4px; font-size: var(--font-lg); }
.section-heading p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.rule-form :deep(.el-form-item) { margin-bottom: 14px; }
.runtime-help { margin-top: 5px; color: var(--text-muted); font-size: 12px; }
.json-input :deep(textarea), pre { font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.gate-actions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.gate-actions .el-button { height: auto; min-height: 32px; margin: 0; white-space: normal; }
.draft-card { margin-top: 14px; padding: 12px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--bg); }
.draft-line { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.draft-line span, .draft-card p { color: var(--text-muted); font-size: var(--font-sm); }
.draft-card p { margin: 8px 0 0; }
.draft-card .el-alert, .validation-summary { margin-top: 10px; }
.validation-summary { color: #067647; font-size: var(--font-sm); }
.draft-details { margin-top: 12px; }
pre { max-height: 420px; overflow: auto; margin: 0; padding: 10px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; }
.history-item { width: 100%; display: flex; justify-content: space-between; gap: 10px; padding: 8px; border: 0; border-bottom: 1px solid var(--border-lighter); background: transparent; cursor: pointer; text-align: left; }
.history-item span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.contract-flow { display: grid; grid-template-columns: 1fr auto 1fr auto 1fr; align-items: center; gap: 10px; margin-bottom: 16px; }
.contract-flow div { display: flex; flex-direction: column; gap: 5px; padding: 16px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--surface); text-align: center; }
.contract-flow span { color: var(--text-muted); font-size: 12px; }
.contract-flow b { color: var(--brand-primary); }
.schema-grid { display: grid; gap: 12px; margin-top: 18px; }
.schema-grid > div > span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: 12px; }
@media (max-width: 1100px) { .rule-workbench { grid-template-columns: 1fr; overflow-y: auto; } .rule-panel { overflow: visible; border-right: 0; } .contract-panel { min-height: 560px; } }
</style>
