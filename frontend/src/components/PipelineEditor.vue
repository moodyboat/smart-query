<template>
  <div class="pipeline-editor">
    <!-- Pipeline List View -->
    <PipelineList
      v-if="!editingPipeline"
      :pipelines="pipelines"
      :data-sources="props.dataSources"
      @create="createPipeline"
      @open="openPipeline"
      @run-from-card="runFromCard"
      @delete="handleDelete"
    />

    <!-- Pipeline Editor View -->
    <template v-else>
      <PipelineToolbar
        :name="editingPipeline.name"
        :source-type="editingPipeline.sourceType"
        :linked-model="linkedModel"
        :sync-status="syncStatus"
        :saving="saving"
        :running="running"
        :can-run="canRun"
        @close="closeEditor"
        @update:name="editingPipeline.name = $event"
        @save="savePipeline()"
        @run="runPipeline"
        @go-to-model="emit('goToModel', $event)"
        @view-script="loadSegmentedScript"
      />

      <div class="editor-body">
        <PipelinePalette
          :algorithm-groups="algorithmGroups"
          :model-type-names="modelTypeNames"
          @palette-drag-start="onPaletteDragStart"
          @algorithm-click="openAlgorithmLibrary"
          @manage-algorithms="openAlgorithmLibrary()"
        />
        <PipelineCanvas
          :nodes="pipelineNodes"
          :selected-node-id="selectedNodeId"
          :running-node-id="runningNodeId"
          :previewing-node-id="previewingNodeId"
          :done-node-ids="doneNodeIds"
          :preview-result="previewResult"
          :script-loading="scriptLoading"
          :is-node-configured="isNodeConfigured"
          :node-summary="nodeSummary"
          @select-node="onSelectNode"
          @node-reorder-start="onNodeReorderStart"
          @drag-end="onDragEnd"
          @node-cmd="onNodeCmd"
          @preview-step="previewStepLocal"
          @view-script="viewScriptLocal"
          @show-preview="showPreviewPanel"
          @canvas-drop="onCanvasDrop"
          @connector-drop="onConnectorDrop"
          @add-step="openAddStep"
        />
      </div>

      <!-- Execution Results Panel (inline) -->
      <div v-if="lastRunResult" class="run-results">
        <div class="results-header">
          <span class="results-title">执行结果</span>
          <el-tag :type="(lastRunResult.status === MODEL_STATUS.TRAINED || lastRunResult.status === EXECUTION_STATUS.SUCCESS) ? 'success' : 'danger'" size="small">
            {{ (lastRunResult.status === MODEL_STATUS.TRAINED || lastRunResult.status === EXECUTION_STATUS.SUCCESS) ? '训练成功' : '训练失败' }}
          </el-tag>
          <span v-if="lastRunResult.modelName" style="font-size: var(--font-sm);color:var(--text-muted);margin-left:8px">模型: {{ lastRunResult.modelName }} (#{{ lastRunResult.modelId }})</span>
          <el-button size="small" text @click="lastRunResult = null" style="margin-left: auto">关闭</el-button>
        </div>
        <div v-if="lastRunResult.metrics" class="results-metrics">
          <template v-for="(val, key) in parseMetrics(lastRunResult.metrics)" :key="key">
            <div v-if="!key.startsWith('confusion_matrix') && !key.startsWith('class_labels')" class="result-metric">
              <span class="rm-value">{{ typeof val === 'number' ? formatMetricValue(key, val) : val }}</span>
              <span class="rm-label">{{ metricLabel(key) }}</span>
            </div>
          </template>
        </div>
        <div v-if="lastRunResult.modelType || lastRunResult.model_type" class="results-meta">
          <span>{{ algorithmLabel(lastRunResult.algorithm) }}</span>
          <span>.</span>
          <span>{{ modelTypeLabel(lastRunResult.modelType || lastRunResult.model_type) }}</span>
          <span>.</span>
          <span>表: {{ lastRunResult.sourceTable }}</span>
        </div>
        <div v-if="lastRunResult.output_table" class="results-meta" style="margin-top: 4px; color: var(--el-color-success)">
          <span>输出: {{ lastRunResult.output_table }} ({{ lastRunResult.output_rows || 0 }} 行)</span>
        </div>
        <div v-if="topFeatures.length" class="results-features">
          <span class="rf-title">Top 特征:</span>
          <div class="rf-bars">
            <div v-for="(f, i) in topFeatures" :key="i" class="rf-bar-row">
              <span class="rf-name">{{ f.name }}</span>
              <div class="rf-track"><div class="rf-fill" :style="{ width: (topFeatures[0].value ? (f.value / topFeatures[0].value * 100) : 0) + '%' }"></div></div>
              <span class="rf-val">{{ (f.value * 100).toFixed(1) }}%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Preview + Script drawers -->
      <PipelinePreview
        :preview-result="previewResult"
        :previewing-node-id="previewingNodeId"
        v-model:show-preview-drawer="showPreviewDrawer"
        v-model:show-script-drawer="showScriptDrawer"
        :script-content="scriptContent"
        :script-loading="scriptLoading"
        @copy-script="copyScript"
      />

      <!-- Segmented Script Viewer -->
      <ScriptTabs
        v-model:show="showSegmentedDrawer"
        :segments="segmentedSegments"
        :full-script="segmentedFullScript"
        :loading="segmentedLoading"
      />

      <!-- Node Config Panel -->
      <NodeConfigPanel
        :selected-node="selectedNode"
        @update:selected-node="onSelectedNodeUpdate"
        v-model:show-node-config="showNodeConfig"
        :selected-node-title="selectedNodeTitle"
        :editing-pipeline="editingPipeline"
        :column-options="columnOptions"
        :table-options="tableOptions"
        :loading-tables="loadingTables"
        :model-types="modelTypes"
        :algorithms="algorithms"
        v-model:feat-checked="featChecked"
        :feat-select-all="featSelectAll"
        :selected-feat-count="selectedFeatCount"
        :feat-analysis="featAnalysis"
        :feat-analyzing="featAnalyzing"
        :ds-preview-loading="dsPreviewLoading"
        :ds-preview-rows="dsPreviewRows"
        :ds-preview-columns="dsPreviewColumns"
        :ds-preview-total-rows="dsPreviewTotalRows"
        :ds-preview-column-stats="dsPreviewColumnStats"
        :trial-missing-loading="trialMissingLoading"
        :trial-missing-result="trialMissingResult"
        :importance-option="importanceOption"
        :correlation-option="correlationOption"
        :histogram-options="histogramOptions"
        :has-chart-data="hasChartData"
        @table-selected="onTableSelected"
        @target-column-change="onTargetColumnChange"
        @feat-select-all-change="onFeatSelectAll"
        @analyze-features="analyzeFeatures"
        @load-ds-preview="loadDsPreview"
        @model-type-change="onModelTypeChange"
        @algorithm-change="onAlgorithmChange"
        @add-transform="addTransform"
        @remove-transform="removeTransform"
        @on-transform-type-change="tf => onTransformTypeChange(tf)"
        @parse-edges="parseEdges"
        @get-column-strategy="getColumnStrategy"
        @update-column-strategy="updateColumnStrategy"
        @sync-feat-cols="syncFeatCols"
        @run-missing-trial="handleMissingTrial"
      />

      <AlgorithmLibraryDialog
        v-model:visible="showAlgorithmLibrary"
        :algorithms="algorithms"
        :categories="categories"
        :model-types="modelTypes"
        :model-type-names="modelTypeNames"
        :is-admin="userStore.isAdmin"
        :initial-algorithm="libraryAlgorithm"
        @refresh="refreshAlgorithmLibrary"
      />
    </template>

    <!-- Add Step Dialog -->
    <el-dialog v-model="showAddStep" title="添加步骤" width="440px" destroy-on-close>
      <div class="step-picker">
        <div v-for="t in stepTypes" :key="t.type" class="step-option" @click="addStep(insertIndex, t.type)">
          <span class="step-option-icon">{{ t.icon }}</span>
          <div class="step-option-info">
            <span class="step-option-title">{{ t.title }}</span>
            <span class="step-option-desc">{{ t.desc }}</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMiningStore } from '../stores/mining'
