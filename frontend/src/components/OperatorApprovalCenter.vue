<template>
  <div class="approval-center">
    <div class="approval-toolbar">
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

    <div class="approval-list-shell">
    <el-table :data="approvals" v-loading="loading" class="operator-table" empty-text="暂无版本审批记录">
      <el-table-column label="算子" min-width="180">
        <template #default="{ row }"><strong>{{ row.operatorName }}</strong><small>{{ row.operatorCode }}</small></template>
      </el-table-column>
      <el-table-column prop="operatorType" label="类型" width="90"><template #default="{ row }"><el-tag effect="plain">{{ row.operatorType }}</el-tag></template></el-table-column>
      <el-table-column label="版本" width="130"><template #default="{ row }">版本 {{ row.versionNo }} · #{{ row.approval.operatorVersionId }}</template></el-table-column>
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
    <div class="operator-card-list">
      <article v-for="row in approvals" :key="row.approval.id" class="operator-card">
        <div class="operator-card-head">
          <div class="operator-name"><span>算</span><div><strong>{{ row.operatorName }}</strong><small>{{ row.operatorCode }} · 版本 {{ row.versionNo }}</small></div></div>
          <el-tag :type="statusType(row.approval.status)" effect="plain">{{ statusLabel(row.approval.status) }}</el-tag>
        </div>
        <dl><div><dt>类型</dt><dd>{{ row.operatorType }}</dd></div><div><dt>实现</dt><dd>{{ row.implementationType }}</dd></div><div><dt>申请人</dt><dd>{{ row.requesterName }}</dd></div><div><dt>申请时间</dt><dd>{{ formatTime(row.approval.createdAt) }}</dd></div></dl>
        <div class="operator-card-actions"><el-button link type="primary" @click="showDetail(row)">详情</el-button><el-button v-if="row.reviewable" link type="warning" @click="openReview(row)">审批</el-button><el-button v-if="row.versionStatus === 'PUBLISHED'" link type="success" @click="emit('openDag', row.approval.operatorVersionId)">加入 DAG</el-button></div>
      </article>
      <el-empty v-if="!loading && !approvals.length" description="暂无版本审批记录" :image-size="72" />
    </div>
    </div>

    <el-drawer v-model="detailVisible" title="不可变版本审批详情" size="min(620px, 94vw)">
      <div v-loading="detailLoading">
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="算子">{{ selected.operatorName }}（{{ selected.operatorCode }}）</el-descriptions-item>
          <el-descriptions-item label="版本">版本 {{ selected.versionNo }} / #{{ selected.approval.operatorVersionId }}</el-descriptions-item>
          <el-descriptions-item label="实现方式">{{ selected.implementationType }}</el-descriptions-item>
          <el-descriptions-item label="内容摘要"><code>{{ selected.contentHash }}</code></el-descriptions-item>
          <el-descriptions-item label="申请说明">{{ selected.approval.requestComment || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批人">{{ selected.reviewerName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审批意见">{{ selected.approval.reviewComment || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert class="immutable-tip" title="审批只改变版本生命周期状态，不会修改源码、SQL、工具权限、Schema 或运行时镜像摘要。" type="info" :closable="false" show-icon />
        <section v-if="selected.operatorType === 'ML'" class="ml-review">
          <div class="ml-review-title"><div><h4>机器学习算子质量评估</h4></div><el-tag :type="mlQualityType" effect="plain">{{ mlQualityLabel }}</el-tag></div>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="任务类型">{{ modelTypeLabel(mlReview.modelType) }}</el-descriptions-item>
            <el-descriptions-item label="算法">{{ mlReview.algorithm || '—' }}<span v-if="mlReview.algorithmVersion"> · 版本 {{ mlReview.algorithmVersion }}</span></el-descriptions-item>
            <el-descriptions-item label="训练数据">{{ mlReview.sourceTable || '—' }}</el-descriptions-item>
            <el-descriptions-item label="目标字段">{{ mlReview.targetColumn || '无监督任务' }}</el-descriptions-item>
            <el-descriptions-item label="验证方式">{{ validationLabel(mlReview.validationMode) }}</el-descriptions-item>
            <el-descriptions-item label="训练制品">{{ mlReview.artifactSchemaVersion ? `Pipeline v${mlReview.artifactSchemaVersion}` : '—' }}</el-descriptions-item>
          </el-descriptions>
          <div v-if="mlMetricEntries.length" class="ml-metrics"><div v-for="item in mlMetricEntries" :key="item[0]"><span>{{ metricLabel(item[0]) }}</span><strong>{{ metricValue(item[1]) }}</strong></div></div>
          <el-empty v-else description="该旧版本未保存机器学习评估快照" :image-size="54" />
        </section>
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

    <el-dialog v-model="reviewVisible" title="审批算子版本" width="min(500px, 92vw)">
      <div v-if="selected" class="review-target">
        <strong>{{ selected.operatorName }} · 版本 {{ selected.versionNo }}</strong>
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
import { fetchMiningModel } from '../api/index.js'

const emit = defineEmits(['openDag'])
const approvals = ref([])
const allApprovals = ref([])
const capability = ref({ canReview: false })
const statusFilter = ref('')
const loading = ref(false)
const saving = ref(false)
const selected = ref(null)
const selectedDetail = ref(null)
const legacyMlModel = ref(null)
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
  selected.value = row; selectedDetail.value = null; legacyMlModel.value = null; detailVisible.value = true; detailLoading.value = true
  try {
    selectedDetail.value = await fetchOperatorApprovalDetail(row.approval.id)
    const payload = parseObject(selectedDetail.value?.implementationPayload)
    if (row.operatorType === 'ML' && payload.modelId) {
      try { legacyMlModel.value = await fetchMiningModel(payload.modelId) } catch { legacyMlModel.value = null }
    }
  }
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
function parseObject(value) { if (!value) return {}; if (typeof value === 'object') return value; try { return JSON.parse(value) } catch { return {} } }
const mlReview = computed(() => ({ ...legacyMlModel.value, ...parseObject(selectedDetail.value?.implementationPayload) }))
const mlMetrics = computed(() => ({ ...parseObject(legacyMlModel.value?.metrics), ...parseObject(mlReview.value.metrics), ...parseObject(legacyMlModel.value?.validationMetrics), ...parseObject(mlReview.value.validationMetrics) }))
const mlMetricEntries = computed(() => Object.entries(mlMetrics.value).filter(([, value]) => typeof value === 'number').slice(0, 10))
const mlQualityLabel = computed(() => ({ evaluation_passed: '质量门禁通过', approved: '质量门禁通过', evaluation_failed: '质量门禁未通过', drift_warning: '漂移预警', drift_critical: '漂移阻断' }[mlReview.value.evaluationStatus] || '待审批确认'))
const mlQualityType = computed(() => ({ evaluation_passed: 'success', approved: 'success', evaluation_failed: 'danger', drift_warning: 'warning', drift_critical: 'danger' }[mlReview.value.evaluationStatus] || 'warning'))
const modelTypeLabel = type => ({ binary_classification: '二分类', multiclass_classification: '多分类', classification: '分类', regression: '回归', clustering: '聚类', time_series: '时间序列' }[type] || type || '—')
const validationLabel = mode => ({ cv: '交叉验证', oos: '独立样本外验证', temporal: '时间窗口验证', train_test: '随机训练/测试切分' }[mode] || mode || '—')
const metricLabel = key => ({ test_balanced_accuracy: '平衡准确率', test_r2: '测试集 R²', risk_recall: '风险类召回率', pr_auc: 'PR-AUC', brier_score: 'Brier 分数', overfitting_gap: '过拟合差距', cv_std: '交叉验证波动', holdout_test_size: '测试样本数' }[key] || key.replaceAll('_', ' '))
const metricValue = value => Math.abs(value) <= 1 ? Number(value).toFixed(4) : Number(value).toLocaleString('zh-CN')

onMounted(async () => { capability.value = await fetchOperatorApprovalCapability(); await load() })
</script>

<style scoped>
.approval-center { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; padding: 16px 18px 18px; overflow: hidden; background: #f5f7fa; }
.approval-toolbar { display: flex; align-items: flex-start; justify-content: flex-end; gap: 16px; margin-bottom: 14px; }
.approval-toolbar h3 { margin: 0 0 4px; font-size: var(--font-lg); }
.approval-toolbar p { margin: 0; color: var(--text-muted); font-size: var(--font-sm); }
.toolbar-actions { display: flex; gap: 8px; }
.toolbar-actions .el-select { width: 130px; }
.approval-stats { display: flex; gap: 10px; margin-bottom: 14px; }
.approval-stats div { min-width: 112px; padding: 10px 14px; border: 1px solid #e4e8ef; border-radius: 8px; background: var(--surface); }
.approval-stats strong { margin-right: 8px; font-size: 20px; }
.approval-stats span, .el-table small { display: block; color: var(--text-muted); font-size: 12px; }
.el-table strong { display: block; }
.review-target { display: flex; flex-direction: column; gap: 4px; margin-bottom: 16px; padding: 12px; border-radius: var(--radius-md); background: var(--bg); }
.review-target span { color: var(--text-muted); font-size: 12px; }
code { word-break: break-all; font-size: 12px; }
.immutable-tip { margin-top: 16px; }
.ml-review { margin-top: 16px; padding: 14px; border: 1px solid #dbe7f5; border-radius: 9px; background: #f7fbff; }
.ml-review-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.ml-review-title h4 { margin: 0 0 4px; font-size: 14px; }.ml-review-title p { margin: 0; color: var(--text-muted); font-size: 11px; }
.ml-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 7px; margin-top: 10px; }
.ml-metrics div { min-width: 0; padding: 9px; border: 1px solid #e1e9f3; border-radius: 7px; background: #fff; }.ml-metrics span,.ml-metrics strong { display: block; }.ml-metrics span { overflow: hidden; color: var(--text-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.ml-metrics strong { margin-top: 4px; font-size: 14px; }
.version-content { margin-top: 16px; }
pre { max-height: 420px; overflow: auto; margin: 0; padding: 10px; border-radius: 6px; background: #101828; color: #d0d5dd; white-space: pre-wrap; word-break: break-all; font: 12px/1.55 ui-monospace, SFMono-Regular, Consolas, monospace; }
.schema-block + .schema-block { margin-top: 12px; }
.schema-block > span { display: block; margin-bottom: 5px; color: var(--text-muted); font-size: 12px; }
.approval-list-shell { min-height: 0; flex: 1; overflow: hidden; border: 1px solid #e4e8ef; border-radius: 8px; background: #fff; }
.operator-table { height: 100%; }
.operator-card-list { display: none; }
.operator-name { min-width: 0; display: flex; align-items: center; gap: 10px; }
.operator-name > span { width: 34px; height: 34px; display: grid; place-items: center; flex-shrink: 0; border-radius: 7px; color: #2468f2; background: #edf3ff; font-size: 10px; font-weight: 700; }
.operator-name > div { min-width: 0; }.operator-name strong, .operator-name small { display: block; }.operator-name small { overflow: hidden; margin-top: 3px; color: #86909c; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1080px) { .approval-toolbar { align-items: stretch; flex-direction: column; }.toolbar-actions { justify-content: flex-end; }.approval-stats { display: grid; grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 1180px) { .operator-table { display: none; }.approval-list-shell { overflow: auto; }.operator-card-list { display: grid; gap: 10px; padding: 12px; }.operator-card { padding: 13px; border: 1px solid #e4e8ef; border-radius: 8px; }.operator-card-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.operator-card dl { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 13px 0; }.operator-card dt { color: #86909c; font-size: 10px; }.operator-card dd { overflow: hidden; margin: 4px 0 0; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.operator-card-actions { border-top: 1px solid #edf0f5; padding-top: 7px; } }
@media (max-width: 600px) { .approval-center { padding: 12px; overflow: auto; }.approval-stats { gap: 7px; }.approval-stats div { min-width: 0; padding: 10px; }.operator-card dl { grid-template-columns: 1fr 1fr; } }
</style>
