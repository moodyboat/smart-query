<template>
  <div class="rule-workbench">
    <section class="rule-panel">
      <div class="section-heading">
        <div><h3>创建规则</h3></div>
        <el-button size="small" @click="createDialog = true">新建规则算子</el-button>
      </div>

      <el-form label-position="top" class="rule-form">
        <el-form-item label="规则算子">
          <el-select v-model="operatorId" placeholder="选择规则算子" style="width:100%" @change="loadDrafts">
            <el-option v-for="item in operators" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="希望它判断什么">
          <el-input v-model="instruction" type="textarea" :rows="5"
            placeholder="例如：按供应商、金额和付款账户识别 24 小时内的重复支付；输出所有可疑订单并追加命中原因、重复次数和风险等级。" />
        </el-form-item>
        <el-collapse class="advanced-settings">
          <el-collapse-item title="高级设置（通常无需修改）" name="advanced">
            <el-form-item label="执行环境">
              <el-select v-model="runtimeProfileId" clearable placeholder="使用平台推荐配置" style="width:100%">
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
        <el-button type="primary" :loading="busy === 'generate'" :disabled="!operatorId || !instruction.trim()" @click="generate">
          生成规则
        </el-button>
        <el-button :loading="busy === 'validate'" :disabled="!canValidate" @click="validate">
          检查规则
        </el-button>
        <el-button type="success" :loading="busy === 'submit'" :disabled="draft?.status !== 'VALIDATED'" @click="submitApproval">
          提交审批
        </el-button>
      </div>

      <div v-if="draft" class="draft-card">
        <div class="draft-line">
          <strong>方案 #{{ draft.id }}</strong>
          <el-tag :type="statusType(draft.status)" effect="plain">{{ statusLabel(draft.status) }}</el-tag>
          <span v-if="draft.candidateVersionId">候选版本 #{{ draft.candidateVersionId }}</span>
          <el-button v-if="draft.status === 'PUBLISHED' && draft.candidateVersionId" size="small" type="primary" plain
            @click="emit('openDag', draft.candidateVersionId)">加入流程编排</el-button>
        </div>
        <el-alert v-if="validationError" :title="validationError" type="error" :closable="false" show-icon />
      </div>

      <el-collapse v-if="draft" class="draft-details">
        <el-collapse-item v-if="drafts.length > 1" title="历史方案" name="history">
          <button v-for="item in drafts" :key="item.id" type="button" class="history-item" @click="selectDraft(item)">
            <span>#{{ item.id }} {{ item.instructionText }}</span>
            <el-tag size="small" effect="plain">{{ statusLabel(item.status) }}</el-tag>
          </button>
        </el-collapse-item>
      </el-collapse>
    </section>

    <section class="contract-panel">
      <div class="contract-heading"><h3>创建进度</h3><span>系统自动完成检查</span></div>
      <div class="simple-progress">
        <div :class="{ done: instruction.trim() }"><em>1</em><strong>描述判断要求</strong><span>{{ instruction.trim() ? '已填写' : '等待填写' }}</span></div>
        <div :class="{ done: draft }"><em>2</em><strong>生成规则方案</strong><span>{{ draft ? '已生成' : '等待生成' }}</span></div>
        <div :class="{ done: draft?.status === 'VALIDATED' || draft?.status === 'PENDING_APPROVAL' || draft?.status === 'PUBLISHED' }"><em>3</em><strong>平台自动检查</strong><span>{{ validationSummary || '等待检查' }}</span></div>
      </div>
      <div v-if="draft" class="rule-summary">
        <span>规则说明</span>
        <p>{{ draft.explanation || '规则方案已生成，检查通过后即可提交审批。' }}</p>
        <div class="automatic-guard"><strong>平台自动保障</strong><span>权限校验、运行隔离、来源追踪和审计记录无需手工配置</span></div>
        <el-collapse class="technical-details">
          <el-collapse-item title="技术详情（管理员查看）" name="technical">
            <div class="schema-grid">
              <div><span>规则实现</span><pre>{{ draft.sourceCode }}</pre></div>
              <div><span>自动测试</span><pre>{{ pretty(draft.testCases) }}</pre></div>
              <div><span>检查报告</span><pre>{{ pretty(draft.validationReport) }}</pre></div>
              <div><span>输入结构</span><pre>{{ pretty(draft.inputSchema) }}</pre></div>
              <div><span>输出结构</span><pre>{{ pretty(draft.outputSchema) }}</pre></div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
      <el-empty v-else description="填写判断要求后生成规则" />
    </section>

    <el-dialog v-model="createDialog" title="新建规则算子" width="460px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="newOperator.name" placeholder="例如：重复支付识别规则" /></el-form-item>
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
  return ['自动检查通过', count != null ? `${count} 项检查` : '', time != null ? `${time} ms` : ''].filter(Boolean).join(' · ')
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
    ElMessage.success('规则方案已生成，请继续检查')
  } finally { busy.value = '' }
}

