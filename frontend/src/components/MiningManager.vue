<template>
  <div class="mining-manager">
    <!-- Header -->
    <div class="mining-header">
      <div class="header-left">
        <button class="back-btn" @click="$emit('close')">
          <span class="back-arrow">←</span> 返回问数
        </button>
        <h2 class="page-title">数据挖掘管理</h2>
      </div>
      <div class="header-actions">
        <el-select v-model="filterDsId" placeholder="数据源" size="small" clearable style="width: 160px">
          <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
        </el-select>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="mining-tabs">
      <el-tab-pane label="模型管理" name="models">
        <div class="tab-toolbar">
          <el-button type="primary" size="small" @click="showCreateDialog = true">新建模型</el-button>
        </div>
        <!-- Model List -->
        <div class="mining-body">
      <div v-if="loading" class="mining-loading"><span class="spinner"></span> 加载中...</div>
      <div v-else-if="!models.length" class="mining-empty">
        <div class="empty-icon">🔬</div>
        <p>暂无挖掘模型</p>
        <p class="empty-hint">点击「新建模型」创建第一个数据挖掘流程</p>
      </div>
      <div v-else class="model-grid">
        <div v-for="model in models" :key="model.id" class="model-card" @click="selectModel(model)">
          <div class="model-card-header">
            <div class="model-card-title">
              <span class="model-type-icon">{{ modelTypeIcon(model.modelType) }}</span>
              <span class="model-name">{{ model.name }}</span>
            </div>
            <span :class="['status-badge', 'status-' + model.status]">{{ statusLabel(model.status) }}</span>
          </div>
          <div class="model-card-body">
            <div class="model-meta">
              <span class="meta-item">{{ algorithmLabel(model.algorithm) }}</span>
              <span class="meta-item">{{ modelTypeLabel(model.modelType) }}</span>
            </div>
            <div class="model-meta">
              <span class="meta-item secondary">表: {{ model.sourceTable || '-' }}</span>
              <span class="meta-item secondary">v{{ model.version }}</span>
            </div>
            <div v-if="model.description" class="model-desc">{{ model.description }}</div>
            <div v-if="model.metrics" class="model-metrics">
              <span class="metric-primary">
                {{ primaryMetricLabel(model) }}
                <strong>{{ primaryMetricValue(model) }}</strong>
              </span>
              <template v-for="(val, key) in parsedMetrics(model.metrics)" :key="key">
                <span v-if="!isPrimary(key, model.modelType)" class="metric-chip">{{ formatMetricName(key) }} {{ formatMetricValue(key, val) }}</span>
              </template>
            </div>
          </div>
          <div class="model-card-actions">
            <el-button size="small" :loading="trainingId === model.id" @click.stop="handleTrain(model.id)">
              {{ model.status === 'training' ? '训练中...' : '训练' }}
            </el-button>
            <el-button v-if="model.status === 'trained' || model.status === 'offline'" size="small" type="success"
              @click.stop="handlePublish(model.id)">发布</el-button>
            <el-button v-if="model.status === 'published'" size="small" type="warning"
              @click.stop="handleOffline(model.id)">下线</el-button>
            <el-button v-if="model.status === 'published' || model.status === 'trained'" size="small" type="primary"
              @click.stop="openPredict(model)">预测</el-button>
            <el-dropdown trigger="click" @command="cmd => onActionCmd(cmd, model)" @click.stop>
              <el-button size="small" @click.stop>更多 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="params">调参</el-dropdown-item>
                  <el-dropdown-item command="schedule">{{ model.scheduleEnabled ? '调度设置 (已启用)' : '调度设置' }}</el-dropdown-item>
                  <el-dropdown-item v-if="model.status === 'published' || model.status === 'trained'" command="predict">预测</el-dropdown-item>
                  <el-dropdown-item command="delete" divided style="color: var(--el-color-danger)">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>
    </div>
      </el-tab-pane>
      <el-tab-pane label="流程编排" name="pipeline">
        <PipelineEditor ref="pipelineEditorRef" :dataSources="dataSources" />
      </el-tab-pane>
    </el-tabs>

    <!-- Create/Edit Dialog -->
    <el-dialog v-model="showCreateDialog" :title="editingModel ? '编辑模型' : '新建模型'" width="660px" destroy-on-close>
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="模型名称" required>
          <el-input v-model="form.name" placeholder="如: 员工离职预测" />
        </el-form-item>
        <el-form-item label="数据源" required>
          <el-select v-model="form.dataSourceId" placeholder="选择数据源" style="width: 100%"
            :teleported="false" @change="onDataSourceChange">
            <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="源数据表" required>
          <el-select v-model="form.sourceTable" placeholder="选择数据表" style="width: 100%"
            :teleported="false" :loading="loadingTables" :disabled="!form.dataSourceId"
            @change="onTableChange" filterable>
            <el-option v-for="t in tableOptions" :key="t.name" :label="t.comment ? `${t.name} (${t.comment})` : t.name" :value="t.name">
              <span>{{ t.name }}</span>
              <span v-if="t.comment" style="color: var(--text-muted); margin-left: 8px; font-size: 12px">{{ t.comment }}</span>
              <span style="float: right; color: var(--text-muted); font-size: 12px">{{ t.rows }}行</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="模型类型" required>
          <el-select v-model="form.modelType" placeholder="选择类型" style="width: 100%" :teleported="false">
            <el-option label="分类 (Classification)" value="classification" />
            <el-option label="回归 (Regression)" value="regression" />
            <el-option label="聚类 (Clustering)" value="clustering" />
            <el-option label="异常检测 (Anomaly Detection)" value="anomaly_detection" />
          </el-select>
        </el-form-item>
        <el-form-item label="算法" required>
          <el-select v-model="form.algorithm" placeholder="选择算法" style="width: 100%" :teleported="false">
            <el-option label="随机森林 (Random Forest)" value="random_forest" />
            <el-option label="XGBoost" value="xgboost" />
            <el-option label="梯度提升 (Gradient Boosting)" value="gradient_boosting" />
            <el-option label="决策树 (Decision Tree)" value="decision_tree" />
            <el-option label="逻辑回归 (Logistic Regression)" value="logistic_regression" />
            <el-option label="SVM" value="svm" />
            <el-option label="KNN" value="knn" />
            <el-option label="K-Means 聚类" value="kmeans" />
          </el-select>
        </el-form-item>
        <el-form-item label="特征列" required>
          <div v-if="!columnOptions.length" class="col-hint">
            请先选择数据源和数据表
          </div>
          <div v-else class="column-picker">
            <el-checkbox v-model="selectAllFeatures" :indeterminate="featureIndeterminate"
              @change="onSelectAllFeatures" style="margin-bottom: 6px">全选</el-checkbox>
            <div class="column-grid">
              <el-checkbox v-for="col in columnOptions" :key="col.name"
                v-model="featureChecked[col.name]" @change="syncFeatureColumns">
                <span class="col-name">{{ col.name }}</span>
                <span class="col-type">{{ col.type }}</span>
              </el-checkbox>
            </div>
            <div class="selected-count">已选 {{ form.featureColumnsList.length }} / {{ columnOptions.length }} 列</div>
          </div>
        </el-form-item>
        <el-form-item v-if="form.modelType !== 'clustering'" label="目标列">
          <el-select v-model="form.targetColumn" placeholder="选择目标列" style="width: 100%"
            :teleported="false" :disabled="!columnOptions.length" clearable filterable>
            <el-option v-for="col in columnOptions" :key="col.name" :label="col.name" :value="col.name">
              <span>{{ col.name }}</span>
              <span style="color: var(--text-muted); margin-left: 8px; font-size: 12px">{{ col.type }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="缺失值处理">
          <el-select v-model="form.preprocessing.handleMissing" style="width: 100%" :teleported="false">
            <el-option label="删除缺失行" value="drop" />
            <el-option label="填充均值" value="fill_mean" />
            <el-option label="填充中位数" value="fill_median" />
          </el-select>
        </el-form-item>
        <el-form-item label="分类编码">
          <el-select v-model="form.preprocessing.encoding" style="width: 100%" :teleported="false">
            <el-option label="Label Encoding" value="label" />
            <el-option label="One-Hot Encoding" value="onehot" />
          </el-select>
        </el-form-item>
        <el-form-item label="特征缩放">
          <el-select v-model="form.preprocessing.scaling" style="width: 100%" :teleported="false">
            <el-option label="不缩放" value="none" />
            <el-option label="标准化 (StandardScaler)" value="standard" />
            <el-option label="归一化 (MinMaxScaler)" value="minmax" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="模型用途描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ editingModel ? '保存' : '创建' }}
        </el-button>
        <el-button v-if="!editingModel" type="success" :loading="saving" @click="handleSaveAndTrain">
          创建并训练
        </el-button>
      </template>
    </el-dialog>

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
          <el-input v-else v-model="paramForm[p.key]" size="small" style="width: 180px" />
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
    <el-dialog v-model="showScheduleDialog" title="定时调度" width="460px" destroy-on-close>
      <div v-if="scheduleModel">
        <p style="margin-bottom: 12px; color: var(--text-secondary)">为「{{ scheduleModel.name }}」配置自动训练调度</p>
        <el-form label-width="100px" size="default">
          <el-form-item label="启用调度">
            <el-switch v-model="scheduleEnabled" active-text="开" inactive-text="关" />
          </el-form-item>
          <el-form-item label="调度间隔">
            <el-select v-model="scheduleCron" style="width: 100%" :teleported="false">
              <el-option label="每 30 分钟" value="*/30" />
              <el-option label="每 1 小时" value="*/60" />
              <el-option label="每 6 小时" value="*/360" />
              <el-option label="每 12 小时" value="*/720" />
              <el-option label="每 24 小时" value="*/1440" />
              <el-option label="每周" value="*/10080" />
            </el-select>
          </el-form-item>
        </el-form>
        <div v-if="scheduleModel.lastRunAt" style="margin-top: 8px; font-size: 12px; color: var(--text-muted)">
          上次运行: {{ new Date(scheduleModel.lastRunAt).toLocaleString('zh-CN') }}
        </div>
      </div>
      <template #footer>
        <el-button @click="showScheduleDialog = false">取消</el-button>
        <el-button type="primary" @click="saveSchedule">保存</el-button>
      </template>
    </el-dialog>

    <!-- Prediction Dialog -->
    <el-dialog v-model="showPredictDialog" title="模型预测" width="660px" destroy-on-close>
      <div v-if="predictModel_ref">
        <p style="color: var(--text-secondary); margin-bottom: 12px">
          使用「{{ predictModel_ref.name }}」进行预测，输入数据格式为 JSON 数组
        </p>
        <el-form label-width="100px" size="default">
          <el-form-item label="输入数据">
            <el-input v-model="predictInput" type="textarea" :rows="6" placeholder='[{"dept_id": 1, "salary": 15000}]' />
          </el-form-item>
          <el-form-item label="保存到表">
            <el-input v-model="predictSaveTable" placeholder="留空不保存，填写表名则自动建表并写入预测结果" />
          </el-form-item>
        </el-form>
        <div v-if="predictResult" class="predict-result">
          <h4>预测结果</h4>
          <div v-if="predictResult.saved_to" style="margin-bottom: 8px; color: var(--color-success)">
            已保存 {{ predictResult.saved_rows }} 条到表 {{ predictResult.saved_to }}
          </div>
          <el-table :data="predictRows" size="small" stripe border max-height="300">
            <el-table-column type="index" label="#" width="50" />
            <el-table-column v-for="(_, key) in (predictRows[0] || {})" :key="key" :prop="key" :label="key" />
          </el-table>
        </div>
      </div>
      <template #footer>
        <el-button @click="showPredictDialog = false">关闭</el-button>
        <el-button type="primary" :loading="predictLoading" @click="handlePredict">执行预测</el-button>
      </template>
    </el-dialog>
    <el-drawer v-model="showDetail" :title="detailModel?.name || '模型详情'" size="480px" direction="rtl">
      <template v-if="detailModel">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="状态">
            <span :class="['status-badge', 'status-' + detailModel.status]">{{ statusLabel(detailModel.status) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="算法">{{ algorithmLabel(detailModel.algorithm) }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ modelTypeLabel(detailModel.modelType) }}</el-descriptions-item>
          <el-descriptions-item label="源表">{{ detailModel.sourceTable }}</el-descriptions-item>
          <el-descriptions-item label="目标列">{{ detailModel.targetColumn || '-' }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detailModel.version }}</el-descriptions-item>
          <el-descriptions-item v-if="detailModel.pipelineId" label="来源">
            <el-button size="small" link type="primary" @click="goToPipeline(detailModel.pipelineId)">
              关联流程 #{{ detailModel.pipelineId }}
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDate(detailModel.createdAt) }}</el-descriptions-item>
        </el-descriptions>

        <div v-if="detailModel.featureColumns" class="detail-section">
          <h4>特征列</h4>
          <div class="feature-tags">
            <el-tag v-for="col in parseJson(detailModel.featureColumns, [])" :key="col" size="small" style="margin: 2px">{{ col }}</el-tag>
          </div>
        </div>

        <div v-if="detailModel.metrics" class="detail-section">
          <h4>评估指标</h4>
          <div class="metrics-grid">
            <div v-for="(val, key) in parsedMetrics(detailModel.metrics)" :key="key" class="metric-card">
              <span class="metric-value">{{ formatMetricValue(key, val) }}</span>
              <span class="metric-name">{{ formatMetricName(key) }}</span>
            </div>
          </div>
        </div>

        <div v-if="detailModel.featureImportance" class="detail-section">
          <h4>特征重要性</h4>
          <div class="importance-list">
            <div v-for="(val, key) in sortedImportance" :key="key" class="importance-bar">
              <span class="imp-label">{{ key }}</span>
              <div class="imp-track">
                <div class="imp-fill" :style="{ width: (val / maxImportance * 100) + '%' }"></div>
              </div>
              <span class="imp-value">{{ (val * 100).toFixed(1) }}%</span>
            </div>
          </div>
        </div>

        <div class="detail-section">
          <h4>执行历史</h4>
          <div v-if="loadingExecutions" style="text-align: center; padding: 16px"><span class="spinner"></span></div>
          <div v-else-if="!executions.length" class="empty-executions">暂无执行记录</div>
          <div v-else class="execution-list">
            <div v-for="exec in executions" :key="exec.id" class="execution-item">
              <span :class="['exec-status', 'exec-' + exec.status]">{{ execStatusLabel(exec.status) }}</span>
              <span class="exec-time">{{ exec.executionTimeMs ? (exec.executionTimeMs / 1000).toFixed(1) + 's' : '-' }}</span>
              <span class="exec-trigger">{{ execTriggerLabel(exec.triggerType) }}</span>
              <span class="exec-date">{{ formatDate(exec.createdAt) }}</span>
            </div>
          </div>
        </div>

        <!-- Quick actions in detail drawer -->
        <div class="detail-actions">
          <el-button size="small" type="primary" :loading="trainingId === detailModel.id"
            @click="handleTrain(detailModel.id); refreshDetail()">
            {{ detailModel.status === 'training' ? '训练中...' : '训练' }}
          </el-button>
          <el-button v-if="detailModel.status === 'trained' || detailModel.status === 'offline'" size="small" type="success"
            @click="handlePublish(detailModel.id); refreshDetail()">发布</el-button>
          <el-button v-if="detailModel.status === 'published'" size="small" type="warning"
            @click="handleOffline(detailModel.id); refreshDetail()">下线</el-button>
          <el-button v-if="detailModel.status === 'published' || detailModel.status === 'trained'" size="small" type="primary"
            @click="showDetail = false; openPredict(detailModel)">预测</el-button>
          <el-button size="small" @click="showDetail = false; editModel(detailModel)">调参</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PipelineEditor from './PipelineEditor.vue'
