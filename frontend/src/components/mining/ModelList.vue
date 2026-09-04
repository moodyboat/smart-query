<template>
  <div class="model-list-wrap">
    <div v-if="loading" class="mining-loading"><span class="spinner"></span> 加载中...</div>
    <div v-else-if="!models.length" class="mining-empty">
      <div class="empty-icon">学</div>
      <p>暂无机器学习算子</p>
      <p class="empty-hint">新建算子后，按页面步骤选择数据并开始训练</p>
    </div>
    <div v-else-if="!filteredModels.length" class="mining-empty">
      <p>没有匹配「{{ modelSearch }}」的算子</p>
    </div>
    <div v-else class="model-grid">
      <div v-for="model in filteredModels" :key="model.id" :class="['model-card', `is-${model.modelType}`, { 'selected': selectedModels.has(model.id) }]" @click="$emit('select', model)">
        <div class="model-card-header">
          <div class="model-card-title">
            <span class="model-type-icon" aria-hidden="true">{{ modelTypeIcon(model.modelType) }}</span>
            <div class="model-title-copy">
              <span class="model-type-label">{{ modelTypeLabel(model.modelType) }}</span>
              <span class="model-name" :title="model.name">{{ model.name }}</span>
              <small>{{ algorithmLabel(model.algorithm) }}</small>
            </div>
          </div>
          <div class="model-card-state" @click.stop>
            <span :class="['status-badge', 'status-' + model.status]">{{ operatorStatusLabel(model) }}</span>
            <el-checkbox :model-value="selectedModels.has(model.id)" aria-label="选择算子" @change="handleSelectChange(model.id, $event)" />
          </div>
        </div>
        <div class="model-card-body">
          <div class="model-facts">
            <div><span>训练数据</span><strong>{{ model.sourceTable || '未选择' }}</strong></div>
            <div><span>当前版本</span><strong>版本 {{ model.version }}</strong></div>
            <div><span>训练结果</span><strong>{{ model.metrics ? `${primaryMetricLabel(model)} ${primaryMetricValue(model)}` : '等待训练' }}</strong></div>
          </div>
          <div class="model-meta status-row">
            <el-tag size="small" effect="plain" :type="model.modelPath && model.artifactSha256 ? 'success' : 'info'">{{ model.modelPath && model.artifactSha256 ? '制品已固化' : '暂无制品' }}</el-tag>
            <el-tag v-if="model.status === MODEL_STATUS.PUBLISHED && isOperatorPublished(model)" size="small" effect="plain" type="primary">已进入算子库</el-tag>
            <el-tag v-else-if="model.status === MODEL_STATUS.PUBLISHED" size="small" effect="plain" type="warning">算子版本审批中</el-tag>
            <el-tag v-else-if="model.status === MODEL_STATUS.OFFLINE" size="small" effect="plain" type="info">已停用</el-tag>
            <el-tag v-if="model.conversationId" size="small" effect="plain" type="info">会话创建</el-tag>
            <el-tag v-else-if="model.pipelineId" size="small" effect="plain" type="success">来自训练流程</el-tag>
            <span v-if="model.pipelineId" class="meta-item secondary" style="cursor:pointer;color:var(--el-color-primary)" @click.stop="$emit('goToPipeline', model.pipelineId)">查看训练流程</span>
            <el-tag v-if="needsSync(model)" size="small" effect="dark" type="warning" style="cursor:pointer" @click.stop="$emit('handleSyncPipeline', model)">未同步</el-tag>
          </div>
          <div v-if="model.description" class="model-desc">{{ model.description }}</div>
        </div>
        <div class="model-card-actions">
          <el-button size="small" :loading="trainingId === model.id" @click.stop="$emit('train', model.id)">
            {{ model.status === MODEL_STATUS.TRAINING ? '训练中...' : '训练' }}
          </el-button>
          <el-button v-if="model.status !== MODEL_STATUS.DRAFT && model.status !== MODEL_STATUS.TRAINING" size="small"
            @click.stop="$emit('editModel', model)">调整设置</el-button>
          <el-button v-if="model.status === MODEL_STATUS.TRAINED || model.status === MODEL_STATUS.OFFLINE" size="small" type="success"
            @click.stop="$emit('publish', model.id)">提交审批</el-button>
          <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" type="warning"
            @click.stop="$emit('offline', model.id)">下线</el-button>
          <el-button v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" size="small" type="primary"
            @click.stop="$emit('predict', model)">试用</el-button>
          <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" plain
            @click.stop="$emit('batchPredict', model)">批量预测</el-button>
          <el-dropdown trigger="click" @command="cmd => $emit('actionCmd', cmd, model)" @click.stop>
            <el-button size="small" @click.stop>更多 ▾</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="model.status === MODEL_STATUS.DRAFT" command="validate">检查配置</el-dropdown-item>
                <el-dropdown-item v-if="model.status === MODEL_STATUS.PUBLISHED" command="batchPredict">批量预测</el-dropdown-item>
                <el-dropdown-item v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" command="predictResults">使用记录</el-dropdown-item>
                <el-dropdown-item v-if="model.pipelineId" command="viewPipeline">查看流程</el-dropdown-item>
                <el-dropdown-item v-if="model.pipelineId" command="syncPipeline">同步流程</el-dropdown-item>
                <el-dropdown-item command="delete" divided style="color: var(--el-color-danger)">删除</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { MODEL_STATUS } from '../../constants'

