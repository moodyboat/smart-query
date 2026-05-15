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
              {{ nodeIcon(n.type) }} {{ nodeLabel(n) }}
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

      <div class="editor-body">
        <!-- Algorithm Palette (Left) -->
        <div class="algorithm-palette">
          <div class="palette-title">算法库</div>
          <div v-for="group in algorithmGroups" :key="group.category" class="palette-group">
            <div class="palette-group-title">{{ group.category }}</div>
            <div
              v-for="algo in group.algorithms"
              :key="algo.algorithmId"
              class="palette-card"
              draggable="true"
              @dragstart="onPaletteDragStart($event, algo)"
            >
              <span class="palette-icon">{{ algo.icon || '🤖' }}</span>
              <div class="palette-info">
                <span class="palette-name">{{ algo.name }}</span>
                <span class="palette-types">{{ modelTypeNames(algo.modelTypes) }}</span>
              </div>
            </div>
          </div>
          <div class="palette-group">
            <div class="palette-group-title">基础节点</div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'data_source')">
              <span class="palette-icon">📥</span>
              <span class="palette-name">数据接入</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'preprocessing')">
              <span class="palette-icon">🔧</span>
              <span class="palette-name">预处理</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'fill_missing')">
              <span class="palette-icon">🩹</span>
              <span class="palette-name">填充缺失值</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'feature_engineering')">
              <span class="palette-icon">⚙️</span>
              <span class="palette-name">特征工程</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'evaluation')">
              <span class="palette-icon">📊</span>
              <span class="palette-name">模型评估</span>
            </div>
            <div class="palette-card" draggable="true" @dragstart="onPaletteDragStart($event, null, 'output')">
              <span class="palette-icon">💾</span>
              <span class="palette-name">输出写入</span>
            </div>
          </div>
        </div>

        <!-- Drop Canvas (Center) -->
        <div
          class="flow-canvas"
          @dragover.prevent
          @drop="onCanvasDrop"
          @click.self="selectedNodeId = null"
        >
          <div v-if="pipelineNodes.length === 0" class="canvas-empty">
            <p>拖拽左侧算法或节点到此处开始编排</p>
          </div>
          <div class="nodes-flow">
            <template v-for="(node, idx) in pipelineNodes" :key="node.id">
              <!-- Node Card -->
              <div
                class="flow-node"
                :class="[node.type, { selected: selectedNodeId === node.id, running: runningNodeId === node.id, done: doneNodeIds.has(node.id) }]"
                draggable="true"
                @dragstart="onNodeReorderStart($event, idx)"
                @dragend="onDragEnd"
                @click.stop="selectNode(node)"
              >
                <div class="node-color-bar"></div>
                <div class="node-body">
                  <div class="node-header">
                    <span class="node-icon">{{ nodeIcon(node.type) }}</span>
                    <span class="node-title">{{ node.config?.title || nodeTitle(node.type) }}</span>
                    <span :class="['node-status-dot', isNodeConfigured(node) ? 'configured' : 'unconfigured']"
                      :title="isNodeConfigured(node) ? '已配置' : '需要配置'"></span>
                    <el-dropdown trigger="click" @command="cmd => onNodeCmd(cmd, idx)" @click.stop size="small">
                      <el-button size="small" text class="node-more" @click.stop>⋯</el-button>
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

              <!-- Connection with drop zone -->
              <div
                class="flow-connector"
                @dragover.prevent="onConnectorDragOver($event)"
                @dragleave="onConnectorDragLeave($event)"
                @drop.stop="onConnectorDrop($event, idx + 1)"
              >
                <div class="connector-line"></div>
                <div class="connector-drop-hint">+</div>
              </div>
            </template>
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
              <el-form-item label="特征变换">
                <div class="transform-list">
                  <div v-for="(tf, i) in (selectedNode.config.transforms || [])" :key="i" class="transform-row">
                    <el-select v-model="tf.type" style="width: 120px" size="small" :teleported="false">
                      <el-option label="对数变换" value="log" />
                      <el-option label="多项式" value="polynomial" />
                      <el-option label="分箱" value="binning" />
                      <el-option label="标准化" value="standardize" />
                      <el-option label="交互项" value="interaction" />
                    </el-select>
                    <el-select v-model="tf.columns" multiple placeholder="选择列" style="flex: 1" size="small" :teleported="false" filterable>
                      <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                    </el-select>
                    <el-input-number v-if="tf.type === 'polynomial'" v-model="tf.degree" :min="2" :max="4" size="small" style="width: 80px" />
                    <el-input-number v-if="tf.type === 'binning'" v-model="tf.bins" :min="2" :max="20" size="small" style="width: 80px" />
                    <el-button size="small" text type="danger" @click="removeTransform(i)">X</el-button>
                  </div>
                  <el-button size="small" type="primary" link @click="addTransform">+ 添加变换</el-button>
                </div>
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
                  <el-option v-for="mt in modelTypes" :key="mt.id" :label="mt.name" :value="mt.id" />
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
              <el-form-item label="验证模式">
                <el-select v-model="selectedNode.config.validationMode" style="width: 100%" :teleported="false" @change="val => { if (val !== 'temporal') selectedNode.config.temporalColumn = null }">
                  <el-option label="训练/测试分割" value="train_test" />
                  <el-option label="交叉验证" value="cv" />
                  <el-option label="样本外验证" value="oos" />
                  <el-option label="时间外验证" value="temporal" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="selectedNode.config.validationMode === 'temporal'" label="时间列">
                <el-select v-model="selectedNode.config.temporalColumn" placeholder="选择时间列" style="width: 100%" :teleported="false" filterable>
                  <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                </el-select>
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

          <!-- Fill Missing Config -->
          <template v-if="selectedNode.type === 'fill_missing'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="填充策略">
                <el-select v-model="selectedNode.config.strategy" style="width: 100%" :teleported="false">
                  <el-option label="自动 (数值列填均值，分类列填众数)" value="auto" />
                  <el-option label="均值填充" value="mean" />
                  <el-option label="中位数填充" value="median" />
                  <el-option label="众数填充" value="mode" />
                  <el-option label="固定值" value="constant" />
                </el-select>
              </el-form-item>
              <el-form-item label="指定列">
                <el-select v-model="selectedNode.config.columns" multiple placeholder="留空=全部列" style="width: 100%" :teleported="false" filterable>
                  <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name" />
                </el-select>
                <div style="font-size: 12px; color: var(--text-muted); margin-top: 4px">不选则对所有含缺失的列填充</div>
              </el-form-item>
            </el-form>
          </template>

          <!-- Output Config -->
          <template v-if="selectedNode.type === 'output'">
            <el-form label-width="100px" size="small">
              <el-form-item label="节点名称">
                <el-input v-model="selectedNode.config.title" />
              </el-form-item>
              <el-form-item label="输出表名">
                <el-input v-model="selectedNode.config.table" placeholder="如: prediction_results" />
              </el-form-item>
              <el-form-item label="写入模式">
                <el-select v-model="selectedNode.config.mode" style="width: 100%" :teleported="false">
                  <el-option label="追加 (append)" value="append" />
                  <el-option label="替换 (replace)" value="replace" />
                </el-select>
              </el-form-item>
              <el-form-item label="自动建表">
                <el-switch v-model="selectedNode.config.autoCreate" active-text="表不存在时自动创建" />
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
  updateMiningPipeline, deleteMiningPipeline, executeMiningPipeline,
  fetchDataSourceTables, fetchTableColumns
} from '../api'
import axios from 'axios'
import { useAlgorithms } from '../composables/useAlgorithms.js'