import { useUserStore } from '../stores/user'
import {
  fetchMiningPipelines, fetchMiningPipeline, createMiningPipeline,
  updateMiningPipeline, deleteMiningPipeline, executeMiningPipeline,
  validateMiningPipeline, previewStepPipeline, getStepScript, fetchDataSourceTables, fetchTableColumns,
  fetchTablePreview, fetchModelByPipeline, fetchPipelineSyncStatus, fetchSegmentedScript, METRIC_NAMES
} from '../api'
import { useAlgorithms } from '../composables/useAlgorithms.js'
import { usePipelineCanvas } from '../composables/usePipelineCanvas.js'
import { useMissingValueTrial } from '../composables/useMissingValueTrial.js'
import { usePipelineStream } from '../composables/usePipelineStream.js'
import { useFeatureCharts } from '../composables/useFeatureCharts.js'
import { DEFAULT_MODEL_TYPE, DEFAULT_ALGORITHM, MODEL_STATUS, EXECUTION_STATUS, NODE_TYPES, NODE_TYPE_LABELS, PIPELINE_STATUS } from '../constants'

import PipelineList from './pipeline/PipelineList.vue'
import PipelineCanvas from './pipeline/PipelineCanvas.vue'
import PipelinePalette from './pipeline/PipelinePalette.vue'
import PipelineToolbar from './pipeline/PipelineToolbar.vue'
import PipelinePreview from './pipeline/PipelinePreview.vue'
import NodeConfigPanel from './pipeline/NodeConfigPanel.vue'
import ScriptTabs from './pipeline/ScriptTabs.vue'
import AlgorithmLibraryDialog from './pipeline/AlgorithmLibraryDialog.vue'

const props = defineProps({
  dataSources: { type: Array, default: () => [] }
})

const {
  algorithms, modelTypes, categories, algorithmGroups, loadAlgorithms,
  getAlgorithmLabel, getAlgorithmsForModelType,
  getAlgorithmParams, getDefaultHyperparams, getModelTypeLabel, modelTypeNames
} = useAlgorithms()

const emit = defineEmits(['close', 'goToModel'])
const miningStore = useMiningStore()
const userStore = useUserStore()
const showAlgorithmLibrary = ref(false)
const libraryAlgorithm = ref(null)

async function openPipelineById(id) {
  if (editingPipeline.value?.id === id) return
  const p = pipelines.value.find(x => x.id === id)
  if (p) { openPipeline(p); return }
  try {
    const fetched = await fetchMiningPipeline(id)
    if (fetched) openPipeline(fetched)
  } catch { /* not found */ }
}

defineExpose({ openPipelineById })

// Pipeline list
const pipelines = ref([])
const editingPipeline = ref(null)
const pipelineNodes = ref([])
const saving = ref(false)
const dirty = ref(false)
const running = ref(false)
const runningNodeId = ref(null)
const doneNodeIds = ref(new Set())
const lastRunResult = ref(null)
const linkedModel = ref(null)
const syncStatus = ref(null)

// Preview state (kept here for save-before-preview logic)
const previewingNodeId = ref(null)
const previewResult = ref(null)
const showPreviewDrawer = ref(false)
const showScriptDrawer = ref(false)
const scriptContent = ref('')
const scriptLoading = ref(false)

// Segmented script viewer
const showSegmentedDrawer = ref(false)
const segmentedLoading = ref(false)
const segmentedSegments = ref([])
const segmentedFullScript = ref('')

// Quick data preview for data_source node
const dsPreviewLoading = ref(false)
const dsPreviewRows = ref([])
const dsPreviewColumns = ref([])
const dsPreviewTotalRows = ref(0)
const dsPreviewColumnStats = ref([])

// Node selection
const selectedNodeId = ref(null)
const showNodeConfig = ref(false)
const showAddStep = ref(false)
const insertIndex = ref(0)

// Table/Column selectors
const tableOptions = ref([])
const columnOptions = ref([])
const loadingTables = ref(false)
const featChecked = ref({})
const featSelectAll = ref(false)

// Feature analysis
const featAnalyzing = ref(false)
const featAnalysis = ref(null)

// Step types
const stepTypes = [
  { type: NODE_TYPES.DATA_SOURCE, icon: '📥', title: '数据接入', desc: '从数据库读取数据' },
  { type: NODE_TYPES.PREPROCESSING, icon: '🔧', title: '数据预处理', desc: '缺失值处理(含逐列策略)、编码、缩放' },
  { type: NODE_TYPES.FILL_MISSING, icon: '🩹', title: '高级缺失值处理', desc: '按列精细配置缺失值填充（预处理节点的增强版）' },
  { type: NODE_TYPES.FEATURE_ENGINEERING, icon: '⚙️', title: '特征工程', desc: '特征选择和目标定义' },
  { type: NODE_TYPES.TRAINING, icon: '🧠', title: '模型训练', desc: '选择算法并训练模型' },
  { type: NODE_TYPES.EVALUATION, icon: '📊', title: '模型评估', desc: '评估指标和验证策略' },
  { type: NODE_TYPES.OUTPUT, icon: '💾', title: '输出写入', desc: '将预测结果写入数据库表' }
]

