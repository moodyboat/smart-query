<template>
  <div class="pipeline-editor">
    <!-- Pipeline List View -->
    <template v-if="!editingPipeline">
      <div class="pipeline-list-header">
        <div class="list-header-left">
          <span class="list-count">{{ filteredPipelines.length }} 个流程</span>
          <el-select v-model="filterDsId" placeholder="全部数据源" size="small" clearable style="width: 160px">
            <el-option v-for="ds in props.dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </div>
        <el-button type="primary" size="small" @click="createPipeline">新建流程</el-button>
      </div>
      <div v-if="filteredPipelines.length === 0" class="empty-pipelines">
        <p>暂无流程，点击「新建流程」开始编排数据分析管道</p>
      </div>
      <div class="pipeline-grid">
        <div v-for="p in filteredPipelines" :key="p.id" class="pipeline-card" @click="openPipeline(p)">
          <div class="pipeline-card-header">
            <span class="pipeline-name">{{ p.name }}</span>
            <el-tag :type="statusType(p.status)" size="small">{{ statusLabel(p.status) }}</el-tag>
          </div>
          <div class="pipeline-card-meta">
            <span>{{ dataSourceName(p.dataSourceId) }}</span>
            <span>{{ nodeCount(p) }} 个步骤</span>
            <span>{{ formatDate(p.createdAt) }}</span>
          </div>
          <div v-if="parsedNodes(p).length" class="pipeline-card-flow">
            <span v-for="(n, i) in parsedNodes(p).slice(0, 5)" :key="i" class="mini-node">
              {{ nodeIcon(n.type) }} {{ nodeTitle(n.type) }}
              <span v-if="i < Math.min(parsedNodes(p).length, 5) - 1" class="mini-arrow">→</span>
            </span>
            <span v-if="parsedNodes(p).length > 5" class="mini-more">+{{ parsedNodes(p).length - 5 }}</span>
          </div>
          <div class="pipeline-card-actions">
            <el-button size="small" text type="primary" @click.stop="openPipeline(p)">编辑</el-button>
            <el-button size="small" text type="success" @click.stop="runFromCard(p)" :disabled="p.status === 'running'">
              {{ p.status === 'running' ? '运行中' : '运行' }}
            </el-button>
            <el-button size="small" text type="danger" @click.stop="handleDelete(p)">删除</el-button>
          </div>
        </div>
      </div>
    </template>

    <!-- Pipeline Editor View -->
    <template v-else>
      <div class="editor-toolbar">
        <el-button size="small" @click="closeEditor">← 返回</el-button>
        <el-input v-model="editingPipeline.name" size="small" style="width: 200px" />
        <div class="toolbar-actions">
          <el-button size="small" @click="savePipeline" :loading="saving">保存</el-button>
          <el-button size="small" type="primary" @click="runPipeline" :loading="running" :disabled="!canRun">
            {{ running ? '运行中...' : '▶ 运行' }}
          </el-button>
        </div>
      </div>

      <div class="flow-canvas" @click.self="selectedNodeId = null">
        <div class="nodes-flow">
          <template v-for="(node, idx) in pipelineNodes" :key="node.id">
            <!-- Node Card -->
            <div
              class="flow-node"
              :class="[node.type, { selected: selectedNodeId === node.id, running: runningNodeId === node.id, done: doneNodeIds.has(node.id) }]"
              @click.stop="selectedNodeId = node.id"
            >
              <div class="node-color-bar"></div>
              <div class="node-body">
                <div class="node-header">
                  <span class="node-icon">{{ nodeIcon(node.type) }}</span>
                  <span class="node-title">{{ node.config?.title || nodeTitle(node.type) }}</span>
                  <el-dropdown trigger="click" @command="cmd => onNodeCmd(cmd, idx)" size="small">
                    <el-button size="small" text class="node-more">⋯</el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item command="config">配置</el-dropdown-item>
                        <el-dropdown-item command="rename">重命名</el-dropdown-item>
                        <el-dropdown-item command="delete" divided style="color: var(--danger)">删除</el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
                <div class="node-summary">{{ nodeSummary(node) }}</div>
              </div>
            </div>

            <!-- Connection Arrow -->
            <div v-if="idx < pipelineNodes.length - 1" class="flow-connector">
              <div class="connector-add" @click.stop="openAddStep(idx + 1)">+</div>
              <div class="connector-line"></div>
            </div>
          </template>

          <!-- Add step at end -->
          <div class="flow-connector">
            <div class="connector-add" @click.stop="openAddStep(pipelineNodes.length)">+</div>
          </div>
        </div>
      </div>

      <!-- Execution Results Panel -->
      <div v-if="lastRunResult" class="run-results">
        <div class="results-header">
          <span class="results-title">执行结果</span>
          <el-tag :type="lastRunResult.status === 'trained' ? 'success' : lastRunResult.status === 'trained_failed' ? 'danger' : 'warning'" size="small">
            {{ lastRunResult.status === 'trained' ? '训练成功' : lastRunResult.status === 'trained_failed' ? '训练失败' : lastRunResult.status }}
          </el-tag>
          <el-button size="small" text @click="lastRunResult = null" style="margin-left: auto">关闭</el-button>
        </div>
        <div v-if="lastRunResult.metrics" class="results-metrics">
          <template v-for="(val, key) in parseMetrics(lastRunResult.metrics)" :key="key">
            <div class="result-metric">
              <span class="rm-value">{{ typeof val === 'number' ? (val * 100).toFixed(1) + '%' : val }}</span>
              <span class="rm-label">{{ metricLabel(key) }}</span>
            </div>
          </template>
        </div>
        <div v-if="lastRunResult.modelType" class="results-meta">
          <span>{{ algorithmLabel(lastRunResult.algorithm) }}</span>
          <span>·</span>
          <span>{{ modelTypeLabel(lastRunResult.modelType) }}</span>
          <span>·</span>
          <span>表: {{ lastRunResult.sourceTable }}</span>
        </div>
        <div v-if="topFeatures.length" class="results-features">
          <span class="rf-title">Top 特征:</span>
          <div class="rf-bars">
            <div v-for="(f, i) in topFeatures" :key="i" class="rf-bar-row">
              <span class="rf-name">{{ f.name }}</span>
              <div class="rf-track"><div class="rf-fill" :style="{ width: (f.value / topFeatures[0].value * 100) + '%' }"></div></div>
              <span class="rf-val">{{ (f.value * 100).toFixed(1) }}%</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Node Config Panel -->
      <el-drawer v-model="showNodeConfig" :title="selectedNodeTitle" size="420px" direction="rtl" :modal="false">
        <div v-if="selectedNode" class="config-panel">
          <!-- Data Source Config -->
          <template v-if="selectedNode.type === 'data_source'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="数据表">
                <el-select v-model="selectedNode.config.table" placeholder="选择数据表" style="width: 100%"
                  :teleported="false" :loading="loadingTables" filterable @change="onTableSelected">
                  <el-option v-for="t in tableOptions" :key="t.name"
                    :label="t.comment ? `${t.name} (${t.comment})` : t.name" :value="t.name" />
                </el-select>
              </el-form-item>
              <el-form-item label="筛选条件">
                <el-input v-model="selectedNode.config.filter" placeholder="如: status = 1" />
              </el-form-item>
            </el-form>
          </template>

          <!-- Preprocessing Config -->
          <template v-if="selectedNode.type === 'preprocessing'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="缺失值处理">
                <el-select v-model="selectedNode.config.handleMissing" style="width: 100%" :teleported="false">
                  <el-option label="删除缺失行 (drop)" value="drop" />
                  <el-option label="填充均值 (mean)" value="fill_mean" />
                  <el-option label="填充中位数 (median)" value="fill_median" />
                  <el-option label="不处理" value="none" />
                </el-select>
              </el-form-item>
              <el-form-item label="分类编码">
                <el-select v-model="selectedNode.config.encoding" style="width: 100%" :teleported="false">
                  <el-option label="Label Encoding" value="label" />
                  <el-option label="One-Hot Encoding" value="onehot" />
                  <el-option label="不编码" value="none" />
                </el-select>
              </el-form-item>
              <el-form-item label="特征缩放">
                <el-select v-model="selectedNode.config.scaling" style="width: 100%" :teleported="false">
                  <el-option label="StandardScaler (标准化)" value="standard" />
                  <el-option label="MinMaxScaler (归一化)" value="minmax" />
                  <el-option label="不缩放" value="none" />
                </el-select>
              </el-form-item>
            </el-form>
          </template>

          <!-- Feature Engineering Config -->
          <template v-if="selectedNode.type === 'feature_engineering'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="特征列">
                <div v-if="columnOptions.length" class="column-picker">
                  <el-checkbox v-model="featSelectAll" @change="onFeatSelectAll" class="col-hint">全选</el-checkbox>
                  <div class="column-grid">
                    <el-checkbox v-for="col in columnOptions" :key="col.name"
                      v-model="featChecked[col.name]" @change="syncFeatCols">
                      <span class="col-name">{{ col.name }}</span>
                      <span class="col-type">{{ col.type }}</span>
                    </el-checkbox>
                  </div>
                  <span class="selected-count">已选 {{ selectedFeatCount }} / {{ columnOptions.length }} 列</span>
                </div>
                <p v-else class="col-hint">请先配置数据接入节点</p>
              </el-form-item>
              <el-form-item label="目标列">
                <el-select v-model="selectedNode.config.targetColumn" placeholder="选择目标列" style="width: 100%"
                  :teleported="false" :disabled="!columnOptions.length">
                  <el-option v-for="col in columnOptions" :key="col.name"
                    :label="`${col.name} (${col.type})`" :value="col.name" />
                </el-select>
              </el-form-item>
            </el-form>
          </template>

          <!-- Training Config -->
          <template v-if="selectedNode.type === 'training'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="模型类型">
                <el-select v-model="selectedNode.config.modelType" style="width: 100%" :teleported="false" @change="onModelTypeChange">
                  <el-option label="分类 (Classification)" value="classification" />
                  <el-option label="回归 (Regression)" value="regression" />
                  <el-option label="聚类 (Clustering)" value="clustering" />
                </el-select>
              </el-form-item>
              <el-form-item label="算法">
                <el-select v-model="selectedNode.config.algorithm" style="width: 100%" :teleported="false">
                  <el-option v-for="a in algoOptions" :key="a.value" :label="a.label" :value="a.value" />
                </el-select>
              </el-form-item>
              <el-divider content-position="left">超参数</el-divider>
              <div v-for="p in currentAlgoParams" :key="p.key" class="param-row">
                <label class="param-label">{{ p.label }} <span v-if="p.hint" class="param-hint">{{ p.hint }}</span></label>
                <el-input-number v-if="p.type === 'int'" v-model="selectedNode.config.hyperparams[p.key]"
                  :min="p.min" :max="p.max" :step="p.step || 1" size="small" style="width: 100%" />
                <el-input-number v-else-if="p.type === 'float'" v-model="selectedNode.config.hyperparams[p.key]"
                  :min="p.min" :max="p.max" :step="p.step || 0.1" :precision="2" size="small" style="width: 100%" />
                <el-select v-else-if="p.type === 'select'" v-model="selectedNode.config.hyperparams[p.key]"
                  size="small" style="width: 100%" :teleported="false">
                  <el-option v-for="o in p.options" :key="o" :label="o" :value="o" />
                </el-select>
              </div>
            </el-form>
          </template>

          <!-- Evaluation Config -->
          <template v-if="selectedNode.type === 'evaluation'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="测试集比例">
                <el-slider v-model="selectedNode.config.testSize" :min="10" :max="40" :step="5"
                  :format-tooltip="v => v + '%'" />
              </el-form-item>
              <el-form-item label="交叉验证">
                <el-select v-model="selectedNode.config.cvFold" style="width: 100%" :teleported="false">
                  <el-option :label="'不使用'" :value="0" />
                  <el-option v-for="n in [3, 5, 10]" :key="n" :label="`${n}-Fold`" :value="n" />
                </el-select>
              </el-form-item>
            </el-form>
          </template>

          <div class="config-footer">
            <el-button size="small" type="primary" @click="showNodeConfig = false">完成</el-button>
          </div>
        </div>
      </el-drawer>
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
import {
  fetchMiningPipelines, fetchMiningPipeline, createMiningPipeline,
  updateMiningPipeline, deleteMiningPipeline, fetchDataSourceTables, fetchTableColumns
} from '../api'
import axios from 'axios'