const props = defineProps({
  dataSources: { type: Array, default: () => [] }
})

const {
  algorithms, modelTypes, algorithmGroups, loadAlgorithms,
  getAlgorithmLabel, getAlgorithmsForModelType,
  getAlgorithmParams, getDefaultHyperparams, getModelTypeLabel, modelTypeNames
} = useAlgorithms()

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
const isDragging = ref(false)
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
  { type: 'fill_missing', icon: '🩹', title: '填充缺失值', desc: '按列配置缺失值填充策略' },
  { type: 'feature_engineering', icon: '⚙️', title: '特征工程', desc: '特征选择和目标定义' },
  { type: 'training', icon: '🧠', title: '模型训练', desc: '选择算法并训练模型' },
  { type: 'evaluation', icon: '📊', title: '模型评估', desc: '评估指标和验证策略' },
  { type: 'output', icon: '💾', title: '输出写入', desc: '将预测结果写入数据库表' }
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

const algoOptions = computed(() => {
  const mt = selectedNode.value?.config?.modelType || 'classification'
  return getAlgorithmsForModelType(mt).map(a => ({ value: a.algorithmId, label: (a.icon ? a.icon + ' ' : '') + a.name }))
})

function algorithmParams(algo) {
  return getAlgorithmParams(algo)
}

const currentAlgoParams = computed(() => {
  const algo = selectedNode.value?.config?.algorithm || firstAlgorithm()
  return algorithmParams(algo)
})