// Selected node computed
const selectedNode = computed(() => {
  if (!selectedNodeId.value) return null
  return pipelineNodes.value.find(n => n.id === selectedNodeId.value)
})
const selectedNodeTitle = computed(() => {
  if (!selectedNode.value) return ''
  return selectedNode.value.config?.title || nodeTitle(selectedNode.value.type)
})

const selectedFeatCount = computed(() => Object.values(featChecked.value).filter(Boolean).length)

const canRun = computed(() => {
  const dsNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.DATA_SOURCE)
  const hasTable = dsNode?.config?.table
  const hasTraining = pipelineNodes.value.some(n => n.type === NODE_TYPES.TRAINING)
  const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
  let hasFeatures = false
  let hasTarget = false
  if (featNode?.config?.featureColumns) {
    try {
      const fc = featNode.config.featureColumns
      const arr = Array.isArray(fc) ? fc : JSON.parse(fc)
      hasFeatures = arr.length > 0
    } catch { hasFeatures = false }
  }
  hasTarget = !!featNode?.config?.targetColumn || !!pipelineNodes.value.find(n => n.type === NODE_TYPES.TRAINING)?.config?.modelType?.includes('clustering')
  return pipelineNodes.value.length >= 3 && !!hasTable && hasTraining && hasFeatures && hasTarget
})

// Watch selected node -> open config drawer
watch(selectedNodeId, (id) => {
  if (id) {
    showNodeConfig.value = true
    loadTableAndColumns()
  }
})

// Close config drawer when drawer closes
watch(showNodeConfig, (v) => {
  if (!v) selectedNodeId.value = null
})

let skipDirtyWatch = false

watch(pipelineNodes, () => {
  if (editingPipeline.value && !skipDirtyWatch) dirty.value = true
}, { deep: true })

// --- Canvas composable ---
const { isDragging, onPaletteDragStart, onNodeReorderStart, onDragEnd, findClosestInsertIndex, reorderNode } = usePipelineCanvas(pipelineNodes)
const { trialLoading: trialMissingLoading, trialResult: trialMissingResult, runTrial: runMissingTrial } = useMissingValueTrial()
const pipelineStream = usePipelineStream()
const { importanceOption, correlationOption, histogramOptions, hasChartData } = useFeatureCharts(featAnalysis)

// --- Node helper functions ---
function nodeTitle(type) {
  return NODE_TYPE_LABELS[type] || type
}

function nodeSummary(node) {
  try {
    const c = node.config || {}
    switch (node.type) {
    case 'data_source': return c.table ? `表: ${c.table}` + (c.filter ? ` | ${c.filter}` : '') : '未配置'
    case 'preprocessing': {
      const parts = []
      if (c.handleMissing && c.handleMissing !== 'none') parts.push(c.handleMissing === 'drop' ? '删除缺失' : '填充')
      if (c.encoding && c.encoding !== 'none') parts.push(c.encoding === 'label' ? 'Label编码' : 'One-Hot')
      if (c.scaling && c.scaling !== 'none') parts.push(c.scaling === 'standard' ? '标准化' : '归一化')
      return parts.length ? parts.join(' + ') : '未配置'
    }
    case 'fill_missing': {
      const strategy = { auto: '自动填充', mean: '均值', median: '中位数', mode: '众数', constant: '固定值' }[c.strategy] || c.strategy || 'auto'
      return c.columns?.length ? `${strategy} (${c.columns.length}列)` : strategy
    }
    case 'feature_engineering': {
      let fc = []
      try {
        fc = c.featureColumns ? (Array.isArray(c.featureColumns) ? c.featureColumns : JSON.parse(c.featureColumns)) : []
      } catch { fc = [] }
      const tfCount = Array.isArray(c.transforms) ? c.transforms.length : 0
      const parts = []
      if (fc.length) parts.push(`${fc.length} 列特征`)
      if (c.targetColumn) parts.push(`-> ${c.targetColumn}`)
      if (tfCount) parts.push(`${tfCount} 变换`)
      return parts.length ? parts.join(' ') : '未配置'
    }
    case 'training': {
      if (!c.algorithm) return '未配置'
      const params = Object.entries(c.hyperparams || {}).slice(0, 2).map(([key, value]) => `${key}=${value}`)
      return `${getAlgorithmLabel(c.algorithm)}${params.length ? ' | ' + params.join(', ') : ''}`
    }
    case 'evaluation': {
      const vm = c.validationMode
      if (vm === 'temporal') return `时间外验证 (${c.temporalColumn || '?'})`
      if (vm === 'cv') return `${c.cvFold || 5}-Fold CV`
      if (vm === 'oos') return `OOS ${c.cvFold || 5}-Fold + 测试集 ${c.testSize || 20}%`
      return `测试集 ${c.testSize || 20}%`
    }
    case 'output': return c.table ? `-> ${c.table}` + (c.mode === 'replace' ? ' (替换)' : '') : '未配置'
    default: return ''
  }
  } catch { return '' }
}

function defaultNodeConfig(type) {
  const T = NODE_TYPES
  switch (type) {
    case T.DATA_SOURCE: return { title: NODE_TYPE_LABELS[T.DATA_SOURCE], table: '', filter: '' }
    case T.PREPROCESSING: return { title: NODE_TYPE_LABELS[T.PREPROCESSING], handleMissing: 'drop', encoding: 'label', scaling: 'standard' }
    case T.FILL_MISSING: return { title: NODE_TYPE_LABELS[T.FILL_MISSING], strategy: 'auto', columns: [], fillValues: {} }
    case T.FEATURE_ENGINEERING: return { title: NODE_TYPE_LABELS[T.FEATURE_ENGINEERING], featureColumns: '[]', targetColumn: '' }
    case T.TRAINING: {
      const algorithm = firstAlgorithm()
      return { title: NODE_TYPE_LABELS[T.TRAINING], modelType: firstModelType(), algorithm, hyperparams: getDefaultHyperparams(algorithm) }
    }
    case T.EVALUATION: return {
      title: NODE_TYPE_LABELS[T.EVALUATION], testSize: 20, cvFold: 5, validationMode: 'cv',
      temporalColumn: null, groupColumns: [], oosTable: '', oosFilter: '', positiveClass: '',
      calibrationMethod: 'none', thresholdPolicy: { mode: 'default' },
      governancePolicy: { minTestRows: 20, maxOverfittingGap: 0.15, maxCvStd: 0.1 }
    }
    case T.OUTPUT: return { title: NODE_TYPE_LABELS[T.OUTPUT], table: '', mode: 'append', autoCreate: false }
    default: return { title: type }
  }
}

