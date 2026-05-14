import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 180000
})

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

export async function publishMiningModel(id) {
  const { data } = await api.post(`/mining/model/${id}/publish`)
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

export async function updateModelSchedule(id, cron, enabled) {
  const { data } = await api.put(`/mining/model/${id}/schedule`, { cron, enabled })
  return data.data
}

export async function fetchModelExecutions(modelId) {
  const { data } = await api.get(`/mining/model/${modelId}/executions`)
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
