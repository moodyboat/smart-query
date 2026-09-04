<template>
  <div :class="['page-container', 'output-center', { embedded: props.embedded }]">
    <div class="page-header">
      <button v-if="!props.embedded" class="back-btn" @click="$emit('close')"><span class="back-arrow">&larr;</span> 返回问数</button>
      <div class="output-heading"><h2 class="page-title">运行结果</h2><p v-if="props.embedded">统一查看线索、图表和类 Excel 结果，并追溯每条结果的原始输入。</p></div>
      <el-radio-group v-if="!props.embedded" v-model="activeMode" size="small">
        <el-radio-button value="results">运行结果</el-radio-button>
        <el-radio-button value="rule">规则设计</el-radio-button>
        <el-radio-button value="authoring">输出设计</el-radio-button>
        <el-radio-button value="policy">SQL / 智能体</el-radio-button>
        <el-radio-button value="approval">版本审批</el-radio-button>
        <el-radio-button v-if="user.canManageRuntime" value="storage">存储与运行</el-radio-button>
        <el-radio-button v-if="user.canManageRuntime" value="capabilities">能力目录</el-radio-button>
      </el-radio-group>
      <el-select v-if="activeMode === 'results'" v-model="kindFilter" clearable placeholder="全部输出" size="small" style="width: 150px" @change="loadOutputs">
        <el-option label="线索" value="LEAD" />
        <el-option label="图表" value="CHART" />
        <el-option label="数据表" value="TABLE" />
        <el-option label="Excel 表格视图" value="EXCEL" />
        <el-option label="组合页面" value="DASHBOARD" />
        <el-option label="平台运行制品" value="ARTIFACT" />
        <el-option label="临时结果" value="TEMP_RESULT" />
        <el-option label="XLSX 导出" value="EXPORT_XLSX" />
        <el-option label="CSV 导出" value="EXPORT_CSV" />
        <el-option label="PDF 导出" value="EXPORT_PDF" />
        <el-option label="JSON 导出" value="EXPORT_JSON" />
        <el-option label="PNG 导出" value="EXPORT_PNG" />
      </el-select>
      <el-button v-if="activeMode === 'results'" size="small" :loading="loadingList" @click="loadOutputs">刷新</el-button>
    </div>

    <div v-if="activeMode === 'results'" class="output-layout">
      <aside class="output-list">
        <div v-if="loadingList" class="list-state">正在加载...</div>
        <button v-for="artifact in outputs" :key="artifact.id" type="button"
          :class="['output-item', { active: artifact.id === selectedId }]" @click="selectOutput(artifact.id)">
          <span :class="['kind-dot', String(artifact.outputKind).toLowerCase()]">{{ kindIcon(artifact.outputKind) }}</span>
          <span class="output-item-main">
            <strong>{{ artifactTitle(artifact) }}</strong>
            <small>运行 #{{ artifact.runId }} · {{ formatTime(artifact.createdAt) }}</small>
          </span>
          <span class="row-count">{{ artifactRowCount(artifact) }}</span>
        </button>
        <el-empty v-if="!loadingList && !outputs.length" description="暂无可视化结果" :image-size="72" />
      </aside>

      <main class="output-detail" v-loading="loadingView">
        <div v-if="view" class="query-panel">
          <el-alert v-if="!view.queryIndexReady" type="info" :closable="false"
            title="这是升级前生成的历史结果；可继续逐页查看，重新运行对应流程后可使用字段筛选和排序。" />
          <div v-else class="filter-builder">
            <el-select v-model="filterDraft.field" filterable placeholder="筛选字段" @change="changeFilterField">
              <el-option v-for="field in view.queryFields" :key="field.field" :label="`${field.field} · ${fieldTypeLabel(field.valueType)}`" :value="field.field" />
            </el-select>
            <el-select v-model="filterDraft.operator" placeholder="条件" class="operator-select">
              <el-option v-for="operator in operatorOptions" :key="operator.value" :label="operator.label" :value="operator.value" />
            </el-select>
            <el-select v-if="selectedQueryField?.valueType==='BOOLEAN' && filterNeedsValue" v-model="filterDraft.value" placeholder="布尔值" class="value-input">
              <el-option label="是 / true" :value="true" /><el-option label="否 / false" :value="false" />
            </el-select>
            <el-input v-else-if="filterNeedsValue" v-model="filterDraft.value" :placeholder="filterDraft.operator==='IN'?'多个值用逗号分隔':'筛选值'" class="value-input" @keyup.enter="addFilter" />
            <el-input v-if="filterDraft.operator==='BETWEEN'" v-model="filterDraft.secondValue" placeholder="上限值" class="value-input" @keyup.enter="addFilter" />
            <el-button type="primary" plain :disabled="!filterDraft.field || !filterDraft.operator" @click="addFilter">添加筛选</el-button>
            <el-button v-if="filters.length" text @click="clearFilters">清空</el-button>
          </div>
          <div v-if="filters.length" class="filter-tags">
            <el-tag v-for="(filter,index) in filters" :key="`${filter.field}-${index}`" closable @close="removeFilter(index)">{{ filterLabel(filter) }}</el-tag>
          </div>
        </div>
        <OutputArtifactViewer :key="`${selectedId}-${sort?.field || 'row'}-${sort?.direction || 'ASC'}`"
          :view="view" server-driven :sort="sort" @sort-change="changeSort" />
        <div v-if="view" class="cursor-pagination">
          <span>筛选后 {{ view.totalRows }} 条 · 第 {{ cursorPage + 1 }} 页</span>
          <el-select :model-value="pageSize" size="small" class="page-size" @change="changePageSize">
            <el-option v-for="size in [20,50,100,200]" :key="size" :label="`${size} 条/页`" :value="size" />
          </el-select>
          <el-button size="small" :disabled="cursorPage===0 || loadingView" @click="previousPage">上一页</el-button>
          <el-button size="small" type="primary" plain :disabled="!view.hasMore || loadingView" @click="nextPage">下一页</el-button>
        </div>
      </main>
    </div>
    <RuleAuthoringWorkbench v-else-if="activeMode === 'rule'" :conversation-id="props.conversationId" @openDag="versionId => emit('openDag', versionId)" />
    <OutputAuthoringWorkbench v-else-if="activeMode === 'authoring'" :conversation-id="props.conversationId" @openDag="versionId => emit('openDag', versionId)" />
    <PolicyAuthoringWorkbench v-else-if="activeMode === 'policy'" :conversation-id="props.conversationId" @openDag="versionId => emit('openDag', versionId)" />
    <OperatorApprovalCenter v-else-if="activeMode === 'approval'" @openDag="versionId => emit('openDag', versionId)" />
    <StorageGovernanceCenter v-else-if="activeMode === 'storage'" />
    <OutputCapabilityGovernance v-else />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import OutputArtifactViewer from './OutputArtifactViewer.vue'
