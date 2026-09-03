<template>
  <div :class="['page-container', 'mining-manager', { embedded: props.embedded }]">
    <!-- Header -->
    <div class="page-header">
      <button v-if="!props.embedded" class="back-btn" @click="$emit('close')">
        <span class="back-arrow">&larr;</span> 返回问数
      </button>
      <div class="mining-heading">
        <h2 class="page-title">{{ repositoryTitle }}</h2>
        <p v-if="props.embedded">{{ repositorySubtitle }}</p>
      </div>
      <el-select v-model="filterDsId" placeholder="数据源" size="small" clearable style="width: 160px; margin-left: auto">
        <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
    </div>

    <div v-if="props.repositoryMode && props.repositorySection === 'schedule'" class="repository-summary">
      <div><strong>{{ models.length }}</strong><span>模型总数</span></div>
      <div><strong>{{ artifactCount }}</strong><span>已固化制品</span></div>
      <div><strong>{{ publishedCount }}</strong><span>流程可用</span></div>
      <div><strong>{{ scheduledCount }}</strong><span>启用调度</span></div>
    </div>

    <el-tabs v-model="activeTab" class="mining-tabs">
      <el-tab-pane v-if="props.repositoryMode && props.repositorySection === 'schedule'" label="模型成品" name="schedule">
        <ModelScheduleCenter
          :models="models"
          :loading="loading"
          :data-sources="dataSources"
          :catalog-items="mlOperatorCatalog"
          :is-admin="userStore.isAdmin"
          :algorithm-label="algorithmLabel"
          :model-type-label="modelTypeLabel"
          :primary-metric-label="primaryMetricLabel"
          :primary-metric-value="primaryMetricValue"
          :schedule-interval-label="scheduleIntervalLabel"
          :format-date="formatDate"
          @configure="openSchedule"
          @open-dag="openModelDag"
        />
      </el-tab-pane>
      <el-tab-pane v-if="props.repositoryMode && props.repositorySection === 'pipeline'" label="流水线草稿" name="pipeline">
        <PipelineEditor ref="pipelineEditorRef" :dataSources="dataSources" repository-mode unified-dag
          @goToModel="goToModel" @openDag="openPipelineDag" />
      </el-tab-pane>
      <el-tab-pane v-if="!props.repositoryMode" label="模型管理" name="models">
        <div class="tab-toolbar">
          <div class="toolbar-left">
            <el-input v-model="modelSearch" placeholder="搜索模型名称、算法、表名..." size="small" clearable style="width:260px" prefix-icon="Search" />
            <div v-if="selectedModels.size > 0" class="selection-info">
              <span class="selected-count">✓ 已选择 {{ selectedModels.size }} 个模型</span>
              <el-button type="danger" size="small" @click="handleBatchDelete">🗑️ 批量删除</el-button>
              <el-button size="small" @click="clearSelection">取消选择</el-button>
            </div>
            <div v-else class="batch-hint">
              <span class="hint-text">💡 提示：点击模型卡片左上角的复选框可批量选择模型进行批量删除</span>
            </div>
          </div>
          <el-button type="primary" size="small" @click="showCreateDialog = true">+ 新建模型</el-button>
        </div>
        <!-- Model List -->
        <ModelList
          :models="models"
          :filteredModels="filteredModels"
          :loading="loading"
          :modelSearch="modelSearch"
          :trainingId="trainingId"
          :dataSources="dataSources"
          :algorithmLabel="algorithmLabel"
          :modelTypeLabel="modelTypeLabel"
          :modelTypeIcon="modelTypeIcon"
          :statusLabel="statusLabel"
          :scheduleIntervalLabel="scheduleIntervalLabel"
          :scheduleTooltip="scheduleTooltip"
          :parsedMetrics="parsedMetrics"
          :primaryMetricRaw="primaryMetricRaw"
          :primaryMetricLabel="primaryMetricLabel"
          :primaryMetricValue="primaryMetricValue"
          :metricQuality="metricQuality"
          :isPrimary="isPrimary"
          :formatMetricName="formatMetricName"
          :formatMetricValue="formatMetricValue"
          :needsSync="needsSync"
          :selectedModels="selectedModels"
          @select="selectModel"
          @train="doTrain"
          @editModel="editModel"
          @publish="doPublish"
          @offline="doOffline"
          @predict="openPredict"
          @batchPredict="openBatchPredict"
          @actionCmd="onActionCmd"
          @goToPipeline="goToPipeline"
          @handleSyncPipeline="handleSyncPipeline"
          @updateSelection="handleUpdateSelection"
        />
      </el-tab-pane>
      <el-tab-pane v-if="!props.repositoryMode" label="训练流水线" name="pipeline">
        <PipelineEditor ref="pipelineEditorRef" :dataSources="dataSources" @goToModel="goToModel" />
      </el-tab-pane>
      <el-tab-pane v-if="!props.repositoryMode" label="算法库管理" name="algorithms">
        <div class="algorithm-admin-page">
          <div class="tab-toolbar">
            <div>
              <h3>算法模板管理</h3>
              <p>查看内置实现；管理员可新增模板、复制内置模板，并修改或删除自定义模板。</p>
            </div>
            <el-button v-if="userStore.isAdmin" type="primary" @click="openAlgorithmManager(null, 'create')">+ 新增算法模板</el-button>
          </div>
          <el-alert v-if="!userStore.isAdmin" type="info" :closable="false" style="margin-bottom: 12px">
            当前账号为只读权限；新增、修改和删除算法模板需要管理员账号。
          </el-alert>
          <el-table :data="algorithms" border stripe height="calc(100vh - 230px)">
            <el-table-column label="算法" min-width="180">
              <template #default="{ row }">
                <span class="algorithm-name-cell"><span>{{ row.icon || '🤖' }}</span><strong>{{ row.name }}</strong></span>
              </template>
            </el-table-column>
            <el-table-column prop="algorithmId" label="算法标识" min-width="160" />
            <el-table-column prop="category" label="分类" width="120" />
            <el-table-column label="适用类型" min-width="150">
              <template #default="{ row }">{{ modelTypeNames(row.modelTypes) }}</template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }"><el-tag :type="row.isBuiltin ? 'info' : 'success'" size="small">{{ row.isBuiltin ? '内置' : '自定义' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openAlgorithmManager(row, 'view')">查看实现</el-button>
                <el-button v-if="userStore.isAdmin && row.isBuiltin" link type="primary" @click="openAlgorithmManager(row, 'clone')">复制为模板</el-button>
                <template v-if="userStore.isAdmin && !row.isBuiltin">
                  <el-button link type="primary" @click="openAlgorithmManager(row, 'edit')">修改</el-button>
                  <el-button link type="danger" @click="removeAlgorithmTemplate(row)">删除</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <AlgorithmLibraryDialog
      v-model:visible="showAlgorithmManager"
      :algorithms="algorithms"
      :categories="categories"
      :model-types="modelTypes"
      :model-type-names="modelTypeNames"
      :is-admin="userStore.isAdmin"
      :initial-algorithm="managedAlgorithm"
      :initial-mode="algorithmManagerMode"
      @refresh="refreshAlgorithmManager"
    />

    <!-- Create/Edit Dialog -->
    <ModelCreateDialog
      v-model:show="showCreateDialog"
      :editing="!!editingModel"
      :form="form"
      :saving="saving"
      :dataSources="dataSources"
      :modelTypes="modelTypes"
      :filteredAlgorithms="filteredAlgorithms"
      :columnOptions="columnOptions"
      :loadingTables="loadingTables"
      :tableOptions="tableOptions"
      :featureChecked="featureChecked"
      :selectAllFeatures="selectAllFeatures"
      :featureIndeterminate="featureIndeterminate"
      @update:form="onFormUpdate"
      @save="handleSave"
      @saveAndTrain="handleSaveAndTrain"
      @dataSourceChange="onDataSourceChange"
      @tableChange="onTableChange"
      @selectAllFeatures="onSelectAllFeatures"
      @toggleFeature="onToggleFeature"
      @syncFeatureColumns="syncFeatureColumns"
    />

    <!-- Hyperparameter Edit Dialog -->
    <el-dialog v-model="showParamsDialog" title="调整超参数" width="520px" destroy-on-close>
      <div v-if="paramModel" class="params-editor">
        <p class="params-model-name">{{ paramModel.name }} - {{ algorithmLabel(paramModel.algorithm) }}</p>
        <div v-for="p in algorithmParams(paramModel.algorithm)" :key="p.key" class="param-row">
          <label class="param-label">
            {{ p.label }}
            <span v-if="p.hint" class="param-hint">{{ p.hint }}</span>
          </label>
          <el-input-number v-if="p.type === 'int'" v-model="paramForm[p.key]"
            :min="p.min" :max="p.max" :step="p.step || 1" size="small" style="width: 180px" />
          <el-input-number v-else-if="p.type === 'float'" v-model="paramForm[p.key]"
            :min="p.min" :max="p.max" :step="p.step || 0.1" :precision="2" size="small" style="width: 180px" />
          <el-select v-else-if="p.type === 'select'" v-model="paramForm[p.key]"
            size="small" style="width: 180px" :teleported="false">
            <el-option v-for="o in p.options" :key="o" :label="o" :value="o" />
          </el-select>
          <el-switch v-else-if="p.type === 'boolean'" v-model="paramForm[p.key]" />
          <el-input v-else v-model="paramForm[p.key]" size="small" style="width: 180px" />
        </div>
        <el-divider v-if="customParamKeys.length" content-position="left">自定义参数</el-divider>
        <div v-for="key in customParamKeys" :key="key" class="param-row">
          <label class="param-label"><code>{{ key }}</code></label>
          <el-input v-model="paramForm[key]" size="small" style="width: 180px" />
          <el-button text type="danger" size="small" @click="removeCustomParam(key)">删除</el-button>
        </div>
        <el-button size="small" type="primary" link @click="showAddParamDialog = true" style="margin-top: 8px">+ 添加自定义参数</el-button>
      </div>
      <template #footer>
        <el-button @click="showParamsDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingParams" @click="handleSaveParams">保存参数</el-button>
      </template>
    </el-dialog>

    <!-- Add custom param dialog -->
    <el-dialog v-model="showAddParamDialog" title="添加自定义参数" width="360px" append-to-body>
      <el-form label-width="80px" size="small">
        <el-form-item label="参数名">
          <el-input v-model="newParamKey" placeholder="如: min_samples_split" />
        </el-form-item>
        <el-form-item label="参数值">
          <el-input v-model="newParamValue" placeholder="如: 5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="showAddParamDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmAddParam">确定</el-button>
      </template>
    </el-dialog>

    <!-- Schedule Dialog -->
    <ModelScheduleDialog
      v-model:show="showScheduleDialog"
      :model="scheduleModel"
      v-model:enabled="scheduleEnabled"
      v-model:mode="scheduleMode"
      v-model:cron="scheduleCron"
      v-model:inputTable="scheduleInputTable"
      v-model:resultTable="scheduleResultTable"
      v-model:inputFilter="scheduleInputFilter"
      :tableOptions="scheduleTableOptions"
      @save="doSaveSchedule"
    />

    <!-- Publish Dialog -->
    <el-dialog v-model="showPublishDialog" title="发布模型" width="560px" destroy-on-close>
      <div v-if="publishModel_ref">
        <p style="margin-bottom: 16px; color: var(--text-secondary)">
          发布「{{ publishModel_ref.name }}」后可进行批量预测和定时调度
        </p>
        <el-form label-width="100px" size="default">
          <el-form-item label="预测输入表">
            <el-select v-model="publishConfig.predictInputTable" placeholder="选择预测输入表（可选）" style="width: 100%" :teleported="false" filterable clearable>
              <el-option v-for="t in publishTableOptions" :key="t.name" :label="t.name" :value="t.name">
                <span>{{ t.name }}</span>
                <span style="float: right; color: var(--text-muted); font-size: var(--font-sm)">{{ t.rows }}行</span>
              </el-option>
            </el-select>
            <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">发布后批量预测的默认输入表</div>
          </el-form-item>
          <el-form-item label="输入筛选条件">
            <el-input v-model="publishConfig.predictInputFilter" placeholder="如: etl_date = '${etl_date}' 或 status = 'active'" />
            <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">
              支持变量: ${etl_date}、${today}、${yesterday}、${today-N}
            </div>
          </el-form-item>
          <el-form-item label="预测结果表">
            <el-input v-model="publishConfig.predictResultTable" placeholder="如: prediction_results（表不存在时自动创建）" />
            <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">留空则每次预测时指定</div>
          </el-form-item>
          <el-divider />
          <el-form-item label="启用定时调度">
            <el-switch v-model="publishConfig.scheduleEnabled" active-text="开" inactive-text="关" />
          </el-form-item>
          <el-form-item v-if="publishConfig.scheduleEnabled" label="调度模式">
            <el-radio-group v-model="publishConfig.scheduleMode">
              <el-radio value="train">定期重训</el-radio>
              <el-radio value="predict">定期预测</el-radio>
            </el-radio-group>
            <div style="font-size: var(--font-sm); color: var(--text-muted); margin-top: 4px">
              {{ publishConfig.scheduleMode === 'predict' ? '用已发布模型对新数据批量预测，结果写入结果表' : '用最新数据重新训练模型' }}
            </div>
          </el-form-item>
          <el-form-item v-if="publishConfig.scheduleEnabled" label="调度间隔">
            <el-select v-model="publishConfig.scheduleCron" style="width: 100%" :teleported="false">
              <el-option label="每 30 分钟" value="*/30 * * * *" />
              <el-option label="每 1 小时" value="0 * * * *" />
              <el-option label="每天早上 6:00" value="0 6 * * *" />
              <el-option label="每天早上 8:00" value="0 8 * * *" />
              <el-option label="每天午夜" value="0 0 * * *" />
              <el-option label="每周一 8:00" value="0 8 * * 1" />
              <el-option label="每月 1 号" value="0 0 1 * *" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="showPublishDialog = false">取消</el-button>
        <el-button type="primary" :loading="publishLoading" @click="doConfirmPublish">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- Predict / Batch Predict / Results Dialogs -->
    <ModelPredictDialog
      v-model:showPredict="showPredictDialog"
      v-model:showBatch="showBatchPredictDialog"
      v-model:showResults="showPredictResultsDialog"
      :predictModel="predictModel_ref"
      :batchPredictModel="batchPredictModel"
      :predictResultsModel="predictResultsModel"
      v-model:predictInput="predictInput"
      v-model:predictSaveTable="predictSaveTable"
      :predictResult="predictResult"
      :predictRows="predictRows"
      :predictLoading="predictLoading"
      v-model:batchInputTable="batchInputTable"
      v-model:batchResultTable="batchResultTable"
      :batchPredictResult="batchPredictResult"
      :batchPredictLoading="batchPredictLoading"
      :resultPreview="resultPreview"
      :resultPreviewColumns="resultPreviewColumns"
      :loadingPredictions="loadingPredictions"
      :predictionResults="predictionResults"
      :batchTableOptions="batchTableOptions"
      :algorithmLabel="algorithmLabel"
      :formatDate="formatDate"
      :parseJson="parseJson"
      @predict="handlePredict"
      @batchPredict="handleBatchPredict"
      @previewResult="previewResult"
    />

    <!-- Detail Drawer -->
    <ModelDetail
      v-model:show="showDetail"
      :model="detailModel"
      :loading="loading"
      :executions="executions"
      :loadingExecutions="loadingExecutions"
      :trainingId="trainingId"
      :detailPipelineNodes="detailPipelineNodes"
      :expandedNodeId="expandedNodeId"
      :syncingNode="syncingNode"
      :sortedImportance="sortedImportance"
      :maxImportance="maxImportance"
      :algorithmLabel="algorithmLabel"
      :modelTypeLabel="modelTypeLabel"
      :statusLabel="statusLabel"
      :formatDate="formatDate"
      :execStatusLabel="execStatusLabel"
      :execTriggerLabel="execTriggerLabel"
      :needsSync="needsSync"
      @train="doTrain"
      @publish="doPublish"
      @offline="doOffline"
      @predict="onDetailPredict"
      @edit="openEditModel"
      @tuneParams="onDetailTuneParams"
      @rollback="handleRollback"
      @goToPipeline="goToPipeline"
      @syncPipeline="handleSyncPipeline"
      @expandNode="expandedNodeId = $event"
      @syncNodeChanges="doSyncNodeChanges"
      @nodeParamUpdate="onNodeParamUpdate"
    />

    <!-- Training Dialog -->
    <TrainingDialog
      v-model:show="showTrainingDialog"
      :model="trainingModel"
      :algorithm-label="algorithmLabel"
      @complete="handleTrainingComplete"
      @error="handleTrainingError"
    />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PipelineEditor from './PipelineEditor.vue'
