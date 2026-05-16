import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  fetchMiningModels, fetchMiningModel, fetchDataSources,
  trainMiningModel, publishMiningModel, offlineMiningModel
} from '../api'

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
    models.value.filter(m => m.status === 'published')
  )

  async function loadModels() {
    loading.value = true
    try {
      const [ms, dss] = await Promise.all([
        fetchMiningModels(filterDsId.value || undefined).catch(() => []),
        dataSources.value.length ? Promise.resolve(dataSources.value) : fetchDataSources()
      ])
      models.value = ms || []
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
    // Close any existing EventSource before opening a new one
    closeEventSource()
    const baseUrl = '/api/v1/mining/model'
    const url = `${baseUrl}/${modelId}/status-stream`
    const eventSource = new EventSource(url)
    activeEventSource.value = eventSource
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        if (data.type === 'model_status' && data.modelId === modelId) {
          const idx = models.value.findIndex(m => m.id === modelId)
          if (idx >= 0) {
            const updated = { ...models.value[idx], status: data.status, metrics: data.metrics || models.value[idx].metrics }
            models.value[idx] = updated
          }
          if (['trained', 'failed', 'published'].includes(data.status)) {
            eventSource.close()
            activeEventSource.value = null
          }
        }
      } catch {}
    }
    eventSource.onerror = () => {
      eventSource.close()
      activeEventSource.value = null
    }
    return eventSource
  }

  function closeEventSource() {
    if (activeEventSource.value) {
      activeEventSource.value.close()
      activeEventSource.value = null
    }
  }

  return {
    models, selectedModelId, selectedModel, publishedModels,
    dataSources, loading, filterDsId,
    loadModels, selectModel, clearSelection,
    updateModelInList, addModel, removeModel, refreshModel,
    watchModelStatus, closeEventSource
  }
})
