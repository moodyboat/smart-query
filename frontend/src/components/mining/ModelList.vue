<template>
  <div class="model-list-wrap">
    <div v-if="loading" class="mining-loading"><span class="spinner"></span> 加载中...</div>
    <div v-else-if="!models.length" class="mining-empty">
      <div class="empty-icon">🔬</div>
      <p>暂无挖掘模型</p>
      <p class="empty-hint">点击「新建模型」创建第一个数据挖掘流程</p>
    </div>
    <div v-else-if="!filteredModels.length" class="mining-empty">
      <p>没有匹配「{{ modelSearch }}」的模型</p>
    </div>
    <div v-else class="model-grid">
      <div v-for="model in filteredModels" :key="model.id" class="model-card" @click="$emit('select', model)">
        <div class="model-card-header">
          <div class="model-card-title">
            <span class="model-type-icon">{{ modelTypeIcon(model.modelType) }}</span>
            <span class="model-name">{{ model.name }}</span>
          </div>
          <span :class="['status-badge', 'status-' + model.status]">{{ statusLabel(model.status) }}</span>
          <span v-if="model.status === MODEL_STATUS.PUBLISHED && model.scheduleEnabled" class="schedule-badge" :title="scheduleTooltip(model)">⏰ {{ scheduleIntervalLabel(model) }}</span>
        </div>
        <div class="model-card-body">
          <div class="model-meta">
            <span class="meta-item">{{ algorithmLabel(model.algorithm) }}</span>
            <span class="meta-item">{{ modelTypeLabel(model.modelType) }}</span>
          </div>
          <div class="model-meta">
            <span class="meta-item secondary">表: {{ model.sourceTable || '-' }}</span>
            <span class="meta-item secondary">v{{ model.version }}</span>
            <el-tag v-if="model.conversationId" size="small" effect="plain" type="info">对话构建</el-tag>
            <el-tag v-else-if="model.pipelineId" size="small" effect="plain" type="success">流程编排</el-tag>
            <span v-if="model.pipelineId" class="meta-item secondary" style="cursor:pointer;color:var(--el-color-primary)" @click.stop="$emit('goToPipeline', model.pipelineId)">流程 #{{ model.pipelineId }}</span>
            <el-tag v-if="needsSync(model)" size="small" effect="dark" type="warning" style="cursor:pointer" @click.stop="$emit('handleSyncPipeline', model)">未同步</el-tag>
          </div>
          <div v-if="model.description" class="model-desc">{{ model.description }}</div>
          <div v-if="model.metrics" class="model-metrics">
            <span :class="['metric-primary', 'quality-' + metricQuality(primaryMetricRaw(model), model.modelType)]">
              {{ primaryMetricLabel(model) }}
              <strong>{{ primaryMetricValue(model) }}</strong>
            </span>
            <template v-for="(val, key) in parsedMetrics(model.metrics)" :key="key">
              <span v-if="!isPrimary(key, model.modelType)" :class="['metric-chip', 'quality-' + metricQuality(val, model.modelType, key)]">{{ formatMetricName(key) }} {{ formatMetricValue(key, val) }}</span>
            </template>
          </div>
        </div>
        <div class="model-card-actions">
          <el-button size="small" :loading="trainingId === model.id" @click.stop="$emit('train', model.id)">
            {{ model.status === MODEL_STATUS.TRAINING ? '训练中...' : '训练' }}
          </el-button>
          <el-button v-if="model.status !== MODEL_STATUS.DRAFT && model.status !== MODEL_STATUS.TRAINING" size="small"
            @click.stop="$emit('editModel', model)">调参</el-button>
          <el-button v-if="model.status === MODEL_STATUS.TRAINED || model.status === MODEL_STATUS.OFFLINE" size="small" type="success"
            @click.stop="$emit('publish', model.id)">发布</el-button>
          <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" type="warning"
            @click.stop="$emit('offline', model.id)">下线</el-button>
          <el-button v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" size="small" type="primary"
            @click.stop="$emit('predict', model)">预测</el-button>
          <el-button v-if="model.status === MODEL_STATUS.PUBLISHED" size="small" plain
            @click.stop="$emit('batchPredict', model)">批量预测</el-button>
          <el-dropdown trigger="click" @command="cmd => $emit('actionCmd', cmd, model)" @click.stop>
            <el-button size="small" @click.stop>更多 ▾</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item v-if="model.status === MODEL_STATUS.DRAFT" command="validate">训练前校验</el-dropdown-item>
                <el-dropdown-item v-if="model.status === MODEL_STATUS.PUBLISHED" command="batchPredict">批量预测</el-dropdown-item>
                <el-dropdown-item command="schedule">{{ model.scheduleEnabled ? '调度设置 (已启用)' : '调度设置' }}</el-dropdown-item>
                <el-dropdown-item v-if="model.status === MODEL_STATUS.PUBLISHED || model.status === MODEL_STATUS.TRAINED" command="predictResults">预测记录</el-dropdown-item>
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

defineProps({
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
  scheduleIntervalLabel: { type: Function, required: true },
  scheduleTooltip: { type: Function, required: true },
  parsedMetrics: { type: Function, required: true },
  primaryMetricRaw: { type: Function, required: true },
  primaryMetricLabel: { type: Function, required: true },
  primaryMetricValue: { type: Function, required: true },
  metricQuality: { type: Function, required: true },
  isPrimary: { type: Function, required: true },
  formatMetricName: { type: Function, required: true },
  formatMetricValue: { type: Function, required: true },
  needsSync: { type: Function, required: true },
  MODEL_STATUS: { type: Object, default: () => MODEL_STATUS }
})

defineEmits([
  'select', 'train', 'editModel', 'publish', 'offline',
  'predict', 'batchPredict', 'actionCmd', 'goToPipeline',
  'handleSyncPipeline', 'search'
])
</script>

<style scoped>
.model-list-wrap { flex: 1; overflow-y: auto; }
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
</style>