import AlgorithmLibraryDialog from './pipeline/AlgorithmLibraryDialog.vue'
import ModelList from './mining/ModelList.vue'
import ModelScheduleCenter from './mining/ModelScheduleCenter.vue'
import ModelCreateDialog from './mining/ModelCreateDialog.vue'
import ModelScheduleDialog from './mining/ModelScheduleDialog.vue'
import ModelPredictDialog from './mining/ModelPredictDialog.vue'
import ModelDetail from './mining/ModelDetail.vue'
import TrainingDialog from './mining/TrainingDialog.vue'
import {
  fetchMiningModels, fetchMiningModel, createMiningModel, updateMiningModel,
  deleteMiningModel, forceDeleteMiningModel, trainMiningModel, publishMiningModel, offlineMiningModel,
  updateModelHyperparams, fetchModelExecutions, fetchDataSources,
  fetchDataSourceTables, fetchTableColumns, updateModelSchedule, predictMiningModel,
  batchPredictMiningModel, validateMiningModel, fetchModelPredictions,
  fetchMiningPipeline, previewResultTable, syncModelPipeline, rollbackModel,
  updateModelPredictConfig, deleteAlgorithm
} from '../api'
import { useAlgorithms } from '../composables/useAlgorithms.js'
import { useMiningStore } from '../stores/mining'
import { useUIStore } from '../stores/ui'
import { useUserStore } from '../stores/user'
import { TRAINING_SAFETY_TIMEOUT_MS, DEFAULT_MODEL_TYPE, DEFAULT_ALGORITHM, PREVIEW_ROW_LIMIT, PREDICTION_RECORD_LIMIT, MODEL_STATUS, MODEL_TYPE, EXECUTION_STATUS, NODE_TYPES, NODE_TYPE_LABELS, STATUS_LABELS, SCHEDULE_INTERVALS, FILTER_VARIABLES } from '../constants'
import { useModelDetail, pipelineNodeIcon, pipelineNodeTitle, pipelineNodeSummary, isNodeConfigured, parsedMetrics, formatMetricName, formatMetricValue, metricQuality, overfittingWarning } from '../composables/useModelDetail'
import { useModelActions } from '../composables/useModelActions'
import { isGhostModel } from '../utils/modelGhost'
import { fetchPublishedOperatorCatalog } from '../api/orchestration.js'

