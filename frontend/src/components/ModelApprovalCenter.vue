<template>
  <section class="model-approval-center">
    <div class="approval-toolbar">
      <div class="toolbar-actions">
        <el-input v-model="searchQuery" clearable placeholder="搜索模型名称或编码" />
        <el-select v-model="statusFilter" placeholder="全部状态">
          <el-option label="全部状态" value="" />
          <el-option label="待审批" value="SUBMITTED" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已驳回" value="REJECTED" />
        </el-select>
        <el-button :icon="Refresh" circle plain :loading="loading" aria-label="刷新" @click="load" />
      </div>
    </div>

    <div class="approval-stats">
      <article><span class="stat-mark pending"><Clock /></span><div><strong>{{ counts.SUBMITTED || 0 }}</strong><small>待人工审批</small></div></article>
      <article><span class="stat-mark approved"><CircleCheck /></span><div><strong>{{ counts.APPROVED || 0 }}</strong><small>审批通过</small></div></article>
      <article><span class="stat-mark rejected"><Warning /></span><div><strong>{{ counts.REJECTED || 0 }}</strong><small>审批驳回</small></div></article>
    </div>

    <div class="approval-list-shell">
      <el-table :data="filteredApprovals" v-loading="loading" class="model-table" empty-text="暂无模型版本审批记录">
        <el-table-column label="模型 / 版本" min-width="230">
          <template #default="{ row }"><div class="model-name"><span>模型</span><div><strong>{{ row.modelName }}</strong><small>{{ row.modelCode }} · 版本 {{ row.versionNo }} · #{{ row.approval.flowVersionId }}</small></div></div></template>
        </el-table-column>
        <el-table-column label="组合结构" min-width="150"><template #default="{ row }"><span class="cell-stack"><strong>{{ row.nodeCount }} 个算子节点</strong><small>{{ row.edgeCount }} 条数据关系</small></span></template></el-table-column>
        <el-table-column label="版本摘要" min-width="160"><template #default="{ row }"><code>{{ shortHash(row.contentHash) }}</code></template></el-table-column>
        <el-table-column label="结构校验" width="105"><template #default="{ row }"><el-tag :type="row.structureValid ? 'success' : 'danger'" effect="plain">{{ row.structureValid ? '已通过' : '未通过' }}</el-tag></template></el-table-column>
        <el-table-column label="申请人" width="110"><template #default="{ row }">{{ row.requesterName || '—' }}</template></el-table-column>
        <el-table-column label="审批状态" width="110"><template #default="{ row }"><el-tag :type="statusType(row.approval.status)" effect="plain">{{ statusLabel(row.approval.status) }}</el-tag></template></el-table-column>
        <el-table-column label="申请时间" width="165"><template #default="{ row }">{{ formatTime(row.approval.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="145" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="showDetail(row)">版本详情</el-button><el-button v-if="row.reviewable" link type="warning" @click="openReview(row)">审批</el-button></template></el-table-column>
      </el-table>

      <div class="model-card-list">
        <article v-for="row in filteredApprovals" :key="row.approval.id" class="model-card">
          <div class="model-card-head"><div class="model-name"><span>模型</span><div><strong>{{ row.modelName }}</strong><small>{{ row.modelCode }} · 版本 {{ row.versionNo }}</small></div></div><el-tag :type="statusType(row.approval.status)" effect="plain">{{ statusLabel(row.approval.status) }}</el-tag></div>
          <dl><div><dt>组合结构</dt><dd>{{ row.nodeCount }} 节点 / {{ row.edgeCount }} 连线</dd></div><div><dt>结构校验</dt><dd>{{ row.structureValid ? '已通过' : '未通过' }}</dd></div><div><dt>申请人</dt><dd>{{ row.requesterName || '—' }}</dd></div><div><dt>申请时间</dt><dd>{{ formatTime(row.approval.createdAt) }}</dd></div></dl>
          <div class="model-card-actions"><el-button link type="primary" @click="showDetail(row)">版本详情</el-button><el-button v-if="row.reviewable" link type="warning" @click="openReview(row)">审批</el-button></div>
        </article>
        <el-empty v-if="!loading && !filteredApprovals.length" description="暂无模型版本审批记录" :image-size="72" />
      </div>
    </div>

    <el-drawer v-model="detailVisible" title="模型不可变版本详情" size="min(760px, 94vw)">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="selected && selectedDetail">
          <div class="detail-title"><span>模型</span><div><h3>{{ selected.modelName }}</h3><p>{{ selected.modelCode }} · 版本 {{ selected.versionNo }}</p></div><el-tag :type="statusType(selected.approval.status)" effect="plain">{{ statusLabel(selected.approval.status) }}</el-tag></div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="模型说明">{{ selected.modelDescription || '—' }}</el-descriptions-item>
            <el-descriptions-item label="版本摘要"><code>{{ selected.contentHash }}</code></el-descriptions-item>
            <el-descriptions-item label="结构规模">{{ selected.nodeCount }} 个固定算子节点，{{ selected.edgeCount }} 条数据关系</el-descriptions-item>
            <el-descriptions-item label="申请说明">{{ selected.approval.requestComment || '—' }}</el-descriptions-item>
            <el-descriptions-item label="审批人">{{ selected.reviewerName || '—' }}</el-descriptions-item>
            <el-descriptions-item label="审批意见">{{ selected.approval.reviewComment || '—' }}</el-descriptions-item>
          </el-descriptions>
          <el-alert class="boundary-tip" type="info" :closable="false" show-icon title="模型审批不会重新解释或修改算子内部实现；每个节点引用的都是已经过各自审批的固定算子版本。" />
          <section class="detail-section"><div class="section-title"><h4>固定算子节点</h4><el-tag effect="plain">{{ selectedDetail.nodes?.length || 0 }} 个</el-tag></div><div class="node-grid"><article v-for="node in selectedDetail.nodes || []" :key="node.id"><strong>{{ node.name || node.id }}</strong><small>节点 {{ node.id }}</small><code>算子版本 #{{ node.operatorVersionId }}</code></article></div><el-empty v-if="!selectedDetail.nodes?.length" description="暂无节点" :image-size="58" /></section>
          <section class="detail-section"><h4>结构验证报告</h4><pre>{{ pretty(selectedDetail.validationReport) }}</pre></section>
          <div v-if="selected.reviewable" class="drawer-actions"><el-button type="primary" @click="openReview(selected)">审批此模型版本</el-button></div>
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="reviewVisible" title="审批模型版本" width="min(500px, 92vw)">
      <div v-if="selected" class="review-target"><strong>{{ selected.modelName }} · 版本 {{ selected.versionNo }}</strong><span>{{ selected.nodeCount }} 个固定算子节点 · {{ selected.requesterName }} 提交</span></div>
      <el-form label-position="top"><el-form-item label="审批意见"><el-input v-model="reviewComment" type="textarea" :rows="4" placeholder="通过时可选；驳回时必须说明修改要求" /></el-form-item></el-form>
      <template #footer><el-button type="danger" :loading="saving" @click="review('REJECT')">驳回</el-button><el-button type="primary" :loading="saving" @click="review('APPROVE')">批准模型版本</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Clock, Refresh, Warning } from '@element-plus/icons-vue'