const props = defineProps({
  models: { type: Array, default: () => [] },
  filteredModels: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  modelSearch: { type: String, default: '' },
  trainingId: { type: [Number, String, null], default: null },
  dataSources: { type: Array, default: () => [] },
  algorithmLabel: { type: Function, required: true },
  modelTypeLabel: { type: Function, required: true },
  modelTypeIcon: { type: Function, required: true },
  statusLabel: { type: Function, required: true },
  isOperatorPublished: { type: Function, default: () => false },
  parsedMetrics: { type: Function, required: true },
  primaryMetricRaw: { type: Function, required: true },
  primaryMetricLabel: { type: Function, required: true },
  primaryMetricValue: { type: Function, required: true },
  metricQuality: { type: Function, required: true },
  isPrimary: { type: Function, required: true },
  formatMetricName: { type: Function, required: true },
  formatMetricValue: { type: Function, required: true },
  needsSync: { type: Function, required: true },
  MODEL_STATUS: { type: Object, default: () => MODEL_STATUS },
  selectedModels: { type: Set, default: () => new Set() }
})

const emit = defineEmits([
  'select', 'train', 'editModel', 'publish', 'offline',
  'predict', 'batchPredict', 'actionCmd', 'goToPipeline', 'handleSyncPipeline',
  'updateSelection'
])

function handleSelectChange(modelId, checked) {
  emit('updateSelection', modelId, checked)
}

function operatorStatusLabel(model) {
  if (model.status === MODEL_STATUS.PUBLISHED && !props.isOperatorPublished(model)) return '版本审批中'
  return props.statusLabel(model.status)
}

const SUMMARY_METRIC_KEYS = [
  'test_balanced_accuracy', 'test_f1_macro', 'roc_auc', 'pr_auc', 'ks',
  'lift_at_10pct', 'test_rmse', 'test_mae', 'cv_mean',
  'silhouette_score', 'overfitting_gap'
]

function summaryMetrics(model) {
  const metrics = props.parsedMetrics(model.metrics)
  return Object.fromEntries(SUMMARY_METRIC_KEYS
    .filter(key => typeof metrics[key] === 'number' && !props.isPrimary(key, model.modelType))
    .slice(0, 6)
    .map(key => [key, metrics[key]]))
}
</script>