const props = defineProps({
  embedded: { type: Boolean, default: false },
  autoCreate: { type: Boolean, default: false },
  repositoryMode: { type: Boolean, default: false },
  repositorySection: { type: String, default: 'schedule' }
})
const emit = defineEmits(['close', 'openDag'])

const mining = useMiningStore()
const ui = useUIStore()
const userStore = useUserStore()
const repositoryTitle = computed(() => {
  if (!props.repositoryMode) return '数据挖掘'
  return props.repositorySection === 'pipeline' ? '模型流水线' : '调度中心'
})
const repositorySubtitle = computed(() => {
  if (!props.repositoryMode) return '在这里完成机器学习算子的模型创建、训练、评估与发布。'
  return props.repositorySection === 'pipeline'
    ? '保存模型训练草稿，并将业务流程交给统一 DAG 编排。'
    : '管理已发布模型成品、调度策略和生产编排入口。'
})

const {
  algorithms, modelTypes, categories, loadAlgorithms,
  getAlgorithmLabel, getAlgorithmsForModelType,
  getAlgorithmParams, getDefaultHyperparams, getModelTypeLabel, modelTypeNames
} = useAlgorithms()

const {
  showDetail, detailModel, executions, detailPipelineNodes, expandedNodeId,
  loadingExecutions, syncingNode, sortedImportance, maxImportance,
  selectModel, onNodeParamUpdate, syncNodeChanges, refreshDetail
} = useModelDetail(mining)