import { fetchModelApprovalCapability, fetchModelVersionApprovalDetail, fetchModelVersionApprovals, reviewModelVersionApproval } from '../api/orchestration.js'

const approvals = ref([]), capability = ref({ canReview: false }), loading = ref(false)
const searchQuery = ref(''), statusFilter = ref('')
const selected = ref(null), selectedDetail = ref(null), detailVisible = ref(false), detailLoading = ref(false)
const reviewVisible = ref(false), reviewComment = ref(''), saving = ref(false)
const counts = computed(() => approvals.value.reduce((result, row) => { result[row.approval.status] = (result[row.approval.status] || 0) + 1; return result }, {}))
const filteredApprovals = computed(() => approvals.value.filter(row => {
  const query = searchQuery.value.trim().toLowerCase()
  const matchesQuery = !query || [row.modelName, row.modelCode, row.modelDescription].some(value => String(value || '').toLowerCase().includes(query))
  return matchesQuery && (!statusFilter.value || row.approval.status === statusFilter.value)
}))

async function load() { loading.value = true; try { approvals.value = await fetchModelVersionApprovals() || [] } catch (error) { ElMessage.error(error.response?.data?.message || '加载模型版本审批失败') } finally { loading.value = false } }
async function showDetail(row) { selected.value = row; selectedDetail.value = null; detailVisible.value = true; detailLoading.value = true; try { selectedDetail.value = await fetchModelVersionApprovalDetail(row.approval.id) } catch (error) { ElMessage.error(error.response?.data?.message || '加载模型版本详情失败') } finally { detailLoading.value = false } }
function openReview(row) { selected.value = row; reviewComment.value = ''; reviewVisible.value = true }
async function review(decision) {
  if (decision === 'REJECT' && !reviewComment.value.trim()) { ElMessage.warning('驳回时必须填写修改原因'); return }
  saving.value = true
  try { await reviewModelVersionApproval(selected.value.approval.id, decision, reviewComment.value); reviewVisible.value = false; detailVisible.value = false; ElMessage.success(decision === 'APPROVE' ? '模型版本审批通过' : '模型版本已驳回'); await load() }
  catch (error) { ElMessage.error(error.response?.data?.message || '模型版本审批失败') }
  finally { saving.value = false }
}
const statusLabel = status => ({ SUBMITTED: '待审批', APPROVED: '已通过', REJECTED: '已驳回' }[status] || status)
const statusType = status => ({ SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger' }[status] || 'info')
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const shortHash = value => value ? `${String(value).slice(0, 12)}…` : '—'
const pretty = value => JSON.stringify(value || {}, null, 2)
onMounted(async () => { try { capability.value = await fetchModelApprovalCapability() || { canReview: false } } finally { await load() } })
</script>

<style scoped>
.model-approval-center{min-width:0;min-height:0;flex:1;display:flex;flex-direction:column;overflow:hidden;padding:16px 18px 18px;background:#f5f7fa}.approval-toolbar{display:flex;align-items:flex-start;justify-content:flex-end;gap:16px;margin-bottom:10px}.toolbar-actions{display:grid;grid-template-columns:220px 130px 34px;gap:8px}.approval-stats{display:grid;grid-template-columns:repeat(3,minmax(0,180px));gap:10px;margin-bottom:12px}.approval-stats article{display:flex;align-items:center;gap:11px;padding:11px 13px;border:1px solid #e4e8ef;border-radius:8px;background:#fff}.stat-mark{width:32px;height:32px;display:grid;place-items:center;border-radius:7px}.stat-mark :deep(svg){width:17px}.stat-mark.pending{color:#d47b00;background:#fff4e2}.stat-mark.approved{color:#00a870;background:#e8f8f3}.stat-mark.rejected{color:#e34d59;background:#fff0f1}.approval-stats strong,.approval-stats small{display:block}.approval-stats strong{font-size:20px;line-height:1}.approval-stats small{margin-top:4px;color:#86909c;font-size:10px}.approval-list-shell{min-height:0;flex:1;overflow:hidden;border:1px solid #e4e8ef;border-radius:8px;background:#fff}.model-table{height:100%}.model-name{display:flex;align-items:center;gap:10px}.model-name>span,.detail-title>span{min-width:38px;height:34px;display:grid;place-items:center;flex-shrink:0;padding:0 4px;border-radius:7px;color:#2468f2;background:#edf3ff;font-size:10px;font-weight:700}.model-name strong,.model-name small,.cell-stack strong,.cell-stack small{display:block}.model-name small,.cell-stack small{margin-top:3px;color:#86909c;font-size:10px}.model-card-list{display:none}.detail-title{display:flex;align-items:center;gap:11px;margin-bottom:16px}.detail-title>div{min-width:0;flex:1}.detail-title h3{margin:0;font-size:17px}.detail-title p{margin:4px 0 0;color:#86909c;font-size:11px}.detail-section{margin-top:20px}.detail-section h4,.section-title h4{margin:0 0 10px;font-size:14px}.section-title{display:flex;align-items:center;justify-content:space-between}.boundary-tip{margin-top:16px}.node-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.node-grid article{display:flex;min-width:0;flex-direction:column;gap:4px;padding:11px;border:1px solid #e4e8ef;border-radius:8px}.node-grid small{color:#86909c;font-size:10px}.node-grid code{color:#2468f2;font-size:10px}code{word-break:break-all;font-size:11px}pre{max-height:300px;overflow:auto;margin:0;padding:11px;border-radius:7px;background:#101828;color:#d0d5dd;white-space:pre-wrap;word-break:break-all;font:11px/1.5 ui-monospace,Consolas,monospace}.drawer-actions{display:flex;justify-content:flex-end;margin-top:20px;padding-top:16px;border-top:1px solid #edf0f5}.review-target{display:flex;flex-direction:column;gap:4px;margin-bottom:16px;padding:12px;border-radius:8px;background:#f5f7fa}.review-target span{color:#86909c;font-size:11px}
@media(max-width:1180px){.model-table{display:none}.approval-list-shell{overflow:auto}.model-card-list{display:grid;gap:10px;padding:12px}.model-card{padding:13px;border:1px solid #e4e8ef;border-radius:8px}.model-card-head{display:flex;align-items:center;justify-content:space-between;gap:10px}.model-card dl{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin:13px 0}.model-card dt{color:#86909c;font-size:10px}.model-card dd{overflow:hidden;margin:4px 0 0;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.model-card-actions{border-top:1px solid #edf0f5;padding-top:7px}}
@media(max-width:760px){.model-approval-center{padding:12px;overflow:auto}.approval-toolbar{align-items:stretch;flex-direction:column}.toolbar-actions{grid-template-columns:1fr 1fr 34px}.toolbar-actions .el-input{grid-column:1/-1}.scope-banner{align-items:flex-start}.approval-stats{grid-template-columns:repeat(3,1fr)}.node-grid{grid-template-columns:1fr}.model-card dl{grid-template-columns:1fr 1fr}}
</style>