import {
  fetchMiningModels, fetchMiningModel, createMiningModel, updateMiningModel,
  deleteMiningModel, trainMiningModel, publishMiningModel, offlineMiningModel,
  updateModelHyperparams, fetchModelExecutions, fetchDataSources,
  fetchDataSourceTables, fetchTableColumns, updateModelSchedule, predictMiningModel
} from '../api'

const emit = defineEmits(['close'])

const models = ref([])
const dataSources = ref([])
const activeTab = ref('models')
const loading = ref(false)
const saving = ref(false)
const trainingId = ref(null)
const filterDsId = ref(null)
const savingParams = ref(false)
const loadingExecutions = ref(false)
const pipelineEditorRef = ref(null)

// Table/Column selectors
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

// Schedule dialog
const showScheduleDialog = ref(false)
const scheduleModel = ref(null)
const scheduleCron = ref('*/60')
const scheduleEnabled = ref(false)

// Prediction dialog
const showPredictDialog = ref(false)
const predictModel_ref = ref(null)
const predictInput = ref('[{"dept_id": 1, "salary": 15000}]')
const predictSaveTable = ref('')
const predictLoading = ref(false)
const predictResult = ref(null)

// Detail drawer
const showDetail = ref(false)
const detailModel = ref(null)
const executions = ref([])