const {
  trainingId, activeCleanup,
  showPublishDialog, publishModel_ref, publishLoading, publishConfig, publishTableOptions,
  showScheduleDialog, scheduleModel, scheduleCron, scheduleEnabled, scheduleMode,
  scheduleInputTable, scheduleResultTable, scheduleInputFilter, scheduleTableOptions,
  showBatchPredictDialog, batchPredictModel, batchInputTable, batchResultTable,
  batchPredictLoading, batchPredictResult, resultPreview, resultPreviewColumns, batchTableOptions,
  showPredictResultsDialog, predictResultsModel, predictionResults, loadingPredictions,
  showPredictDialog, predictModel_ref, predictInput, predictSaveTable, predictLoading, predictResult,
  handleTrain, showTrainingResult,
  handlePublish, buildPublishConfig, confirmPublish,
  handleOffline, handleDelete,
  openSchedule, saveSchedule,
  openBatchPredict, handleBatchPredict, previewResult,
  openPredictResults, openPredict, handlePredict,
  handleValidate, cleanup: cleanupActions
} = useModelActions(mining)

// Shared state from Pinia store
const models = computed(() => mining.models)
const artifactCount = computed(() => models.value.filter(model => model.modelPath && model.artifactSha256).length)
const publishedCount = computed(() => models.value.filter(model => model.status === MODEL_STATUS.PUBLISHED).length)
const scheduledCount = computed(() => models.value.filter(model => model.status === MODEL_STATUS.PUBLISHED && model.scheduleEnabled).length)
const dataSources = computed(() => mining.dataSources)
const loading = computed(() => mining.loading)
const mlOperatorCatalog = ref([])
const filterDsId = computed({
  get: () => mining.filterDsId,
  set: (v) => { mining.filterDsId = v }
})
const activeTab = ref(props.repositoryMode ? props.repositorySection : 'models')
const modelSearch = ref('')
const selectedModels = ref(new Set())
const saving = ref(false)
const savingParams = ref(false)
const pipelineEditorRef = ref(null)
const showAlgorithmManager = ref(false)
const managedAlgorithm = ref(null)
const algorithmManagerMode = ref('view')

// Table/Column selectors (for create/edit dialog)
const tableOptions = ref([])
const columnOptions = ref([])
const loadingTables = ref(false)
const featureChecked = ref({})
const selectAllFeatures = ref(false)

// Create/Edit form
const showCreateDialog = ref(false)
const editingModel = ref(null)
const form = ref(defaultForm())

// Hyperparameter dialog
const showParamsDialog = ref(false)
const paramModel = ref(null)
const paramForm = ref({})
const showAddParamDialog = ref(false)
const newParamKey = ref('')
const newParamValue = ref('')

function defaultForm() {
  const firstModelType = modelTypes.value.length > 0 ? modelTypes.value[0].id : DEFAULT_MODEL_TYPE
  const firstAlgo = algorithms.value.length > 0 ? algorithms.value[0].algorithmId : DEFAULT_ALGORITHM
  return {
    name: '', dataSourceId: null, sourceTable: '', modelType: firstModelType,
    algorithm: firstAlgo, featureColumnsList: [], targetColumn: '',
    description: '',
    hyperparameters: getDefaultHyperparams(firstAlgo) || {},
    preprocessing: { handleMissing: 'drop', encoding: 'label', scaling: 'none' },
    validationMode: 'train_test', cvFolds: 5, testSize: 0.2, temporalColumn: ''
  }
}

const statusLabels = STATUS_LABELS
function statusLabel(s) { return statusLabels[s] || s }

function execStatusLabel(s) {
  return { [EXECUTION_STATUS.SUCCESS]: '成功', [EXECUTION_STATUS.FAILED]: '失败', [EXECUTION_STATUS.RUNNING]: '运行中', [EXECUTION_STATUS.PENDING]: '等待中' }[s] || s
}
function execTriggerLabel(t) {
  return { manual: '手动', schedule: '定时', chat: '对话' }[t] || t
}

const algorithmLabel = getAlgorithmLabel

const featureIndeterminate = computed(() => {
  const total = columnOptions.value.length
  const selected = form.value.featureColumnsList.length
  return selected > 0 && selected < total
})

const filteredAlgorithms = computed(() => {
  return getAlgorithmsForModelType(form.value.modelType)
})

const customParamKeys = computed(() => {
  if (!paramModel.value) return []
  const defined = new Set(algorithmParams(paramModel.value.algorithm).map(p => p.key))
  return Object.keys(paramForm.value || {}).filter(key => !defined.has(key))
})

const filteredModels = computed(() => {
  if (!modelSearch.value) return models.value
  const q = modelSearch.value.toLowerCase()
  return models.value.filter(m =>
    m.name?.toLowerCase().includes(q) ||
    m.algorithm?.toLowerCase().includes(q) ||
    m.sourceTable?.toLowerCase().includes(q) ||
    m.modelType?.toLowerCase().includes(q) ||
    m.description?.toLowerCase().includes(q)
  )
})

const modelTypeLabel = getModelTypeLabel

function isPrimary(key, modelType) {
  if (key.startsWith('train_') || key.startsWith('test_') ||
      ['overfitting_gap', 'cv_mean', 'cv_std', 'confusion_matrix', 'class_labels'].includes(key)) return false
  if (modelType === MODEL_TYPE.REGRESSION) return key === 'r2'
  if (modelType === MODEL_TYPE.CLUSTERING) return key === 'silhouette_score' || key === 'inertia'
  return key === 'accuracy'
}

