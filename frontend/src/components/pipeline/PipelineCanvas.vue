<template>
  <div
    class="flow-canvas"
    @dragover.prevent
    @drop="onCanvasDrop"
    @click.self="$emit('selectNode', null)"
  >
    <div v-if="nodes.length === 0" class="canvas-empty">
      <p>拖拽左侧算法或节点到此处开始编排</p>
    </div>
    <div class="nodes-flow">
      <template v-for="(node, idx) in nodes" :key="node.id">
        <!-- Node Card -->
        <div
          class="flow-node"
          :class="[node.type, { selected: selectedNodeId === node.id, running: runningNodeId === node.id || previewingNodeId === node.id, done: doneNodeIds.has(node.id) }]"
          draggable="true"
          @dragstart="$emit('nodeReorderStart', $event, idx)"
          @dragend="$emit('dragEnd')"
          @click.stop="$emit('selectNode', node)"
        >
          <div class="node-color-bar"></div>
          <div class="node-body">
            <div class="node-header">
              <span class="node-icon">{{ nodeIcon(node.type) }}</span>
              <span class="node-title">{{ node.config?.title || nodeTitle(node.type) }}</span>
              <span :class="['node-status-dot', isNodeConfigured(node) ? 'configured' : 'unconfigured']"
                :title="isNodeConfigured(node) ? '已配置' : '需要配置'"></span>
              <el-dropdown trigger="click" @command="cmd => $emit('nodeCmd', cmd, idx)" @click.stop size="small">
                <el-button size="small" text class="node-more" @click.stop>⋯</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="config">配置</el-dropdown-item>
                    <el-dropdown-item command="preview" :disabled="!isNodeConfigured(node) || previewingNodeId === node.id">
                      {{ previewingNodeId === node.id ? '运行中...' : '试运行' }}
                    </el-dropdown-item>
                    <el-dropdown-item command="rename">重命名</el-dropdown-item>
                    <el-dropdown-item command="delete" divided style="color: var(--danger)">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
            <div class="node-summary">{{ nodeSummary(node) }}</div>
            <div v-if="isNodeConfigured(node) || previewResult?.nodeId === node.id" class="node-actions-bar">
              <el-button v-if="isNodeConfigured(node)" size="small" text type="primary"
                :loading="previewingNodeId === node.id"
                @click.stop="$emit('previewStep', node.id)">
                {{ previewingNodeId === node.id ? '运行中...' : '▶ 试运行' }}
              </el-button>
              <el-button v-if="isNodeConfigured(node)" size="small" text type="info"
                :loading="scriptLoading"
                @click.stop="$emit('viewScript', node.id)">
                查看脚本
              </el-button>
              <el-button v-if="previewResult?.nodeId === node.id && previewingNodeId !== node.id" size="small" text type="success" @click.stop="$emit('showPreview')">
                查看结果
              </el-button>
            </div>
          </div>
        </div>

        <!-- Connection with drop zone -->
        <div
          class="flow-connector"
          @click="$emit('addStep', idx + 1)"
          @dragover.prevent="onConnectorDragOver($event)"
          @dragleave="onConnectorDragLeave($event)"
          @drop.stop="$emit('connectorDrop', $event, idx + 1)"
        >
          <div class="connector-line"></div>
          <div class="connector-drop-hint">+</div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
const props = defineProps({
  nodes: { type: Array, required: true },
  selectedNodeId: { type: String, default: null },
  runningNodeId: { type: String, default: null },
  previewingNodeId: { type: String, default: null },
  doneNodeIds: { type: Set, default: () => new Set() },
  previewResult: { type: Object, default: null },
  scriptLoading: { type: Boolean, default: false },
  isNodeConfigured: { type: Function, required: true },
  nodeSummary: { type: Function, required: true }
})

const emit = defineEmits([
  'selectNode',
  'nodeReorderStart',
  'dragEnd',
  'nodeCmd',
  'previewStep',
  'viewScript',
  'showPreview',
  'canvasDrop',
  'connectorDrop',
  'addStep'
])

function nodeIcon(type) {
  return {
    data_source: '📥',
    preprocessing: '🔧',
    fill_missing: '🩹',
    feature_engineering: '⚙️',
    training: '🧠',
    evaluation: '📊',
    output: '💾'
  }[type] || '📦'
}