function defaultForm() {
  return {
    name: '', dataSourceId: null, sourceTable: '', modelType: 'classification',
    algorithm: 'random_forest', featureColumnsList: [], targetColumn: '',
    description: '',
    preprocessing: { handleMissing: 'drop', encoding: 'label', scaling: 'none' }
  }
}

const statusLabels = {
  draft: '草稿', training: '训练中', trained: '已训练', trained_failed: '训练失败',
  published: '已发布', offline: '已下线', failed: '失败'
}
function statusLabel(s) { return statusLabels[s] || s }

function execStatusLabel(s) {
  return { success: '成功', failed: '失败', running: '运行中', pending: '等待中' }[s] || s
}
function execTriggerLabel(t) {
  return { manual: '手动', schedule: '定时', chat: '对话' }[t] || t
}

const algorithmLabels = {
  random_forest: '随机森林', xgboost: 'XGBoost', decision_tree: '决策树',
  logistic_regression: '逻辑回归', svm: 'SVM', knn: 'KNN',
  kmeans: 'K-Means', gradient_boosting: '梯度提升', lightgbm: 'LightGBM'
}
function algorithmLabel(a) { return algorithmLabels[a] || a }

const featureIndeterminate = computed(() => {
  const total = columnOptions.value.length
  const selected = form.value.featureColumnsList.length
  return selected > 0 && selected < total
})

