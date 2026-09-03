<template>
  <div ref="canvasEl" class="dag-canvas" @dragover.prevent @drop="onDrop" @click.self="emit('selectNode', null)">
    <div class="canvas-grid" />
    <div v-if="!nodes.length" class="canvas-empty">
      <strong>拖入左侧已发布算子</strong>
      <span>连接端口形成 DAG；输出算子必须位于流程末端。</span>
    </div>
    <svg class="edge-layer" :width="canvasSize.width" :height="canvasSize.height">
      <defs><marker id="dag-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="currentColor" /></marker></defs>
      <g v-for="edge in edges" :key="edgeKey(edge)" class="edge-group" @click.stop="emit('selectEdge', edge)">
        <path class="edge-hit" :d="edgePath(edge)" />
        <path :class="['edge-path', { selected: selectedEdge && edgeKey(selectedEdge) === edgeKey(edge) }]" :d="edgePath(edge)" marker-end="url(#dag-arrow)" />
        <g :class="['edge-label', contractClass(edge)]" :transform="`translate(${edgeMidpoint(edge).x} ${edgeMidpoint(edge).y})`">
          <rect x="-43" y="-11" width="86" height="22" rx="11" />
          <text text-anchor="middle" dominant-baseline="middle">{{ edgeLabel(edge) }}</text>
        </g>
      </g>
    </svg>

    <article v-for="node in nodes" :key="node.id" :class="['dag-node', typeClass(node), {
      selected: selectedNodeId === node.id, connecting: connectionSource === node.id,
      running: nodeStatuses[node.id] === 'RUNNING', success: nodeStatuses[node.id] === 'SUCCESS',
      failed: nodeStatuses[node.id] === 'FAILED', system: node.systemManaged
    }]" :style="nodeStyle(node)" @click.stop="emit('selectNode', node)" @pointerdown="startMove($event, node)">
      <button v-if="node.implementationType !== 'SQL_AST'" class="input-port" title="连接到此节点" @pointerdown.stop @click.stop="completeConnection(node)" />
      <div class="node-type-bar" />
      <div class="node-main">
        <div class="node-head"><span>{{ typeIcon(node.operatorType) }}</span><strong>{{ node.name }}</strong><small>v{{ node.versionNo }}</small></div>
        <div class="node-meta">{{ typeLabel(node.operatorType) }} · #{{ node.operatorVersionId }}</div>
        <div v-if="node.operatorType === 'OUTPUT'" class="output-chip">{{ node.metadata?.outputKind || 'OUTPUT' }} 可视化</div>
        <div v-if="node.implementationType === 'SQL_AST'" class="capability-chip sql-chip">SQL · DS #{{ node.metadata?.dataSourceId }} · {{ tableCount(node) }} 表</div>
        <div v-if="node.implementationType === 'AGENT_POLICY'" class="capability-chip agent-chip">{{ node.metadata?.model }} · {{ toolCount(node) }} 个只读工具</div>
        <div class="runtime-code">{{ node.runtimeProfileCode }}</div>
      </div>
      <button v-if="node.operatorType !== 'OUTPUT'" class="output-port" title="从此节点开始连接" @pointerdown.stop @click.stop="beginConnection(node)" />
      <span v-if="nodeStatuses[node.id]" class="run-dot" :title="nodeStatuses[node.id]" />
    </article>

    <div v-if="connectionSource" class="connect-tip">请选择下游节点 · Esc 取消</div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  nodes: { type: Array, required: true }, edges: { type: Array, required: true },
  selectedNodeId: { type: String, default: null }, selectedEdge: { type: Object, default: null },
  connectionSource: { type: String, default: null }, nodeStatuses: { type: Object, default: () => ({}) },
  edgeContracts: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['dropOperator', 'selectNode', 'selectEdge', 'moveNode', 'beginConnection', 'completeConnection', 'cancelConnection'])
