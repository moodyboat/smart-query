<template>
  <el-drawer :model-value="show" @update:model-value="$emit('update:show', $event)"
    :title="model?.name || '模型详情'" size="480px" direction="rtl">
    <template v-if="model">
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="状态">
          <span :class="['status-badge', 'status-' + model.status]">{{ statusLabel(model.status) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="算法">{{ algorithmLabel(model.algorithm) }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ modelTypeLabel(model.modelType) }}</el-descriptions-item>
        <el-descriptions-item label="源表">{{ model.sourceTable }}</el-descriptions-item>
        <el-descriptions-item label="目标列">{{ model.targetColumn || '-' }}</el-descriptions-item>
        <el-descriptions-item label="版本">v{{ model.version }}</el-descriptions-item>
        <el-descriptions-item v-if="model.pipelineId" label="来源">
          <div style="display: flex; align-items: center; gap: 8px">
            <el-button size="small" link type="primary" @click="$emit('goToPipeline', model.pipelineId)">
              关联流程 #{{ model.pipelineId }}
            </el-button>
            <el-tag v-if="needsSync(model)" size="small" effect="dark" type="warning" style="cursor:pointer" @click="$emit('syncPipeline', model)">配置未同步，点击同步</el-tag>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatDate(model.createdAt) }}</el-descriptions-item>
      </el-descriptions>

      <!-- Pipeline Node Visualization -->
      <div v-if="detailPipelineNodes.length" class="detail-section">
        <h4 style="display: flex; align-items: center; gap: 8px">
          流程节点
          <el-button v-if="model.pipelineId" size="small" link type="primary" @click="$emit('goToPipeline', model.pipelineId)">编辑流程</el-button>
        </h4>
        <div class="pipeline-mini-flow">
          <template v-for="(node, i) in detailPipelineNodes" :key="i">
            <div :class="['mini-flow-node', { expanded: expandedNodeId === node.id }]"
                 @click="$emit('expandNode', expandedNodeId === node.id ? null : node.id)">
              <div class="mini-flow-node-header">
                <span class="mini-flow-icon">{{ pipelineNodeIcon(node.type) }}</span>
                <span class="mini-flow-title">{{ pipelineNodeTitle(node, algorithmLabel) }}</span>
                <span :class="['node-status-dot', isNodeConfiguredFn(node) ? 'configured' : 'unconfigured']"></span>
              </div>
              <span class="mini-flow-detail">{{ pipelineNodeSummary(node) }}</span>
            </div>
            <!-- Expanded node params panel -->
            <div v-if="expandedNodeId === node.id" class="node-expand-panel" @click.stop>
              <NodeParamsEditor :node="node" :model="model" :readonly="model.status === MODEL_STATUS.TRAINING"
                @update="onNodeUpdate" />
              <div class="node-expand-actions">
                <el-button size="small" type="primary" :loading="syncingNode" @click="$emit('syncNodeChanges')">同步并保存</el-button>
              </div>
            </div>
            <span v-if="i < detailPipelineNodes.length - 1" class="mini-flow-arrow">&rarr;</span>
          </template>
        </div>
      </div>

      <div v-if="model.featureColumns" class="detail-section">
        <h4>特征列</h4>
        <div class="feature-tags">
          <el-tag v-for="col in parseJson(model.featureColumns, [])" :key="col" size="small" style="margin: 2px">{{ col }}</el-tag>
        </div>
      </div>

      <div v-if="model.metrics" class="detail-section">
        <h4>评估指标</h4>
        <div v-if="overfittingWarningFn(model)" class="overfitting-warning">
          {{ overfittingWarningFn(model) }}
        </div>
        <div class="metrics-grid">
          <div v-for="(val, key) in parsedMetrics(model.metrics)" :key="key" class="metric-card">
            <span class="metric-value">{{ formatMetricValue(key, val) }}</span>
            <span class="metric-name">{{ formatMetricName(key) }}</span>
          </div>
        </div>
      </div>

      <div v-if="model.featureImportance" class="detail-section">
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

      <div v-if="model.validationMetrics" class="detail-section">
        <h4>验证结果</h4>
        <div class="validation-info">
          <template v-if="parsedMetrics(model.validationMetrics).cv_mean !== undefined">
            <div class="val-item">
              <span class="val-label">CV {{ parsedMetrics(model.validationMetrics).cv_folds || 5 }}-Fold</span>
              <span class="val-value">{{ (parsedMetrics(model.validationMetrics).cv_mean * 100).toFixed(1) }}% &pm; {{ (parsedMetrics(model.validationMetrics).cv_std * 100).toFixed(1) }}%</span>
            </div>
            <div v-if="parsedMetrics(model.validationMetrics).cv_scores" class="val-scores">
              <span v-for="(s, i) in parsedMetrics(model.validationMetrics).cv_scores" :key="i" class="score-chip">{{ (s * 100).toFixed(1) }}%</span>
            </div>
          </template>
          <template v-if="parsedMetrics(model.validationMetrics).temporal_split">
            <div class="val-item">
              <span class="val-label">时间外验证</span>
              <span class="val-value">{{ parsedMetrics(model.validationMetrics).temporal_split }}</span>
            </div>
            <div v-if="parsedMetrics(model.validationMetrics).temporal_accuracy" class="val-item">
              <span class="val-label">时序准确率</span>
              <span class="val-value">{{ (parsedMetrics(model.validationMetrics).temporal_accuracy * 100).toFixed(1) }}%</span>
            </div>
          </template>
        </div>
      </div>

      <div v-if="model.status === MODEL_STATUS.PUBLISHED && (model.predictInputTable || model.predictResultTable || model.scheduleEnabled)" class="detail-section">
        <h4>发布配置</h4>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item v-if="model.predictInputTable" label="输入表">{{ model.predictInputTable }}</el-descriptions-item>
          <el-descriptions-item v-if="model.predictInputFilter" label="筛选条件">
            <code style="font-size: var(--font-sm)">{{ model.predictInputFilter }}</code>
          </el-descriptions-item>
          <el-descriptions-item v-if="model.predictResultTable" label="结果表">{{ model.predictResultTable }}</el-descriptions-item>
          <el-descriptions-item label="定时调度">
            <span v-if="model.scheduleEnabled" style="color: var(--el-color-success)">
              {{ { train: '定期重训', predict: '定期预测' }[model.scheduleMode] || model.scheduleMode }}
              ({{ formatScheduleCron(model.scheduleCron) }})
            </span>
            <span v-else style="color: var(--text-muted)">未启用</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="model.lastRunAt" label="上次运行">{{ formatDate(model.lastRunAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="model.nextRunAt" label="下次运行">{{ formatDate(model.nextRunAt) }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="detail-section">
        <h4>执行历史</h4>
        <div v-if="loadingExecutions" style="text-align: center; padding: 16px"><span class="spinner"></span></div>
        <div v-else-if="!executions.length" class="empty-executions">暂无执行记录</div>
        <div v-else class="execution-list">
          <div v-for="exec in executions" :key="exec.id" class="execution-item" :class="{ 'exec-failed-row': exec.status === EXECUTION_STATUS.FAILED }">
            <div class="exec-row-main">
              <span :class="['exec-status', 'exec-' + exec.status]">{{ execStatusLabel(exec.status) }}</span>
              <span class="exec-time">{{ exec.executionTimeMs ? (exec.executionTimeMs / 1000).toFixed(1) + 's' : '-' }}</span>
              <span class="exec-trigger">{{ execTriggerLabel(exec.triggerType) }}</span>
              <span class="exec-date">{{ formatDate(exec.createdAt) }}</span>
              <el-button v-if="exec.status === EXECUTION_STATUS.SUCCESS && model" size="small" text type="warning"
                @click="$emit('rollback', model.id, exec.id)" style="margin-left:auto">回滚</el-button>
            </div>
            <div v-if="exec.metrics && exec.status === EXECUTION_STATUS.SUCCESS" class="exec-metrics">
              <template v-for="(val, key) in parsedMetrics(exec.metrics)" :key="key">
                <span v-if="isExecMetricKey(key)" class="exec-metric-chip">{{ formatMetricName(key) }} {{ formatMetricValue(key, val) }}</span>
              </template>
            </div>
            <div v-if="exec.status === EXECUTION_STATUS.FAILED && exec.executionLog" class="exec-error">
              {{ exec.executionLog.split('\n').filter(l => l.trim()).pop() }}
            </div>
          </div>
        </div>
      </div>

      <!-- Quick actions in detail drawer -->
      <div class="detail-actions">
        <el-button size="small" type="primary" :loading="trainingId === model.id"
          @click="$emit('train', model.id)">
          {{ model.status === MODEL_STATUS.TRAINING ? '训练中...' : '训练' }}
        </el-button>
        <el-button v-if="model.status === MODEL_STATUS.TRAINED || model.status === MODEL_STATUS.OFFLINE" size="small" type="success"
          @click="$emit('publish', model.id)">发布</el-button>
        <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" type="warning"
          @click="$emit('offline', model.id)">下线</el-button>
        <el-button v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" size="small" type="primary"
          @click="$emit('predict', model)">预测</el-button>
        <el-button size="small" @click="$emit('edit', model)">编辑</el-button>
        <el-button size="small" @click="$emit('tuneParams', model)">调参</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
import NodeParamsEditor from '../NodeParamsEditor.vue'
import {
  pipelineNodeIcon, pipelineNodeTitle, pipelineNodeSummary,
  isNodeConfigured, parsedMetrics, formatMetricName, formatMetricValue,
  overfittingWarning
} from '../../composables/useModelDetail'
import { EXECUTION_STATUS, MODEL_STATUS } from '../../constants'

const props = defineProps({
  show: { type: Boolean, default: false },
  model: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  executions: { type: Array, default: () => [] },
  loadingExecutions: { type: Boolean, default: false },
  trainingId: { type: [Number, String, null], default: null },
  detailPipelineNodes: { type: Array, default: () => [] },
  expandedNodeId: { type: [String, Number, null], default: null },
  syncingNode: { type: Boolean, default: false },
  sortedImportance: { type: Object, default: () => ({}) },
  maxImportance: { type: Number, default: 1 },
  algorithmLabel: { type: Function, required: true },
  modelTypeLabel: { type: Function, required: true },
  statusLabel: { type: Function, required: true },
  formatDate: { type: Function, required: true },
  execStatusLabel: { type: Function, required: true },
  execTriggerLabel: { type: Function, required: true },
  needsSync: { type: Function, required: true },
  EXECUTION_STATUS: { type: Object, default: () => EXECUTION_STATUS },
  MODEL_STATUS: { type: Object, default: () => MODEL_STATUS }
})

const emit = defineEmits([
  'update:show', 'train', 'publish', 'offline', 'predict', 'edit',
  'tuneParams', 'rollback', 'goToPipeline', 'syncPipeline',
  'expandNode', 'syncNodeChanges', 'nodeParamUpdate'
])

// Re-exported helpers from composable
const isNodeConfiguredFn = isNodeConfigured
const overfittingWarningFn = overfittingWarning

const EXEC_METRIC_KEYS = [
  'accuracy', 'f1', 'precision', 'recall', 'r2', 'rmse', 'mae',
  'silhouette_score', 'train_accuracy', 'test_accuracy', 'train_f1',
  'test_f1', 'overfitting_gap', 'cv_mean', 'cv_std'
]

function isExecMetricKey(key) {
  return EXEC_METRIC_KEYS.includes(key)
}

function parseJson(json, fallback) {
  try { return typeof json === 'string' ? JSON.parse(json) : json || fallback } catch { return fallback }
}

function onNodeUpdate(payload) {
  emit('nodeParamUpdate', payload)
}

const CRON_LABELS = {
  '*/60': '每小时', '*/1440': '每天', '*/5': '每5分钟', '*/15': '每15分钟',
  '*/30': '每30分钟', '*/360': '每6小时', '*/720': '每12小时'
}

function formatScheduleCron(cron) {
  if (!cron) return ''
  return CRON_LABELS[cron] || '每' + cron.replace('*/', '') + '分钟'
}
</script>

<style scoped>
.status-badge {
  display: inline-block; padding: 2px 8px; border-radius: var(--radius-pill);
  font-size: var(--font-xs); font-weight: 500;
}
.status-draft { background: var(--color-info-light); color: var(--color-info); }
.status-training { background: var(--color-warning-light); color: var(--color-warning); }
.status-trained { background: var(--color-success-light); color: var(--color-success); }
.status-failed { background: var(--color-danger-light); color: var(--color-danger); }
.status-published { background: var(--primary-light); color: var(--primary); }
.status-offline { background: var(--border-light); color: var(--text-muted); }
.detail-section { margin-top: var(--space-lg); }
.overfitting-warning { padding: 8px 12px; border-radius: var(--radius-md); margin-bottom: 8px; font-size: var(--font-md); background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); border: 1px solid var(--el-color-warning-light-7); }
.detail-section h4 {
  font-size: var(--font-base); font-weight: 600; color: var(--text-primary);
  margin-bottom: var(--space-sm); padding-bottom: var(--space-xs);
  border-bottom: 1px solid var(--border-light);
}
.feature-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.pipeline-mini-flow {
  display: flex; align-items: flex-start; gap: 2px; overflow-x: auto;
  padding: 8px 0; flex-wrap: wrap;
}
.mini-flow-node {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  background: var(--bg-secondary); border: 1px solid var(--border-light);
  border-radius: var(--radius-md); padding: 6px 8px; min-width: 60px; max-width: 80px;
  text-align: center; flex-shrink: 0; cursor: pointer; transition: all 0.15s;
}
.mini-flow-node:hover { border-color: var(--primary); background: var(--primary-light); }
.mini-flow-node.expanded { border-color: var(--primary); background: var(--primary-light); box-shadow: 0 0 0 2px var(--primary-light); }
.mini-flow-node-header { display: flex; align-items: center; gap: 2px; }
.mini-flow-icon { font-size: var(--font-xl); }
.mini-flow-title { font-size: 10px; font-weight: 600; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70px; }
.mini-flow-detail { font-size: 9px; color: var(--text-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 70px; }
.node-status-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.node-status-dot.configured { background: var(--el-color-success); }
.node-status-dot.unconfigured { background: var(--el-color-danger); }
.mini-flow-arrow { color: var(--text-muted); font-size: var(--font-sm); margin-top: 14px; flex-shrink: 0; }
.node-expand-panel {
  width: 100%; margin: 4px 0; padding: var(--space-md); background: var(--bg-secondary);
  border: 1px solid var(--border); border-radius: var(--radius-lg); order: 999;
  animation: slideDown 0.2s ease;
}
.node-expand-actions { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); text-align: right; }
@keyframes slideDown { from { opacity: 0; transform: translateY(-8px); } to { opacity: 1; transform: translateY(0); } }
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
.imp-track { flex: 1; height: 8px; background: var(--border-light); border-radius: var(--radius-sm); overflow: hidden; }
.imp-fill { height: 100%; background: var(--primary); border-radius: var(--radius-sm); transition: width 0.3s; }
.imp-value { width: 40px; font-size: var(--font-sm); color: var(--text-muted); text-align: right; }
.validation-info { display: flex; flex-direction: column; gap: 6px; }
.val-item { display: flex; justify-content: space-between; font-size: var(--font-sm); }
.val-label { color: var(--text-secondary); }
.val-value { font-weight: 600; color: var(--text-primary); }
.val-scores { display: flex; flex-wrap: wrap; gap: 4px; }
.score-chip { font-size: var(--font-xs); background: var(--primary-light); color: var(--primary); padding: 1px 6px; border-radius: var(--radius-sm); }
.execution-list { display: flex; flex-direction: column; gap: var(--space-xs); }
.execution-item {
  padding: var(--space-xs) var(--space-sm); background: var(--border-lighter);
  border-radius: var(--radius-sm); font-size: var(--font-sm);
}
.exec-row-main { display: flex; align-items: center; gap: var(--space-sm); }
.exec-failed-row { flex-direction: column; align-items: stretch; }
.exec-error {
  margin-top: 4px; padding: 4px 8px; font-size: var(--font-xs); color: var(--color-danger);
  background: rgba(245, 108, 108, 0.08); border-radius: var(--radius-sm);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.exec-metrics { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 4px; }
.exec-metric-chip {
  font-size: var(--font-xs); padding: 1px 6px; border-radius: var(--radius-sm);
  background: var(--color-success-light); color: var(--color-success); font-weight: 500;
}
.exec-status { font-weight: 500; }
.exec-success { color: var(--color-success); }
.exec-failed { color: var(--color-danger); }
.exec-running { color: var(--color-warning); }
.exec-pending { color: var(--color-info); }
.exec-time { color: var(--text-secondary); }
.exec-trigger { color: var(--text-muted); }
.exec-date { margin-left: auto; color: var(--text-muted); font-size: var(--font-xs); }
.empty-executions { text-align: center; color: var(--text-muted); padding: var(--space-lg); }
.detail-actions {
  margin-top: 24px; padding-top: 16px;
  border-top: 1px solid var(--border);
  display: flex; gap: 8px; flex-wrap: wrap;
}
.spinner {
  width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; animation: spin 0.6s linear infinite; display: inline-block;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