const selectedFeatCount = computed(() => Object.values(featChecked.value).filter(Boolean).length)

const canRun = computed(() => {
  const hasTable = pipelineNodes.value.some(n => n.type === 'data_source' && n.config?.table)
  const hasTraining = pipelineNodes.value.some(n => n.type === 'training')
  const featNode = pipelineNodes.value.find(n => n.type === 'feature_engineering')
  const hasFeatures = featNode?.config?.featureColumns && (() => {
    try {
      const fc = featNode.config.featureColumns
      const arr = Array.isArray(fc) ? fc : JSON.parse(fc)
      return arr.length > 0
    } catch { return false }
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
  return { data_source: '📥', preprocessing: '🔧', fill_missing: '🩹', feature_engineering: '⚙️', training: '🧠', evaluation: '📊', output: '💾' }[type] || '📦'
}

function nodeTitle(type) {
  return { data_source: '数据接入', preprocessing: '数据预处理', fill_missing: '填充缺失值', feature_engineering: '特征工程', training: '模型训练', evaluation: '模型评估', output: '输出写入' }[type] || type
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
      return fc.length ? `${fc.length} 列特征` + (c.targetColumn ? ` → ${c.targetColumn}` : '') : '未配置'
    }
    case 'training': {
      return c.algorithm ? getAlgorithmLabel(c.algorithm) : '未配置'
    }
    case 'evaluation': {
      const vm = c.validationMode
      if (vm === 'temporal') return `时间外验证 (${c.temporalColumn || '?'})`
      if (vm === 'cv') return `${c.cvFold || 5}-Fold CV`
      if (vm === 'oos') return `OOS ${c.cvFold || 5}-Fold + 测试集 ${c.testSize || 20}%`
      return `测试集 ${c.testSize || 20}%`
    }
    case 'output': return c.table ? `→ ${c.table}` + (c.mode === 'replace' ? ' (替换)' : '') : '未配置'
    default: return ''
  }
  } catch { return '' }
}

function defaultNodeConfig(type) {
  switch (type) {
    case 'data_source': return { title: '数据接入', table: '', filter: '' }
    case 'preprocessing': return { title: '数据预处理', handleMissing: 'drop', encoding: 'label', scaling: 'standard' }
    case 'fill_missing': return { title: '填充缺失值', strategy: 'auto', columns: [], fillValues: {} }
    case 'feature_engineering': return { title: '特征工程', featureColumns: '[]', targetColumn: '' }
    case 'training': return { title: '模型训练', modelType: firstModelType(), algorithm: firstAlgorithm(), hyperparams: {} }
    case 'evaluation': return { title: '模型评估', testSize: 20, cvFold: 0, validationMode: 'train_test', temporalColumn: null }
    case 'output': return { title: '输出写入', table: '', mode: 'append', autoCreate: true }
    default: return { title: type }
  }
}

function firstModelType() {
  return modelTypes.value.length > 0 ? modelTypes.value[0].id : 'classification'
}

function firstAlgorithm() {
  return algorithms.value.length > 0 ? algorithms.value[0].algorithmId : 'random_forest'
}