const props = defineProps({
  dataSources: { type: Array, default: () => [] }
})

const emit = defineEmits(['close'])

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
const running = ref(false)
const runningNodeId = ref(null)
const doneNodeIds = ref(new Set())
const filterDsId = ref(null)
const lastRunResult = ref(null)

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

// Step types
const stepTypes = [
  { type: 'data_source', icon: '📥', title: '数据接入', desc: '从数据库读取数据' },
  { type: 'preprocessing', icon: '🔧', title: '数据预处理', desc: '缺失值处理、编码、缩放' },
  { type: 'feature_engineering', icon: '⚙️', title: '特征工程', desc: '特征选择和目标定义' },
  { type: 'training', icon: '🧠', title: '模型训练', desc: '选择算法并训练模型' },
  { type: 'evaluation', icon: '📊', title: '模型评估', desc: '评估指标和验证策略' }
]

// Selected node
const selectedNode = computed(() => {
  if (!selectedNodeId.value) return null
  return pipelineNodes.value.find(n => n.id === selectedNodeId.value)
})
const selectedNodeTitle = computed(() => {
  if (!selectedNode.value) return ''
  return selectedNode.value.config?.title || nodeTitle(selectedNode.value.type)
})

// Algorithm options for training node
const algoMap = {
  classification: [
    { value: 'random_forest', label: '随机森林' },
    { value: 'xgboost', label: 'XGBoost' },
    { value: 'decision_tree', label: '决策树' },
    { value: 'logistic_regression', label: '逻辑回归' },
    { value: 'svm', label: 'SVM' },
    { value: 'knn', label: 'KNN' },
    { value: 'gradient_boosting', label: '梯度提升' }
  ],
  regression: [
    { value: 'random_forest', label: '随机森林' },
    { value: 'xgboost', label: 'XGBoost' },
    { value: 'decision_tree', label: '决策树' },
    { value: 'gradient_boosting', label: '梯度提升' }
  ],
  clustering: [
    { value: 'kmeans', label: 'K-Means' }
  ]
}
const algoOptions = computed(() => {
  const mt = selectedNode.value?.config?.modelType || 'classification'
  return algoMap[mt] || algoMap.classification
})