function firstModelType() {
  return modelTypes.value.length > 0 ? modelTypes.value[0].id : DEFAULT_MODEL_TYPE
}

function firstAlgorithm() {
  return algorithms.value.find(a => a.algorithmId === DEFAULT_ALGORITHM)?.algorithmId
    || algorithms.value[0]?.algorithmId || DEFAULT_ALGORITHM
}

function isNodeConfigured(node) {
  const T = NODE_TYPES
  const c = node.config || {}
  switch (node.type) {
    case T.DATA_SOURCE: return !!c.table
    case T.PREPROCESSING: return true
    case T.FILL_MISSING: return true
    case T.FEATURE_ENGINEERING: {
      try {
        const fc = c.featureColumns ? (typeof c.featureColumns === 'string' ? JSON.parse(c.featureColumns) : c.featureColumns) : []
        return fc.length > 0
      } catch { return false }
    }
    case T.TRAINING: return !!c.algorithm
    case T.EVALUATION: return true
    case T.OUTPUT: return !!c.table
    default: return true
  }
}

function algorithmParams(algo) {
  return getAlgorithmParams(algo)
}

// --- Pipeline CRUD ---
async function loadPipelines() {
  try {
    pipelines.value = await fetchMiningPipelines() || []
  } catch { pipelines.value = [] }
}

async function createPipeline() {
  const ds = props.dataSources.find(d => !d.system) || props.dataSources[0]
  if (!ds) { ElMessage.warning('请先配置数据源'); return }

  const defaultNodes = [
    { id: 'n1', type: NODE_TYPES.DATA_SOURCE, config: { ...defaultNodeConfig(NODE_TYPES.DATA_SOURCE) } },
    { id: 'n2', type: NODE_TYPES.PREPROCESSING, config: { ...defaultNodeConfig(NODE_TYPES.PREPROCESSING) } },
    { id: 'n3', type: NODE_TYPES.FEATURE_ENGINEERING, config: { ...defaultNodeConfig(NODE_TYPES.FEATURE_ENGINEERING) } },
    { id: 'n4', type: NODE_TYPES.TRAINING, config: { ...defaultNodeConfig(NODE_TYPES.TRAINING) } },
    { id: 'n5', type: NODE_TYPES.EVALUATION, config: { ...defaultNodeConfig(NODE_TYPES.EVALUATION) } },
    { id: 'n6', type: NODE_TYPES.OUTPUT, config: { ...defaultNodeConfig(NODE_TYPES.OUTPUT) } }
  ]
  const defaultEdges = [
    { source: 'n1', target: 'n2' }, { source: 'n2', target: 'n3' },
    { source: 'n3', target: 'n4' }, { source: 'n4', target: 'n5' },
    { source: 'n5', target: 'n6' }
  ]

  try {
    const p = await createMiningPipeline({
      name: '新数据分析流程',
      dataSourceId: ds.id,
      nodes: defaultNodes,
      edges: defaultEdges
    })
    pipelines.value.unshift(p)
    openPipeline(p)

    try {
      const tables = await fetchDataSourceTables(ds.id) || []
      if (tables.length > 0) {
        const firstTable = tables[0].name
        const dsNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.DATA_SOURCE)
        if (dsNode) dsNode.config.table = firstTable

        const columns = await fetchTableColumns(ds.id, firstTable) || []
        columnOptions.value = columns
        autoConfigureFeatures(columns, firstTable)
      }
    } catch { /* auto-config is best-effort */ }

    ElMessage.success('已创建并预配置')
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  }
}

function autoConfigureFeatures(columns, tableName) {
  const skipCols = new Set(['id', 'created_at', 'updated_at', 'updated_by'])
  const labelTypes = new Set(['tinyint'])
  let targetCol = null
  const featureCols = []
  const labelSuffixes = ['_label', '_flag', '_target', '_class', '_status', '_type']

  for (const col of columns) {
    const name = col.name.toLowerCase()
    if (skipCols.has(name) || name.endsWith('_id') || name.endsWith('_at') || name === tableName + '_id') continue

    const type = (col.type || '').toLowerCase()
    if (!targetCol) {
      if (labelSuffixes.some(s => name.includes(s)) || labelTypes.has(type)) {
        targetCol = col.name
        continue
      }
    }
    featureCols.push(col.name)
  }

  if (!targetCol) {
    const enumCol = columns.find(c => (c.type || '').toLowerCase().startsWith('enum') && !skipCols.has(c.name.toLowerCase()))
    if (enumCol) targetCol = enumCol.name
  }

  const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
  if (featNode) {
    const feats = targetCol ? featureCols.filter(c => c !== targetCol) : featureCols
    featNode.config.featureColumns = JSON.stringify(feats)
    featNode.config.targetColumn = targetCol || ''
    const checked = {}
    columns.forEach(c => {
      checked[c.name] = feats.includes(c.name) && c.name !== targetCol
    })
    featChecked.value = checked
  }

  const outNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.OUTPUT)
  if (outNode && tableName) {
    outNode.config.table = tableName + '_prediction_result'
  }
}

function openPipeline(p) {
  skipDirtyWatch = true
  editingPipeline.value = { ...p }
  pipelineNodes.value = normalizeNodes(JSON.parse(p.nodes || '[]'))
  selectedNodeId.value = null
  showNodeConfig.value = false
  runningNodeId.value = null
  doneNodeIds.value = new Set()
  linkedModel.value = null
  syncStatus.value = null
  dirty.value = false
  nextTick(() => { skipDirtyWatch = false })
  fetchModelByPipeline(p.id).then(m => { linkedModel.value = m }).catch(() => {})
  fetchPipelineSyncStatus(p.id).then(s => { syncStatus.value = s }).catch(() => {})
  loadTableAndColumns()
}