function nodeTitle(type) {
  return {
    data_source: '数据接入',
    preprocessing: '数据预处理',
    fill_missing: '填充缺失值',
    feature_engineering: '特征工程',
    training: '模型训练',
    evaluation: '模型评估',
    output: '输出写入'
  }[type] || type
}

function onCanvasDrop(event) {
  emit('canvasDrop', event)
}

function onConnectorDragOver(event) {
  event.currentTarget?.classList?.add('connector-drag-over')
}

function onConnectorDragLeave(event) {
  event.currentTarget?.classList?.remove('connector-drag-over')
}
</script>

<style scoped>
/* Flow Canvas */
.flow-canvas {
  flex: 1;
  overflow: auto;
  padding: var(--space-xl);
}

.canvas-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 300px;
  color: var(--text-muted);
  font-size: var(--font-base);
  border: 2px dashed var(--border);
  border-radius: var(--radius-xl);
  margin: var(--space-xl);
}

.nodes-flow {
  display: flex;
  align-items: center;
  gap: 0;
  min-width: max-content;
  padding: var(--space-xl) 0;
}

.flow-node {
  position: relative;
  display: flex;
  background: var(--surface);
  border: 2px solid var(--border);
  border-radius: var(--radius-xl);
  min-width: var(--node-min-width);
  max-width: var(--node-max-width);
  cursor: pointer;
  transition: all 0.25s;
  overflow: hidden;
  box-shadow: var(--shadow-sm);
}

.flow-node:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.flow-node.selected {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px var(--primary-light);
}

.flow-node.running {
  border-color: var(--color-warning);
  animation: pulse 1s infinite;
}

.flow-node.done {
  border-color: var(--color-success);
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.node-color-bar {
  width: var(--space-xs);
  flex-shrink: 0;
}

.flow-node.data_source .node-color-bar { background: var(--node-color-data-source); }
.flow-node.preprocessing .node-color-bar { background: var(--node-color-preprocessing); }
.flow-node.fill_missing .node-color-bar { background: var(--node-color-fill-missing); }
.flow-node.feature_engineering .node-color-bar { background: var(--node-color-feature-engineering); }
.flow-node.training .node-color-bar { background: var(--node-color-training); }
.flow-node.evaluation .node-color-bar { background: var(--node-color-evaluation); }
.flow-node.output .node-color-bar { background: var(--node-color-output); }

.node-body {
  padding: var(--space-md);
  flex: 1;
}

.node-header {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.node-icon {
  font-size: var(--font-xl);
}

.node-title {
  font-size: var(--font-md);
  font-weight: 600;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.node-status-dot {
  width: var(--space-sm);
  height: var(--space-sm);
  border-radius: 50%;
  flex-shrink: 0;
}
.node-status-dot.configured { background: var(--el-color-success); }
.node-status-dot.unconfigured { background: var(--el-color-danger); animation: pulse-dot 2s infinite; }

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.node-more {
  padding: 0 !important;
  min-width: var(--space-xl);
}

.node-summary {
  margin-top: var(--space-xs);
  font-size: var(--font-xs);
  color: var(--text-muted);
  line-height: 1.4;
}

.node-actions-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
}

/* Flow Connector */
.flow-connector {
  display: flex;
  align-items: center;
  gap: 0;
  position: relative;
  padding: var(--space-xs) var(--space-sm);
  min-width: 44px;
  transition: background 0.15s;
  cursor: default;
}

.flow-connector:hover {
  background: var(--primary-light);
  border-radius: var(--radius-sm);
  cursor: pointer;
}

.flow-connector.connector-drag-over {
  background: var(--primary-light);
  border-radius: var(--radius-sm);
  padding: var(--space-xs) var(--space-md);
}

.connector-drop-hint {
  position: absolute;
  width: var(--space-xl);
  height: var(--space-xl);
  border-radius: 50%;
  background: var(--primary);
  color: white;
  font-size: var(--font-base);
  font-weight: bold;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transform: translate(-50%, -50%) scale(0.5);
  transition: all 0.15s;
  pointer-events: none;
  left: 50%;
  top: 50%;
  z-index: 2;
}

.flow-connector.connector-drag-over .connector-drop-hint {
  opacity: 1;
  transform: translate(-50%, -50%) scale(1);
}

.connector-line {
  width: var(--connector-width);
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
</style>
