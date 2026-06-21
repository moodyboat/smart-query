import axios from 'axios'
import { ElMessage } from 'element-plus'
import { AUTH_STORAGE_KEYS, DEFAULT_TIMEOUT_MS, PREDICTION_RECORD_LIMIT, PREVIEW_ROW_LIMIT, ROUTES } from '../constants.js'

export const METRIC_NAMES = {
  accuracy: '准确率', precision: '精确率', recall: '召回率', f1: 'F1',
  mse: 'MSE', rmse: 'RMSE', r2: 'R²', mae: 'MAE',
  inertia: '惯性', n_clusters: '聚类数', silhouette: '轮廓系数', silhouette_score: '轮廓系数',
  cluster_sizes: '聚类大小',
  train_accuracy: '训练准确率', test_accuracy: '测试准确率',
  train_f1: '训练F1', test_f1: '测试F1',
  train_precision: '训练精确率', test_precision: '测试精确率',
  train_recall: '训练召回率', test_recall: '测试召回率',
  train_r2: '训练R²', test_r2: '测试R²',
  overfitting_gap: '过拟合差距', cv_mean: '交叉验证均值', cv_std: '交叉验证标准差'
}

export const API_BASE = '/api/v1'

const api = axios.create({
  baseURL: API_BASE,
  timeout: DEFAULT_TIMEOUT_MS
})

// 请求拦截器：注入 JWT
api.interceptors.request.use(config => {
  const token = localStorage.getItem(AUTH_STORAGE_KEYS.TOKEN)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
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
      const responseData = error.response.data
      // Try to get the actual error message from backend response
      let errorMsg = null
      if (responseData?.message) {
        errorMsg = responseData.message
      } else if (typeof responseData === 'string') {
        errorMsg = responseData
      }

      // 401：登录接口=凭据错误（仅提示）；其它接口=会话失效（清 token 跳登录）
      if (status === 401) {
        const reqUrl = error.config?.url || ''
        if (reqUrl.includes('/auth/login')) {
          ElMessage.error(errorMsg || '用户名或密码错误')
        } else {
          localStorage.removeItem(AUTH_STORAGE_KEYS.TOKEN)
          localStorage.removeItem(AUTH_STORAGE_KEYS.USER)
          ElMessage.error(errorMsg || '登录已失效，请重新登录')
          if (window.location.pathname !== ROUTES.LOGIN) {
            setTimeout(() => { window.location.href = ROUTES.LOGIN }, 400)
          }
        }
        return Promise.reject(error)
      }

      const msgs = {
        400: errorMsg || '请求参数错误',
        403: '没有操作权限',
        404: '请求的资源不存在',
        409: errorMsg || '操作冲突',
        429: '请求过于频繁，请稍后重试',
        500: errorMsg || '服务内部错误，请稍后重试'
      }
      ElMessage.error(msgs[status] || `请求失败 (${status})`)
    }
    return Promise.reject(error)
  }
)

export default api

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