function normalizeNodes(nodes) {
  try {
    return (Array.isArray(nodes) ? nodes : []).map(n => {
      const config = { ...n.config }
      if (n.type === NODE_TYPES.TRAINING && config.hyperparameters && !config.hyperparams) {
        config.hyperparams = config.hyperparameters
        delete config.hyperparameters
      }
      if (n.type === NODE_TYPES.TRAINING && (!config.hyperparams || Object.keys(config.hyperparams).length === 0)) {
        config.hyperparams = getDefaultHyperparams(config.algorithm)
      }
      if (n.type === NODE_TYPES.EVALUATION) {
        if (!config.validationMode) config.validationMode = 'cv'
        if (!config.cvFold) config.cvFold = 5
        if (!Array.isArray(config.groupColumns)) config.groupColumns = []
        if (!config.thresholdPolicy) config.thresholdPolicy = { mode: 'default' }
        if (!config.governancePolicy) {
          config.governancePolicy = { minTestRows: 20, maxOverfittingGap: 0.15, maxCvStd: 0.1 }
        }
      }
      if (n.type === NODE_TYPES.FEATURE_ENGINEERING && Array.isArray(config.featureColumns)) {
        config.featureColumns = JSON.stringify(config.featureColumns)
      }
      if (n.type === NODE_TYPES.FEATURE_ENGINEERING) {
        if (!config.transforms) config.transforms = []
        else if (!Array.isArray(config.transforms)) config.transforms = []
        for (const tf of config.transforms) {
          if (tf.type === 'date_extract' && tf.parts && !tf.partsArr) {
            tf.partsArr = tf.parts.split(',').map(s => s.trim()).filter(Boolean)
          }
          if (tf.type === 'binning' && tf.edges && !tf.edgesInput) {
            tf.edgesInput = tf.edges.join(',')
          }
        }
      }
      return { ...n, config }
    })
  } catch {
    return []
  }
}

async function runFromCard(p) {
  openPipeline(p)
  await loadTableAndColumns()
  await nextTick()
  if (canRun.value) runPipeline()
  else ElMessage.warning('流程未配置完整，无法运行')
}

function closeEditor() {
  const doClose = () => {
    editingPipeline.value = null
    pipelineNodes.value = []
    selectedNodeId.value = null
    showNodeConfig.value = false
    dirty.value = false
  }
  if (dirty.value) {
    ElMessageBox.confirm('您有未保存的更改，确定要离开吗？', '未保存的更改', {
      confirmButtonText: '离开', cancelButtonText: '取消', type: 'warning'
    }).then(doClose).catch(() => {})
  } else {
    doClose()
  }
}

async function savePipeline(silent = false) {
  if (!editingPipeline.value) return
  saving.value = true
  try {
    const nodesToSave = JSON.parse(JSON.stringify(pipelineNodes.value))
    for (const node of nodesToSave) {
      if (node.type === NODE_TYPES.TRAINING && node.config?.hyperparams) {
        const params = algorithmParams(node.config.algorithm)
        for (const p of params) {
          if (p.type === 'float' && typeof node.config.hyperparams[p.key] === 'number') {
            node.config.hyperparams[p.key] = Math.round(node.config.hyperparams[p.key] * 10000) / 10000
          }
        }
      }
      if (node.type === NODE_TYPES.FEATURE_ENGINEERING && node.config?.transforms) {
        for (const tf of node.config.transforms) {
          if (tf.type === 'date_extract' && tf.partsArr) tf.parts = tf.partsArr.join(',')
          if (tf.type === 'binning' && tf.strategy === 'custom' && tf.edgesInput) {
            tf.edges = tf.edgesInput.split(',').map(v => parseFloat(v.trim())).filter(v => !isNaN(v))
          }
        }
      }
    }
    const edges = []
    for (let i = 0; i < nodesToSave.length - 1; i++) {
      edges.push({ source: nodesToSave[i].id, target: nodesToSave[i + 1].id })
    }
    await updateMiningPipeline(editingPipeline.value.id, {
      name: editingPipeline.value.name,
      nodes: nodesToSave,
      edges: edges,
      status: canRun.value ? PIPELINE_STATUS.READY : PIPELINE_STATUS.DRAFT
    })

    try {
      const vr = await validateMiningPipeline(editingPipeline.value.id)
      if (!vr.valid) {
        if (!silent) ElMessage.warning('流水线校验未通过: ' + (vr.errors || []).join('; '))
      } else if (vr.warnings && vr.warnings.length > 0) {
        if (!silent) ElMessage.success('已保存（有警告: ' + vr.warnings.join('; ') + '）')
      } else {
        if (!silent) ElMessage.success('已保存')
      }
    } catch {
      if (!silent) ElMessage.success('已保存')
    }
    await loadPipelines()
    if (editingPipeline.value?.id) {
      fetchPipelineSyncStatus(editingPipeline.value.id).then(s => { syncStatus.value = s }).catch(() => {})
    }
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
    dirty.value = false
  }
}