// Algorithm hyperparams (reused from MiningManager)
function algorithmParams(algo) {
  const defs = {
    random_forest: [
      { key: 'n_estimators', label: '树的数量', type: 'int', min: 1, max: 1000, default: 100 },
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 100, default: 10 },
      { key: 'min_samples_split', label: '最小分裂样本数', type: 'int', min: 2, max: 100, default: 2 },
      { key: 'min_samples_leaf', label: '叶节点最小样本数', type: 'int', min: 1, max: 50, default: 1 }
    ],
    xgboost: [
      { key: 'n_estimators', label: '树的数量', type: 'int', min: 1, max: 1000, default: 100 },
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 50, default: 6 },
      { key: 'learning_rate', label: '学习率', type: 'float', min: 0.001, max: 1, step: 0.01, default: 0.3 },
      { key: 'subsample', label: '子采样率', type: 'float', min: 0.1, max: 1, step: 0.1, default: 1 }
    ],
    decision_tree: [
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 100, default: 10 },
      { key: 'min_samples_split', label: '最小分裂样本数', type: 'int', min: 2, max: 100, default: 2 },
      { key: 'criterion', label: '分裂标准', type: 'select', options: ['gini', 'entropy'], default: 'gini' }
    ],
    logistic_regression: [
      { key: 'C', label: '正则化强度', type: 'float', min: 0.01, max: 100, step: 0.1, default: 1 },
      { key: 'max_iter', label: '最大迭代次数', type: 'int', min: 10, max: 10000, default: 100 }
    ],
    svm: [
      { key: 'C', label: '正则化强度', type: 'float', min: 0.01, max: 100, step: 0.1, default: 1 },
      { key: 'kernel', label: '核函数', type: 'select', options: ['rbf', 'linear', 'poly'], default: 'rbf' }
    ],
    knn: [
      { key: 'n_neighbors', label: '邻居数 K', type: 'int', min: 1, max: 100, default: 5 },
      { key: 'weights', label: '权重', type: 'select', options: ['uniform', 'distance'], default: 'uniform' }
    ],
    kmeans: [
      { key: 'n_clusters', label: '聚类数', type: 'int', min: 2, max: 50, default: 3 },
      { key: 'max_iter', label: '最大迭代次数', type: 'int', min: 10, max: 1000, default: 300 }
    ],
    gradient_boosting: [
      { key: 'n_estimators', label: '树的数量', type: 'int', min: 1, max: 1000, default: 100 },
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 50, default: 3 },
      { key: 'learning_rate', label: '学习率', type: 'float', min: 0.001, max: 1, step: 0.01, default: 0.1 }
    ]
  }
  return defs[algo] || []
}