function primaryMetricLabel(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === MODEL_TYPE.REGRESSION) return m.r2 !== undefined ? 'R²' : 'RMSE'
  if (model.modelType === MODEL_TYPE.CLUSTERING) return '聚类'
  return m.accuracy !== undefined ? '准确率' : 'F1'
}

function primaryMetricValue(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === MODEL_TYPE.REGRESSION) return m.r2 !== undefined ? (m.r2 * 100).toFixed(1) + '%' : m.rmse?.toFixed(4) || '-'
  if (model.modelType === MODEL_TYPE.CLUSTERING) return (m.n_clusters != null ? m.n_clusters : '-') + ' 类'
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] !== undefined ? (m[key] * 100).toFixed(1) + '%' : '-'
}

function primaryMetricRaw(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 ?? m.rmse ?? null
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] ?? null
}

function modelTypeIcon(t) {
  return { classification: '\u{1F3F7}️', regression: '\u{1F4C8}', clustering: '\u{1F3AF}', anomaly_detection: '\u{1F50D}' }[t] || '\u{1F916}'
}

function parseJson(json, fallback) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || fallback } catch { return fallback }
}
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

const cronLabelMap = {
  '*/5 * * * *': '每5分钟', '*/15 * * * *': '每15分钟', '*/30 * * * *': '每30分钟',
  '0 * * * *': '每小时', '0 6 * * *': '每天6:00', '0 8 * * *': '每天8:00',
  '0 0 * * *': '每天午夜', '0 8 * * 1': '每周一8:00', '0 0 1 * *': '每月1号',
  '*/5': '每5分钟', '*/15': '每15分钟', '*/30': '每30分钟', '*/60': '每小时',
  '*/360': '每6小时', '*/720': '每12小时', '*/1440': '每天', '*/10080': '每周'
}
function scheduleIntervalLabel(model) {
  return cronLabelMap[model.scheduleCron] || '定期'
}
function scheduleTooltip(model) {
  const mode = { train: '定期重训', predict: '定期预测' }[model.scheduleMode] || model.scheduleMode
  const interval = cronLabelMap[model.scheduleCron] || model.scheduleCron
  let tip = `${mode} · ${interval}`
  if (model.nextRunAt) tip += `\n下次运行: ${formatDate(model.nextRunAt)}`
  return tip
}

const predictRows = computed(() => {
  if (!predictResult.value) return []
  let inputRows = []
  try { inputRows = JSON.parse(predictInput.value) } catch { return [] }
  const preds = predictResult.value.predictions || []
  return inputRows.map((row, i) => ({ ...row, prediction: preds[i], probability: predictResult.value.probabilities?.[i] ? (Math.max(...predictResult.value.probabilities[i]) * 100).toFixed(1) + '%' : '' }))
})

async function loadModels() {
  await mining.loadModels()
}

async function loadMlOperatorCatalog() {
  if (!props.repositoryMode) return
  try { mlOperatorCatalog.value = await fetchPublishedOperatorCatalog('ML') }
  catch { mlOperatorCatalog.value = [] }
}

function algorithmParams(algo) {
  return getAlgorithmParams(algo)
}

async function onDataSourceChange(dsId) {
  form.value.sourceTable = ''
  form.value.featureColumnsList = []
  form.value.targetColumn = ''
  columnOptions.value = []
  featureChecked.value = {}
  if (!dsId) { tableOptions.value = []; return }
  loadingTables.value = true
  try {
    tableOptions.value = await fetchDataSourceTables(dsId) || []
  } catch { tableOptions.value = [] }
  finally { loadingTables.value = false }
}

async function onTableChange(tableName) {
  form.value.featureColumnsList = []
  form.value.targetColumn = ''
  featureChecked.value = {}
  if (!tableName || !form.value.dataSourceId) { columnOptions.value = []; return }
  try {
    columnOptions.value = await fetchTableColumns(form.value.dataSourceId, tableName) || []
  } catch { columnOptions.value = [] }
}

function onSelectAllFeatures(checked) {
  const cols = columnOptions.value.map(c => c.name)
  if (checked) {
    form.value.featureColumnsList = [...cols]
    const map = {}
    cols.forEach(c => { map[c] = true })
    featureChecked.value = map
  } else {
    form.value.featureColumnsList = []
    featureChecked.value = {}
  }
}

function onToggleFeature(colName, checked) {
  featureChecked.value = { ...featureChecked.value, [colName]: !!checked }
  syncFeatureColumns()
}

function syncFeatureColumns() {
  form.value.featureColumnsList = Object.entries(featureChecked.value)
    .filter(([, v]) => v).map(([k]) => k)
  const total = columnOptions.value.length
  selectAllFeatures.value = form.value.featureColumnsList.length === total
}

function onFormUpdate(newForm) {
  form.value = newForm
}

watch(filterDsId, () => loadModels())
watch(activeTab, tab => { if (tab === 'schedule') loadMlOperatorCatalog() })
watch(() => props.repositorySection, section => {
  if (props.repositoryMode) activeTab.value = section === 'pipeline' ? 'pipeline' : 'schedule'
})
watch(() => mining.models, () => {
  if (detailModel.value) {
    const fresh = mining.models.find(m => m.id === detailModel.value.id)
    if (fresh) detailModel.value = fresh
  }
}, { deep: true })
watch(() => form.value.modelType, (mt) => {
  const available = getAlgorithmsForModelType(mt)
  if (available.length && !available.find(a => a.algorithmId === form.value.algorithm)) {
    form.value.algorithm = available[0].algorithmId
  }
})

watch(() => form.value.algorithm, (algo) => {
  if (!algo) return
  const defaults = getDefaultHyperparams(algo)
  if (defaults && Object.keys(defaults).length > 0) {
    form.value.hyperparameters = defaults
  }
})
onMounted(async () => {
  await Promise.allSettled([loadAlgorithms(), loadModels(), loadMlOperatorCatalog()])
  const initialId = ui.consumeMiningInitialModel()
  if (initialId) {
    nextTick(() => mining.selectModel(initialId))
  }
  if (props.autoCreate) {
    activeTab.value = 'models'
    showCreateDialog.value = true
  }
})

onBeforeUnmount(() => {
  mining.closeEventSource()
  cleanupActions()
})

// Training Dialog state
const showTrainingDialog = ref(false)
const trainingModel = ref(null)

async function doTrain(id) {
  const model = models.value.find(m => m.id === id) || detailModel.value
  if (!model) return

  // 打开训练对话框，显示实时进度
  trainingModel.value = model
  showTrainingDialog.value = true

  // 同时触发实际训练（这里仍然调用原有的训练逻辑）
  return handleTrain(id, detailModel)
}

