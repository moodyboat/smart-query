<template>
  <div class="approval-center">
    <div class="approval-toolbar">
      <div>
        <h3>算子版本审批</h3>
        <p>{{ capability.canReview ? '审批队列包含所有作者提交的不可变版本，创建人与审批人必须分离。' : '这里展示你提交的版本申请及审批结果。' }}</p>
      </div>
      <div class="toolbar-actions">
        <el-select v-model="statusFilter" clearable placeholder="全部状态" size="small" @change="load">
          <el-option label="待审批" value="SUBMITTED" /><el-option label="已通过" value="APPROVED" /><el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="approval-stats">
      <div><strong>{{ counts.SUBMITTED || 0 }}</strong><span>待审批</span></div>
      <div><strong>{{ counts.APPROVED || 0 }}</strong><span>已通过</span></div>
      <div><strong>{{ counts.REJECTED || 0 }}</strong><span>已驳回</span></div>
    </div>

    <el-table :data="approvals" v-loading="loading" border stripe height="calc(100vh - 270px)" empty-text="暂无版本审批记录">
      <el-table-column label="算子" min-width="180">
        <template #default="{ row }"><strong>{{ row.operatorName }}</strong><small>{{ row.operatorCode }}</small></template>
      </el-table-column>
      <el-table-column prop="operatorType" label="类型" width="90"><template #default="{ row }"><el-tag effect="plain">{{ row.operatorType }}</el-tag></template></el-table-column>
      <el-table-column label="版本" width="110"><template #default="{ row }">v{{ row.versionNo }} · #{{ row.approval.operatorVersionId }}</template></el-table-column>
      <el-table-column prop="implementationType" label="实现" min-width="150" />
      <el-table-column label="申请人" width="120"><template #default="{ row }">{{ row.requesterName }}</template></el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><el-tag :type="statusType(row.approval.status)" effect="plain">{{ statusLabel(row.approval.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column label="申请时间" width="175"><template #default="{ row }">{{ formatTime(row.approval.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="showDetail(row)">详情</el-button>
          <el-button v-if="row.reviewable" link type="warning" @click="openReview(row)">审批</el-button>
          <el-button v-if="row.versionStatus === 'PUBLISHED'" link type="success" @click="emit('openDag', row.approval.operatorVersionId)">加入 DAG</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="detailVisible" title="不可变版本审批详情" size="620px">
      <div v-loading="detailLoading">
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="算子">{{ selected.operatorName }}（{{ selected.operatorCode }}）</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ selected.versionNo }} / #{{ selected.approval.operatorVersionId }}</el-descriptions-item>
          <el-descriptions-item label="实现方式">{{ selected.implementationType }}</el-descriptions-item>
          <el-descriptions-item label="内容摘要"><code>{{ selected.contentHash }}</code></el-descriptions-item>
          <el-descriptions-item label="申请说明">{{ selected.approval.requestComment || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批人">{{ selected.reviewerName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批意见">{{ selected.approval.reviewComment || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert class="immutable-tip" title="审批只改变版本生命周期状态，不会修改源码、SQL、工具权限、Schema 或运行时镜像摘要。" type="info" :closable="false" show-icon />
        <el-collapse v-if="selectedDetail" class="version-content">
          <el-collapse-item title="实现工件（源码 / SQL / 输出规格 / 智能体策略）" name="payload"><pre>{{ pretty(selectedDetail.implementationPayload) }}</pre></el-collapse-item>
          <el-collapse-item title="自动验证与固定运行时报告" name="validation"><pre>{{ pretty(selectedDetail.validationReport) }}</pre></el-collapse-item>
          <el-collapse-item title="输入 / 输出 / 参数 Schema" name="schemas">
            <div class="schema-block"><span>输入</span><pre>{{ pretty(selectedDetail.inputSchema) }}</pre></div>
            <div class="schema-block"><span>输出</span><pre>{{ pretty(selectedDetail.outputSchema) }}</pre></div>
            <div class="schema-block"><span>参数</span><pre>{{ pretty(selectedDetail.parameterSchema) }}</pre></div>
          </el-collapse-item>
        </el-collapse>
      </template>
      </div>
    </el-drawer>

    <el-dialog v-model="reviewVisible" title="审批算子版本" width="500px">
      <div v-if="selected" class="review-target">
        <strong>{{ selected.operatorName }} · v{{ selected.versionNo }}</strong>
        <span>{{ selected.implementationType }} · {{ selected.requesterName }} 提交</span>
      </div>
      <el-form label-position="top">
        <el-form-item label="审批意见"><el-input v-model="reviewComment" type="textarea" :rows="4" placeholder="通过时可选；驳回时必须说明修改要求" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button type="danger" :loading="saving" @click="review('REJECT')">驳回</el-button>
        <el-button type="primary" :loading="saving" @click="review('APPROVE')">批准发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchOperatorApprovalCapability, fetchOperatorApprovalDetail, fetchOperatorApprovals, reviewOperatorApproval } from '../api/orchestration.js'

const emit = defineEmits(['openDag'])
const approvals = ref([])
const allApprovals = ref([])
const capability = ref({ canReview: false })
const statusFilter = ref('')
const loading = ref(false)
const saving = ref(false)
const selected = ref(null)
const selectedDetail = ref(null)
const detailLoading = ref(false)
const detailVisible = ref(false)
const reviewVisible = ref(false)
const reviewComment = ref('')
const counts = computed(() => allApprovals.value.reduce((result, row) => {
  result[row.approval.status] = (result[row.approval.status] || 0) + 1
  return result
}, {}))

async function load() {
  loading.value = true
  try {
    const [filtered, all] = await Promise.all([
      fetchOperatorApprovals(statusFilter.value),
      statusFilter.value ? fetchOperatorApprovals() : Promise.resolve(null)
    ])
    approvals.value = filtered
    allApprovals.value = all || filtered
  } finally { loading.value = false }
}
async function showDetail(row) {
  selected.value = row; selectedDetail.value = null; detailVisible.value = true; detailLoading.value = true
  try { selectedDetail.value = await fetchOperatorApprovalDetail(row.approval.id) }
  finally { detailLoading.value = false }
}
function openReview(row) { selected.value = row; reviewComment.value = ''; reviewVisible.value = true }
async function review(decision) {
  if (decision === 'REJECT' && !reviewComment.value.trim()) { ElMessage.warning('驳回时必须填写修改原因'); return }
  saving.value = true
  try {
    await reviewOperatorApproval(selected.value.approval.id, decision, reviewComment.value)
    reviewVisible.value = false
    ElMessage.success(decision === 'APPROVE' ? '审批通过，版本已进入 DAG 发布目录' : '版本已驳回，作者需要生成新版本')
    await load()
  } finally { saving.value = false }
}
function statusLabel(status) { return ({ SUBMITTED: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }[status] || status) }
function statusType(status) { return ({ SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[status] || 'info') }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
function pretty(value) { if (!value) return '{}'; if (typeof value === 'object') return JSON.stringify(value, null, 2); try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value } }

onMounted(async () => { capability.value = await fetchOperatorApprovalCapability(); await load() })
</script>

<style scoped>
.approval-center { flex: 1; min-height: 0; padding: 18px 20px; overflow: hidden; background: var(--bg); }
.approval-toolbar { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.approval-toolbar h3 { margin: 0 0 4px; font-size: var(--font-lg); }
.approval-toolbar p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.toolbar-actions { display: flex; gap: 8px; }
.toolbar-actions .el-select { width: 130px; }
.approval-stats { display: flex; gap: 10px; margin-bottom: 14px; }
.approval-stats div { min-width: 112px; padding: 10px 14px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--surface); }
.approval-stats strong { margin-right: 8px; font-size: 20px; }
.approval-stats span, .el-table small { display: block; color: var(--text-muted); font-size: 12px; }
.el-table strong { display: block; }
.review-target { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; padding: 12px; border-radius: var(--radius-md); background: var(--bg); }
.review-target span { color: var(--text-muted); font-size: 12px; }
code { word-break: break-all; font-size: 12px; }
.immutable-tip { margin-top: 16px; }
.version-content { margin-top: 16px; }
pre { max-height: 420px; overflow: auto; margin: 0; padding: 10px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.schema-block + .schema-block { margin-top: 12px; }
.schema-block > span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: 12px; }
</style>
