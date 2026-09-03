<template>
  <div :class="['page-container', 'dependency-center', { embedded: props.embedded }]">
    <div class="page-header">
      <button v-if="!props.embedded" class="back-btn" @click="$emit('close')"><span class="back-arrow">&larr;</span> 返回问数</button>
      <h2 class="page-title">依赖与运行时中心</h2>
      <el-tag type="info">运行时禁止临时安装</el-tag>
      <span class="spacer" />
      <el-button @click="loadAll" :loading="loading">刷新</el-button>
      <el-button type="primary" @click="requestVisible = true">申请依赖</el-button>
    </div>

    <el-alert class="policy" :closable="false" :type="buildCapability.workerEnabled ? 'success' : 'warning'" show-icon
      :title="buildCapability.workerEnabled
        ? '外部构建器已启用：审批通过后自动排队，回传 SBOM、provenance 与镜像摘要后自动重验关联草稿。'
        : '外部构建器未配置：依赖仍会进入持久队列，但 worker API 将保持关闭。旧算子版本不会被改写。'" />

    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="依赖申请" name="requests">
        <el-table :data="requests" v-loading="loading" height="calc(100vh - 220px)" stripe>
          <el-table-column prop="requestNo" label="申请号" width="180" />
          <el-table-column label="依赖" min-width="220">
            <template #default="{ row }">
              <strong>{{ row.dependencyName }}</strong>
              <div class="muted">{{ row.dependencyType }} · {{ row.requestedVersion }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="runtimeType" label="运行时" width="170" />
          <el-table-column label="状态" width="130">
            <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="reason" label="用途" min-width="200" show-overflow-tooltip />
          <el-table-column label="安全结果" min-width="180">
            <template #default="{ row }">
              <span v-if="row.resolvedVersion">{{ row.resolvedVersion }} · 高危 {{ row.vulnerabilityHigh || 0 }} / 严重 {{ row.vulnerabilityCritical || 0 }}</span>
              <span v-else class="muted">待审批</span>
            </template>
          </el-table-column>
          <el-table-column v-if="user.isAdmin" label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button v-if="['SUBMITTED','UNDER_REVIEW'].includes(row.status)" link type="primary" @click="openReview(row)">审批</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="构建任务" name="buildJobs">
        <div class="runtime-toolbar">
          <el-tag :type="buildCapability.workerEnabled ? 'success' : 'danger'">
            {{ buildCapability.workerEnabled ? 'HMAC 构建器已连接' : '构建器未配置' }}
          </el-tag>
          <span class="muted">{{ buildCapability.protocol }}</span>
        </div>
        <el-table :data="buildJobs" v-loading="loading" height="calc(100vh - 260px)" stripe>
          <el-table-column label="任务" min-width="220">
            <template #default="{ row }"><strong>{{ row.jobNo }}</strong><div class="muted">申请 #{{ row.dependencyRequestId }} · {{ buildSpec(row).profileName || '不可变运行时' }}</div></template>
          </el-table-column>
          <el-table-column prop="runtimeType" label="运行时" width="170" />
          <el-table-column label="状态" width="125"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column label="尝试" width="90"><template #default="{ row }">{{ row.attemptNo || 0 }} / {{ row.maxAttempts }}</template></el-table-column>
          <el-table-column label="构建器" min-width="160"><template #default="{ row }">{{ row.workerId || '等待领取' }}</template></el-table-column>
          <el-table-column label="结果" min-width="220">
            <template #default="{ row }">
              <span v-if="row.runtimeProfileId">运行时 #{{ row.runtimeProfileId }}</span>
              <span v-else-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="190" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="inspectBuild(row)">详情</el-button>
              <el-button v-if="user.isAdmin && row.status === 'FAILED'" link type="warning" @click="retryBuild(row)">重试</el-button>
              <el-button v-if="user.isAdmin && ['QUEUED','RETRYABLE'].includes(row.status)" link type="danger" @click="cancelBuild(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="运行时档案" name="profiles">
        <div class="runtime-toolbar">
          <el-checkbox v-model="includeDeprecated" @change="loadProfiles">显示已废弃</el-checkbox>
          <el-button v-if="user.isAdmin" @click="openBuild">应急手工登记</el-button>
        </div>
        <el-table :data="profiles" v-loading="loading" height="calc(100vh - 260px)" stripe>
          <el-table-column label="运行时" min-width="230">
            <template #default="{ row }"><strong>{{ row.profile.name }}</strong><div class="muted">{{ row.profile.code }}</div></template>
          </el-table-column>
          <el-table-column prop="profile.runtimeType" label="类型" width="180" />
          <el-table-column label="固定镜像" min-width="300">
            <template #default="{ row }"><code>{{ row.profile.imageRef }}</code><div class="digest">{{ row.profile.imageDigest }}</div></template>
          </el-table-column>
          <el-table-column label="依赖" min-width="220">
            <template #default="{ row }">
              <el-tag v-for="dep in row.dependencies" :key="dep.id" size="small" class="dep-tag">{{ dep.dependencyName }}@{{ dep.dependencyVersion }}</el-tag>
              <span v-if="!row.dependencies.length" class="muted">核心运行时</span>
            </template>
          </el-table-column>
          <el-table-column label="使用中版本" prop="versionUsageCount" width="110" />
          <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.profile.status)">{{ row.profile.status }}</el-tag></template></el-table-column>
          <el-table-column v-if="user.isAdmin" label="操作" width="100" fixed="right">
            <template #default="{ row }"><el-button v-if="row.profile.status === 'ACTIVE' && !row.profile.defaultProfile" link type="warning" @click="deprecate(row.profile)">废弃</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="requestVisible" title="提交依赖申请" width="560px">
      <el-form label-position="top">
        <el-form-item label="依赖类别"><el-select v-model="requestForm.dependencyType" style="width:100%"><el-option v-for="item in dependencyTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <div class="form-grid"><el-form-item label="名称"><el-input v-model="requestForm.name" placeholder="例如 pandas" /></el-form-item><el-form-item label="期望版本"><el-input v-model="requestForm.requestedVersion" placeholder="例如 2.2.3" /></el-form-item></div>
        <el-form-item label="业务用途"><el-input v-model="requestForm.reason" type="textarea" :rows="3" /></el-form-item>
        <div class="form-grid"><el-form-item label="关联草稿类型（可选）"><el-select v-model="requestForm.draftType" clearable><el-option label="规则草稿" value="RULE" /><el-option label="输出草稿" value="OUTPUT" /><el-option label="SQL / 智能体策略草稿" value="POLICY" /></el-select></el-form-item><el-form-item label="草稿 ID"><el-input-number v-model="requestForm.draftId" :min="1" controls-position="right" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="requestVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitRequest">提交</el-button></template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="安全审批" width="650px">
      <el-form label-position="top"><div class="form-grid"><el-form-item label="锁定版本"><el-input v-model="reviewForm.resolvedVersion" /></el-form-item><el-form-item label="许可证"><el-input v-model="reviewForm.licenseName" /></el-form-item></div>
        <el-form-item label="可信来源"><el-input v-model="reviewForm.sourceUri" /></el-form-item><el-form-item label="SHA-256"><el-input v-model="reviewForm.checksumSha256" /></el-form-item>
        <div class="form-grid"><el-form-item label="严重漏洞"><el-input-number v-model="reviewForm.vulnerabilityCritical" :min="0" /></el-form-item><el-form-item label="高危漏洞"><el-input-number v-model="reviewForm.vulnerabilityHigh" :min="0" /></el-form-item></div>
        <el-checkbox v-model="reviewForm.sourceVerified">来源已验证</el-checkbox><el-checkbox v-model="reviewForm.licenseApproved">许可证允许使用</el-checkbox><el-form-item label="审批意见"><el-input v-model="reviewForm.comment" type="textarea" /></el-form-item>
      </el-form>
      <template #footer><el-button type="danger" :loading="saving" @click="review('REJECT')">驳回</el-button><el-button type="primary" :loading="saving" @click="review('APPROVE')">批准</el-button></template>
    </el-dialog>

    <el-dialog v-model="buildVisible" title="登记受控构建产物" width="720px">
      <el-alert :closable="false" type="info" title="这里只接收构建器证明，不在应用进程中构建镜像或安装包。" />
      <el-form label-position="top" class="build-form"><el-form-item label="已批准申请"><el-select v-model="buildForm.requestIds" multiple style="width:100%"><el-option v-for="item in approvedRequests" :key="item.id" :label="`${item.dependencyName}@${item.resolvedVersion} · ${item.runtimeType}`" :value="item.id" /></el-select></el-form-item>
        <div class="form-grid"><el-form-item label="档案代码"><el-input v-model="buildForm.code" /></el-form-item><el-form-item label="档案名称"><el-input v-model="buildForm.name" /></el-form-item></div>
        <el-form-item label="镜像引用（必须含 @sha256）"><el-input v-model="buildForm.imageRef" /></el-form-item><el-form-item label="镜像摘要"><el-input v-model="buildForm.imageDigest" placeholder="sha256:..." /></el-form-item>
        <div class="form-grid"><el-form-item label="构建器身份"><el-input v-model="buildForm.builder" /></el-form-item><el-form-item label="SBOM 摘要"><el-input v-model="buildForm.sbomDigest" /></el-form-item></div>
        <el-form-item label="Provenance 摘要"><el-input v-model="buildForm.provenanceDigest" /></el-form-item>
        <div class="form-grid"><el-form-item label="严重漏洞"><el-input-number v-model="buildForm.critical" :min="0" /></el-form-item><el-form-item label="高危漏洞"><el-input-number v-model="buildForm.high" :min="0" /></el-form-item></div>
        <el-checkbox v-model="buildForm.sourceVerified">构建器证明来源已验证</el-checkbox><el-checkbox v-model="buildForm.licenseApproved">构建器证明许可证已批准</el-checkbox>
      </el-form>
      <template #footer><el-button @click="buildVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="registerBuild">登记并解锁复验</el-button></template>
    </el-dialog>

    <el-drawer v-model="buildDetailVisible" title="运行时构建任务详情" size="680px">
      <template v-if="selectedBuild">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务号">{{ selectedBuild.jobNo }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="statusType(selectedBuild.status)">{{ selectedBuild.status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="运行时">{{ selectedBuild.runtimeType }}</el-descriptions-item>
          <el-descriptions-item label="构建器">{{ selectedBuild.workerId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="尝试次数">{{ selectedBuild.attemptNo || 0 }} / {{ selectedBuild.maxAttempts }}</el-descriptions-item>
          <el-descriptions-item label="运行时档案">{{ selectedBuild.runtimeProfileId ? `#${selectedBuild.runtimeProfileId}` : '—' }}</el-descriptions-item>
          <el-descriptions-item label="失败代码">{{ selectedBuild.errorCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="失败原因">{{ selectedBuild.errorMessage || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert class="immutable-tip" title="构建规范由审批结果生成；worker 不能替换依赖申请、基础运行时或目标档案代码。" type="info" :closable="false" show-icon />
        <el-collapse>
          <el-collapse-item title="不可变构建规范" name="spec"><pre>{{ pretty(selectedBuild.buildSpec) }}</pre></el-collapse-item>
          <el-collapse-item title="构建器回传" name="result"><pre>{{ pretty(selectedBuild.resultManifest) }}</pre></el-collapse-item>
          <el-collapse-item title="草稿自动重验" name="revalidation"><pre>{{ pretty(selectedBuild.revalidationReport) }}</pre></el-collapse-item>
        </el-collapse>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../stores/user.js'
import { cancelRuntimeBuild, deprecateRuntimeProfile, fetchDependencyRequests, fetchRuntimeBuildCapability, fetchRuntimeBuildJobs, fetchRuntimeProfiles, registerRuntimeBuild, retryRuntimeBuild, reviewDependencyRequest, submitDependencyRequest } from '../api/orchestration.js'

const props = defineProps({ embedded: { type: Boolean, default: false } })
defineEmits(['close'])
const user = useUserStore()
const loading = ref(false), saving = ref(false), activeTab = ref('requests'), includeDeprecated = ref(false)
const requestVisible = ref(false), reviewVisible = ref(false), buildVisible = ref(false), buildDetailVisible = ref(false)
const requests = ref([]), profiles = ref([]), buildJobs = ref([]), selectedRequest = ref(null), selectedBuild = ref(null)
const buildCapability = ref({ workerEnabled: false, protocol: 'HMAC_PULL_V1' })
const dependencyTypes = [
  { value: 'PYTHON_PACKAGE', label: 'Python 包（规则运行时）' }, { value: 'ML_ALGORITHM', label: 'ML 算法（模型运行时）' },
  { value: 'JDBC_DRIVER', label: 'JDBC 驱动（数据连接器）' }, { value: 'AGENT_TOOL', label: '智能体工具（工具网关）' },
  { value: 'FRONTEND_RENDERER', label: '前端渲染器（输出运行时）' }
]
const requestForm = reactive({ dependencyType: 'PYTHON_PACKAGE', name: '', requestedVersion: '', reason: '', draftType: '', draftId: null })
const reviewForm = reactive({ resolvedVersion: '', sourceUri: '', checksumSha256: '', licenseName: '', sourceVerified: false, licenseApproved: false, vulnerabilityCritical: 0, vulnerabilityHigh: 0, comment: '' })
const buildForm = reactive({ requestIds: [], code: '', name: '', imageRef: '', imageDigest: '', builder: '', sbomDigest: '', provenanceDigest: '', sourceVerified: false, licenseApproved: false, critical: 0, high: 0 })
const approvedRequests = computed(() => requests.value.filter(item => item.status === 'APPROVED'))
function statusType(status) { return ({ ACTIVE: 'success', READY: 'success', APPROVED: 'success', SUCCEEDED: 'success', DEPRECATED: 'info', CANCELED: 'info', REJECTED: 'danger', FAILED: 'danger', SUBMITTED: 'warning', QUEUED: 'warning', RETRYABLE: 'warning', BUILDING: 'primary' }[status] || 'warning') }
async function loadAll() { loading.value = true; try { [requests.value, profiles.value, buildJobs.value, buildCapability.value] = await Promise.all([fetchDependencyRequests(), fetchRuntimeProfiles('', includeDeprecated.value), fetchRuntimeBuildJobs(), fetchRuntimeBuildCapability()]) } finally { loading.value = false } }
async function loadProfiles() { profiles.value = await fetchRuntimeProfiles('', includeDeprecated.value) }
async function submitRequest() { saving.value = true; try { const body = { ...requestForm }; if (!body.draftType) { delete body.draftType; delete body.draftId } await submitDependencyRequest(body); requestVisible.value = false; ElMessage.success('依赖申请已提交'); await loadAll() } finally { saving.value = false } }
function openReview(row) { selectedRequest.value = row; Object.assign(reviewForm, { resolvedVersion: row.requestedVersion, sourceUri: '', checksumSha256: '', licenseName: '', sourceVerified: false, licenseApproved: false, vulnerabilityCritical: 0, vulnerabilityHigh: 0, comment: '' }); reviewVisible.value = true }
async function review(decision) { saving.value = true; try { await reviewDependencyRequest(selectedRequest.value.id, { ...reviewForm, decision, licenseDecision: reviewForm.licenseApproved ? 'APPROVED' : 'REJECTED' }); reviewVisible.value = false; ElMessage.success(decision === 'APPROVE' ? '审批已通过，等待构建运行时' : '申请已驳回'); await loadAll() } finally { saving.value = false } }
function openBuild() { Object.assign(buildForm, { requestIds: [], code: '', name: '', imageRef: '', imageDigest: '', builder: '', sbomDigest: '', provenanceDigest: '', sourceVerified: false, licenseApproved: false, critical: 0, high: 0 }); buildVisible.value = true }
async function registerBuild() { saving.value = true; try { await registerRuntimeBuild({ ...buildForm, buildManifest: { builder: buildForm.builder, sbomDigest: buildForm.sbomDigest, provenanceDigest: buildForm.provenanceDigest }, securityReport: { sourceVerified: buildForm.sourceVerified, licenseDecision: buildForm.licenseApproved ? 'APPROVED' : 'REJECTED', critical: buildForm.critical, high: buildForm.high } }); buildVisible.value = false; ElMessage.success('不可变运行时已登记，可返回草稿选择该运行时重新验证'); await loadAll() } finally { saving.value = false } }
function inspectBuild(row) { selectedBuild.value = row; buildDetailVisible.value = true }
async function retryBuild(row) { await retryRuntimeBuild(row.id); ElMessage.success('构建任务已重新排队'); await loadAll() }
async function cancelBuild(row) { await ElMessageBox.confirm(`取消构建任务 ${row.jobNo}？`, '取消构建', { type: 'warning' }); await cancelRuntimeBuild(row.id); await loadAll() }
async function deprecate(profile) { await ElMessageBox.confirm(`废弃 ${profile.name}？已绑定版本仍可运行，但新版本不能再选择。`, '废弃运行时', { type: 'warning' }); await deprecateRuntimeProfile(profile.id); await loadProfiles() }
function parseJson(value) { if (!value) return {}; if (typeof value === 'object') return value; try { return JSON.parse(value) } catch { return { raw: value } } }
function buildSpec(row) { return parseJson(row.buildSpec) }
function pretty(value) { return JSON.stringify(parseJson(value), null, 2) }
onMounted(loadAll)
</script>

<style scoped>
.dependency-center { flex: 1; min-width: 0; background: var(--bg); display: flex; flex-direction: column; }
.dependency-center.embedded { max-width: none; margin: 0; padding: 0; }
.embedded .page-header { min-height: 68px; margin: 0; padding: 12px 20px; background: var(--surface); }
.page-header { gap: 12px; }.spacer { flex: 1; }.policy { margin: 12px 20px 0; width: auto; }.tabs { flex: 1; min-height: 0; padding: 0 20px; }.runtime-toolbar { display:flex; justify-content:flex-end; align-items:center; gap:12px; margin-bottom:12px; }.muted,.digest { color:var(--text-muted); font-size:12px; margin-top:4px; }.digest { overflow-wrap:anywhere; }.error-text { color:var(--danger); font-size:12px; }.dep-tag { margin:2px 4px 2px 0; }.form-grid { display:grid; grid-template-columns:1fr 1fr; gap:14px; }.build-form { margin-top:14px; }.immutable-tip { margin:16px 0; } pre { white-space:pre-wrap; overflow-wrap:anywhere; font-size:12px; } code { font-size:12px; overflow-wrap:anywhere; }
@media (max-width: 700px) { .form-grid { grid-template-columns:1fr; gap:0; } }
</style>
