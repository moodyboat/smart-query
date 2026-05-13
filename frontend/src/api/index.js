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