function isNodeConfigured(node) {
  const c = node.config || {}
  switch (node.type) {
    case 'data_source': return !!c.table
    case 'preprocessing': return true
    case 'fill_missing': return true
    case 'feature_engineering': {
      const fc = c.featureColumns ? (typeof c.featureColumns === 'string' ? JSON.parse(c.featureColumns) : c.featureColumns) : []
      return fc.length > 0
    }
    case 'training': return !!c.algorithm
    case 'evaluation': return true
    case 'output': return !!c.table
    default: return true
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
    { id: 'n5', type: 'evaluation', config: { ...defaultNodeConfig('evaluation') } },
    { id: 'n6', type: 'output', config: { ...defaultNodeConfig('output') } }
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
  pipelineNodes.value = normalizeNodes(parsedNodes(p))
  selectedNodeId.value = null
  showNodeConfig.value = false
  runningNodeId.value = null
  doneNodeIds.value = new Set()
}

function normalizeNodes(nodes) {
  return nodes.map(n => {
    const config = { ...n.config }
    // Normalize hyperparameters → hyperparams for training nodes
    if (n.type === 'training' && config.hyperparameters && !config.hyperparams) {
      config.hyperparams = config.hyperparameters
      delete config.hyperparameters
    }
    // Ensure hyperparams exists
    if (n.type === 'training' && !config.hyperparams) {
      config.hyperparams = {}
    }
    // Normalize featureColumns: array → JSON string for consistency
    if (n.type === 'feature_engineering' && Array.isArray(config.featureColumns)) {
      config.featureColumns = JSON.stringify(config.featureColumns)
    }
    return { ...n, config }
  })
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
    // Clean float precision in training node hyperparams
    for (const node of pipelineNodes.value) {
      if (node.type === 'training' && node.config?.hyperparams) {
        const params = algorithmParams(node.config.algorithm)
        for (const p of params) {
          if (p.type === 'float' && typeof node.config.hyperparams[p.key] === 'number') {
            node.config.hyperparams[p.key] = Math.round(node.config.hyperparams[p.key] * 10000) / 10000
          }
        }
      }
    }
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
    // Animate through nodes while backend executes
    const executePromise = executeMiningPipeline(editingPipeline.value.id)
    const animDelay = 600
    for (const node of pipelineNodes.value) {
      runningNodeId.value = node.id
      await new Promise(r => setTimeout(r, animDelay))
      doneNodeIds.value.add(node.id)
    }
    runningNodeId.value = null

    // Wait for backend execution to complete
    const result = await executePromise
    lastRunResult.value = result

    await loadPipelines()
    ElMessage.success(`流程执行完成 — 模型ID: ${result.modelId}`)
  } catch (e) {
    await updateMiningPipeline(editingPipeline.value.id, { status: 'failed' })
    ElMessage.error('执行失败: ' + (e.message || '未知错误'))
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
      const raw = featNode.config?.featureColumns
      const saved = raw ? (Array.isArray(raw) ? raw : JSON.parse(raw)) : []
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

function addTransform() {
  if (!selectedNode.value) return
  const transforms = selectedNode.value.config.transforms || []
  transforms.push({ type: 'log', columns: [], degree: 2, bins: 5 })
  selectedNode.value.config.transforms = [...transforms]
}

function removeTransform(idx) {
  if (!selectedNode.value) return
  const transforms = [...(selectedNode.value.config.transforms || [])]
  transforms.splice(idx, 1)
  selectedNode.value.config.transforms = transforms
}

function onModelTypeChange() {
  if (!selectedNode.value) return
  const c = selectedNode.value.config
  const available = getAlgorithmsForModelType(c.modelType)
  c.algorithm = available.length ? available[0].algorithmId : firstAlgorithm()
  const defaults = getDefaultHyperparams(c.algorithm)
  c.hyperparams = { ...defaults }
}

// Init
loadAlgorithms()
loadPipelines()

// Drag and Drop handlers
function onPaletteDragStart(event, algo, nodeType) {
  const data = algo
    ? { type: 'algorithm', algorithmId: algo.algorithmId, name: algo.name, modelTypes: algo.modelTypes }
    : { type: 'node', nodeType }
  event.dataTransfer.setData('application/json', JSON.stringify(data))
  event.dataTransfer.effectAllowed = 'copy'
}

function onNodeReorderStart(event, idx) {
  isDragging.value = true
  event.dataTransfer.setData('application/json', JSON.stringify({ type: 'reorder', fromIdx: idx }))
  event.dataTransfer.effectAllowed = 'move'
}

function onDragEnd() {
  isDragging.value = false
}

function onCanvasDrop(event) {
  const raw = event.dataTransfer.getData('application/json')
  if (!raw) return
  const data = JSON.parse(raw)

  // Find closest insertion index based on drop position
  const insertIdx = findClosestInsertIndex(event.clientX, event.clientY)

  if (data.type === 'algorithm') {
    addAlgorithmNode(data, insertIdx)
  } else if (data.type === 'node') {
    addStep(insertIdx, data.nodeType)
  } else if (data.type === 'reorder') {
    reorderNode(data.fromIdx, insertIdx)
  }
}

function findClosestInsertIndex(clientX, clientY) {
  const nodes = document.querySelectorAll('.flow-node')
  if (nodes.length === 0) return 0
  let closest = nodes.length
  let minDist = Infinity
  nodes.forEach((el, i) => {
    const rect = el.getBoundingClientRect()
    const midY = rect.top + rect.height / 2
    const dist = Math.abs(clientY - midY)
    if (dist < minDist) {
      minDist = dist
      closest = clientY < midY ? i : i + 1
    }
  })
  return closest
}

function onConnectorDrop(event, insertIdx) {
  event.stopPropagation()
  event.currentTarget?.classList?.remove('connector-drag-over')
  const raw = event.dataTransfer.getData('application/json')
  if (!raw) return
  const data = JSON.parse(raw)
  if (data.type === 'algorithm') {
    addAlgorithmNode(data, insertIdx)
  } else if (data.type === 'node') {
    addStep(insertIdx, data.nodeType)
  } else if (data.type === 'reorder') {
    reorderNode(data.fromIdx, insertIdx)
  }
}

function onConnectorDragOver(event) {
  event.currentTarget?.classList?.add('connector-drag-over')
}

function onConnectorDragLeave(event) {
  event.currentTarget?.classList?.remove('connector-drag-over')
}

function addAlgorithmNode(algoData, idx) {
  const id = 'n' + Date.now()
  const types = parseJson(algoData.modelTypes, ['classification'])
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

function reorderNode(fromIdx, toIdx) {
  if (fromIdx === toIdx || fromIdx === toIdx - 1) return
  const [node] = pipelineNodes.value.splice(fromIdx, 1)
  const insertAt = fromIdx < toIdx ? toIdx - 1 : toIdx
  pipelineNodes.value.splice(insertAt, 0, node)
}

function selectNode(node) {
  if (isDragging.value) return
  selectedNodeId.value = node.id
  showNodeConfig.value = true
}

function parseJson(val, fallback) {
  if (!val) return fallback
  if (typeof val === 'string') { try { return JSON.parse(val) } catch { return fallback } }
  return val
}

function nodeLabel(node) {
  if (node.type === 'training' && node.config?.algorithm) {
    return getAlgorithmLabel(node.config.algorithm)
  }
  return nodeTitle(node.type)
}

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

const algorithmLabel = getAlgorithmLabel
const modelTypeLabel = getModelTypeLabel
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

/* Editor Body - Three column layout */
.editor-body {
  flex: 1;
  display: flex;
  overflow: hidden;
}

/* Algorithm Palette (Left) */
.algorithm-palette {
  width: 200px;
  min-width: 200px;
  border-right: 1px solid var(--border);
  overflow-y: auto;
  padding: 12px;
  background: var(--surface);
}

.palette-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 12px;
  color: var(--text);
}

.palette-group {
  margin-bottom: 12px;
}

.palette-group-title {
  font-size: 12px;
  color: var(--text-muted);
  padding: 4px 0;
  border-bottom: 1px solid var(--border);
  margin-bottom: 6px;
}

.palette-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  margin-bottom: 4px;
  border-radius: 8px;
  cursor: grab;
  transition: background 0.15s;
  border: 1px solid transparent;
}

.palette-card:hover {
  background: var(--hover);
  border-color: var(--border);
}

.palette-card:active {
  cursor: grabbing;
}

.palette-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.palette-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.palette-name {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-types {
  font-size: 11px;
  color: var(--text-muted);
}

/* Flow Canvas */
.flow-canvas {
  flex: 1;
  overflow: auto;
  padding: 20px;
}

.canvas-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 300px;
  color: var(--text-muted);
  font-size: 14px;
  border: 2px dashed var(--border);
  border-radius: 12px;
  margin: 20px;
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
.flow-node.fill_missing .node-color-bar { background: #E91E63; }
.flow-node.feature_engineering .node-color-bar { background: var(--warning); }
.flow-node.training .node-color-bar { background: #9B59B6; }
.flow-node.evaluation .node-color-bar { background: #00BCD4; }
.flow-node.output .node-color-bar { background: #FF9800; }

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

.node-status-dot {
  width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0;
}
.node-status-dot.configured { background: var(--el-color-success); }
.node-status-dot.unconfigured { background: var(--el-color-danger); animation: pulse-dot 2s infinite; }
@keyframes pulse-dot { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }

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
  padding: 6px 8px;
  min-width: 44px;
  transition: background 0.15s;
  cursor: default;
}

.flow-connector:hover {
  background: var(--primary-light);
  border-radius: 4px;
}

.flow-connector.connector-drag-over {
  background: var(--primary-light);
  border-radius: 4px;
  padding: 6px 12px;
}

.flow-connector.connector-drag-over .connector-drop-hint {
  opacity: 1;
  transform: scale(1);
}

.connector-drop-hint {
  position: absolute;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--primary);
  color: white;
  font-size: 14px;
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transform: scale(0.5);
  transition: all 0.15s;
  pointer-events: none;
  left: 50%;
  top: 50%;
  margin-left: -10px;
  margin-top: -10px;
  z-index: 2;
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
.transform-list { display: flex; flex-direction: column; gap: 6px; }
.transform-row { display: flex; gap: 4px; align-items: center; }

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