const currentAlgoParams = computed(() => {
  const algo = selectedNode.value?.config?.algorithm || 'random_forest'
  return algorithmParams(algo)
})

const selectedFeatCount = computed(() => Object.values(featChecked.value).filter(Boolean).length)

const canRun = computed(() => {
  const hasTable = pipelineNodes.value.some(n => n.type === 'data_source' && n.config?.table)
  const hasTraining = pipelineNodes.value.some(n => n.type === 'training')
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  const hasFeatures = featNode?.config?.featureColumns && (() => {
    try { return JSON.parse(featNode.config.featureColumns).length > 0 } catch { return false }
  })()
  return pipelineNodes.value.length >= 3 && hasTable && hasTraining && hasFeatures
})

const filteredPipelines = computed(() => {
  if (!filterDsId.value) return pipelines.value
  return pipelines.value.filter(p => p.dataSourceId === filterDsId.value)
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

// Load pipelines
async function loadPipelines() {
  try {
    pipelines.value = await fetchMiningPipelines() || []
  } catch { pipelines.value = [] }
}

function parsedNodes(p) {
  try { return JSON.parse(p.nodes || '[]') } catch { return [] }
}

function nodeCount(p) { return parsedNodes(p).length }

function dataSourceName(dsId) {
  const ds = props.dataSources.find(d => d.id === dsId)
  return ds ? ds.name : '未知数据源'
}

function statusType(s) {
  return { draft: 'info', ready: '', running: 'warning', completed: 'success', failed: 'danger' }[s] || 'info'
}

function statusLabel(s) {
  return { draft: '草稿', ready: '就绪', running: '运行中', completed: '已完成', failed: '失败' }[s] || s
}

function formatDate(dt) {
  if (!dt) return ''
  return new Date(dt).toLocaleDateString('zh-CN')
}

function nodeIcon(type) {
  return { data_source: '📥', preprocessing: '🔧', feature_engineering: '⚙️', training: '🧠', evaluation: '📊' }[type] || '📦'
}

function nodeTitle(type) {
  return { data_source: '数据接入', preprocessing: '数据预处理', feature_engineering: '特征工程', training: '模型训练', evaluation: '模型评估' }[type] || type
}

function nodeSummary(node) {
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
    case 'feature_engineering': {
      const fc = c.featureColumns ? JSON.parse(c.featureColumns) : []
      return fc.length ? `${fc.length} 列特征` + (c.targetColumn ? ` → ${c.targetColumn}` : '') : '未配置'
    }
    case 'training': {
      const algoLabels = { random_forest: '随机森林', xgboost: 'XGBoost', decision_tree: '决策树', logistic_regression: '逻辑回归', svm: 'SVM', knn: 'KNN', kmeans: 'K-Means', gradient_boosting: '梯度提升' }
      return c.algorithm ? (algoLabels[c.algorithm] || c.algorithm) : '未配置'
    }
    case 'evaluation': return `测试集 ${c.testSize || 20}%`
    default: return ''
  }
}

function defaultNodeConfig(type) {
  switch (type) {
    case 'data_source': return { title: '数据接入', table: '', filter: '' }
    case 'preprocessing': return { title: '数据预处理', handleMissing: 'drop', encoding: 'label', scaling: 'standard' }
    case 'feature_engineering': return { title: '特征工程', featureColumns: '[]', targetColumn: '' }
    case 'training': return { title: '模型训练', modelType: 'classification', algorithm: 'random_forest', hyperparams: { n_estimators: 100, max_depth: 10 } }
    case 'evaluation': return { title: '模型评估', testSize: 20, cvFold: 0 }
    default: return { title: type }
  }
}

// Pipeline CRUD
async function createPipeline() {
  const ds = props.dataSources.find(d => d.databaseName !== 'smart_query') || props.dataSources[0]
  if (!ds) { ElMessage.warning('请先配置数据源'); return }

  const defaultNodes = [
    { id: 'n1', type: 'data_source', config: { ...defaultNodeConfig('data_source') } },
    { id: 'n2', type: 'preprocessing', config: { ...defaultNodeConfig('preprocessing') } },
    { id: 'n3', type: 'feature_engineering', config: { ...defaultNodeConfig('feature_engineering') } },
    { id: 'n4', type: 'training', config: defaultNodeConfig('training') },
    { id: 'n5', type: 'evaluation', config: { ...defaultNodeConfig('evaluation') } }
  ]
  const defaultEdges = [
    { source: 'n1', target: 'n2' }, { source: 'n2', target: 'n3' },
    { source: 'n3', target: 'n4' }, { source: 'n4', target: 'n5' }
  ]

  try {
    const p = await createMiningPipeline({
      name: '新数据分析流程',
      dataSourceId: ds.id,
      nodes: JSON.stringify(defaultNodes),
      edges: JSON.stringify(defaultEdges)
    })
    pipelines.value.unshift(p)
    openPipeline(p)
    ElMessage.success('已创建')
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  }
}

function openPipeline(p) {
  editingPipeline.value = { ...p }
  pipelineNodes.value = parsedNodes(p)
  selectedNodeId.value = null
  showNodeConfig.value = false
  runningNodeId.value = null
  doneNodeIds.value = new Set()
}

async function runFromCard(p) {
  openPipeline(p)
  await nextTick()
  if (canRun.value) runPipeline()
  else ElMessage.warning('流程未配置完整，无法运行')
}

function closeEditor() {
  editingPipeline.value = null
  pipelineNodes.value = []
  selectedNodeId.value = null
  showNodeConfig.value = false
}

async function savePipeline() {
  if (!editingPipeline.value) return
  saving.value = true
  try {
    const edges = []
    for (let i = 0; i < pipelineNodes.value.length - 1; i++) {
      edges.push({ source: pipelineNodes.value[i].id, target: pipelineNodes.value[i + 1].id })
    }
    await updateMiningPipeline(editingPipeline.value.id, {
      name: editingPipeline.value.name,
      nodes: JSON.stringify(pipelineNodes.value),
      edges: JSON.stringify(edges),
      status: canRun.value ? 'ready' : 'draft'
    })
    ElMessage.success('已保存')
    await loadPipelines()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function runPipeline() {
  if (!editingPipeline.value || !canRun.value) return
  await savePipeline()
  running.value = true
  runningNodeId.value = null
  doneNodeIds.value = new Set()
  lastRunResult.value = null

  try {
    // Animate through nodes
    for (const node of pipelineNodes.value) {
      runningNodeId.value = node.id
      await new Promise(r => setTimeout(r, 400))
      doneNodeIds.value.add(node.id)
    }
    runningNodeId.value = null

    // Execute on backend
    const dsNode = pipelineNodes.value.find(n => n.type === 'data_source')
    const trainNode = pipelineNodes.value.find(n => n.type === 'training')
    const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')

    if (dsNode?.config?.table && trainNode?.config?.algorithm) {
      const features = featNode?.config?.featureColumns ? JSON.parse(featNode.config.featureColumns) : []
      const payload = {
        name: editingPipeline.value.name,
        dataSourceId: editingPipeline.value.dataSourceId,
        sourceTable: dsNode.config.table,
        modelType: trainNode.config.modelType || 'classification',
        algorithm: trainNode.config.algorithm,
        featureColumns: JSON.stringify(features),
        targetColumn: featNode?.config?.targetColumn || '',
        hyperparameters: JSON.stringify(trainNode.config.hyperparams || {}),
        pipelineId: editingPipeline.value.id,
        preprocessing: JSON.stringify({
          handleMissing: pipelineNodes.value.find(n => n.type === 'preprocessing')?.config?.handleMissing || 'drop',
          encoding: pipelineNodes.value.find(n => n.type === 'preprocessing')?.config?.encoding || 'label',
          scaling: pipelineNodes.value.find(n => n.type === 'preprocessing')?.config?.scaling || 'standard'
        })
      }
      const apiBase = axios.create({ baseURL: '/api/v1', timeout: 180000 })

      // Reuse existing model for this pipeline, or create new one
      let modelId = null
      const { data: { data: existingModels } } = await apiBase.get('/mining/model', { params: { dataSourceId: editingPipeline.value.dataSourceId } })
      const existing = (existingModels || []).find(m => m.pipelineId === editingPipeline.value.id)
      if (existing) {
        await apiBase.put(`/mining/model/${existing.id}`, payload)
        modelId = existing.id
      } else {
        const { data: { data: created } } = await apiBase.post('/mining/model', payload)
        modelId = created?.id
      }

      if (modelId) {
        const { data: { data: trained } } = await apiBase.post(`/mining/model/${modelId}/train`)
        const { data: { data: fullModel } } = await apiBase.get(`/mining/model/${modelId}`)
        lastRunResult.value = fullModel || trained
      }
    }

    // Update pipeline status
    await updateMiningPipeline(editingPipeline.value.id, { status: 'completed' })
    ElMessage.success('流程执行完成')
    await loadPipelines()
  } catch (e) {
    await updateMiningPipeline(editingPipeline.value.id, { status: 'failed' })
    ElMessage.error('执行失败: ' + (e.message || ''))
  } finally {
    running.value = false
    runningNodeId.value = null
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

// Node actions
function openAddStep(idx) {
  insertIndex.value = idx
  showAddStep.value = true
}

function addStep(idx, type) {
  const id = 'n' + Date.now()
  const node = { id, type, config: defaultNodeConfig(type) }
  pipelineNodes.value.splice(idx, 0, node)
  showAddStep.value = false
}

function onNodeCmd(cmd, idx) {
  if (cmd === 'config') {
    selectedNodeId.value = pipelineNodes.value[idx].id
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
    pipelineNodes.value.splice(idx, 1)
    if (selectedNodeId.value === pipelineNodes.value[idx]?.id) {
      selectedNodeId.value = null
      showNodeConfig.value = false
    }
  }
}

// Table & Column loading
async function loadTableAndColumns() {
  if (!editingPipeline.value) return
  const dsId = editingPipeline.value.dataSourceId
  if (!dsId) return

  // Load tables
  loadingTables.value = true
  try { tableOptions.value = await fetchDataSourceTables(dsId) || [] }
  catch { tableOptions.value = [] }
  finally { loadingTables.value = false }

  // Load columns if table is set
  const dsNode = pipelineNodes.value.find(n => n.type === 'data_source')
  if (dsNode?.config?.table) {
    await loadColumns(dsNode.config.table)
  }
}

async function onTableSelected(tableName) {
  await loadColumns(tableName)
}

async function loadColumns(tableName) {
  if (!editingPipeline.value?.dataSourceId || !tableName) { columnOptions.value = []; return }
  try {
    columnOptions.value = await fetchTableColumns(editingPipeline.value.dataSourceId, tableName) || []
    // Init feature checkboxes
    const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
    if (featNode) {
      const saved = featNode.config?.featureColumns ? JSON.parse(featNode.config.featureColumns) : []
      const checked = {}
      columnOptions.value.forEach(c => { checked[c.name] = saved.includes(c.name) })
      featChecked.value = checked
    }
  } catch { columnOptions.value = [] }
}

function onFeatSelectAll(val) {
  const checked = {}
  columnOptions.value.forEach(c => { checked[c.name] = val })
  featChecked.value = checked
  syncFeatCols()
}

function syncFeatCols() {
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  if (featNode) {
    const cols = Object.entries(featChecked.value).filter(([, v]) => v).map(([k]) => k)
    featNode.config.featureColumns = JSON.stringify(cols)
  }
}

function onModelTypeChange() {
  if (!selectedNode.value) return
  const c = selectedNode.value.config
  c.algorithm = algoMap[c.modelType]?.[0]?.value || 'random_forest'
  const params = algorithmParams(c.algorithm)
  c.hyperparams = {}
  params.forEach(p => { c.hyperparams[p.key] = p.default !== undefined ? p.default : (p.type === 'int' ? 100 : p.type === 'float' ? 0.1 : '') })
}

// Init
loadPipelines()

function parseMetrics(json) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || {} } catch { return {} }
}

function metricLabel(key) {
  const names = { accuracy: '准确率', precision: '精确率', recall: '召回率', f1: 'F1', mse: 'MSE', rmse: 'RMSE', r2: 'R²' }
  return names[key] || key
}

const topFeatures = computed(() => {
  if (!lastRunResult.value?.featureImportance) return []
  const parsed = parseMetrics(lastRunResult.value.featureImportance)
  return Object.entries(parsed)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([name, value]) => ({ name, value }))
})

const algorithmLabels = {
  random_forest: '随机森林', xgboost: 'XGBoost', decision_tree: '决策树',
  logistic_regression: '逻辑回归', svm: 'SVM', knn: 'KNN',
  kmeans: 'K-Means', gradient_boosting: '梯度提升'
}
function algorithmLabel(a) { return algorithmLabels[a] || a }

const modelTypeLabels = { classification: '分类', regression: '回归', clustering: '聚类' }
function modelTypeLabel(t) { return modelTypeLabels[t] || t }
</script>

<style scoped>
.pipeline-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.pipeline-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.list-header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.list-count {
  color: var(--text-muted);
  font-size: 13px;
}

.empty-pipelines {
  text-align: center;
  padding: 60px 0;
  color: var(--text-muted);
}

.pipeline-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 16px;
}

.pipeline-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px;
  cursor: pointer;
  transition: all 0.2s;
}