async function runPipeline() {
  if (!editingPipeline.value || !canRun.value || running.value) return
  try {
    await savePipeline(true)
  } catch (e) {
    ElMessage.error('保存流程失败，无法执行: ' + (e.message || ''))
    return
  }
  running.value = true
  runningNodeId.value = null
  doneNodeIds.value = new Set()
  lastRunResult.value = null

  try {
    // Try SSE streaming first
    let usedSSE = true
    try {
      pipelineStream.reset()
      pipelineStream.startStream(editingPipeline.value.id)

      // Wait for SSE events to drive progress
      const nodeProgress = pipelineStream.nodeProgress
      const typeOrder = ['data_source', 'preprocessing', 'fill_missing', 'feature_engineering', 'training', 'evaluation', 'output']

      const checkInterval = setInterval(() => {
        // Update runningNodeId based on latest progress
        const completedTypes = Object.keys(nodeProgress.value)
        for (const nt of typeOrder) {
          const node = pipelineNodes.value.find(n => n.type === nt)
          if (!node) continue
          if (!completedTypes.includes(nt)) {
            if (!doneNodeIds.value.has(node.id)) {
              runningNodeId.value = node.id
            }
            break
          } else {
            doneNodeIds.value.add(node.id)
          }
        }
      }, 200)

      // Wait for streaming to complete
      await new Promise((resolve) => {
        const unwatch = watch(() => pipelineStream.isStreaming.value, (v) => {
          if (!v) {
            clearInterval(checkInterval)
            unwatch()
            resolve()
          }
        }, { immediate: true })
      })

      // Mark all nodes as done
      for (const n of pipelineNodes.value) doneNodeIds.value.add(n.id)
      runningNodeId.value = null

      if (pipelineStream.streamError.value) {
        throw new Error(pipelineStream.streamError.value)
      }

      if (pipelineStream.pipelineResult.value) {
        lastRunResult.value = pipelineStream.pipelineResult.value
      }
    } catch (sseError) {
      // Fallback to synchronous execution
      usedSSE = false
      pipelineStream.stopStream()

      let backendDone = false
      const executePromise = executeMiningPipeline(editingPipeline.value.id)
        .then(r => { backendDone = true; return r })
        .catch(e => { backendDone = true; throw e })

      const nodeCount = pipelineNodes.value.length
      const animDelay = Math.max(400, Math.min(1200, 6000 / nodeCount))
      for (let i = 0; i < nodeCount; i++) {
        if (backendDone) {
          for (let j = i; j < nodeCount; j++) doneNodeIds.value.add(pipelineNodes.value[j].id)
          runningNodeId.value = null
          break
        }
        runningNodeId.value = pipelineNodes.value[i].id
        await Promise.race([
          new Promise(r => setTimeout(r, animDelay)),
          executePromise.then(() => {}).catch(() => {})
        ])
        doneNodeIds.value.add(pipelineNodes.value[i].id)
      }
      runningNodeId.value = null

      const result = await executePromise
      lastRunResult.value = result
    }

    await loadPipelines()
    miningStore.loadModels()

    fetchModelByPipeline(editingPipeline.value.id).then(m => { linkedModel.value = m }).catch(() => {})
    fetchPipelineSyncStatus(editingPipeline.value.id).then(s => { syncStatus.value = s }).catch(() => {})

    const result = lastRunResult.value
    const metrics = parseMetrics(result.metrics)
    const primaryKey = result.modelType === 'regression' ? 'test_r2' : 'test_accuracy'
    const primary = metrics[primaryKey] ?? metrics[primaryKey.replace('test_', '')]
    const parts = [`模型 #${result.modelId}`]
    if (primary != null) parts.push(`${metricLabel(primaryKey)} ${primary < 10 ? primary.toFixed(4) : (primary * 100).toFixed(1) + '%'}`)
    if (metrics.overfitting_gap != null) parts.push(`过拟合差距 ${(metrics.overfitting_gap * 100).toFixed(1)}%`)
    ElMessage.success(`流程执行完成 — ${parts.join('，')}`)
  } catch (e) {
    try { await updateMiningPipeline(editingPipeline.value.id, { status: PIPELINE_STATUS.FAILED }) } catch {}
    ElMessage.error('执行失败: ' + (e.message || '未知错误'))
  } finally {
    running.value = false
    runningNodeId.value = null
    pipelineStream.stopStream()
  }
}

async function handleDelete(p) {
  try {
    await ElMessageBox.confirm(`确定删除流程「${p.name}」？`, '删除流程', { type: 'warning' })
    await deleteMiningPipeline(p.id)
    pipelines.value = pipelines.value.filter(x => x.id !== p.id)
    ElMessage.success('已删除')
  } catch { /* cancelled */ }
}

// --- Node actions ---
function openAddStep(idx) {
  insertIndex.value = idx
  showAddStep.value = true
}

function addStep(idx, type) {
  const id = 'n_' + Math.random().toString(36).slice(2, 10)
  const node = { id, type, config: defaultNodeConfig(type) }
  pipelineNodes.value.splice(idx, 0, node)
  showAddStep.value = false
}

function onNodeCmd(cmd, idx) {
  if (cmd === 'config') {
    selectedNodeId.value = pipelineNodes.value[idx].id
  } else if (cmd === 'preview') {
    previewStepLocal(pipelineNodes.value[idx].id)
  } else if (cmd === 'rename') {
    const node = pipelineNodes.value[idx]
    ElMessageBox.prompt('步骤名称:', '重命名', {
      inputValue: node.config?.title || nodeTitle(node.type),
      confirmButtonText: '确定',
      cancelButtonText: '取消'
    }).then(({ value }) => {
      if (value && node.config) node.config.title = value
    }).catch(() => {})
  } else if (cmd === 'delete') {
    const deletedId = pipelineNodes.value[idx]?.id
    pipelineNodes.value.splice(idx, 1)
    if (selectedNodeId.value === deletedId) {
      selectedNodeId.value = null
      showNodeConfig.value = false
    }
  }
}

function onSelectNode(node) {
  if (node === null) {
    selectedNodeId.value = null
    return
  }
  if (isDragging.value) return
  selectedNodeId.value = node.id
  showNodeConfig.value = true
}

// --- Table & Column loading ---
async function loadTableAndColumns() {
  if (!editingPipeline.value) return
  const dsId = editingPipeline.value.dataSourceId
  if (!dsId) return

  loadingTables.value = true
  try { tableOptions.value = await fetchDataSourceTables(dsId) || [] }
  catch { tableOptions.value = [] }
  finally { loadingTables.value = false }

  const dsNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.DATA_SOURCE)
  if (dsNode?.config?.table) {
    await loadColumns(dsNode.config.table)
  }
}

async function onTableSelected(tableName) {
  await loadColumns(tableName)
  const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
  if (featNode) {
    featNode.config.featureColumns = '[]'
    featNode.config.targetColumn = ''
    featChecked.value = {}
  }
}

async function loadColumns(tableName) {
  if (!editingPipeline.value?.dataSourceId || !tableName) { columnOptions.value = []; return }
  try {
    columnOptions.value = await fetchTableColumns(editingPipeline.value.dataSourceId, tableName) || []
    const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
    if (featNode) {
      const raw = featNode.config?.featureColumns
      const saved = raw ? (Array.isArray(raw) ? raw : JSON.parse(raw)) : []
      const checked = {}
      columnOptions.value.forEach(c => { checked[c.name] = saved.includes(c.name) })
      featChecked.value = checked
      syncFeatCols()
    }
  } catch { columnOptions.value = [] }
}

function onFeatSelectAll(val) {
  const checked = {}
  const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
  const target = featNode?.config?.targetColumn
  columnOptions.value.forEach(c => {
    if (val && c.name === target) {
      checked[c.name] = false
    } else {
      checked[c.name] = val
    }
  })
  featChecked.value = checked
  syncFeatCols()
}

function syncFeatCols() {
  const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
  if (featNode) {
    const target = featNode.config.targetColumn
    const cols = Object.entries(featChecked.value)
      .filter(([, v]) => v)
      .map(([k]) => k)
      .filter(c => c !== target)
    featNode.config.featureColumns = JSON.stringify(cols)
  }
  autoAnalyzeDebounced()
}

function onTargetColumnChange(target) {
  const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
  if (!featNode) return
  if (target && featChecked.value[target]) {
    featChecked.value[target] = false
  }
  syncFeatCols()
}