import OutputAuthoringWorkbench from './OutputAuthoringWorkbench.vue'
import PolicyAuthoringWorkbench from './PolicyAuthoringWorkbench.vue'
import RuleAuthoringWorkbench from './RuleAuthoringWorkbench.vue'
import OperatorApprovalCenter from './OperatorApprovalCenter.vue'
import StorageGovernanceCenter from './StorageGovernanceCenter.vue'
import OutputCapabilityGovernance from './OutputCapabilityGovernance.vue'
import { fetchRecentOutputs, queryOutputView } from '../api/orchestration.js'
import { useUserStore } from '../stores/user.js'

const emit = defineEmits(['close', 'openDag'])
const props = defineProps({
  conversationId: { type: Number, default: null },
  embedded: { type: Boolean, default: false },
  initialMode: { type: String, default: 'results' }
})
const user = useUserStore()

const outputs = ref([])
const selectedId = ref(null)
const view = ref(null)
const kindFilter = ref('')
const loadingList = ref(false)
const loadingView = ref(false)
const activeMode = ref(props.embedded ? 'results' : props.initialMode)
const filters = ref([])
const sort = ref(null)
const pageSize = ref(50)
const cursorPage = ref(0)
const cursorStack = ref([null])
const filterDraft = reactive({ field: '', operator: '', value: '', secondValue: '' })
let querySequence = 0

const selectedQueryField = computed(() => view.value?.queryFields?.find(item => item.field === filterDraft.field))
const filterNeedsValue = computed(() => !['IS_NULL', 'NOT_NULL'].includes(filterDraft.operator))
const operatorOptions = computed(() => {
  const type = selectedQueryField.value?.valueType
  const common = [{ value: 'EQ', label: '等于' }, { value: 'NE', label: '不等于' }]
  const nullable = [{ value: 'IS_NULL', label: '为空/缺失' }, { value: 'NOT_NULL', label: '不为空' }]
  if (type === 'NUMBER') return [...common, { value: 'GT', label: '大于' }, { value: 'GTE', label: '大于等于' }, { value: 'LT', label: '小于' }, { value: 'LTE', label: '小于等于' }, { value: 'BETWEEN', label: '区间' }, { value: 'IN', label: '属于集合' }, ...nullable]
  if (type === 'BOOLEAN') return [...common, { value: 'IN', label: '属于集合' }, ...nullable]
  if (type === 'STRING') return [...common, { value: 'CONTAINS', label: '包含' }, { value: 'STARTS_WITH', label: '开头是' }, { value: 'IN', label: '属于集合' }, ...nullable]
  return nullable
})

