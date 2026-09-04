<template>
  <div class="pipeline-list-header">
    <div class="list-header-left">
      <span class="list-count">{{ filteredPipelines.length }} 个算子训练流程</span>
      <el-select v-model="filterDsId" placeholder="流水线数据源筛选" aria-label="流水线数据源筛选" size="small" clearable style="width: 190px">
        <el-option v-for="ds in dataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
      </el-select>
    </div>
    <el-button type="primary" size="small" @click="$emit('create')">新建算子训练流程</el-button>
  </div>
  <div v-if="filteredPipelines.length === 0" class="empty-pipelines">
    <p>暂无算子训练流程</p>
  </div>
  <div class="pipeline-grid">
    <div v-for="p in filteredPipelines" :key="p.id" class="pipeline-card" @click="openPrimary(p)">
      <div class="pipeline-card-header">
        <span class="pipeline-name">{{ p.name }}</span>
        <div style="display: flex; gap: 4px; align-items: center;">
          <el-tag v-if="p.sourceType === 'chat'" type="info" size="small" effect="plain">对话构建</el-tag>
          <el-tag :type="statusType(p.status)" size="small">{{ statusLabel(p.status) }}</el-tag>
        </div>
      </div>
      <div class="pipeline-card-meta">
        <span>{{ dataSourceName(p.dataSourceId) }}</span>
        <span>{{ nodeCount(p) }} 个步骤</span>
        <span>{{ formatDate(p.createdAt) }}</span>
        <span v-if="props.repositoryMode">{{ props.isAdmin ? `归属用户 #${p.userId || '-'}` : '仅本人可编辑' }}</span>
      </div>
      <div v-if="parsedNodes(p).length" class="pipeline-card-flow">
        <span v-for="(n, i) in parsedNodes(p).slice(0, 5)" :key="i" class="mini-node">
          {{ nodeIcon(n.type) }} {{ nodeLabel(n) }}
          <span v-if="i < Math.min(parsedNodes(p).length, 5) - 1" class="mini-arrow">→</span>
        </span>
        <span v-if="parsedNodes(p).length > 5" class="mini-more">+{{ parsedNodes(p).length - 5 }}</span>
      </div>
      <div class="pipeline-card-actions">
        <el-button size="small" text type="primary" @click.stop="openPrimary(p)">{{ props.unifiedDag ? '进入流程编排' : '编辑' }}</el-button>
        <el-button v-if="props.unifiedDag" size="small" text @click.stop="emit('open', p)">编辑训练草稿</el-button>
        <el-button size="small" text type="success" @click.stop="$emit('runFromCard', p)" :disabled="p.status === PIPELINE_STATUS.RUNNING">
          {{ p.status === PIPELINE_STATUS.RUNNING ? '运行中' : '运行' }}
        </el-button>
        <el-button size="small" text type="danger" @click.stop="$emit('delete', p)">删除</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { PIPELINE_STATUS } from '../../constants'

const props = defineProps({
  pipelines: { type: Array, default: () => [] },
  dataSources: { type: Array, default: () => [] },
  repositoryMode: { type: Boolean, default: false },
  unifiedDag: { type: Boolean, default: false },
  isAdmin: { type: Boolean, default: false }
})

const emit = defineEmits(['create', 'open', 'openDag', 'runFromCard', 'delete'])

const filterDsId = ref(null)

function openPrimary(pipeline) {
  emit(props.unifiedDag ? 'openDag' : 'open', pipeline)
}

const filteredPipelines = computed(() => {
  let visible = props.repositoryMode
    ? props.pipelines.filter(p => [PIPELINE_STATUS.DRAFT, PIPELINE_STATUS.READY, PIPELINE_STATUS.RUNNING, PIPELINE_STATUS.FAILED].includes(p.status))
    : props.pipelines
  if (filterDsId.value) visible = visible.filter(p => p.dataSourceId === filterDsId.value)
  return visible
})

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

function nodeLabel(node) {
  const title = node.config?.title
  if (title) return title
  return { data_source: '数据接入', preprocessing: '预处理', fill_missing: '填充', feature_engineering: '特征', training: '训练', evaluation: '评估', output: '输出' }[node.type] || node.type
}
</script>

<style scoped>
.pipeline-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-lg);
}

.list-header-left {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.list-heading { display: flex; flex-direction: column; gap: 3px; margin-right: var(--space-sm); }
.list-heading strong { color: #17365d; font-size: 16px; }
.list-heading small { color: #7b899b; font-size: 10px; }

.list-count {
  color: var(--text-muted);
  font-size: var(--font-md);
}

.empty-pipelines {
  text-align: center;
  padding: var(--space-2xl) 0;
  color: var(--text-muted);
}

.pipeline-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-lg);
}

.pipeline-card {
  min-width: 0;
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-xl);
  padding: var(--space-lg);
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
  margin-bottom: var(--space-sm);
}

.pipeline-name {
  font-weight: 600;
  font-size: var(--font-base);
}

.pipeline-card-meta {
  display: flex;
  gap: var(--space-md);
  color: var(--text-muted);
  font-size: var(--font-sm);
  margin-bottom: var(--space-sm);
}

.pipeline-card-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: var(--space-sm);
  font-size: var(--font-xs);
}

.mini-node { color: var(--text-secondary); }
.mini-arrow { color: var(--text-muted); margin: 0 2px; }
.mini-more { color: var(--text-muted); }

.pipeline-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
  border-top: 1px solid var(--border-light);
  padding-top: var(--space-sm);
}
.pipeline-card-actions :deep(.el-button) { margin-left: 0; }

@media (max-width: 1180px) {
  .pipeline-grid { grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr)); }
  .pipeline-card-actions :deep(.el-button) { padding-inline: 7px; }
}

@media (max-width: 680px) {
  .pipeline-grid { grid-template-columns: 1fr; }
  .pipeline-card-meta { flex-wrap: wrap; gap: var(--space-xs) var(--space-sm); }
}
</style>
