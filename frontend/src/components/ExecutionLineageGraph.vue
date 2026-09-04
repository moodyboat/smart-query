<template>
  <div class="lineage-graph-shell">
    <svg class="lineage-graph" :viewBox="`0 0 ${layout.width} ${layout.height}`" :style="{ minWidth: `${layout.width}px`, height: `${layout.height}px` }" role="img" aria-label="算子执行链路图">
      <defs>
        <marker id="lineage-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#9db7df" /></marker>
      </defs>
      <g class="graph-edges">
        <path v-for="edge in layout.edges" :key="`${edge.source}-${edge.target}`" :d="edge.path" marker-end="url(#lineage-arrow)" />
      </g>
      <g v-for="node in layout.nodes" :key="node.nodeId">
        <foreignObject :x="node.x" :y="node.y" width="182" height="86">
          <button type="button" :class="['graph-node', `is-${String(node.operatorType).toLowerCase()}`, { selected: node.nodeRunId === selectedNodeRunId }]" @click="emit('select', node)">
            <span class="graph-node-top"><em>{{ operatorLabel(node.operatorType) }}</em><i>{{ statusLabel(node.status) }}</i></span>
            <strong>{{ node.operatorName }}</strong>
            <small>{{ node.model?.name || `版本 ${node.operatorVersionNo || '-'}` }}</small>
          </button>
        </foreignObject>
      </g>
    </svg>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] },
  selectedNodeRunId: { type: [Number, String], default: null }
})
const emit = defineEmits(['select'])

const layout = computed(() => {
  const nodes = props.nodes.map(node => ({ ...node, nodeId: String(node.nodeId) }))
  const nodeIds = new Set(nodes.map(node => node.nodeId))
  const edges = props.edges.filter(edge => nodeIds.has(String(edge.source)) && nodeIds.has(String(edge.target)))
  const incoming = new Map(nodes.map(node => [node.nodeId, []]))
  edges.forEach(edge => incoming.get(String(edge.target)).push(String(edge.source)))
  const levels = new Map()
  const resolveLevel = (id, visiting = new Set()) => {
    if (levels.has(id)) return levels.get(id)
    if (visiting.has(id)) return 0
    visiting.add(id)
    const parents = incoming.get(id) || []
    const level = parents.length ? Math.max(...parents.map(parent => resolveLevel(parent, visiting) + 1)) : 0
    visiting.delete(id); levels.set(id, level); return level
  }
  nodes.forEach(node => resolveLevel(node.nodeId))
  const groups = new Map()
  nodes.forEach(node => { const level = levels.get(node.nodeId) || 0; if (!groups.has(level)) groups.set(level, []); groups.get(level).push(node) })
  const maxLevel = Math.max(0, ...levels.values())
  const maxRows = Math.max(1, ...[...groups.values()].map(group => group.length))
  const width = Math.max(760, (maxLevel + 1) * 238 + 34)
  const height = Math.max(150, maxRows * 112 + 24)
  const positioned = []
  const positions = new Map()
  for (const [level, group] of groups) {
    const total = group.length * 86 + Math.max(0, group.length - 1) * 26
    const startY = Math.max(12, (height - total) / 2)
    group.forEach((node, index) => {
      const value = { ...node, x: 18 + level * 238, y: startY + index * 112 }
      positioned.push(value); positions.set(node.nodeId, value)
    })
  }
  const paths = edges.map(edge => {
    const source = positions.get(String(edge.source)), target = positions.get(String(edge.target))
    const x1 = source.x + 182, y1 = source.y + 43, x2 = target.x, y2 = target.y + 43
    const mid = (x1 + x2) / 2
    return { ...edge, path: `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}` }
  })
  return { width, height, nodes: positioned, edges: paths }
})

const operatorLabel = type => ({ DATA:'数据', ML:'机器学习', RULE:'规则', AGENT:'智能体', OUTPUT:'输出' }[type] || '算子')
const statusLabel = status => ({ SUCCESS:'成功', COMPLETED:'成功', RUNNING:'运行中', FAILED:'失败' }[String(status).toUpperCase()] || status || '未知')
</script>

<style scoped>
.lineage-graph-shell{overflow:auto;border:1px solid #e4e8ef;border-radius:10px;background:#f8fafc}.lineage-graph{display:block}.graph-edges path{fill:none;stroke:#9db7df;stroke-width:2}.graph-node{width:178px;height:82px;display:flex;flex-direction:column;gap:5px;padding:9px 10px;border:1px solid #d9e3f2;border-left:3px solid #3b82f6;border-radius:9px;background:#fff;text-align:left;cursor:pointer;transition:.15s}.graph-node:hover,.graph-node.selected{border-color:#74a5ef;background:#f4f8ff;box-shadow:0 0 0 2px rgba(36,104,242,.1)}.graph-node.is-rule{border-left-color:#7c3aed}.graph-node.is-ml{border-left-color:#db2777}.graph-node.is-agent{border-left-color:#ea580c}.graph-node.is-output{border-left-color:#059669}.graph-node-top{display:flex;align-items:center;justify-content:space-between}.graph-node em,.graph-node i{font-size:9px;font-style:normal}.graph-node em{color:#2468f2;font-weight:650}.graph-node i{color:#6e7b8f}.graph-node strong,.graph-node small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.graph-node strong{color:#263142;font-size:12px}.graph-node small{color:#8792a2;font-size:9px}
</style>
