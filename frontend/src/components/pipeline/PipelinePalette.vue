<template>
  <div class="algorithm-palette">
    <div class="palette-title">
      <span>训练方法</span>
      <el-button size="small" text type="primary" @click="$emit('manageAlgorithms')">管理算法</el-button>
    </div>
    <div v-for="group in algorithmGroups" :key="group.category" class="palette-group">
      <div class="palette-group-title" @click="toggleGroup(group.category)">
        <span>{{ group.category }}</span>
        <span class="expand-icon" :class="{ expanded: isGroupExpanded(group.category) }">▼</span>
      </div>
      <div v-show="isGroupExpanded(group.category)" class="palette-content">
        <div
          v-for="algo in group.algorithms"
          :key="algo.algorithmId"
          class="palette-card"
          draggable="true"
          @dragstart="$emit('paletteDragStart', $event, algo)"
          @click="$emit('algorithmClick', algo)"
        >
          <span class="palette-icon">{{ algo.icon || '🤖' }}</span>
          <div class="palette-info">
            <span class="palette-name">{{ algo.name }}</span>
            <span class="palette-types">{{ modelTypeNames(algo.modelTypes) }}</span>
          </div>
        </div>
      </div>
    </div>
    <div class="palette-group">
      <div class="palette-group-title" @click="toggleBaseNodes">
        <span>基础节点</span>
        <span class="expand-icon" :class="{ expanded: baseNodesExpanded }">▼</span>
      </div>
      <div v-show="baseNodesExpanded" class="palette-content">
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
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'

const props = defineProps({
  algorithmGroups: { type: Array, default: () => [] },
  modelTypeNames: { type: Function, required: true }
})

defineEmits(['paletteDragStart', 'algorithmClick', 'manageAlgorithms'])

// 每个算法组的展开状态
const groupStates = reactive({})

// 基础节点展开状态
const baseNodesExpanded = ref(true)

// 初始化所有组为展开状态
const initializeGroups = () => {
  props.algorithmGroups.forEach(group => {
    if (!(group.category in groupStates)) {
      groupStates[group.category] = true
    }
  })
}

// 监听 algorithmGroups 变化
import { watch } from 'vue'
watch(() => props.algorithmGroups, initializeGroups, { immediate: true })

const isGroupExpanded = (category) => {
  return groupStates[category] !== false // 默认展开
}

const toggleGroup = (category) => {
  groupStates[category] = !groupStates[category]
}

const toggleBaseNodes = () => {
  baseNodesExpanded.value = !baseNodesExpanded.value
}

const baseNodes = [
  { type: 'data_source', icon: '📥', name: '数据接入' },
  { type: 'preprocessing', icon: '🔧', name: '预处理' },
  { type: 'fill_missing', icon: '🩹', name: '填充缺失值' },
  { type: 'feature_engineering', icon: '⚙️', name: '特征工程' },
  { type: 'evaluation', icon: '📊', name: '效果评估' },
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
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.palette-group { margin-bottom: var(--space-md); }

.palette-group-title {
  font-size: var(--font-xs);
  color: var(--text-muted);
  padding: var(--space-xs);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s;
  border-radius: var(--radius-sm);
}

.palette-group-title:hover {
  background: var(--hover);
}

.palette-group-title > span:first-child {
  flex: 1;
}

.expand-icon {
  font-size: 10px;
  transition: transform 0.2s ease;
  padding: var(--space-xs);
  margin-left: 4px;
}

.expand-icon.expanded {
  transform: rotate(180deg);
}

.palette-content {
  animation: slideDown 0.2s ease;
}

@keyframes slideDown {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
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

.palette-icon { font-size: var(--font-2xl); }

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