function json(value) {
  if (!value) return {}
  if (typeof value === 'object') return value
  try { return JSON.parse(value) } catch { return {} }
}

function artifactTitle(artifact) {
  const spec = json(artifact.contentSpec)
  return spec.title || spec.sheetName || ({ LEAD: '流程线索', CHART: '分析图表', TABLE: '结果表', EXCEL: 'Excel 表格视图',
    DASHBOARD: '组合分析页面', ARTIFACT: '平台运行制品', TEMP_RESULT: '临时结果', EXPORT_XLSX: 'XLSX 文件',
    EXPORT_CSV: 'CSV 文件', EXPORT_PDF: 'PDF 文件', EXPORT_JSON: 'JSON 文件', EXPORT_PNG: 'PNG 图片'
  }[artifact.outputKind] || '流程输出')
}

function artifactRowCount(artifact) {
  const data = json(artifact.artifactData)
  return data.recordCount == null ? '' : `${data.recordCount} 条`
}

function kindIcon(kind) { return ({ LEAD: '◎', CHART: '▥', TABLE: '▦', EXCEL: 'X', DASHBOARD: '▤',
  ARTIFACT: '◇', TEMP_RESULT: '◷', EXPORT_XLSX: 'X', EXPORT_CSV: 'C', EXPORT_PDF: 'P',
  EXPORT_JSON: '{ }', EXPORT_PNG: '▣' }[kind] || '·') }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '' }

async function loadOutputs() {
  loadingList.value = true
  try {
    outputs.value = await fetchRecentOutputs(kindFilter.value, 100)
    if (!outputs.value.some(item => item.id === selectedId.value)) {
      selectedId.value = outputs.value[0]?.id || null
      resetQuery()
    }
    if (selectedId.value) await loadView()
    else view.value = null
  } finally {
    loadingList.value = false
  }
}

async function loadView() {
  if (!selectedId.value) return
  const sequence = ++querySequence
  loadingView.value = true
  try {
    const result = await queryOutputView(selectedId.value, {
      pageSize: pageSize.value,
      cursor: cursorStack.value[cursorPage.value],
      filters: filters.value,
      sort: sort.value
    })
    if (sequence === querySequence) view.value = result
  } finally {
    if (sequence === querySequence) loadingView.value = false
  }
}

async function selectOutput(id) {
  selectedId.value = id
  resetQuery()
  await loadView()
}

function resetCursor() { cursorPage.value = 0; cursorStack.value = [null] }
function resetQuery() { filters.value = []; sort.value = null; resetCursor(); Object.assign(filterDraft, { field: '', operator: '', value: '', secondValue: '' }) }
function changeFilterField() { filterDraft.operator = operatorOptions.value[0]?.value || 'IS_NULL'; filterDraft.value = ''; filterDraft.secondValue = '' }
function normalizeDraftValue(value, type) {
  if (type === 'NUMBER') { const text = String(value).trim(); const number = Number(text); if (!text || !Number.isFinite(number)) throw new Error('请输入有效数字'); return text }
  if (type === 'BOOLEAN') return value === true || String(value).toLowerCase() === 'true'
  return String(value)
}
async function addFilter() {
  const field = selectedQueryField.value
  if (!field || !filterDraft.operator) return
  try {
    const needsValue = filterNeedsValue.value
    if (needsValue && String(filterDraft.value).trim() === '') throw new Error('请填写筛选值')
    const value = filterDraft.operator === 'IN'
      ? String(filterDraft.value).split(',').map(item => normalizeDraftValue(item.trim(), field.valueType))
      : needsValue ? normalizeDraftValue(filterDraft.value, field.valueType) : null
    if (filterDraft.operator === 'BETWEEN' && String(filterDraft.secondValue).trim() === '') throw new Error('请填写区间上限')
    const secondValue = filterDraft.operator === 'BETWEEN' ? normalizeDraftValue(filterDraft.secondValue, field.valueType) : null
    filters.value.push({ field: field.field, operator: filterDraft.operator, value, secondValue })
    filterDraft.value = ''; filterDraft.secondValue = ''; resetCursor(); await loadView()
  } catch (error) { ElMessage.warning(error.message) }
}
async function removeFilter(index) { filters.value.splice(index, 1); resetCursor(); await loadView() }
async function clearFilters() { filters.value = []; resetCursor(); await loadView() }
async function changeSort(event) { sort.value = event.order ? { field: event.prop, direction: event.order === 'descending' ? 'DESC' : 'ASC' } : null; resetCursor(); await loadView() }
async function nextPage() { if (!view.value?.nextCursor) return; cursorStack.value = [...cursorStack.value.slice(0, cursorPage.value + 1), view.value.nextCursor]; cursorPage.value++; await loadView() }
async function previousPage() { if (cursorPage.value === 0) return; cursorPage.value--; await loadView() }
async function changePageSize(size) { pageSize.value = size; resetCursor(); await loadView() }
function fieldTypeLabel(type) { return ({ STRING: '文本', NUMBER: '数值', BOOLEAN: '布尔', NULL: '空值', MIXED: '混合类型' }[type] || type) }
function filterLabel(filter) {
  const names = { EQ: '=', NE: '≠', CONTAINS: '包含', STARTS_WITH: '开头是', GT: '>', GTE: '≥', LT: '<', LTE: '≤', BETWEEN: '区间', IN: '属于', IS_NULL: '为空', NOT_NULL: '不为空' }
  if (!filterNeedsValueFor(filter.operator)) return `${filter.field} ${names[filter.operator]}`
  if (filter.operator === 'BETWEEN') return `${filter.field} ${names[filter.operator]} ${filter.value}～${filter.secondValue}`
  return `${filter.field} ${names[filter.operator]} ${Array.isArray(filter.value) ? filter.value.join('、') : filter.value}`
}
function filterNeedsValueFor(operator) { return !['IS_NULL', 'NOT_NULL'].includes(operator) }