.pipeline-card:hover {
  box-shadow: var(--shadow-md);
  border-color: var(--primary);
}

.pipeline-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.pipeline-name {
  font-weight: 600;
  font-size: 15px;
  color: var(--text-primary);
  cursor: pointer;
  transition: color 0.15s;
}
.pipeline-name:hover {
  color: var(--primary);
}

.pipeline-card-meta {
  display: flex;
  gap: 12px;
  color: var(--text-muted);
  font-size: 12px;
  margin-bottom: 10px;
}

.pipeline-card-flow {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 8px 0;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-secondary);
}

.mini-arrow {
  color: var(--text-muted);
}

.mini-more {
  color: var(--text-muted);
}

.pipeline-card-actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}

/* Editor Toolbar */
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border);
  margin-bottom: 20px;
}

.toolbar-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

/* Flow Canvas */
.flow-canvas {
  flex: 1;
  overflow-x: auto;
  overflow-y: auto;
  padding: 20px;
}

.nodes-flow {
  display: flex;
  align-items: center;
  gap: 0;
  min-width: max-content;
  padding: 20px 0;
}

.flow-node {
  position: relative;
  display: flex;
  background: var(--surface);
  border: 2px solid var(--border);
  border-radius: 12px;
  min-width: 170px;
  max-width: 210px;
  cursor: pointer;
  transition: all 0.25s;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.flow-node:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  transform: translateY(-1px);
}