let _autoAnalyzeTimer = null
function autoAnalyzeDebounced() {
  if (_autoAnalyzeTimer) clearTimeout(_autoAnalyzeTimer)
  _autoAnalyzeTimer = setTimeout(() => {
    const featNode = pipelineNodes.value.find(n => n.type === NODE_TYPES.FEATURE_ENGINEERING)
    if (!featNode) return
    const hasTarget = !!featNode.config.targetColumn
    let hasFeatures = false
    try {
      const fc = featNode.config.featureColumns
      const arr = Array.isArray(fc) ? fc : JSON.parse(fc)
      hasFeatures = arr.length > 0
    } catch { hasFeatures = false }
    if (hasTarget && hasFeatures && !featAnalyzing.value) {
      analyzeFeatures()
    }
  }, 1500)
}

async function analyzeFeatures() {
  if (!selectedNode.value || selectedNode.value.type !== NODE_TYPES.FEATURE_ENGINEERING) return
  featAnalyzing.value = true
  featAnalysis.value = null
  try {
    await savePipeline(true)
    const nodeId = selectedNode.value.id
    const res = await fetch(`/api/v1/mining/pipeline/${editingPipeline.value.id}/preview-step`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeId })
    })
    const json = await res.json()
    if (json.data?.status === EXECUTION_STATUS.SUCCESS) {
      featAnalysis.value = json.data
    } else {
      ElMessage.error(json.data?.error || json.message || '分析失败')
    }
  } catch (e) {
    ElMessage.error('特征分析请求失败: ' + e.message)
  } finally {
    featAnalyzing.value = false
  }
}

// --- Transform helpers ---
function addTransform() {
  if (!selectedNode.value) return
  const transforms = selectedNode.value.config.transforms || []
  transforms.push({ type: 'log', columns: [], degree: 2, bins: 5, strategy: 'equal_width', edges: [], edgesInput: '', parts: 'year,month,day', partsArr: ['year', 'month', 'day'] })
  selectedNode.value.config.transforms = [...transforms]
}

function onTransformTypeChange(tf) {
  if (tf.type === 'binning') {
    tf.strategy = tf.strategy || 'equal_width'
    tf.bins = tf.bins || 5
  }
  if (tf.type === 'date_extract') {
    tf.partsArr = tf.partsArr || ['year', 'month', 'day']
    tf.parts = tf.parts || 'year,month,day'
  }
}

function parseEdges(tf) {
  if (tf.edgesInput) {
    tf.edges = tf.edgesInput.split(',').map(v => parseFloat(v.trim())).filter(v => !isNaN(v))
  } else {
    tf.edges = []
  }
}

function removeTransform(idx) {
  if (!selectedNode.value) return
  const transforms = [...(selectedNode.value.config.transforms || [])]
  transforms.splice(idx, 1)
  selectedNode.value.config.transforms = transforms
}

// --- Per-column missing value strategy ---
function getColumnStrategy(colName) {
  return selectedNode.value?.config?.columnStrategies?.[colName] || 'inherit'
}

function updateColumnStrategy(colName, value) {
  if (!selectedNode.value) return
  const config = { ...selectedNode.value.config }
  const current = { ...(config.columnStrategies || {}) }
  if (value === 'inherit') {
    delete current[colName]
  } else {
    current[colName] = value
  }
  if (Object.keys(current).length) {
    config.columnStrategies = current
  } else {
    delete config.columnStrategies
  }
  selectedNode.value.config = config
}

async function handleMissingTrial() {
  if (!editingPipeline.value || !selectedNode.value) return
  const strategies = selectedNode.value.config.columnStrategies
  if (!strategies || !Object.keys(strategies).length) {
    ElMessage.warning('请先设置至少一列的缺失值策略')
    return
  }
  await runMissingTrial(editingPipeline.value.id, strategies)
}

// --- Quick data source preview ---
async function loadDsPreview() {
  const dsId = editingPipeline.value?.dataSourceId
  const tableName = selectedNode.value?.config?.table
  if (!dsId || !tableName) return
  dsPreviewLoading.value = true
  try {
    const result = await fetchTablePreview(dsId, tableName, 20)
    dsPreviewColumns.value = result.columns || []
    dsPreviewRows.value = result.rows || []
    dsPreviewTotalRows.value = result.totalCount || 0
    dsPreviewColumnStats.value = result.columnStats || []
  } catch {
    dsPreviewRows.value = []
    dsPreviewColumns.value = []
    dsPreviewTotalRows.value = 0
    dsPreviewColumnStats.value = []
  } finally {
    dsPreviewLoading.value = false
  }
}

// --- Preview step (save before preview) ---
async function previewStepLocal(nodeId) {
  if (!editingPipeline.value || previewingNodeId.value) return
  previewingNodeId.value = nodeId
  previewResult.value = null
  try {
    await savePipeline()
    const result = await previewStepPipeline(editingPipeline.value.id, nodeId)
    previewResult.value = result
    if (result.status === EXECUTION_STATUS.SUCCESS) {
      ElMessage.success('试运行完成')
    } else {
      ElMessage.warning('试运行出错: ' + (result.error || '未知错误'))
    }
    showPreviewDrawer.value = true
  } catch (e) {
    ElMessage.error('试运行失败: ' + (e.message || ''))
    previewResult.value = { nodeId, status: 'error', error: e.message }
    showPreviewDrawer.value = true
  } finally {
    previewingNodeId.value = null
  }
}

function showPreviewPanel() {
  showPreviewDrawer.value = true
}

async function viewScriptLocal(nodeId) {
  if (!editingPipeline.value || scriptLoading.value) return
  scriptLoading.value = true
  scriptContent.value = ''
  try {
    await savePipeline()
    const result = await getStepScript(editingPipeline.value.id, nodeId)
    scriptContent.value = result.script || ''
    showScriptDrawer.value = true
  } catch (e) {
    ElMessage.error('获取脚本失败: ' + (e.message || ''))
  } finally {
    scriptLoading.value = false
  }
}

async function loadSegmentedScript() {
  if (!editingPipeline.value || segmentedLoading.value) return
  segmentedLoading.value = true
  try {
    await savePipeline()
    const result = await fetchSegmentedScript(editingPipeline.value.id)
    segmentedSegments.value = result.segments || []
    segmentedFullScript.value = result.fullScript || ''
    showSegmentedDrawer.value = true
  } catch (e) {
    ElMessage.error('获取脚本失败: ' + (e.message || ''))
  } finally {
    segmentedLoading.value = false
  }
}