const modelTypeLabels = {
  classification: '分类', regression: '回归', clustering: '聚类', anomaly_detection: '异常检测'
}
function modelTypeLabel(t) { return modelTypeLabels[t] || t }

function isPrimary(key, modelType) {
  if (modelType === 'regression') return key === 'r2'
  if (modelType === 'clustering') return key === 'inertia'
  return key === 'accuracy'
}

function primaryMetricLabel(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 !== undefined ? 'R²' : 'RMSE'
  if (model.modelType === 'clustering') return '聚类'
  return m.accuracy !== undefined ? '准确率' : 'F1'
}

function primaryMetricValue(model) {
  const m = parsedMetrics(model.metrics)
  if (model.modelType === 'regression') return m.r2 !== undefined ? (m.r2 * 100).toFixed(1) + '%' : m.rmse?.toFixed(4) || '-'
  if (model.modelType === 'clustering') return (m.n_clusters || '-') + ' 类'
  const key = m.accuracy !== undefined ? 'accuracy' : 'f1'
  return m[key] !== undefined ? (m[key] * 100).toFixed(1) + '%' : '-'
}

function modelTypeIcon(t) {
  return { classification: '🏷️', regression: '📈', clustering: '🎯', anomaly_detection: '🔍' }[t] || '🤖'
}

function formatMetricValue(key, val) {
  const pctKeys = ['accuracy', 'precision', 'recall', 'f1', 'r2']
  return pctKeys.includes(key) ? (val * 100).toFixed(1) + '%' : val.toFixed ? Number(val).toFixed(4) : val
}
function formatMetricName(key) {
  const names = { accuracy: '准确率', precision: '精确率', recall: '召回率', f1: 'F1', mse: 'MSE', rmse: 'RMSE', r2: 'R²' }
  return names[key] || key
}
function parsedMetrics(json) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || {} } catch { return {} }
}
function parseJson(json, fallback) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || fallback } catch { return fallback }
}
function formatDate(d) { return d ? new Date(d).toLocaleString('zh-CN') : '-' }

