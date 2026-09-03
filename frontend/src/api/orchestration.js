import axios from 'axios'
import { ElMessage } from 'element-plus'
import { AUTH_STORAGE_KEYS, DEFAULT_TIMEOUT_MS, ROUTES } from '../constants.js'

const orchestrationApi = axios.create({
  baseURL: '/api/v2',
  timeout: DEFAULT_TIMEOUT_MS
})

orchestrationApi.interceptors.request.use(config => {
  const token = localStorage.getItem(AUTH_STORAGE_KEYS.TOKEN)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

orchestrationApi.interceptors.response.use(
  response => {
    const { code, message } = response.data || {}
    if (code !== undefined && code !== 200) {
      return Promise.reject(new Error(message || `请求失败 (code: ${code})`))
    }
    return response
  },
  error => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message || '请求失败'
    if (status === 401) {
      localStorage.removeItem(AUTH_STORAGE_KEYS.TOKEN)
      localStorage.removeItem(AUTH_STORAGE_KEYS.USER)
      if (window.location.pathname !== ROUTES.LOGIN) {
        setTimeout(() => { window.location.href = ROUTES.LOGIN }, 400)
      }
    }
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

export async function fetchRecentOutputs(outputKind, limit = 100) {
  const { data } = await orchestrationApi.get('/outputs', {
    params: { outputKind: outputKind || undefined, limit }
  })
  return data.data || []
}

export async function fetchOperators() {
  const { data } = await orchestrationApi.get('/operators')
  return data.data || []
}

export async function fetchPublishedOperatorCatalog(operatorType) {
  const { data } = await orchestrationApi.get('/operator-catalog', {
    params: { operatorType: operatorType || undefined }
  })
  return data.data || []
}

export async function validateDag(nodes, edges) {
  const { data } = await orchestrationApi.post('/dags/validate', { nodes, edges })
  return data.data
}

export async function fetchFlows() {
  const { data } = await orchestrationApi.get('/flows')
  return data.data || []
}

export async function createFlow(request) {
  const { data } = await orchestrationApi.post('/flows', request)
  return data.data
}

export async function fetchFlowVersions(flowId) {
  const { data } = await orchestrationApi.get(`/flows/${flowId}/versions`)
  return data.data || []
}

export async function createFlowVersion(flowId, request) {
  const { data } = await orchestrationApi.post(`/flows/${flowId}/versions`, request)
  return data.data
}

export async function submitTrialRun(flowVersionId, input) {
  const { data } = await orchestrationApi.post(`/flow-versions/${flowVersionId}/trial-runs`, input)
  return data.data
}

export async function fetchRun(runId) {
  const { data } = await orchestrationApi.get(`/runs/${runId}`)
  return data.data
}

export async function fetchFlowRuns(flowId, limit = 100) {
  const { data } = await orchestrationApi.get(`/flows/${flowId}/runs`, { params: { limit } })
  return data.data || []
}

export async function cancelRun(runId) {
  const { data } = await orchestrationApi.post(`/runs/${runId}/cancel`)
  return data.data
}

export async function fetchRunNodes(runId) {
  const { data } = await orchestrationApi.get(`/runs/${runId}/nodes`)
  return data.data || []
}

export async function createNodeReplay(runId, nodeRunId) {
  const { data } = await orchestrationApi.post(`/runs/${runId}/nodes/${nodeRunId}/replays`)
  return data.data
}

export async function fetchNodeReplays(runId) {
  const { data } = await orchestrationApi.get(`/runs/${runId}/node-replays`)
  return data.data || []
}

export async function fetchNodeReplay(replayId) {
  const { data } = await orchestrationApi.get(`/node-replays/${replayId}`)
  return data.data
}

export async function cancelNodeReplay(replayId) {
  const { data } = await orchestrationApi.post(`/node-replays/${replayId}/cancel`)
  return data.data
}

export async function fetchRunOutputs(runId) {
  const { data } = await orchestrationApi.get(`/runs/${runId}/outputs`)
  return data.data || []
}

export async function fetchRunLeads(runId) {
  const { data } = await orchestrationApi.get(`/runs/${runId}/leads`)
  return data.data || []
}

export async function fetchLeadDetail(leadId) {
  const { data } = await orchestrationApi.get(`/leads/${leadId}`)
  return data.data
}

export async function createOperator(request) {
  const { data } = await orchestrationApi.post('/operators', request)
  return data.data
}

export async function fetchOperatorVersions(operatorId) {
  const { data } = await orchestrationApi.get(`/operators/${operatorId}/versions`)
  return data.data || []
}

export async function createOperatorVersion(operatorId, request) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/versions`, request)
  return data.data
}

export async function fetchRuleDrafts(operatorId) {
  const { data } = await orchestrationApi.get(`/operators/${operatorId}/rule-drafts`)
  return data.data || []
}

export async function generateRuleDraft(operatorId, request) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/rule-drafts/from-dialogue`, request)
  return data.data
}

export async function validateRuleDraft(operatorId, draftId, runtimeProfileId) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/rule-drafts/${draftId}/validate`, {
    runtimeProfileId: runtimeProfileId || undefined
  })
  return data.data
}

export async function createRuleCandidate(operatorId, draftId) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/rule-drafts/${draftId}/candidate-version`)
  return data.data
}

export async function fetchOperatorApprovals(status) {
  const { data } = await orchestrationApi.get('/operator-version-approvals', {
    params: { status: status || undefined }
  })
  return data.data || []
}

export async function fetchOperatorApprovalCapability() {
  const { data } = await orchestrationApi.get('/operator-version-approvals/capability')
  return data.data || { canReview: false }
}