.flow-node.selected {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.flow-node.running {
  border-color: var(--warning);
  animation: pulse 1s infinite;
}

.flow-node.done {
  border-color: var(--success);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.node-color-bar {
  width: 4px;
  flex-shrink: 0;
}

.flow-node.data_source .node-color-bar { background: var(--primary); }
.flow-node.preprocessing .node-color-bar { background: var(--success); }
.flow-node.feature_engineering .node-color-bar { background: var(--warning); }
.flow-node.training .node-color-bar { background: #9B59B6; }
.flow-node.evaluation .node-color-bar { background: #00BCD4; }

.node-body {
  padding: 12px;
  flex: 1;
}

.node-header {
  display: flex;
  align-items: center;
  gap: 6px;
}

.node-icon {
  font-size: 16px;
}

.node-title {
  font-size: 13px;
  font-weight: 600;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-more {
  padding: 0 !important;
  min-width: 20px;
}

.node-summary {
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-muted);
  line-height: 1.4;
}

/* Flow Connector */
.flow-connector {
  display: flex;
  align-items: center;
  gap: 0;
  position: relative;
  padding: 0 4px;
}

.connector-line {
  width: 36px;
  height: 2px;
  background: var(--border);
  position: relative;
}

.connector-line::after {
  content: '';
  position: absolute;
  right: -5px;
  top: -4px;
  border: 5px solid transparent;
  border-left: 6px solid var(--border);
}

.connector-add {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--surface);
  border: 1px dashed var(--border);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: var(--text-muted);
  cursor: pointer;
  transition: all 0.2s;
  flex-shrink: 0;
}

.connector-add:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-light);
}

/* Config Panel */
.config-panel {
  padding: 0 8px;
}

.config-footer {
  padding-top: 16px;
  border-top: 1px solid var(--border);
  margin-top: 16px;
}

.param-row {
  margin-bottom: 12px;
}

.param-label {
  display: block;
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}

.param-hint {
  color: var(--text-muted);
  font-size: 11px;
  margin-left: 4px;
}

/* Column picker */
.column-picker {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px;
}

.column-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 4px;
  max-height: 200px;
  overflow-y: auto;
  margin: 6px 0;
}

