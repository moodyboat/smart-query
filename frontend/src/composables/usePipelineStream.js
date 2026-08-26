import { ref, readonly } from 'vue'
import { apiStartPipelineStream } from '../api/index.js'
import { createAuthenticatedEventStream } from '../api/sse.js'

export function usePipelineStream() {
  const nodeProgress = ref({})
  const pipelineResult = ref(null)
  const streamError = ref(null)
  const isStreaming = ref(false)
  let eventSource = null

  async function startStream(pipelineId) {
    stopStream()
    nodeProgress.value = {}
    pipelineResult.value = null
    streamError.value = null
    isStreaming.value = true

    try {
      const submission = await apiStartPipelineStream(pipelineId)
      const url = `/api/v1/mining/pipeline/${pipelineId}/execute-stream?runId=${encodeURIComponent(submission.runId)}`
      eventSource = createAuthenticatedEventStream(url)
    } catch (error) {
      streamError.value = error.message || '提交执行失败'
      isStreaming.value = false
      return
    }

    const handleEvent = (event) => {
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
    eventSource.addEventListener('node_progress', handleEvent)
    eventSource.addEventListener('pipeline_complete', handleEvent)
    eventSource.addEventListener('pipeline_error', handleEvent)

    eventSource.onerror = (error) => {
      if (isStreaming.value) {
        streamError.value = error.willReconnect
          ? `连接中断，正在续传（第 ${error.retryCount} 次）`
          : '连接无法恢复，请查询流程状态'
      }
      if (!error.willReconnect) isStreaming.value = false
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