function handleTrainingComplete(result) {
  ElMessage.success('训练完成')
  loadModels() // 刷新模型列表
  if (detailModel.value) {
    refreshDetail()
  }
}

function handleTrainingError(error) {
  ElMessage.error('训练失败: ' + error.error)
}

async function doPublish(id) { return handlePublish(id, models, detailModel) }
async function doConfirmPublish() {
  const result = await confirmPublish(detailModel)
  await loadMlOperatorCatalog()
  return result
}
async function doOffline(id) { return handleOffline(id, detailModel) }
async function doDelete(id, name) { return handleDelete(id, name, showDetail, detailModel) }

// 批量选择和删除功能
function handleUpdateSelection(modelId, checked) {
  if (checked) {
    selectedModels.value.add(modelId)
  } else {
    selectedModels.value.delete(modelId)
  }
}

function clearSelection() {
  selectedModels.value.clear()
}

async function handleBatchDelete() {
  const selectedIds = Array.from(selectedModels.value)
  if (selectedIds.length === 0) return

  const selectedModelsList = models.value.filter(m => selectedIds.includes(m.id))

  // 检查是否有特殊状态的模型
  const publishedModels = selectedModelsList.filter(m => m.status === MODEL_STATUS.PUBLISHED)
  const trainingModels = selectedModelsList.filter(m => m.status === MODEL_STATUS.TRAINING)
  const ghostModels = selectedModelsList.filter(m => isGhostModel(m))

  let confirmMessage = `确定要删除选中的 ${selectedIds.length} 个模型吗？此操作不可恢复。`

  if (publishedModels.length > 0) {
    confirmMessage += `\n\n⚠️ 包含 ${publishedModels.length} 个已发布模型，将使用强制删除`
  }
  if (trainingModels.length > 0) {
    confirmMessage += `\n\n⚠️ 包含 ${trainingModels.length} 个训练中模型，将使用强制删除`
  }
  if (ghostModels.length > 0) {
    confirmMessage += `\n\n👻 包含 ${ghostModels.length} 个幽灵模型（文件丢失或状态异常），将使用强制删除`
  }

  if (!confirm(confirmMessage)) {
    return
  }

  try {
    let successCount = 0
    let forceDeleteCount = 0

    // 分别处理不同类型的模型
    for (const model of selectedModelsList) {
      try {
        const needsForceDelete = model.status === MODEL_STATUS.PUBLISHED ||
                                model.status === MODEL_STATUS.TRAINING ||
                                ghostModels.includes(model)

        if (needsForceDelete) {
          await forceDeleteMiningModel(model.id)
          forceDeleteCount++
        } else {
          await deleteMiningModel(model.id)
        }
        successCount++
      } catch (e) {
        console.error(`删除模型 ${model.name} (ID: ${model.id}) 失败:`, e)
      }
    }

    if (successCount === selectedIds.length) {
      ElMessage.success(`成功删除 ${successCount} 个模型` + (forceDeleteCount > 0 ? `（其中 ${forceDeleteCount} 个使用强制删除）` : ''))
    } else if (successCount > 0) {
      ElMessage.warning(`部分删除成功：${successCount}/${selectedIds.length}`)
    } else {
      ElMessage.error('批量删除失败')
    }

    clearSelection()
    await loadModels()
  } catch (e) {
    ElMessage.error('批量删除失败: ' + (e.message || '未知错误'))
  }
}
async function doSaveSchedule() { return saveSchedule(loadModels) }
async function doSyncNodeChanges() {
  try {
    await syncNodeChanges()
    ElMessage.success('节点参数已同步')
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.message || ''))
  }
}
function editModel(model) {
  paramModel.value = model
  const definedParams = algorithmParams(model.algorithm)
  const savedParams = (() => {
    try { return typeof model.hyperparameters === 'string' ? JSON.parse(model.hyperparameters) : model.hyperparameters || {} } catch { return {} }
  })()
  const formMap = {}
  for (const p of definedParams) {
    const raw = savedParams[p.key] !== undefined ? savedParams[p.key] : (p.defaultValue !== undefined && p.defaultValue !== null ? p.defaultValue : (p.type === 'int' ? 100 : p.type === 'float' ? 0.1 : p.type === 'select' ? (p.options?.[0] || '') : ''))
    if (p.type === 'float' && typeof raw === 'number') {
      formMap[p.key] = Math.round(raw * 10000) / 10000
    } else {
      formMap[p.key] = raw
    }
  }
  for (const [k, v] of Object.entries(savedParams)) {
    if (!(k in formMap)) formMap[k] = v
  }
  paramForm.value = formMap
  showParamsDialog.value = true
}

function confirmAddParam() {
  if (!newParamKey.value.trim()) return
  paramForm.value[newParamKey.value.trim()] = newParamValue.value
  newParamKey.value = ''
  newParamValue.value = ''
  showAddParamDialog.value = false
}

function removeCustomParam(key) {
  const next = { ...paramForm.value }
  delete next[key]
  paramForm.value = next
}

async function handleSaveParams() {
  if (!paramModel.value) return
  savingParams.value = true
  try {
    const paramsToSave = { ...paramForm.value }
    const definedParams = algorithmParams(paramModel.value.algorithm)
    for (const p of definedParams) {
      if (p.type === 'float' && typeof paramsToSave[p.key] === 'number') {
        paramsToSave[p.key] = Math.round(paramsToSave[p.key] * 10000) / 10000
      }
    }
    const model = await updateModelHyperparams(paramModel.value.id, paramsToSave)
    mining.updateModelInList(model)
    showParamsDialog.value = false
    ElMessage.success('参数已更新')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || '未知错误'))
  } finally {
    savingParams.value = false
  }
}

function resetForm() {
  editingModel.value = null
  const ds = dataSources.value.find(d => !d.system) || dataSources.value[0]
  form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
}