onMounted(loadOutputs)
</script>

<style scoped>
.output-center { flex: 1; min-width: 0; background: var(--bg); display: flex; flex-direction: column; }
.output-center.embedded { max-width: none; margin: 0; padding: 0; }
.embedded .page-header { min-height: 70px; margin: 0; padding: 12px 22px; background: var(--surface); }
.output-heading { flex: 1; }
.output-heading p { margin: 4px 0 0; color: var(--text-muted); font-size: var(--font-sm); }
.page-header { gap: 12px; flex-wrap: wrap; }
.output-layout { flex: 1; min-height: 0; display: grid; grid-template-columns: 292px minmax(0, 1fr); border-top: 1px solid var(--border-light); }
.output-list { overflow-y: auto; padding: 12px; border-right: 1px solid var(--border-light); background: var(--surface); }
.output-item { width: 100%; display: flex; align-items: center; gap: 10px; padding: 11px 9px; border: 1px solid transparent; border-radius: var(--radius-md); background: transparent; text-align: left; cursor: pointer; color: var(--text-primary); }
.output-item:hover { background: var(--bg); }
.output-item.active { border-color: var(--brand-primary-light); background: var(--brand-primary-lighter); }
.kind-dot { width: 28px; height: 28px; display: grid; place-items: center; flex-shrink: 0; border-radius: 7px; background: var(--border-lighter); font-weight: 700; }
.kind-dot.lead { color: #b42318; background: #fee4e2; }
.kind-dot.chart { color: #027a48; background: #d1fadf; }
.kind-dot.excel { color: #217346; background: #d8f3e5; }
.output-item-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
.output-item-main strong { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-size: var(--font-sm); }
.output-item-main small, .row-count { color: var(--text-muted); font-size: var(--font-xs); }
.row-count { flex-shrink: 0; }
.output-detail { position: relative; min-width: 0; overflow: hidden; padding: 18px 20px 12px; display: flex; flex-direction: column; }
.query-panel{display:flex;flex-direction:column;gap:8px;margin-bottom:12px;flex-shrink:0}.filter-builder{display:grid;grid-template-columns:minmax(180px,1.2fr) 130px minmax(150px,1fr) minmax(130px,.8fr) auto auto;gap:8px;align-items:center}.operator-select{width:130px}.value-input{min-width:130px}.filter-tags{display:flex;flex-wrap:wrap;gap:6px}.cursor-pagination{display:flex;justify-content:flex-end;align-items:center;gap:8px;padding-top:10px;flex-shrink:0;color:var(--text-muted);font-size:var(--font-sm)}.page-size{width:105px}
.list-state { padding: 24px; text-align: center; color: var(--text-muted); }
@media (max-width: 900px) {
  .output-layout { grid-template-columns: 220px minmax(0, 1fr); }
  .filter-builder{grid-template-columns:1fr 120px 1fr}.cursor-pagination{flex-wrap:wrap}
}
</style>