function copyScript() {
  if (!scriptContent.value) return
  navigator.clipboard.writeText(scriptContent.value).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

// --- Model type change ---
function onModelTypeChange() {
  if (!selectedNode.value) return
  const c = selectedNode.value.config
  const available = getAlgorithmsForModelType(c.modelType)
  c.algorithm = available.length ? available[0].algorithmId : firstAlgorithm()
  const defaults = getDefaultHyperparams(c.algorithm)
  c.hyperparams = { ...defaults }
}

function onAlgorithmChange(algorithmId) {
  if (!selectedNode.value) return
  selectedNode.value.config = {
    ...selectedNode.value.config,
    algorithm: algorithmId,
    hyperparams: { ...getDefaultHyperparams(algorithmId) }
  }
}

function openAlgorithmLibrary(algorithm = null) {
  libraryAlgorithm.value = algorithm
  showAlgorithmLibrary.value = true
}

async function refreshAlgorithmLibrary(selectedId) {
  await loadAlgorithms(true)
  libraryAlgorithm.value = selectedId ? algorithms.value.find(a => a.id === selectedId) || null : null
}

// --- Node update from config panel ---
function onSelectedNodeUpdate(updatedNode) {
  if (!selectedNodeId.value) return
  const idx = pipelineNodes.value.findIndex(n => n.id === selectedNodeId.value)
  if (idx >= 0) {
    pipelineNodes.value[idx] = updatedNode
  }
}

// --- Drag and Drop ---
function onCanvasDrop(event) {
  const raw = event.dataTransfer.getData('application/json')
  if (!raw) return
  let data
  try { data = JSON.parse(raw) } catch { return }

  const insertIdx = findClosestInsertIndex(event.clientX, event.clientY)

  if (data.type === 'algorithm') {
    addAlgorithmNode(data, insertIdx)
  } else if (data.type === 'node') {
    addStep(insertIdx, data.nodeType)
  } else if (data.type === 'reorder') {
    reorderNode(data.fromIdx, insertIdx)
  }
}

function onConnectorDrop(event, insertIdx) {
  event.stopPropagation()
  event.currentTarget?.classList?.remove('connector-drag-over')
  const raw = event.dataTransfer.getData('application/json')
  if (!raw) return
  let data
  try { data = JSON.parse(raw) } catch { return }
  if (data.type === 'algorithm') {
    addAlgorithmNode(data, insertIdx)
  } else if (data.type === 'node') {
    addStep(insertIdx, data.nodeType)
  } else if (data.type === 'reorder') {
    reorderNode(data.fromIdx, insertIdx)
  }
}

function addAlgorithmNode(algoData, idx) {
  const id = 'n_' + Math.random().toString(36).slice(2, 10)
  const types = parseJson(algoData.modelTypes, [DEFAULT_MODEL_TYPE])
  const defaults = getDefaultHyperparams(algoData.algorithmId)
  const node = {
    id,
    type: 'training',
    config: {
      title: algoData.name,
      modelType: types[0],
      algorithm: algoData.algorithmId,
      hyperparams: { ...defaults }
    }
  }
  pipelineNodes.value.splice(idx, 0, node)
}

function parseJson(val, fallback) {
  if (!val) return fallback
  if (typeof val === 'string') { try { return JSON.parse(val) } catch { return fallback } }
  return val
}

// --- Metrics helpers ---
function parseMetrics(json) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || {} } catch { return {} }
}

function metricLabel(key) {
  return METRIC_NAMES[key] || key
}

const PERCENT_METRICS = new Set([
  'accuracy', 'precision', 'recall', 'f1',
  'train_accuracy', 'test_accuracy', 'train_f1', 'test_f1',
  'cv_mean', 'overfitting_gap'
])

function formatMetricValue(key, val) {
  if (PERCENT_METRICS.has(key)) {
    return (val * 100).toFixed(1) + '%'
  }
  if (key === 'silhouette_score' || key === 'silhouette') {
    return val.toFixed(4)
  }
  return val < 10 ? val.toFixed(4) : val.toFixed(2)
}

const topFeatures = computed(() => {
  const fi = lastRunResult.value?.featureImportance || lastRunResult.value?.feature_importance
  if (!fi) return []
  const parsed = parseMetrics(fi)
  return Object.entries(parsed)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([name, value]) => ({ name, value }))
})

const algorithmLabel = getAlgorithmLabel
const modelTypeLabel = getModelTypeLabel

// --- Init ---
loadAlgorithms().catch(() => {})
loadPipelines().catch(() => {})
</script>

<style scoped>
.pipeline-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* Editor Body */
.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* Execution Results */
.run-results {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  margin-top: var(--space-lg);
}

.results-header {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
}

.results-title {
  font-weight: 600;
  font-size: var(--font-base);
}

.results-metrics {
  display: flex;
  gap: var(--space-md);
  flex-wrap: wrap;
  margin-bottom: var(--space-md);
}

.result-metric {
  background: var(--color-success-light);
  border-radius: var(--radius-md);
  padding: var(--space-sm) var(--space-lg);
  text-align: center;
  min-width: 80px;
}

.rm-value {
  display: block;
  font-size: var(--font-2xl);
  font-weight: 700;
  color: var(--color-success);
}

.rm-label {
  display: block;
  font-size: var(--font-xs);
  color: var(--text-muted);
  margin-top: 2px;
}

.results-meta {
  font-size: var(--font-sm);
  color: var(--text-muted);
  margin-bottom: var(--space-md);
  display: flex;
  gap: var(--space-xs);
}

.results-features { margin-top: var(--space-sm); }

.rf-title {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  font-weight: 500;
}

.rf-bars {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  margin-top: var(--space-xs);
}

.rf-bar-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.rf-name {
  width: 100px;
  font-size: var(--font-xs);
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rf-track {
  flex: 1;
  height: var(--space-xs);
  background: var(--border-light);
  border-radius: 3px;
  overflow: hidden;
}

.rf-fill {
  height: 100%;
  background: var(--primary);
  border-radius: 3px;
}

.rf-val {
  width: 40px;
  font-size: var(--font-xs);
  color: var(--text-muted);
  text-align: right;
}

/* Step Picker */
.step-picker {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.step-option {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: all 0.2s;
}

.step-option:hover {
  border-color: var(--primary);
  background: var(--primary-light);
}

.step-option-icon {
  font-size: var(--font-2xl);
  width: 40px;
  text-align: center;
}

.step-option-info {
  display: flex;
  flex-direction: column;
}

.step-option-title {
  font-weight: 600;
  font-size: var(--font-base);
}

.step-option-desc {
  color: var(--text-muted);
  font-size: var(--font-sm);
}
</style>