.col-name {
  font-size: 12px;
}

.col-type {
  font-size: 10px;
  color: var(--text-muted);
  margin-left: 4px;
}

.selected-count {
  font-size: 11px;
  color: var(--text-muted);
}

.col-hint {
  font-size: 12px;
  color: var(--text-muted);
}

/* Step Picker */
.step-picker {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.step-option:hover {
  border-color: var(--primary);
  background: var(--primary-light);
}

.step-option-icon {
  font-size: 24px;
  width: 40px;
  text-align: center;
}

.step-option-info {
  display: flex;
  flex-direction: column;
}

.step-option-title {
  font-weight: 600;
  font-size: 14px;
}

.step-option-desc {
  color: var(--text-muted);
  font-size: 12px;
}

/* Execution Results */
.run-results {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 16px;
  margin-top: 16px;
}
.results-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.results-title { font-weight: 600; font-size: 14px; }
.results-metrics { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.result-metric {
  background: var(--color-success-light); border-radius: var(--radius-md);
  padding: 8px 16px; text-align: center; min-width: 80px;
}
.rm-value { display: block; font-size: 18px; font-weight: 700; color: var(--color-success); }
.rm-label { display: block; font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.results-meta { font-size: 12px; color: var(--text-muted); margin-bottom: 12px; display: flex; gap: 6px; }
.results-features { margin-top: 8px; }
.rf-title { font-size: 12px; color: var(--text-secondary); font-weight: 500; }
.rf-bars { display: flex; flex-direction: column; gap: 4px; margin-top: 6px; }
.rf-bar-row { display: flex; align-items: center; gap: 8px; }
.rf-name { width: 100px; font-size: 11px; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rf-track { flex: 1; height: 6px; background: var(--border-light); border-radius: 3px; overflow: hidden; }
.rf-fill { height: 100%; background: var(--primary); border-radius: 3px; }
.rf-val { width: 40px; font-size: 11px; color: var(--text-muted); text-align: right; }
</style>