export async function batchDeleteConversations(ids) {
  await api.post('/conversation/batch-delete', { ids })
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

export async function fetchTablePreview(dataSourceId, tableName, limit = PREVIEW_ROW_LIMIT) {
  const { data } = await api.get(`/datasource/${dataSourceId}/tables/${encodeURIComponent(tableName)}/preview?limit=${limit}`)
  return data.data
}

export async function createDataSource(dataSource) {
  const { data } = await api.post('/datasource', dataSource)
  return data.data
}

export async function updateDataSource(id, dataSource) {
  const { data } = await api.put(`/datasource/${id}`, dataSource)
  return data.data
}

export async function deleteDataSource(id) {
  const { data } = await api.delete(`/datasource/${id}`)
  return data.data
}

export async function testDataSourceConnection(id) {
  const { data } = await api.post(`/datasource/${id}/test`)
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

export function buildChatUrl(conversationId, dataSourceId, scenario = null) {
  let url = `${API_BASE}/chat?conversationId=${conversationId}&dataSourceId=${dataSourceId}`
  if (scenario) {
    url += `&scenario=${scenario}`
  }
  // EventSource 无法设置请求头，通过 ?token= 携带 JWT（后端 AuthInterceptor 支持）
  const token = localStorage.getItem(AUTH_STORAGE_KEYS.TOKEN)
  if (token) {
    url += `&token=${encodeURIComponent(token)}`
  }
  return url
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

export async function fetchModelByPipeline(pipelineId) {
  const { data } = await api.get(`/mining/model/by-pipeline/${pipelineId}`)
  return data.data
}

export async function syncModelPipeline(modelId) {
  const { data } = await api.post(`/mining/model/${modelId}/sync-pipeline`)
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

export async function forceDeleteMiningModel(id) {
  await api.delete(`/mining/model/${id}/force`)
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

export async function batchPredictMiningModel(id, overrides = {}) {
  const { data } = await api.post(`/mining/model/${id}/batch-predict`, overrides)
  return data.data
}

export async function updateModelPredictConfig(id, config) {
  const { data } = await api.put(`/mining/model/${id}/predict-config`, config)
  return data.data
}

export async function validateMiningModel(id) {
  const { data } = await api.get(`/mining/model/${id}/validate`)
  return data.data
}

export async function fetchModelPredictions(id, limit = PREDICTION_RECORD_LIMIT) {
  const { data } = await api.get(`/mining/model/${id}/predictions?limit=${limit}`)
  return data.data
}

export async function previewResultTable(modelId, tableName, limit = PREVIEW_ROW_LIMIT) {
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

export async function getStepScript(id, nodeId) {
  const { data } = await api.get(`/mining/pipeline/${id}/step-script`, { params: { nodeId } })
  return data.data
}

export async function trialMissingStrategy(pipelineId, columnStrategies) {
  const { data } = await api.post(`/mining/pipeline/${pipelineId}/trial-missing-strategy`, { columnStrategies })
  return data.data
}

export async function fetchSegmentedScript(pipelineId) {
  const { data } = await api.get(`/mining/pipeline/${pipelineId}/segmented-script`)
  return data.data
}

export async function fetchPipelineSyncStatus(id) {
  const { data } = await api.get(`/mining/pipeline/${id}/sync-status`)
  return data.data
}

export async function rollbackModel(modelId, executionId) {
  const { data } = await api.post(`/mining/model/${modelId}/rollback/${executionId}`)
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

// Word Report APIs
export async function downloadWordReport(conversationId, title) {
  const params = []
  if (title) params.push('title=' + encodeURIComponent(title))
  const token = localStorage.getItem(AUTH_STORAGE_KEYS.TOKEN)
  if (token) params.push('token=' + encodeURIComponent(token))
  const url = `/word-report/download/conversation/${conversationId}${params.length ? '?' + params.join('&') : ''}`
  window.open(`${API_BASE}${url}`, '_blank')
}

// ===== 鉴权 API =====
export async function login(username, password) {
  const { data } = await api.post('/auth/login', { username, password })
  return data.data
}

export async function fetchCurrentUser() {
  const { data } = await api.get('/auth/me')
  return data.data
}

export async function logout() {
  await api.post('/auth/logout')
}

// ===== 用户管理（admin） =====
export async function fetchUsers(keyword) {
  const params = keyword ? `?keyword=${encodeURIComponent(keyword)}` : ''
  const { data } = await api.get(`/users${params}`)
  return data.data
}

export async function createUser(user) {
  const { data } = await api.post('/users', user)
  return data.data
}

export async function updateUser(id, updates) {
  const { data } = await api.put(`/users/${id}`, updates)
  return data.data
}

export async function resetUserPassword(id, newPassword) {
  await api.put(`/users/${id}/password`, { newPassword })
}

export async function deleteUser(id) {
  await api.delete(`/users/${id}`)
}

export async function changeMyPassword(oldPassword, newPassword) {
  await api.post('/auth/password', { oldPassword, newPassword })
}

// ===== 场景管理（admin） =====
export async function fetchAllScenariosForAdmin() {
  const { data } = await api.get('/scenarios/admin/all')
  return data.data
}

export async function createScenario(scenario) {
  const { data } = await api.post('/scenarios', scenario)
  return data.data
}

export async function updateScenario(id, updates) {
  const { data } = await api.put(`/scenarios/${id}`, updates)
  return data.data
}

export async function deleteScenario(id) {
  await api.delete(`/scenarios/${id}`)
}

export async function fetchScenarioRoles(id) {
  const { data } = await api.get(`/scenarios/${id}/roles`)
  return data.data
}

export async function setScenarioRoles(id, roles) {
  await api.put(`/scenarios/${id}/roles`, { roles })
}