export async function fetchOperatorApprovalDetail(approvalId) {
  const { data } = await orchestrationApi.get(`/operator-version-approvals/${approvalId}`)
  return data.data
}

export async function submitOperatorVersionApproval(operatorId, versionId, comment) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/versions/${versionId}/submit-approval`, { comment })
  return data.data
}

export async function reviewOperatorApproval(approvalId, decision, comment) {
  const { data } = await orchestrationApi.post(`/operator-version-approvals/${approvalId}/review`, { decision, comment })
  return data.data
}

export async function fetchOutputView(artifactId, page = 1, pageSize = 50) {
  const { data } = await orchestrationApi.get(`/outputs/${artifactId}/view`, {
    params: { page, pageSize }
  })
  return data.data
}

export async function queryOutputView(artifactId, request = {}) {
  const { data } = await orchestrationApi.post(`/outputs/${artifactId}/query`, request)
  return data.data
}

export async function fetchStorageGovernance() {
  const { data } = await orchestrationApi.get('/storage-governance/dashboard')
  return data.data
}

export async function updateStoragePolicy(request) {
  const { data } = await orchestrationApi.post('/storage-governance/policy', request)
  return data.data
}

export async function archiveOutputArtifact(artifactId, reason) {
  const { data } = await orchestrationApi.post(`/storage-governance/outputs/${artifactId}/archive`, { reason })
  return data.data
}

export async function archiveNodeReplay(replayId, reason) {
  const { data } = await orchestrationApi.post(`/storage-governance/node-replays/${replayId}/archive`, { reason })
  return data.data
}

export async function restoreStorageArchive(archiveId) {
  const { data } = await orchestrationApi.post(`/storage-governance/archives/${archiveId}/restore`)
  return data.data
}

export async function runStorageRetention() {
  const { data } = await orchestrationApi.post('/storage-governance/retention/run')
  return data.data
}

export async function fetchOutputDrafts(operatorId) {
  const { data } = await orchestrationApi.get(`/operators/${operatorId}/output-drafts`)
  return data.data || []
}

export async function generateOutputDraft(operatorId, request) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/output-drafts/from-dialogue`, request)
  return data.data
}

export async function shapeOutputDraft(operatorId, draftId, runtimeProfileId) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/output-drafts/${draftId}/shape`, {
    runtimeProfileId: runtimeProfileId || undefined
  })
  return data.data
}

export async function previewOutputDraft(operatorId, draftId, records) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/output-drafts/${draftId}/preview`, { records })
  return data.data
}

export async function publishOutputDraft(operatorId, draftId) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/output-drafts/${draftId}/publish-version`)
  return data.data
}

export async function fetchAgentTools() {
  const { data } = await orchestrationApi.get('/agent-tools')
  return data.data || []
}

export async function fetchPolicyDrafts(operatorId) {
  const { data } = await orchestrationApi.get(`/operators/${operatorId}/policy-drafts`)
  return data.data || []
}

export async function generatePolicyDraft(operatorId, request) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/policy-drafts/from-dialogue`, request)
  return data.data
}

export async function shapePolicyDraft(operatorId, draftId, runtimeProfileId) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/policy-drafts/${draftId}/shape`, {
    runtimeProfileId: runtimeProfileId || undefined
  })
  return data.data
}

export async function previewPolicyDraft(operatorId, draftId, request) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/policy-drafts/${draftId}/preview`, request)
  return data.data
}

export async function publishPolicyDraft(operatorId, draftId) {
  const { data } = await orchestrationApi.post(`/operators/${operatorId}/policy-drafts/${draftId}/publish-version`)
  return data.data
}

export async function fetchDependencyRequests(status) {
  const { data } = await orchestrationApi.get('/dependency-requests', { params: { status: status || undefined } })
  return data.data || []
}

export async function submitDependencyRequest(request) {
  const { data } = await orchestrationApi.post('/dependency-requests', request)
  return data.data
}

export async function reviewDependencyRequest(requestId, request) {
  const { data } = await orchestrationApi.post(`/dependency-requests/${requestId}/review`, request)
  return data.data
}

export async function fetchRuntimeProfiles(runtimeType, includeDeprecated = false) {
  const { data } = await orchestrationApi.get('/runtime-profiles', {
    params: { runtimeType: runtimeType || undefined, includeDeprecated }
  })
  return data.data || []
}

export async function registerRuntimeBuild(request) {
  const { data } = await orchestrationApi.post('/runtime-profiles/register-build', request)
  return data.data
}

export async function fetchRuntimeBuildJobs(status) {
  const { data } = await orchestrationApi.get('/runtime-build-jobs', {
    params: { status: status || undefined }
  })
  return data.data || []
}

export async function fetchRuntimeBuildCapability() {
  const { data } = await orchestrationApi.get('/runtime-build-jobs/capability')
  return data.data || { workerEnabled: false, protocol: 'HMAC_PULL_V1' }
}

export async function retryRuntimeBuild(jobId) {
  const { data } = await orchestrationApi.post(`/runtime-build-jobs/${jobId}/retry`)
  return data.data
}

export async function cancelRuntimeBuild(jobId) {
  const { data } = await orchestrationApi.post(`/runtime-build-jobs/${jobId}/cancel`)
  return data.data
}

export async function deprecateRuntimeProfile(profileId) {
  const { data } = await orchestrationApi.post(`/runtime-profiles/${profileId}/deprecate`)
  return data.data
}

export async function fetchDraftDependencies(draftType, draftId) {
  const { data } = await orchestrationApi.get(`/drafts/${draftType}/${draftId}/dependencies`)
  return data.data || []
}
