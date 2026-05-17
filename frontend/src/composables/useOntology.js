import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const API_BASE = '/api/v1'

export function useOntology() {
  const loading = ref(false)

  async function apiCall(url, options = {}) {
    loading.value = true
    try {
      const response = await fetch(`${API_BASE}${url}`, {
        headers: { 'Content-Type': 'application/json' },
        ...options,
        body: options.body ? JSON.stringify(options.body) : undefined
      })
      const result = await response.json()
      if (!response.ok) {
        const errorMsg = result.message || `请求失败 (${response.status})`
        ElMessage.error(errorMsg)
        throw new Error(errorMsg)
      }
      if (result.code !== undefined && result.code !== 200) {
        const errorMsg = result.message || `请求失败 (code: ${result.code})`
        ElMessage.error(errorMsg)
        throw new Error(errorMsg)
      }
      return result.data
    } catch (e) {
      if (e.message && !e.message.includes('请求失败')) {
        ElMessage.error('网络请求失败')
      }
      throw e
    } finally {
      loading.value = false
    }
  }

  return {
    loading,

    // Metrics
    listMetrics: (dsId) => apiCall(`/ontology/${dsId}/metrics`),
    createMetric: (dsId, data) => apiCall(`/ontology/${dsId}/metrics`, { method: 'POST', body: data }),
    updateMetric: (id, data) => apiCall(`/ontology/metrics/${id}`, { method: 'PUT', body: data }),
    deleteMetric: (id) => apiCall(`/ontology/metrics/${id}`, { method: 'DELETE' }),
    resolveMetricSql: (id) => apiCall(`/ontology/metrics/${id}/resolve-sql`),

    // Dimensions
    listDimensions: (dsId) => apiCall(`/ontology/${dsId}/dimensions`),
    getDimensionTree: (dsId) => apiCall(`/ontology/${dsId}/dimensions/tree`),
    createDimension: (dsId, data) => apiCall(`/ontology/${dsId}/dimensions`, { method: 'POST', body: data }),
    updateDimension: (id, data) => apiCall(`/ontology/dimensions/${id}`, { method: 'PUT', body: data }),
    deleteDimension: (id) => apiCall(`/ontology/dimensions/${id}`, { method: 'DELETE' }),

    // Glossary
    listGlossary: (dsId) => apiCall(`/ontology/${dsId}/glossary`),
    createGlossary: (dsId, data) => apiCall(`/ontology/${dsId}/glossary`, { method: 'POST', body: data }),
    updateGlossary: (id, data) => apiCall(`/ontology/glossary/${id}`, { method: 'PUT', body: data }),
    deleteGlossary: (id) => apiCall(`/ontology/glossary/${id}`, { method: 'DELETE' }),
    resolveTerm: (dsId, q) => apiCall(`/ontology/${dsId}/glossary/resolve?q=${encodeURIComponent(q)}`),

    // Indicator Config
    listIndicatorConfigs: (dsId) => apiCall(`/ontology/${dsId}/indicator-configs`),
    createIndicatorConfig: (dsId, data) => apiCall(`/ontology/${dsId}/indicator-configs`, { method: 'POST', body: data }),
    updateIndicatorConfig: (id, data) => apiCall(`/ontology/indicator-configs/${id}`, { method: 'PUT', body: data }),
    deleteIndicatorConfig: (id) => apiCall(`/ontology/indicator-configs/${id}`, { method: 'DELETE' })
  }
}