function openEditModel(model) {
  editingModel.value = model
  let preprocessing = { handleMissing: 'drop', encoding: 'label', scaling: 'none' }
  try { preprocessing = typeof model.preprocessing === 'string' ? JSON.parse(model.preprocessing) : model.preprocessing || preprocessing } catch {}
  let featureCols = []
  try { featureCols = typeof model.featureColumns === 'string' ? JSON.parse(model.featureColumns) : model.featureColumns || [] } catch {}

  let hyperparams = {}
  try { hyperparams = typeof model.hyperparameters === 'string' ? JSON.parse(model.hyperparameters) : model.hyperparameters || {} } catch {}

  form.value = {
    name: model.name || '',
    dataSourceId: model.dataSourceId,
    sourceTable: model.sourceTable || '',
    modelType: model.modelType || DEFAULT_MODEL_TYPE,
    algorithm: model.algorithm || DEFAULT_ALGORITHM,
    featureColumnsList: featureCols,
    targetColumn: model.targetColumn || '',
    description: model.description || '',
    hyperparameters: hyperparams,
    preprocessing: { ...preprocessing },
    validationMode: model.validationMode || 'train_test',
    cvFolds: model.cvFolds || 5,
    testSize: model.testSize || 0.2,
    temporalColumn: model.temporalColumn || ''
  }

  if (model.dataSourceId) {
    loadingTables.value = true
    fetchDataSourceTables(model.dataSourceId).then(tables => {
      tableOptions.value = tables || []
      if (model.sourceTable) {
        fetchTableColumns(model.dataSourceId, model.sourceTable).then(cols => {
          columnOptions.value = cols || []
          const checked = {}
          cols.forEach(c => { checked[c.name] = featureCols.includes(c.name) })
          featureChecked.value = checked
        }).catch(() => { columnOptions.value = [] })
      }
    }).catch(() => { tableOptions.value = [] })
    .finally(() => { loadingTables.value = false })
  }

  showDetail.value = false
  showCreateDialog.value = true
}

watch(showCreateDialog, (v) => {
  if (v && !editingModel.value) {
    const ds = dataSources.value.find(d => !d.system) || dataSources.value[0]
    form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
    if (ds?.id) onDataSourceChange(ds.id)
  } else if (!v) {
  editingModel.value = null
  form.value = defaultForm()
  tableOptions.value = []
  columnOptions.value = []
  featureChecked.value = {}
  }
  })

async function handleSave() {
  if (!form.value.name || !form.value.dataSourceId || !form.value.sourceTable || !form.value.algorithm) {
    ElMessage.warning('请填写必填字段')
    return
  }
  if (!form.value.featureColumnsList.length) {
    ElMessage.warning('请至少选择一个特征列')
    return
  }
  if (form.value.modelType !== MODEL_TYPE.CLUSTERING && !form.value.targetColumn) {
    ElMessage.warning('请选择目标列')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      dataSourceId: form.value.dataSourceId,
      sourceTable: form.value.sourceTable,
      modelType: form.value.modelType,
      algorithm: form.value.algorithm,
      featureColumns: JSON.stringify(form.value.featureColumnsList),
      targetColumn: form.value.targetColumn,
      hyperparameters: form.value.hyperparameters ? JSON.stringify(form.value.hyperparameters) : '{}',
      description: form.value.description,
      preprocessing: JSON.stringify(form.value.preprocessing),
      validationMode: form.value.validationMode || 'train_test',
      cvFolds: form.value.cvFolds || 5,
      testSize: form.value.testSize || 0.2,
      temporalColumn: form.value.temporalColumn || null
    }
    if (editingModel.value) {
      const updated = await updateMiningModel(editingModel.value.id, payload)
      mining.updateModelInList(updated)
      ElMessage.success('已更新')
    } else {
      const created = await createMiningModel(payload)
      mining.addModel(created)
      ElMessage.success('已创建')
    }
    showCreateDialog.value = false
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleSaveAndTrain() {
  if (!form.value.name || !form.value.dataSourceId || !form.value.sourceTable || !form.value.algorithm) {
    ElMessage.warning('请填写必填字段')
    return
  }
  if (!form.value.featureColumnsList.length) {
    ElMessage.warning('请至少选择一个特征列')
    return
  }
  if (form.value.modelType !== MODEL_TYPE.CLUSTERING && !form.value.targetColumn) {
    ElMessage.warning('请选择目标列')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      dataSourceId: form.value.dataSourceId,
      sourceTable: form.value.sourceTable,
      modelType: form.value.modelType,
      algorithm: form.value.algorithm,
      featureColumns: JSON.stringify(form.value.featureColumnsList),
      targetColumn: form.value.targetColumn,
      hyperparameters: form.value.hyperparameters ? JSON.stringify(form.value.hyperparameters) : '{}',
      description: form.value.description,
      preprocessing: JSON.stringify(form.value.preprocessing),
      validationMode: form.value.validationMode || 'train_test',
      cvFolds: form.value.cvFolds || 5,
      testSize: form.value.testSize || 0.2,
      temporalColumn: form.value.temporalColumn || null
    }
    const created = await createMiningModel(payload)
    mining.addModel(created)
    showCreateDialog.value = false
    saving.value = false
    ElMessage.success('已创建，开始训练...')
    trainingId.value = created.id
    try {
      const trained = await trainMiningModel(created.id)
      mining.updateModelInList(trained)

      if (trained.status === MODEL_STATUS.TRAINING) {
        ElMessage.info('训练已启动，正在监听状态...')
        mining.watchModelStatus(created.id)
        await new Promise((resolve) => {
          const unwatch = watch(
            () => mining.models.find(m => m.id === created.id),
            (m) => {
              if (m && [MODEL_STATUS.TRAINED, MODEL_STATUS.FAILED, MODEL_STATUS.PUBLISHED].includes(m.status)) {
                showTrainingResult(m)
                if (detailModel.value?.id === created.id) detailModel.value = m
                unwatch()
                if (activeCleanup.unwatch === unwatch) activeCleanup.unwatch = null
                resolve()
              }
            },
            { deep: true }
          )
          activeCleanup.unwatch = unwatch
          activeCleanup.timeoutId = setTimeout(() => {
            activeCleanup.unwatch = null; activeCleanup.timeoutId = null
            unwatch(); resolve()
          }, TRAINING_SAFETY_TIMEOUT_MS)
        })
      } else {
        showTrainingResult(trained)
      }
    } catch (e) {
      ElMessage.warning('模型已创建(ID:' + created.id + ')，但训练启动失败: ' + (e.message || ''))
    }
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.message || ''))
  } finally {
    saving.value = false
    trainingId.value = null
  }
}


function onActionCmd(cmd, model) {
  if (cmd === 'params') editModel(model)
  else if (cmd === 'validate') handleValidate(model.id)
  else if (cmd === 'schedule') openSchedule(model)
  else if (cmd === 'predict') openPredict(model)
  else if (cmd === 'batchPredict') openBatchPredict(model)
  else if (cmd === 'predictResults') openPredictResults(model)
  else if (cmd === 'viewPipeline') goToPipeline(model.pipelineId)
  else if (cmd === 'syncPipeline') handleSyncPipeline(model)
  else if (cmd === 'delete') doDelete(model.id, model.name)
}