const sortedImportance = computed(() => {
  if (!detailModel.value?.featureImportance) return {}
  const parsed = parseJson(detailModel.value.featureImportance, {})
  return Object.entries(parsed).sort((a, b) => b[1] - a[1]).slice(0, 10).reduce((acc, [k, v]) => { acc[k] = v; return acc }, {})
})
const maxImportance = computed(() => {
  const vals = Object.values(sortedImportance.value)
  return vals.length ? Math.max(...vals) : 1
})

const predictRows = computed(() => {
  if (!predictResult.value) return []
  let inputRows = []
  try { inputRows = JSON.parse(predictInput.value) } catch { return [] }
  const preds = predictResult.value.predictions || []
  return inputRows.map((row, i) => ({ ...row, prediction: preds[i], probability: predictResult.value.probabilities?.[i] ? (Math.max(...predictResult.value.probabilities[i]) * 100).toFixed(1) + '%' : '' }))
})

async function loadModels() {
  loading.value = true
  try {
    const [ms, dss] = await Promise.all([
      fetchMiningModels(filterDsId.value || undefined).catch(() => []),
      dataSources.value.length ? Promise.resolve(dataSources.value) : fetchDataSources()
    ])
    models.value = ms || []
    dataSources.value = dss || []
  } catch (e) {
    console.error('Failed to load mining models:', e)
  } finally {
    loading.value = false
  }
}

function algorithmParams(algo) {
  const defs = {
    random_forest: [
      { key: 'n_estimators', label: '树的数量', type: 'int', min: 1, max: 1000, default: 100, hint: 'n_estimators' },
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 100, default: 10, hint: 'max_depth' },
      { key: 'min_samples_split', label: '最小分裂样本数', type: 'int', min: 2, max: 100, default: 2, hint: 'min_samples_split' },
      { key: 'min_samples_leaf', label: '叶节点最小样本数', type: 'int', min: 1, max: 50, default: 1, hint: 'min_samples_leaf' }
    ],
    xgboost: [
      { key: 'n_estimators', label: '树的数量', type: 'int', min: 1, max: 1000, default: 100, hint: 'n_estimators' },
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 50, default: 6, hint: 'max_depth' },
      { key: 'learning_rate', label: '学习率', type: 'float', min: 0.001, max: 1, step: 0.01, default: 0.3, hint: 'learning_rate' },
      { key: 'subsample', label: '子采样率', type: 'float', min: 0.1, max: 1, step: 0.1, default: 1, hint: 'subsample' }
    ],
    decision_tree: [
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 100, default: 10, hint: 'max_depth' },
      { key: 'min_samples_split', label: '最小分裂样本数', type: 'int', min: 2, max: 100, default: 2, hint: 'min_samples_split' },
      { key: 'criterion', label: '分裂标准', type: 'select', options: ['gini', 'entropy'], default: 'gini', hint: 'criterion' }
    ],
    logistic_regression: [
      { key: 'C', label: '正则化强度', type: 'float', min: 0.01, max: 100, step: 0.1, default: 1, hint: 'C' },
      { key: 'max_iter', label: '最大迭代次数', type: 'int', min: 10, max: 10000, default: 100, hint: 'max_iter' },
      { key: 'solver', label: '求解器', type: 'select', options: ['lbfgs', 'liblinear', 'saga'], default: 'lbfgs', hint: 'solver' }
    ],
    svm: [
      { key: 'C', label: '正则化强度', type: 'float', min: 0.01, max: 100, step: 0.1, default: 1, hint: 'C' },
      { key: 'kernel', label: '核函数', type: 'select', options: ['rbf', 'linear', 'poly'], default: 'rbf', hint: 'kernel' },
      { key: 'gamma', label: 'Gamma', type: 'select', options: ['scale', 'auto'], default: 'scale', hint: 'gamma' }
    ],
    knn: [
      { key: 'n_neighbors', label: '邻居数 K', type: 'int', min: 1, max: 100, default: 5, hint: 'n_neighbors' },
      { key: 'weights', label: '权重', type: 'select', options: ['uniform', 'distance'], default: 'uniform', hint: 'weights' }
    ],
    kmeans: [
      { key: 'n_clusters', label: '聚类数', type: 'int', min: 2, max: 50, default: 3, hint: 'n_clusters' },
      { key: 'max_iter', label: '最大迭代次数', type: 'int', min: 10, max: 1000, default: 300, hint: 'max_iter' }
    ],
    gradient_boosting: [
      { key: 'n_estimators', label: '树的数量', type: 'int', min: 1, max: 1000, default: 100, hint: 'n_estimators' },
      { key: 'max_depth', label: '最大深度', type: 'int', min: 1, max: 50, default: 3, hint: 'max_depth' },
      { key: 'learning_rate', label: '学习率', type: 'float', min: 0.001, max: 1, step: 0.01, default: 0.1, hint: 'learning_rate' }
    ]
  }
  return defs[algo] || []
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