async function validate() {
  busy.value = 'validate'
  try {
    draft.value = await validateRuleDraft(operatorId.value, draft.value.id, runtimeProfileId.value)
    replaceDraft(draft.value)
    if (draft.value.status === 'VALIDATED') ElMessage.success('平台自动检查通过')
    else ElMessage.error(parseJson(draft.value.validationReport)?.error || '检查未通过')
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
  if (!newOperator.name.trim()) { ElMessage.warning('请填写算子名称'); return }
  busy.value = 'create'
  try {
    const created = await createOperator({ ...newOperator, code: `rule_${Date.now().toString(36)}`, operatorType: 'RULE' })
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
function statusLabel(status) { return ({ GENERATED: '方案已生成', DEPENDENCY_MISSING: '平台配置待完善', VALIDATED: '检查通过', VALIDATION_FAILED: '检查未通过', PENDING_APPROVAL: '待人工审批', APPROVAL_REJECTED: '审批驳回', PUBLISHED: '已发布' }[status] || status) }
function statusType(status) { return ({ GENERATED: 'info', DEPENDENCY_MISSING: 'warning', VALIDATED: 'success', VALIDATION_FAILED: 'danger', PENDING_APPROVAL: 'warning', APPROVAL_REJECTED: 'danger', PUBLISHED: 'success' }[status] || 'info') }

onMounted(async () => {
  runtimeProfiles.value = await fetchRuntimeProfiles('RULE_PYTHON')
  await loadOperators()
  if (props.autoCreate) createDialog.value = true
})
</script>

<style scoped>
.rule-workbench { flex:1;min-height:0;display:grid;grid-template-columns:minmax(410px,46%) minmax(0,1fr);gap:12px;padding:12px;background:#f5f7fa; }
.rule-panel,.contract-panel { min-width:0;border:1px solid #e4e8ef;border-radius:10px;box-shadow:none; }
.rule-panel { overflow-y:auto;padding:18px 20px 28px;background:#fff; }
.contract-panel { overflow-y:auto;padding:20px;background:#fff; }
.section-heading, .contract-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-heading h3, .contract-heading h3 { margin:0;color:#26364a;font-size:17px;font-weight:670;letter-spacing:-.02em; }
.section-heading p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.rule-form :deep(.el-form-item) { margin-bottom: 14px; }
.advanced-settings { margin: 2px 0 14px; padding: 0 10px; border: 1px solid #e4e8ef; border-radius: 9px; background: #fafbfc; }
.advanced-settings :deep(.el-collapse-item__header) { height: 42px; color: #586579; font-size: 12px; }
.advanced-settings :deep(.el-collapse-item__wrap) { background: transparent; }
.json-input :deep(textarea), pre { font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.gate-actions { display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:7px;padding:8px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc; }
.gate-actions .el-button { height:auto;min-height:34px;min-width:0;margin:0;padding-inline:7px;white-space:normal;font-size:10.5px; }
.draft-card { margin-top:14px;padding:12px;border:1px solid #e4e8ef;border-radius:9px;background:#fafbfc; }
.draft-line { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.draft-line span, .draft-card p { color: var(--text-muted); font-size: var(--font-sm); }
.draft-card p { margin: 8px 0 0; }
.draft-card .el-alert, .validation-summary { margin-top: 10px; }
.validation-summary { color: #067647; font-size: var(--font-sm); }
.draft-details { margin-top:12px;padding:0 10px;border:1px solid var(--border-lighter);border-radius:12px;background:rgba(255,255,255,.6); }
pre { max-height: 420px; overflow: auto; margin: 0; padding: 10px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; }
.history-item { width: 100%; display: flex; justify-content: space-between; gap: 10px; padding: 8px; border: 0; border-bottom: 1px solid var(--border-lighter); background: transparent; cursor: pointer; text-align: left; }
.history-item span { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.contract-heading > span { color: #86909c; font-size: 11px; }
.simple-progress { display: grid; grid-template-columns: repeat(3, 1fr); gap: 9px; margin-bottom: 14px; }
.simple-progress > div { min-width: 0; display: grid; grid-template-columns: 26px 1fr; gap: 3px 9px; padding: 13px; border: 1px solid #e4e8ef; border-radius: 9px; background: #fafbfc; }
.simple-progress em { grid-row: 1 / 3; width: 26px; height: 26px; display: grid; place-items: center; border-radius: 7px; color: #758398; background: #edf0f5; font-size: 11px; font-style: normal; font-weight: 700; }
.simple-progress strong { color: #3f4d61; font-size: 12px; }
.simple-progress span { overflow: hidden; color: #9aa4b2; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.simple-progress > div.done { border-color: #cdddfd; background: #f7faff; }
.simple-progress > div.done em { color: #fff; background: #2468f2; }
.rule-summary { padding: 16px; border: 1px solid #e4e8ef; border-radius: 9px; background: #fff; }
.rule-summary > span { color: #86909c; font-size: 10px; }
.rule-summary > p { margin: 7px 0 14px; color: #3f4d61; font-size: 12px; line-height: 1.7; }
.automatic-guard { display: flex; align-items: center; gap: 12px; padding: 11px 12px; border-radius: 8px; background: #f5f7fa; }
.automatic-guard strong { color: #2468f2; font-size: 11px; }
.automatic-guard span { color: #758398; font-size: 10px; }
.technical-details { margin-top: 12px; }
.schema-grid { display: grid; gap: 12px; }
.schema-grid > div > span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: 12px; }
@media (max-width:1100px) { .rule-workbench{grid-template-columns:1fr;overflow-y:auto}.rule-panel{overflow:visible}.contract-panel{min-height:520px} }
@media (max-width:720px) { .simple-progress{grid-template-columns:1fr}.rule-workbench{padding:8px}.rule-panel,.contract-panel{padding:16px}.gate-actions{grid-template-columns:1fr}.automatic-guard{align-items:flex-start;flex-direction:column;gap:4px} }
</style>
