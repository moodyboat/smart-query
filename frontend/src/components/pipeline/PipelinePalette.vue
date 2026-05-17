<template>
  <div class="algorithm-palette">
    <div class="palette-title">算法库</div>
    <div v-for="group in algorithmGroups" :key="group.category" class="palette-group">
      <div class="palette-group-title">{{ group.category }}</div>
      <div
        v-for="algo in group.algorithms"
        :key="algo.algorithmId"
        class="palette-card"
        draggable="true"
        @dragstart="$emit('paletteDragStart', $event, algo)"
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
      <div
        v-for="node in baseNodes"
        :key="node.type"
        class="palette-card"
        draggable="true"
        @dragstart="$emit('paletteDragStart', $event, null, node.type)"
      >
        <span class="palette-icon">{{ node.icon }}</span>
        <span class="palette-name">{{ node.name }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  algorithmGroups: { type: Array, default: () => [] },
  modelTypeNames: { type: Function, required: true }
})

defineEmits(['paletteDragStart'])

const baseNodes = [
  { type: 'data_source', icon: '📥', name: '数据接入' },
  { type: 'preprocessing', icon: '🔧', name: '预处理' },
  { type: 'fill_missing', icon: '🩹', name: '填充缺失值' },
  { type: 'feature_engineering', icon: '⚙️', name: '特征工程' },
  { type: 'evaluation', icon: '📊', name: '模型评估' },
  { type: 'output', icon: '💾', name: '输出写入' }
]
</script>

<style scoped>
.algorithm-palette {
  width: var(--palette-width);
  border-right: 1px solid var(--border);
  overflow-y: auto;
  padding: var(--space-sm);
  flex-shrink: 0;
}

.palette-title {
  font-size: var(--font-base);
  font-weight: 600;
  padding: var(--space-sm) var(--space-xs);
  margin-bottom: var(--space-xs);
}

.palette-group { margin-bottom: var(--space-md); }

.palette-group-title {
  font-size: var(--font-xs);
  color: var(--text-muted);
  padding: var(--space-xs);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.palette-card {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-xs);
  border-radius: var(--radius-md);
  cursor: grab;
  transition: background 0.15s;
  margin-bottom: 2px;
}

.palette-card:hover { background: var(--hover); }
.palette-card:active { cursor: grabbing; }

.palette-icon { font-size: 18px; }

.palette-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.palette-name {
  font-size: var(--font-sm);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.palette-types {
  font-size: var(--font-xs);
  color: var(--text-muted);
}
</style>
