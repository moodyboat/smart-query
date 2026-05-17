import { ref } from 'vue'
import { trialMissingStrategy } from '../api'

export function useMissingValueTrial() {
  const trialLoading = ref(false)
  const trialResult = ref(null)

  async function runTrial(pipelineId, columnStrategies) {
    trialLoading.value = true
    trialResult.value = null
    try {
      trialResult.value = await trialMissingStrategy(pipelineId, columnStrategies)
    } catch (e) {
      trialResult.value = { status: 'error', error: e.message || '试运行失败' }
    } finally {
      trialLoading.value = false
    }
  }

  function clearTrial() {
    trialResult.value = null
  }

  return { trialLoading, trialResult, runTrial, clearTrial }
}
