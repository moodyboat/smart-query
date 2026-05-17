import { ref, readonly } from 'vue'

export function usePipelineStream() {
  const nodeProgress = ref({})
  const pipelineResult = ref(null)
  const streamError = ref(null)
  const isStreaming = ref(false)
  let eventSource = null

  function startStream(pipelineId) {
    stopStream()
    nodeProgress.value = {}
    pipelineResult.value = null
    streamError.value = null
    isStreaming.value = true

    const url = `/api/v1/mining/pipeline/${pipelineId}/execute-stream`
    eventSource = new EventSource(url)

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'node_progress') {
          const nt = data.nodeType
          nodeProgress.value = {
            ...nodeProgress.value,
            [nt]: { status: data.status, ...(data.rows != null ? { rows: data.rows } : {}), ...(data.features != null ? { features: data.features } : {}) }
          }
        } else if (data.type === 'pipeline_complete') {
          pipelineResult.value = data
          isStreaming.value = false
          eventSource.close()
          eventSource = null
        } else if (data.type === 'pipeline_error') {
          streamError.value = data.error || '执行失败'
          isStreaming.value = false
          eventSource.close()
          eventSource = null
        }
      } catch (ignored) {}
    }

    eventSource.onerror = () => {
      if (isStreaming.value) {
        streamError.value = '连接断开'
      }
      isStreaming.value = false
      if (eventSource) {
        eventSource.close()
        eventSource = null
      }
    }
  }

  function stopStream() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    isStreaming.value = false
  }

  function reset() {
    nodeProgress.value = {}
    pipelineResult.value = null
    streamError.value = null
    isStreaming.value = false
  }

  return {
    nodeProgress: readonly(nodeProgress),
    pipelineResult: readonly(pipelineResult),
    streamError: readonly(streamError),
    isStreaming: readonly(isStreaming),
    startStream,
    stopStream,
    reset
  }
}