function syncFeatureColumns() {
  form.value.featureColumnsList = Object.entries(featureChecked.value)
    .filter(([, v]) => v).map(([k]) => k)
  const total = columnOptions.value.length
  selectAllFeatures.value = form.value.featureColumnsList.length === total
}

watch(filterDsId, () => loadModels())
onMounted(loadModels)

async function handleTrain(id) {
  trainingId.value = id
  try {
    const model = await trainMiningModel(id)
    const idx = models.value.findIndex(m => m.id === id)
    if (idx >= 0) models.value[idx] = model
    ElMessage.success(model.status === 'trained' ? '训练完成' : '训练已启动')
    if (detailModel.value?.id === id) detailModel.value = model
  } catch (e) {
    ElMessage.error('训练失败: ' + (e.message || '未知错误'))
  } finally {
    trainingId.value = null
  }
}

async function handlePublish(id) {
  try {
    const model = await publishMiningModel(id)
    const idx = models.value.findIndex(m => m.id === id)
    if (idx >= 0) models.value[idx] = model
    ElMessage.success('模型已发布')
    if (detailModel.value?.id === id) detailModel.value = model
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  }
}

async function handleOffline(id) {
  try {
    const model = await offlineMiningModel(id)
    const idx = models.value.findIndex(m => m.id === id)
    if (idx >= 0) models.value[idx] = model
    ElMessage.success('模型已下线')
    if (detailModel.value?.id === id) detailModel.value = model
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function handleDelete(id, name) {
  try {
    await ElMessageBox.confirm(`确定删除模型「${name}」？此操作不可撤销。`, '删除模型', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning'
    })
    await deleteMiningModel(id)
    models.value = models.value.filter(m => m.id !== id)
    if (detailModel.value?.id === id) showDetail.value = false
    ElMessage.success('已删除')
  } catch { /* cancelled */ }
}

function openSchedule(model) {
  scheduleModel.value = model
  scheduleCron.value = model.scheduleCron || '*/60'
  scheduleEnabled.value = !!model.scheduleEnabled
  showScheduleDialog.value = true
}

async function saveSchedule() {
  if (!scheduleModel.value) return
  try {
    const updated = await updateModelSchedule(scheduleModel.value.id, scheduleCron.value, scheduleEnabled.value)
    const idx = models.value.findIndex(m => m.id === scheduleModel.value.id)
    if (idx >= 0) models.value[idx] = { ...models.value[idx], ...updated }
    ElMessage.success(scheduleEnabled.value ? '调度已启用' : '调度已更新')
    showScheduleDialog.value = false
  } catch (e) {
    ElMessage.error('保存调度失败: ' + (e.message || ''))
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
    const raw = savedParams[p.key] !== undefined ? savedParams[p.key] : (p.default !== undefined ? p.default : (p.type === 'int' ? 100 : p.type === 'float' ? 0.1 : p.type === 'select' ? (p.options?.[0] || '') : ''))
    if (p.type === 'float' && typeof raw === 'number') {
      formMap[p.key] = Math.round(raw * 10000) / 10000
    } else {
      formMap[p.key] = raw
    }
  }
  // Add any custom params not in the definition
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

async function handleSaveParams() {
  if (!paramModel.value) return
  savingParams.value = true
  try {
    const model = await updateModelHyperparams(paramModel.value.id, paramForm.value)
    const idx = models.value.findIndex(m => m.id === paramModel.value.id)
    if (idx >= 0) models.value[idx] = model
    showParamsDialog.value = false
    ElMessage.success('参数已更新')
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    savingParams.value = false
  }
}

function resetForm() {
  editingModel.value = null
  const ds = dataSources.value.find(d => d.databaseName !== 'smart_query') || dataSources.value[0]
  form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
}

watch(showCreateDialog, (v) => {
  if (v) {
    const ds = dataSources.value.find(d => d.databaseName !== 'smart_query') || dataSources.value[0]
    form.value = { ...defaultForm(), dataSourceId: ds?.id || null }
    if (ds?.id) onDataSourceChange(ds.id)
  } else {
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
      hyperparameters: '{}',
      description: form.value.description,
      preprocessing: JSON.stringify(form.value.preprocessing)
    }
    if (editingModel.value) {
      const updated = await updateMiningModel(editingModel.value.id, payload)
      const idx = models.value.findIndex(m => m.id === editingModel.value.id)
      if (idx >= 0) models.value[idx] = updated
      ElMessage.success('已更新')
    } else {
      const created = await createMiningModel(payload)
      models.value.unshift(created)
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
      hyperparameters: '{}',
      description: form.value.description,
      preprocessing: JSON.stringify(form.value.preprocessing)
    }
    const created = await createMiningModel(payload)
    models.value.unshift(created)
    showCreateDialog.value = false
    ElMessage.success('已创建，开始训练...')
    trainingId.value = created.id
    const trained = await trainMiningModel(created.id)
    const idx = models.value.findIndex(m => m.id === created.id)
    if (idx >= 0) models.value[idx] = trained
    if (trained.status === 'trained') ElMessage.success('训练完成！')
    else ElMessage.warning('训练未成功: ' + (trained.status || '未知'))
  } catch (e) {
    ElMessage.error('操作失败: ' + (e.message || ''))
  } finally {
    saving.value = false
    trainingId.value = null
  }
}

async function refreshDetail() {
  if (!detailModel.value) return
  setTimeout(async () => {
    try {
      const updated = await fetchMiningModel(detailModel.value.id)
      detailModel.value = updated
      const idx = models.value.findIndex(m => m.id === updated.id)
      if (idx >= 0) models.value[idx] = updated
    } catch {}
  }, 1000)
}

async function selectModel(model) {
  detailModel.value = model
  showDetail.value = true
  loadingExecutions.value = true
  try {
    executions.value = await fetchModelExecutions(model.id) || []
  } catch {
    executions.value = []
  } finally {
    loadingExecutions.value = false
  }
}

function onActionCmd(cmd, model) {
  if (cmd === 'params') editModel(model)
  else if (cmd === 'schedule') openSchedule(model)
  else if (cmd === 'predict') openPredict(model)
  else if (cmd === 'delete') handleDelete(model.id, model.name)
}

function openPredict(model) {
  predictModel_ref.value = model
  predictInput.value = '[\n  '
  try {
    const cols = JSON.parse(model.featureColumns || '[]')
    const sample = {}
    cols.forEach(c => { sample[c] = 0 })
    predictInput.value = JSON.stringify([sample], null, 2)
  } catch { predictInput.value = '[{"col1": 0}]' }
  predictSaveTable.value = model.sourceTable ? model.sourceTable + '_prediction' : ''
  predictResult.value = null
  showPredictDialog.value = true
}

async function handlePredict() {
  let inputRows
  try {
    inputRows = JSON.parse(predictInput.value)
    if (!Array.isArray(inputRows) || inputRows.length === 0) throw new Error()
  } catch {
    ElMessage.error('输入数据格式错误，需要JSON数组')
    return
  }

  predictLoading.value = true
  predictResult.value = null
  try {
    const result = await predictMiningModel(
      predictModel_ref.value.id,
      inputRows,
      predictSaveTable.value || null
    )
    predictResult.value = result
    if (result.saved_to) {
      ElMessage.success(`预测完成，${result.saved_rows} 条结果已写入 ${result.saved_to}`)
    } else {
      ElMessage.success(`预测完成，共 ${result.predictions?.length || 0} 条结果`)
    }
  } catch (e) {
    ElMessage.error('预测失败: ' + (e.message || '未知错误'))
  } finally {
    predictLoading.value = false
  }
}

function goToPipeline(pipelineId) {
  showDetail.value = false
  activeTab.value = 'pipeline'
  nextTick(() => {
    pipelineEditorRef.value?.openPipelineById(pipelineId)
  })
}
</script>

<style scoped>
.mining-manager {
  flex: 1; min-width: 0;
  background: var(--bg); display: flex; flex-direction: column;
}
.mining-tabs {
  flex: 1; display: flex; flex-direction: column; overflow: hidden;
  padding: 0 var(--space-xl);
}
.mining-tabs :deep(.el-tabs__content) { flex: 1; overflow: auto; }
.mining-tabs :deep(.el-tab-pane) { height: 100%; }
.tab-toolbar { display: flex; justify-content: flex-end; margin-bottom: 12px; }
.mining-header {
  height: 52px; background: var(--surface); border-bottom: 1px solid var(--border);
  display: flex; align-items: center; justify-content: space-between;
  padding: 0 var(--space-xl); flex-shrink: 0;
}
.header-left { display: flex; align-items: center; gap: var(--space-md); }
.back-btn {
  background: none; border: none; cursor: pointer; font-size: var(--font-md);
  color: var(--primary); display: flex; align-items: center; gap: 4px;
  padding: 4px 8px; border-radius: var(--radius-md);
  transition: background 0.15s;
}
.back-btn:hover { background: var(--primary-light); }
.back-arrow { font-size: var(--font-lg); }
.page-title { font-size: var(--font-xl); font-weight: 600; color: var(--text-primary); }
.header-actions { display: flex; align-items: center; gap: var(--space-sm); }

.mining-body {
  flex: 1; overflow-y: auto;
}
.mining-loading, .mining-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 60px 0; color: var(--text-muted); font-size: var(--font-md);
}
.empty-icon { font-size: 48px; margin-bottom: var(--space-md); }
.empty-hint { font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs); }