const canvasEl = ref(null)
const canvasSize = computed(() => {
  let width = canvasEl.value?.clientWidth || 1200, height = canvasEl.value?.clientHeight || 760
  props.nodes.forEach(node => { width = Math.max(width, (node.position?.x || 0) + 300); height = Math.max(height, (node.position?.y || 0) + 190) })
  return { width, height }
})

function onDrop(event) {
  let payload
  try { payload = JSON.parse(event.dataTransfer.getData('application/x-smart-query-operator')) } catch { return }
  const rect = canvasEl.value.getBoundingClientRect()
  emit('dropOperator', payload, { x: Math.max(20, event.clientX - rect.left + canvasEl.value.scrollLeft - 110), y: Math.max(20, event.clientY - rect.top + canvasEl.value.scrollTop - 45) })
}
function nodeStyle(node) { return { left: `${node.position?.x || 30}px`, top: `${node.position?.y || 30}px` } }
function center(node, side) { return { x: (node.position?.x || 0) + (side === 'right' ? 232 : 0), y: (node.position?.y || 0) + 48 } }
function edgePath(edge) {
  const source = props.nodes.find(node => node.id === edge.source), target = props.nodes.find(node => node.id === edge.target)
  if (!source || !target) return ''
  const a = center(source, 'right'), b = center(target, 'left'), bend = Math.max(55, Math.abs(b.x - a.x) * 0.45)
  return `M ${a.x} ${a.y} C ${a.x + bend} ${a.y}, ${b.x - bend} ${b.y}, ${b.x} ${b.y}`
}
function edgeKey(edge) { return `${edge.source}->${edge.target}` }
function edgeMidpoint(edge) {
  const source = props.nodes.find(node => node.id === edge.source), target = props.nodes.find(node => node.id === edge.target)
  if (!source || !target) return { x: 0, y: 0 }
  const a = center(source, 'right'), b = center(target, 'left')
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
}
function edgeLabel(edge) { return `${edge.mappingMode || 'MERGE'} · ${edge.fieldMappings?.length || 0}` }
function contractClass(edge) { const report=props.edgeContracts[edgeKey(edge)]; return report ? (report.compatible?'compatible':'incompatible') : '' }
function beginConnection(node) { emit('beginConnection', node.id) }
function completeConnection(node) { emit('completeConnection', node.id) }
let move = null
function startMove(event, node) {
  if (event.button !== 0 || event.target.classList.contains('input-port') || event.target.classList.contains('output-port')) return
  move = { id: node.id, startX: event.clientX, startY: event.clientY, x: node.position?.x || 0, y: node.position?.y || 0 }
  window.addEventListener('pointermove', moving); window.addEventListener('pointerup', stopMove)
}
function moving(event) { if (move) emit('moveNode', move.id, { x: Math.max(10, move.x + event.clientX - move.startX), y: Math.max(10, move.y + event.clientY - move.startY) }) }
function stopMove() { move = null; window.removeEventListener('pointermove', moving); window.removeEventListener('pointerup', stopMove) }
function onKey(event) { if (event.key === 'Escape') emit('cancelConnection') }
function typeIcon(type) { return ({ DATA: '⇥', RULE: '◇', ML: '✦', AGENT: '◎', OUTPUT: '▦' }[type] || '□') }
function typeLabel(type) { return ({ DATA: '输入', RULE: '规则', ML: '模型', AGENT: '智能体', OUTPUT: '输出' }[type] || type) }
function typeClass(node) { return `type-${String(node.operatorType || '').toLowerCase()}` }
function tableCount(node) { return Array.isArray(node.metadata?.allowedTables) ? node.metadata.allowedTables.length : 0 }
function toolCount(node) { return Array.isArray(node.metadata?.allowedTools) ? node.metadata.allowedTools.length : 0 }
onMounted(() => window.addEventListener('keydown', onKey))
onBeforeUnmount(() => { stopMove(); window.removeEventListener('keydown', onKey) })
</script>

