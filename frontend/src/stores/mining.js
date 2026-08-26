import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchMiningModels, fetchMiningModel, fetchDataSources,
  trainMiningModel, publishMiningModel, offlineMiningModel
} from '../api'
import { createAuthenticatedEventStream } from '../api/sse.js'
import { MODEL_STATUS } from '../constants'

export const useMiningStore = defineStore('mining', () => {
  const models = ref([])
  const selectedModelId = ref(null)
  const dataSources = ref([])
  const loading = ref(false)
  const filterDsId = ref(null)
  const activeEventSource = ref(null)

  const selectedModel = computed(() =>
    models.value.find(m => m.id === selectedModelId.value) || null
  )

  const publishedModels = computed(() =>
    models.value.filter(m => m.status === MODEL_STATUS.PUBLISHED)
  )

  async function loadModels() {
    loading.value = true
    try {
      const [ms, dss] = await Promise.all([
        fetchMiningModels(filterDsId.value || undefined).catch(() => []),
        dataSources.value.length ? Promise.resolve(dataSources.value) : fetchDataSources()
      ])
      const fetched = ms || []
      if (activeEventSource.value && fetched.length > 0) {
        const watchedId = models.value.find(m =>
          [MODEL_STATUS.TRAINING, MODEL_STATUS.QUEUED].includes(m.status)
        )?.id
        if (watchedId) {
          const watchedModel = models.value.find(m => m.id === watchedId)
          const idx = fetched.findIndex(m => m.id === watchedId)
          if (idx >= 0 && watchedModel) {
            fetched[idx] = { ...fetched[idx], status: watchedModel.status, metrics: watchedModel.metrics }
          }
        }
      }
      models.value = fetched
      dataSources.value = dss || []
    } catch (e) {
      console.error('Failed to load mining models:', e)
    } finally {
      loading.value = false
    }
  }

  function selectModel(modelId) {
    selectedModelId.value = modelId
  }

  function clearSelection() {
    selectedModelId.value = null
  }

  function updateModelInList(model) {
    const idx = models.value.findIndex(m => m.id === model.id)
    if (idx >= 0) models.value[idx] = model
  }

  function addModel(model) {
    models.value.unshift(model)
  }

  function removeModel(modelId) {
    models.value = models.value.filter(m => m.id !== modelId)
    if (selectedModelId.value === modelId) selectedModelId.value = null
  }

  async function refreshModel(modelId) {
    try {
      const updated = await fetchMiningModel(modelId)
      updateModelInList(updated)
      return updated
    } catch { return null }
  }

  function watchModelStatus(modelId) {
    if (activeEventSource.value) {
      const currentUrl = activeEventSource.value.url || ''
      if (currentUrl.includes(`/${modelId}/status-stream`)) return currentUrl
      closeEventSource()
    }
    const url = `/api/v1/mining/model/${modelId}/status-stream`
    const eventSource = createAuthenticatedEventStream(url)
    activeEventSource.value = eventSource
    eventSource.addEventListener('model_status', (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'model_status' && data.modelId === modelId) {
          const idx = models.value.findIndex(m => m.id === modelId)
          if (idx >= 0) {
            const updated = { ...models.value[idx], status: data.status, metrics: data.metrics || models.value[idx].metrics }
            models.value[idx] = updated
          }
          if ([MODEL_STATUS.TRAINED, MODEL_STATUS.FAILED, MODEL_STATUS.PUBLISHED].includes(data.status)) {
            eventSource.close()
            activeEventSource.value = null
          }
        }
      } catch {}
    })
    eventSource.onerror = (error) => {
      console.warn('[MiningStore] SSE connection lost for model', modelId)
      if (!error.willReconnect) {
        eventSource.close()
        activeEventSource.value = null
        refreshModel(modelId)
      }
    }
    return eventSource
  }

  function closeEventSource() {
    if (activeEventSource.value) {
      activeEventSource.value.close()
      activeEventSource.value = null
    }
  }

  /** 登出/切换账号时清空，避免下一个账号看到上一个账号的模型列表 */
  function reset() {
    closeEventSource()
    models.value = []
    selectedModelId.value = null
    dataSources.value = []
    filterDsId.value = null
    loading.value = false
  }

  return {
    models, selectedModelId, selectedModel, publishedModels,
    dataSources, loading, filterDsId,
    loadModels, selectModel, clearSelection,
    updateModelInList, addModel, removeModel, refreshModel,
    watchModelStatus, closeEventSource, reset
  }
})
