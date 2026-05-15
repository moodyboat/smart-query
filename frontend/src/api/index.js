import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 180000
})

api.interceptors.response.use(
  response => {
    const { code, message } = response.data || {}
    if (code !== undefined && code !== 200) {
      const errorMsg = message || `请求失败 (code: ${code})`
      if (code !== 400 || !response.config._silent) {
        ElMessage.error(errorMsg)
      }
      const error = new Error(errorMsg)
      error.code = code
      return Promise.reject(error)
    }
    return response
  },
  error => {
    if (error.code === 'ECONNABORTED' || error.message?.includes('timeout')) {
      ElMessage.error('请求超时，请稍后重试')
    } else if (!error.response) {
      ElMessage.error('网络连接失败，请检查网络')
    } else {
      const status = error.response.status
      const msgs = {
        400: error.response.data?.message || '请求参数错误',
        401: '请先登录',
        403: '没有操作权限',
        404: '请求的资源不存在',
        409: error.response.data?.message || '操作冲突',
        429: '请求过于频繁，请稍后重试',
        500: '服务内部错误，请稍后重试'
      }
      ElMessage.error(msgs[status] || `请求失败 (${status})`)
    }
    return Promise.reject(error)
  }
)

export async function fetchConversations() {
  const { data } = await api.get('/conversation')
  return data.data
}

export async function createConversation(title) {
  const { data } = await api.post('/conversation', { title })
  return data.data
}

export async function deleteConversation(id) {
  await api.delete(`/conversation/${id}`)
}

export async function renameConversation(id, title) {
  await api.put(`/conversation/${id}/title`, { title })
}

export async function fetchConversationMessages(conversationId) {
  const { data } = await api.get(`/conversation/${conversationId}/messages`)
  return data.data
}

export async function fetchConversationCharts(conversationId) {
  const { data } = await api.get(`/chart/conversation/${conversationId}`)
  return data.data
}

export async function fetchConversationReports(conversationId) {
  const { data } = await api.get(`/report/conversation/${conversationId}`)
  return data.data
}

export async function fetchReport(reportId) {
  const { data } = await api.get(`/report/${reportId}`)
  return data.data
}

export async function fetchConversationDashboards(conversationId) {
  const { data } = await api.get(`/dashboard/conversation/${conversationId}`)
  return data.data
}

export async function fetchDataSources() {
  const { data } = await api.get('/datasource')
  return data.data
}

export async function fetchDataSourceTables(dataSourceId) {
  const { data } = await api.get(`/datasource/${dataSourceId}/tables`)
  return data.data
}

export async function fetchTableColumns(dataSourceId, tableName) {
  const { data } = await api.get(`/datasource/${dataSourceId}/tables/${tableName}/columns`)
  return data.data
}

export async function rerenderChart(chartId, filterValues) {
  const { data } = await api.post(`/chart/${chartId}/rerender`, filterValues)
  return data.data
}

export async function fetchDashboardWithCharts(dashboardId) {
  const { data } = await api.get(`/dashboard/${dashboardId}/charts`)
  return data.data
}

export function buildChatUrl(conversationId, dataSourceId) {
  return `/api/v1/chat?conversationId=${conversationId}&dataSourceId=${dataSourceId}`
}

// Mining Model APIs
export async function fetchMiningModels(dataSourceId) {
  const params = dataSourceId ? `?dataSourceId=${dataSourceId}` : ''
  const { data } = await api.get(`/mining/model${params}`)
  return data.data
}

export async function fetchMiningModel(id) {
  const { data } = await api.get(`/mining/model/${id}`)
  return data.data
}

export async function createMiningModel(model) {
  const { data } = await api.post('/mining/model', model)
  return data.data
}

export async function updateMiningModel(id, updates) {
  const { data } = await api.put(`/mining/model/${id}`, updates)
  return data.data
}

export async function deleteMiningModel(id) {
  await api.delete(`/mining/model/${id}`)
}

export async function trainMiningModel(id) {
  const { data } = await api.post(`/mining/model/${id}/train`)
  return data.data
}