.model-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: var(--space-md);
}
.model-card {
  background: var(--surface); border-radius: var(--radius-lg); border: 1px solid var(--border);
  padding: var(--space-lg); cursor: pointer; transition: all 0.2s;
  box-shadow: 0 1px 2px rgba(0,0,0,0.04);
}
.model-card:hover { border-color: var(--primary); box-shadow: 0 4px 12px rgba(0,0,0,0.08); transform: translateY(-1px); }
.model-card-header {
  display: flex; align-items: center; justify-content: space-between; margin-bottom: var(--space-sm);
}
.model-card-title { display: flex; align-items: center; gap: 6px; min-width: 0; }
.model-type-icon { font-size: 16px; flex-shrink: 0; }
.model-name { font-size: var(--font-lg); font-weight: 600; color: var(--text-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-card-body { margin-bottom: var(--space-md); }
.model-desc {
  font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%;
}
.model-meta { display: flex; gap: var(--space-sm); margin-bottom: var(--space-xs); }
.meta-item { font-size: var(--font-sm); color: var(--text-secondary); }
.meta-item.secondary { color: var(--text-muted); font-size: var(--font-xs); }
.model-metrics { display: flex; flex-wrap: wrap; gap: var(--space-xs); margin-top: var(--space-sm); align-items: center; }
.metric-primary {
  background: var(--color-success); color: #fff;
  padding: 2px 10px; border-radius: var(--radius-sm);
  font-size: var(--font-sm); font-weight: 500;
  display: inline-flex; align-items: center; gap: 4px;
}
.metric-primary strong { font-size: var(--font-lg); }
.metric-chip {
  background: var(--color-success-light); color: var(--color-success);
  padding: 2px var(--space-sm); border-radius: var(--radius-sm);
  font-size: var(--font-xs); font-weight: 500;
}
.model-card-actions {
  display: flex; gap: var(--space-xs); border-top: 1px solid var(--border-light);
  padding-top: var(--space-sm);
}

.status-badge {
  display: inline-block; padding: 2px 8px; border-radius: var(--radius-pill);
  font-size: var(--font-xs); font-weight: 500;
}
.status-draft { background: var(--color-info-light); color: var(--color-info); }
.status-training { background: var(--color-warning-light); color: var(--color-warning); }
.status-trained { background: var(--color-success-light); color: var(--color-success); }
.status-published { background: var(--primary-light); color: var(--primary); }
.status-offline { background: var(--border-light); color: var(--text-muted); }
.status-failed { background: var(--color-danger-light); color: var(--color-danger); }

/* Detail drawer */
.detail-section { margin-top: var(--space-lg); }
.detail-section h4 {
  font-size: var(--font-base); font-weight: 600; color: var(--text-primary);
  margin-bottom: var(--space-sm); padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--border-light);
}
.feature-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.metrics-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-sm); }
.metric-card {
  background: var(--color-success-light); border-radius: var(--radius-md);
  padding: var(--space-sm); text-align: center;
}
.metric-value { display: block; font-size: var(--font-xl); font-weight: 700; color: var(--color-success); }
.metric-name { font-size: var(--font-xs); color: var(--text-muted); }
.importance-list { display: flex; flex-direction: column; gap: var(--space-xs); }
.importance-bar { display: flex; align-items: center; gap: var(--space-sm); }
.imp-label { width: 100px; font-size: var(--font-sm); color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.imp-track { flex: 1; height: 8px; background: var(--border-light); border-radius: 4px; overflow: hidden; }
.imp-fill { height: 100%; background: var(--primary); border-radius: 4px; transition: width 0.3s; }
.imp-value { width: 40px; font-size: var(--font-sm); color: var(--text-muted); text-align: right; }

.predict-result { margin-top: 16px; }
.predict-result h4 { font-size: var(--font-md); color: var(--text-primary); margin-bottom: 8px; }

.execution-list { display: flex; flex-direction: column; gap: var(--space-xs); }
.execution-item {
  display: flex; align-items: center; gap: var(--space-sm);
  padding: var(--space-xs) var(--space-sm); background: var(--border-lighter);
  border-radius: var(--radius-sm); font-size: var(--font-sm);
}
.exec-status { font-weight: 500; }
.exec-success { color: var(--color-success); }
.exec-failed { color: var(--color-danger); }
.exec-running { color: var(--color-warning); }

.detail-actions {
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--border);
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.exec-pending { color: var(--color-info); }
.exec-time { color: var(--text-secondary); }
.exec-trigger { color: var(--text-muted); }
.exec-date { margin-left: auto; color: var(--text-muted); font-size: var(--font-xs); }
.empty-executions { text-align: center; color: var(--text-muted); padding: var(--space-lg); }

/* Params editor */
.params-editor { padding: var(--space-sm) 0; }
.params-model-name { font-weight: 600; margin-bottom: var(--space-md); color: var(--text-primary); }
.param-row { display: flex; align-items: center; gap: var(--space-sm); margin-bottom: var(--space-sm); }
.param-label { width: 160px; font-size: var(--font-sm); color: var(--text-secondary); text-align: right; flex-shrink: 0; }
.param-hint { display: block; font-size: 11px; color: var(--text-muted); }

/* Column picker */
.col-hint { color: var(--text-muted); font-size: var(--font-sm); padding: 8px 0; }
.column-picker { width: 100%; }
.column-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px 12px;
  max-height: 200px; overflow-y: auto; border: 1px solid var(--border-light);
  border-radius: var(--radius-md); padding: var(--space-sm);
}
.col-name { font-size: var(--font-sm); }
.col-type { font-size: 11px; color: var(--text-muted); margin-left: 4px; }
.selected-count { font-size: 12px; color: var(--text-muted); margin-top: 4px; }

.spinner {
  width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; animation: spin 0.6s linear infinite; display: inline-block;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