<style scoped>
.dag-canvas { position:relative; width:100%; height:100%; min-width:900px; min-height:650px; overflow:auto; background:#f8fafc; user-select:none; }
.canvas-grid { position:absolute; inset:0; min-width:1600px; min-height:1000px; background-image:radial-gradient(#cbd5e1 1px,transparent 1px); background-size:20px 20px; pointer-events:none; }
.canvas-empty { position:absolute; left:50%; top:43%; transform:translate(-50%,-50%); display:flex; flex-direction:column; gap:7px; align-items:center; color:var(--text-muted); }.canvas-empty strong{color:var(--text-secondary)}
.edge-layer { position:absolute; left:0; top:0; min-width:100%; min-height:100%; overflow:visible; color:#94a3b8; pointer-events:none; }.edge-group{pointer-events:stroke;cursor:pointer}.edge-path{fill:none;stroke:currentColor;stroke-width:2}.edge-path.selected{stroke:#2563eb;stroke-width:3}.edge-hit{fill:none;stroke:transparent;stroke-width:14}.edge-label{pointer-events:all}.edge-label rect{fill:white;stroke:#cbd5e1;stroke-width:1}.edge-label text{fill:#475569;font-size:10px;font-family:ui-monospace,Consolas,monospace}.edge-label.compatible rect{fill:#ecfdf3;stroke:#12b76a}.edge-label.compatible text{fill:#027a48}.edge-label.incompatible rect{fill:#fef3f2;stroke:#f04438}.edge-label.incompatible text{fill:#b42318}
.dag-node { position:absolute; width:232px; min-height:96px; display:flex; border:1px solid #d0d5dd; border-radius:11px; background:white; box-shadow:0 4px 12px rgba(15,23,42,.08); cursor:grab; z-index:2; }.dag-node:active{cursor:grabbing}.dag-node.selected{border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,.16),0 8px 20px rgba(15,23,42,.12)}.dag-node.connecting{box-shadow:0 0 0 3px rgba(124,58,237,.2)}
.node-type-bar{width:6px;border-radius:10px 0 0 10px;background:#64748b;flex-shrink:0}.type-data .node-type-bar{background:#0ea5e9}.type-rule .node-type-bar{background:#7c3aed}.type-ml .node-type-bar{background:#db2777}.type-agent .node-type-bar{background:#ea580c}.type-output .node-type-bar{background:#059669}
.node-main{padding:12px 12px 10px;min-width:0;flex:1}.node-head{display:flex;align-items:center;gap:7px}.node-head strong{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:14px}.node-head small{color:var(--text-muted)}.node-meta,.runtime-code{margin-top:6px;color:var(--text-muted);font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.runtime-code{font-family:monospace}.output-chip,.capability-chip{display:inline-block;max-width:100%;margin-top:7px;padding:2px 7px;border-radius:999px;font-size:11px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.output-chip{background:#ecfdf3;color:#027a48}.sql-chip{background:#e0f2fe;color:#0369a1}.agent-chip{background:#ffedd5;color:#c2410c}
.input-port,.output-port{position:absolute;top:40px;width:15px;height:15px;padding:0;border:3px solid white;border-radius:50%;background:#64748b;box-shadow:0 0 0 1px #94a3b8;cursor:crosshair;z-index:3}.input-port{left:-8px}.output-port{right:-8px}.input-port:hover,.output-port:hover{background:#2563eb;transform:scale(1.18)}
.dag-node.running{border-color:#f59e0b}.dag-node.success{border-color:#12b76a}.dag-node.failed{border-color:#f04438}.run-dot{position:absolute;right:8px;bottom:7px;width:8px;height:8px;border-radius:50%;background:#667085}.running .run-dot{background:#f59e0b;animation:pulse 1s infinite}.success .run-dot{background:#12b76a}.failed .run-dot{background:#f04438}.system{opacity:.76}.connect-tip{position:sticky;left:50%;top:16px;width:max-content;transform:translateX(-50%);padding:7px 12px;border-radius:999px;background:#312e81;color:white;font-size:12px;z-index:5}
@keyframes pulse{50%{opacity:.35}}
</style>