<style scoped>
.model-list-wrap { flex: 1; overflow-y: auto; }
.mining-loading, .mining-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 60px 0; color: var(--text-muted); font-size: var(--font-md);
}
.empty-icon { width:42px;height:42px;display:grid;place-items:center;margin-bottom:12px;border:1px solid #cdddfd;border-radius:9px;color:#2468f2;background:#edf3ff;font-size:14px;font-weight:700; }
.empty-hint { font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs); }
.model-grid { display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:12px;padding-bottom:12px; }
.model-card {
  position:relative;min-width:0;padding:15px 15px 12px;overflow:hidden;background:#fff;border:1px solid #e2e7ee;border-radius:10px;cursor:pointer;transition:border-color .15s,background-color .15s;
}
.model-card::before { content:'';position:absolute;inset:0 auto 0 0;width:3px;background:#3b82f6; }
.model-card.is-regression::before { background:#0f8b8d; }.model-card.is-clustering::before { background:#7c3aed; }.model-card.is-anomaly_detection::before { background:#d97706; }
.model-card.selected {
  border-color:#91b4f9;background:#f7faff;
}
.model-card:hover { border-color:#adc6ee;background:#fcfdff;box-shadow:none;transform:none; }
.model-card-header { display:flex;align-items:flex-start;justify-content:space-between;gap:10px;margin:0 0 13px; }
.model-card-title { min-width:0;display:flex;align-items:flex-start;gap:9px; }
.model-type-icon { width:38px;height:38px;display:grid;place-items:center;flex:none;border:1px solid #d7e4fb;border-radius:9px;background:#f3f7ff;font-size:17px;line-height:1; }
.is-regression .model-type-icon { border-color:#cce8e7;background:#f0fafa; }.is-clustering .model-type-icon { border-color:#e2d9fa;background:#f8f5ff; }.is-anomaly_detection .model-type-icon { border-color:#f2dfbd;background:#fff9ed; }
.model-type-label { color:#7e8998;font-size:9px;font-weight:600; }
.model-title-copy { min-width:0;display:flex;flex-direction:column;gap:2px; }
.model-title-copy small { color:#86909c;font-size:10px; }
.model-name { overflow:hidden;color:#29292c;font-size:14px;font-weight:650;text-overflow:ellipsis;white-space:nowrap; }
.model-card-state { display:flex;align-items:center;gap:8px;flex:none; }
.model-card-body { margin-bottom:12px; }
.model-desc {
  font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%;
}
.model-meta { display: flex; flex-wrap: wrap; gap: var(--space-sm); margin-bottom: var(--space-xs); }
.status-row { gap:6px;margin:10px 0 0; }
.model-facts { display:grid;grid-template-columns:1fr .7fr 1fr;border:1px solid #edf0f5;border-radius:8px;background:#fafbfc; }
.model-facts > div { min-width:0;display:flex;flex-direction:column;gap:4px;padding:9px 10px;border-right:1px solid #edf0f5; }
.model-facts > div:last-child { border-right:0; }
.model-facts span { color:#86909c;font-size:9px; }
.model-facts strong { overflow:hidden;color:#4e5969;font-size:10px;text-overflow:ellipsis;white-space:nowrap; }
.meta-item { font-size: var(--font-sm); color: var(--text-secondary); }
.meta-item.secondary { color: var(--text-muted); font-size: var(--font-xs); }
.model-metrics { display: flex; flex-wrap: wrap; gap: var(--space-xs); margin-top: var(--space-sm); align-items: center; }
.metric-primary {
  padding: 2px 10px; border-radius: var(--radius-sm);
  font-size: var(--font-sm); font-weight: 500;
  display: inline-flex; align-items: center; gap: 4px;
  color: var(--surface);
}
.metric-primary.quality-good { background: var(--color-success); }
.metric-primary.quality-moderate { background: var(--color-warning); }
.metric-primary.quality-poor { background: var(--color-danger); }
.metric-primary.quality-neutral { background: var(--color-success); }
.metric-primary strong { font-size: var(--font-lg); }
.metric-chip {
  padding: 2px var(--space-sm); border-radius: var(--radius-sm);
  font-size: var(--font-xs); font-weight: 500;
}
.metric-chip.quality-good { background: var(--color-success-light); color: var(--color-success); }
.metric-chip.quality-moderate { background: var(--color-warning-light); color: var(--color-warning); }
.metric-chip.quality-poor { background: var(--color-danger-light); color: var(--color-danger); }
.metric-chip.quality-neutral { background: var(--color-success-light); color: var(--color-success); }
.model-card-actions { display:flex;flex-wrap:wrap;gap:5px;padding-top:10px;border-top:1px solid #edf0f5; }
.model-card-actions :deep(.el-button + .el-button) { margin-left:0; }
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
.schedule-badge {
  display: inline-flex; align-items: center; gap: 2px;
  padding: 2px 8px; border-radius: var(--radius-pill);
  font-size: var(--font-xs); font-weight: 500;
  background: var(--primary-ghost-bg); color: var(--primary-ghost-fg); cursor: default;
}
.spinner {
  width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; animation: spin 0.6s linear infinite; display: inline-block;
}
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width:620px) { .model-grid{grid-template-columns:1fr}.model-card-header{align-items:flex-start}.model-card-state{align-items:flex-end;flex-direction:column}.model-facts{grid-template-columns:1fr}.model-facts>div{border-right:0;border-bottom:1px solid #edf0f5}.model-facts>div:last-child{border-bottom:0} }
</style>