export async function publishMiningModel(id, config) {
  const { data } = await api.post(`/mining/model/${id}/publish`, config || {})
  return data.data
}

export async function offlineMiningModel(id) {
  const { data } = await api.post(`/mining/model/${id}/offline`)
  return data.data
}

export async function updateModelHyperparams(id, hyperparameters) {
  const { data } = await api.put(`/mining/model/${id}/hyperparams`, { hyperparameters })
  return data.data
}

export async function updateModelSchedule(id, cron, enabled, mode) {
  const body = { cron, enabled }
  if (mode) body.mode = mode
  const { data } = await api.put(`/mining/model/${id}/schedule`, body)
  return data.data
}

export async function fetchModelExecutions(modelId) {
  const { data } = await api.get(`/mining/model/${modelId}/executions`)
  return data.data
}

export async function predictMiningModel(id, input, saveTable) {
  const body = { input }
  if (saveTable) body.saveTable = saveTable
  const { data } = await api.post(`/mining/model/${id}/predict`, body)
  return data.data
}

export async function batchPredictMiningModel(id) {
  const { data } = await api.post(`/mining/model/${id}/batch-predict`)
  return data.data
}

export async function validateMiningModel(id) {
  const { data } = await api.get(`/mining/model/${id}/validate`)
  return data.data
}

export async function fetchModelPredictions(id, limit = 100) {
  const { data } = await api.get(`/mining/model/${id}/predictions?limit=${limit}`)
  return data.data
}

export async function previewResultTable(modelId, tableName, limit = 10) {
  const { data } = await api.get(`/mining/model/${modelId}/preview-result-table?tableName=${encodeURIComponent(tableName)}&limit=${limit}`)
  return data.data
}

// Mining Pipeline APIs
export async function fetchMiningPipelines(dataSourceId) {
  const params = dataSourceId ? `?dataSourceId=${dataSourceId}` : ''
  const { data } = await api.get(`/mining/pipeline${params}`)
  return data.data
}

export async function fetchMiningPipeline(id) {
  const { data } = await api.get(`/mining/pipeline/${id}`)
  return data.data
}

export async function createMiningPipeline(pipeline) {
  const { data } = await api.post('/mining/pipeline', pipeline)
  return data.data
}

export async function updateMiningPipeline(id, updates) {
  const { data } = await api.put(`/mining/pipeline/${id}`, updates)
  return data.data
}

export async function deleteMiningPipeline(id) {
  await api.delete(`/mining/pipeline/${id}`)
}

export async function executeMiningPipeline(id) {
  const { data } = await api.post(`/mining/pipeline/${id}/execute`)
  return data.data
}

export async function validateMiningPipeline(id) {
  const { data } = await api.post(`/mining/pipeline/${id}/validate`)
  return data.data
}

export async function previewStepPipeline(id, nodeId) {
  const { data } = await api.post(`/mining/pipeline/${id}/preview-step`, { nodeId })
  return data.data
}

// Algorithm Registry
export async function fetchAlgorithms(modelType) {
  const params = modelType ? `?modelType=${modelType}` : ''
  const { data } = await api.get(`/mining/algorithms${params}`)
  return data.data
}

export async function fetchAlgorithmCategories() {
  const { data } = await api.get('/mining/algorithms/categories')
  return data.data
}

export async function createAlgorithm(algorithm) {
  const { data } = await api.post('/mining/algorithms', algorithm)
  return data.data
}

export async function updateAlgorithm(id, updates) {
  const { data } = await api.put(`/mining/algorithms/${id}`, updates)
  return data.data
}

export async function deleteAlgorithm(id) {
  await api.delete(`/mining/algorithms/${id}`)
}

export async function fetchModelTypes() {
  const { data } = await api.get('/mining/model-types')
  return data.data
}

export async function fetchConversationTraces(conversationId) {
  const { data } = await api.get(`/traces/${conversationId}`)
  return data
}

export async function fetchAdminStats() {
  const { data } = await api.get('/admin/stats')
  return data
}

export async function fetchAdminSessions() {
  const { data } = await api.get('/admin/sessions')
  return data
}