async function openModelDag(model) {
  try {
    const authorizedModel = await fetchMiningModel(model.id)
    if (authorizedModel.status !== MODEL_STATUS.PUBLISHED) {
      ElMessage.warning('只有已发布的模型成品可以进入流程编排')
      return
    }
    await loadMlOperatorCatalog()
    const catalogItem = mlOperatorCatalog.value.find(item => item.code === `ml_model_${authorizedModel.id}`)
    if (!catalogItem) {
      ElMessage.warning('该模型对应的机器学习算子版本尚未通过审批，暂不能进入流程编排')
      return
    }
    emit('openDag', {
      operatorVersionId: catalogItem.operatorVersionId,
      sourceType: 'MODEL_ARTIFACT',
      sourceId: authorizedModel.id,
      sourceName: `${authorizedModel.name} v${authorizedModel.version}`
    })
  } catch (error) {
    const message = error?.response?.data?.message || error?.message || '无权访问该模型成品'
    ElMessage.error(message)
  }
}

function openPipelineDag(pipeline) {
  emit('openDag', {
    operatorVersionId: null,
    sourceType: 'PIPELINE_DRAFT',
    sourceId: pipeline.id,
    sourceName: `${pipeline.name}（草稿 #${pipeline.id}）`
  })
}

function goToPipeline(pipelineId) {
  if (props.repositoryMode) {
    openPipelineDag({ id: pipelineId, name: '模型流水线' })
    return
  }
  showDetail.value = false
  activeTab.value = 'pipeline'
  nextTick(() => {
    pipelineEditorRef.value?.openPipelineById(pipelineId)
  })
}

function goToModel(modelId) {
  if (props.repositoryMode) {
    ElMessage.info('模型成品统一在调度中心管理')
    return
  }
  activeTab.value = 'models'
  nextTick(() => {
    const model = mining.models.find(m => m.id === modelId)
    if (model) selectModel(model)
  })
}

function openAlgorithmManager(algorithm = null, mode = 'view') {
  managedAlgorithm.value = algorithm
  algorithmManagerMode.value = mode
  showAlgorithmManager.value = true
}

async function refreshAlgorithmManager(selectedId) {
  await loadAlgorithms(true)
  managedAlgorithm.value = selectedId ? algorithms.value.find(item => item.id === selectedId) || null : null
  algorithmManagerMode.value = 'view'
}

async function removeAlgorithmTemplate(algorithm) {
  try {
    await ElMessageBox.confirm(`确定删除自定义算法“${algorithm.name}”吗？已有流程若引用它将无法继续训练。`, '删除算法模板', { type: 'warning' })
    await deleteAlgorithm(algorithm.id)
    await loadAlgorithms(true)
    ElMessage.success('算法模板已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || '删除失败')
  }
}

function needsSync(model) {
  if (!model.pipelineId || !model.updatedAt) return false
  if (!model.lastSyncedAt) return true
  return new Date(model.updatedAt) > new Date(model.lastSyncedAt)
}

async function handleSyncPipeline(model) {
  try {
    await syncModelPipeline(model.id)
    ElMessage.success('模型与流程已同步')
    await loadModels()
  } catch (e) {
    ElMessage.error('同步失败: ' + (e.message || '未知错误'))
  }
}

async function handleRollback(modelId, executionId) {
  try {
    await rollbackModel(modelId, executionId)
    ElMessage.success(`已回滚到执行记录 #${executionId}`)
    await loadModels()
    if (detailModel.value?.id === modelId) {
      detailModel.value = await fetchMiningModel(modelId)
      executions.value = await fetchModelExecutions(modelId) || []
    }
  } catch (e) {
    ElMessage.error('回滚失败: ' + (e.message || '未知错误'))
  }
}

function onDetailPredict(model) {
  showDetail.value = false
  openPredict(model)
}

function onDetailTuneParams(model) {
  showDetail.value = false
  editModel(model)
}
</script>

<style scoped>
.mining-manager {
  flex: 1; min-width: 0;
  background: var(--bg); display: flex; flex-direction: column;
}
.mining-manager.embedded { max-width: none; margin: 0; padding: 0; }
.embedded .page-header { min-height: 70px; margin: 0; padding: 12px 22px; background: var(--surface); }
.mining-heading { min-width: 0; }
.mining-heading p { margin: 4px 0 0; color: var(--text-muted); font-size: var(--font-sm); }
.repository-summary { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 10px; padding: 14px var(--space-xl) 4px; }
.repository-summary > div { display: flex; align-items: baseline; gap: 8px; padding: 12px 15px; border: 1px solid #dfe7f1; border-radius: 10px; background: white; }
.repository-summary strong { color: #1559b7; font-size: 22px; }
.repository-summary span { color: #758197; font-size: 12px; }
.mining-tabs {
  flex: 1; display: flex; flex-direction: column; overflow: hidden;
  padding: 0 var(--space-xl);
}
.mining-tabs :deep(.el-tabs__content) { flex: 1; overflow: auto; }
.mining-tabs :deep(.el-tab-pane) { height: 100%; }
.algorithm-admin-page { height: 100%; padding: 0 var(--space-sm) var(--space-sm); }
.algorithm-admin-page .tab-toolbar { align-items: flex-start; }
.algorithm-admin-page h3 { margin: 0 0 4px; font-size: var(--font-lg); }
.algorithm-admin-page p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.algorithm-name-cell { display: inline-flex; align-items: center; gap: 8px; }
.tab-toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.toolbar-left { display: flex; align-items: center; gap: 12px; flex: 1; flex-wrap: wrap; }
.selection-info { display: flex; align-items: center; gap: 8px; background: var(--el-color-danger-light-9); padding: 6px 12px; border-radius: var(--radius-md); border: 1px solid var(--el-color-danger-light-5); }
.selected-count { font-size: var(--font-base); color: var(--el-color-danger); font-weight: 600; }
.batch-hint { display: flex; align-items: center; }
.hint-text { font-size: var(--font-sm); color: var(--el-color-info); background: var(--el-color-info-light-9); padding: 4px 10px; border-radius: var(--radius-sm); }

/* Params editor (kept inline since it's a small dialog) */
.params-editor { padding: var(--space-sm) 0; }
.params-model-name { font-weight: 600; margin-bottom: var(--space-md); color: var(--text-primary); }
.param-row { display: flex; align-items: center; gap: var(--space-sm); margin-bottom: var(--space-sm); }
.param-label { width: 160px; font-size: var(--font-sm); color: var(--text-secondary); text-align: right; flex-shrink: 0; }
.param-hint { display: block; font-size: var(--font-xs); color: var(--text-muted); }
</style>
