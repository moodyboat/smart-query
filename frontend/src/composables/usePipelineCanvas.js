import { ref } from 'vue'

/**
 * Composable for pipeline canvas drag-and-drop logic.
 * Extracted from PipelineEditor.vue to reduce component size.
 */
export function usePipelineCanvas(pipelineNodes) {
  const isDragging = ref(false)

  function onPaletteDragStart(event, algo, nodeType) {
    const data = algo
      ? { type: 'algorithm', algorithmId: algo.algorithmId, name: algo.name, modelTypes: algo.modelTypes }
      : { type: 'node', nodeType }
    event.dataTransfer.setData('application/json', JSON.stringify(data))
    event.dataTransfer.effectAllowed = 'copy'
  }

  function onNodeReorderStart(event, idx) {
    isDragging.value = true
    event.dataTransfer.setData('application/json', JSON.stringify({ type: 'reorder', fromIdx: idx }))
    event.dataTransfer.effectAllowed = 'move'
  }

  function onDragEnd() {
    isDragging.value = false
  }

  function findClosestInsertIndex(clientX, clientY) {
    const nodes = document.querySelectorAll('.flow-node')
    if (nodes.length === 0) return 0
    let closest = nodes.length
    let minDist = Infinity
    nodes.forEach((el, i) => {
      const rect = el.getBoundingClientRect()
      const midY = rect.top + rect.height / 2
      const dist = Math.abs(clientY - midY)
      if (dist < minDist) {
        minDist = dist
        closest = clientY < midY ? i : i + 1
      }
    })
    return closest
  }

  function reorderNode(fromIdx, toIdx) {
    if (fromIdx === toIdx || fromIdx === toIdx - 1) return
    const [node] = pipelineNodes.value.splice(fromIdx, 1)
    const insertAt = fromIdx < toIdx ? toIdx - 1 : toIdx
    pipelineNodes.value.splice(insertAt, 0, node)
  }

  return {
    isDragging,
    onPaletteDragStart,
    onNodeReorderStart,
    onDragEnd,
    findClosestInsertIndex,
    reorderNode
  }
}
