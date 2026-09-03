<template>
  <div class="storage-governance" v-loading="loading">
    <div class="governance-head">
      <div><h3>存储、归档与运行监控</h3><p>热数据受容量门禁保护；归档明细经压缩和摘要校验后可恢复。</p></div>
      <el-button :loading="runningRetention" @click="runRetention">立即执行保留扫描</el-button>
      <el-button type="primary" plain @click="load">刷新</el-button>
    </div>

    <div v-if="data.alerts?.length" class="alert-list">
      <el-alert v-for="item in data.alerts" :key="item.title + item.detail" :closable="false" show-icon
        :type="item.severity === 'critical' ? 'error' : 'warning'" :title="item.title" :description="item.detail" />
    </div>

    <div class="summary-grid">
      <div class="summary-card"><strong>{{ bytes(data.summary?.hotBytes) }}</strong><span>热数据</span></div>
      <div class="summary-card"><strong>{{ bytes(data.summary?.archiveBytes) }}</strong><span>压缩归档</span></div>
      <div class="summary-card"><strong>{{ data.summary?.activeOutputs || 0 }}</strong><span>活跃输出</span></div>
      <div class="summary-card"><strong>{{ data.summary?.archivedOutputs || 0 }}</strong><span>已归档输出</span></div>
      <div class="summary-card"><strong>{{ data.summary?.activeReplays || 0 }}</strong><span>活跃回放</span></div>
      <div class="summary-card"><strong>{{ data.runs?.expiredLeases || 0 }}</strong><span>过期租约</span></div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="策略与容量" name="capacity">
        <el-form :model="policyForm" inline class="policy-form">
          <el-form-item label="输出保留"><el-input-number v-model="policyForm.outputRetentionDays" :min="1" :max="3650" /><span>天</span></el-form-item>
          <el-form-item label="回放保留"><el-input-number v-model="policyForm.replayRetentionDays" :min="1" :max="3650" /><span>天</span></el-form-item>
          <el-form-item label="每用户热配额"><el-input-number v-model="policyForm.hotQuotaGb" :min="0.01" :max="1048576" :precision="2" /><span>GB</span></el-form-item>
          <el-form-item label="每用户归档配额"><el-input-number v-model="policyForm.archiveQuotaGb" :min="0.01" :max="1048576" :precision="2" /><span>GB</span></el-form-item>
          <el-form-item label="告警阈值"><el-input-number v-model="policyForm.warningPercent" :min="50" :max="100" /><span>%</span></el-form-item>
          <el-form-item><el-switch v-model="policyForm.autoArchiveEnabled" active-text="自动归档" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="savingPolicy" @click="savePolicy">保存策略</el-button></el-form-item>
        </el-form>
        <el-table :data="data.usages || []" stripe size="small" max-height="420">
          <el-table-column prop="ownerUserId" label="用户" width="100" />
          <el-table-column label="热容量" min-width="260"><template #default="{ row }"><el-progress :percentage="ratio(row.hotBytes, data.policy?.hotQuotaBytesPerUser)" /><small>{{ bytes(row.hotBytes) }} / {{ bytes(data.policy?.hotQuotaBytesPerUser) }}</small></template></el-table-column>
          <el-table-column label="归档容量" min-width="260"><template #default="{ row }"><el-progress :percentage="ratio(row.archiveBytes, data.policy?.archiveQuotaBytesPerUser)" /><small>{{ bytes(row.archiveBytes) }} / {{ bytes(data.policy?.archiveQuotaBytesPerUser) }}</small></template></el-table-column>
          <el-table-column label="构成" min-width="180"><template #default="{ row }">输出 {{ bytes(row.outputHotBytes) }} · 回放 {{ bytes(row.replayHotBytes) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="热数据" name="hot">
        <h4>最近输出产物</h4>
        <el-table :data="data.outputs || []" stripe size="small" max-height="280">
          <el-table-column prop="id" label="ID" width="70" /><el-table-column label="名称" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ outputTitle(row) }}</template></el-table-column>
          <el-table-column prop="ownerUserId" label="用户" width="80" /><el-table-column prop="outputKind" label="类型" width="85" />
          <el-table-column label="容量" width="100"><template #default="{ row }">{{ bytes(row.payloadBytes) }}</template></el-table-column>
          <el-table-column label="保留至" width="165"><template #default="{ row }">{{ dateTime(row.retentionUntil) }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="warning" @click="archiveOutput(row)">归档</el-button></template></el-table-column>
        </el-table>
        <h4>最近节点回放</h4>
        <el-table :data="data.replays || []" stripe size="small" max-height="280">
          <el-table-column prop="id" label="ID" width="70" /><el-table-column prop="replayNo" label="回放编号" min-width="190" show-overflow-tooltip />
          <el-table-column prop="ownerUserId" label="用户" width="80" /><el-table-column prop="nodeId" label="节点" min-width="120" />
          <el-table-column label="容量" width="100"><template #default="{ row }">{{ bytes(row.payloadBytes) }}</template></el-table-column>
          <el-table-column label="保留至" width="165"><template #default="{ row }">{{ dateTime(row.retentionUntil) }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="warning" @click="archiveReplay(row)">归档</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="归档记录" name="archives">
        <el-table :data="data.archives || []" stripe size="small" max-height="620">
          <el-table-column prop="id" label="ID" width="70" /><el-table-column prop="targetType" label="对象" width="140" /><el-table-column prop="targetId" label="对象 ID" width="90" />
          <el-table-column prop="ownerUserId" label="用户" width="80" /><el-table-column prop="state" label="状态" width="95"><template #default="{ row }"><el-tag :type="row.state==='READY'?'warning':'info'">{{ row.state==='READY'?'可恢复':'已恢复' }}</el-tag></template></el-table-column>
          <el-table-column label="压缩" width="150"><template #default="{ row }">{{ bytes(row.originalBytes) }} → {{ bytes(row.storedBytes) }}</template></el-table-column>
          <el-table-column prop="reason" label="原因" min-width="170" show-overflow-tooltip /><el-table-column label="归档时间" width="165"><template #default="{ row }">{{ dateTime(row.archivedAt) }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.state==='READY'" link type="primary" @click="restore(row)">恢复</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="运行监控" name="runs">
        <p class="monitor-note">状态分布基于最近 {{ data.runs?.statusWindowSize || 0 }} 条运行；租约、心跳和排队检查覆盖当前最多 500 条活动运行<span v-if="data.runs?.activeSampleTruncated">（当前活动运行已超过监控窗口）</span>。</p>
        <div class="status-tags"><el-tag v-for="(count,status) in data.runs?.statuses || {}" :key="status" :type="statusType(status)">{{ status }} {{ count }}</el-tag></div>
        <el-table :data="data.runs?.recentRuns || []" stripe size="small" max-height="620">
          <el-table-column prop="id" label="运行" width="75" /><el-table-column prop="flowVersionId" label="流程版本" width="100" /><el-table-column prop="ownerUserId" label="用户" width="80" />
          <el-table-column prop="status" label="状态" width="105"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template></el-table-column>
          <el-table-column prop="attemptNo" label="尝试" width="65" /><el-table-column prop="recoveryCount" label="恢复" width="65" />
          <el-table-column label="心跳" width="165"><template #default="{ row }">{{ dateTime(row.heartbeatAt) }}</template></el-table-column><el-table-column label="租约到期" width="165"><template #default="{ row }">{{ dateTime(row.leaseExpiresAt) }}</template></el-table-column>
          <el-table-column label="创建时间" width="165"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { archiveNodeReplay, archiveOutputArtifact, fetchStorageGovernance, restoreStorageArchive, runStorageRetention, updateStoragePolicy } from '../api/orchestration.js'

const data = ref({}), loading = ref(false), savingPolicy = ref(false), runningRetention = ref(false), tab = ref('capacity')
const policyForm = reactive({ outputRetentionDays: 90, replayRetentionDays: 30, hotQuotaGb: 1, archiveQuotaGb: 5, warningPercent: 80, autoArchiveEnabled: true })
const GB = 1024 ** 3

async function load() {
  loading.value = true
  try { data.value = await fetchStorageGovernance() || {}; fillPolicy(data.value.policy) } finally { loading.value = false }
}
function fillPolicy(policy) {
  if (!policy) return
  Object.assign(policyForm, { outputRetentionDays: policy.outputRetentionDays, replayRetentionDays: policy.replayRetentionDays,
    hotQuotaGb: Number(policy.hotQuotaBytesPerUser || 0) / GB, archiveQuotaGb: Number(policy.archiveQuotaBytesPerUser || 0) / GB,
    warningPercent: policy.warningPercent, autoArchiveEnabled: policy.autoArchiveEnabled === 1 })
}
async function savePolicy() {
  savingPolicy.value = true
  try {
    await updateStoragePolicy({ outputRetentionDays: policyForm.outputRetentionDays, replayRetentionDays: policyForm.replayRetentionDays,
      hotQuotaBytesPerUser: Math.round(policyForm.hotQuotaGb * GB), archiveQuotaBytesPerUser: Math.round(policyForm.archiveQuotaGb * GB),
      warningPercent: policyForm.warningPercent, autoArchiveEnabled: policyForm.autoArchiveEnabled })
    ElMessage.success('存储与保留策略已保存'); await load()
  } finally { savingPolicy.value = false }
}
async function runRetention() { runningRetention.value = true; try { data.value = await runStorageRetention(); fillPolicy(data.value.policy); ElMessage.success('保留扫描已执行') } finally { runningRetention.value = false } }
async function archiveOutput(row) { if (!await confirmed(`归档输出 #${row.id}？明细会迁移到压缩归档，可稍后恢复。`)) return; await archiveOutputArtifact(row.id, '管理员从治理中心手动归档'); ElMessage.success('输出已归档'); await load() }
async function archiveReplay(row) { if (!await confirmed(`归档节点回放 #${row.id}？`)) return; await archiveNodeReplay(row.id, '管理员从治理中心手动归档'); ElMessage.success('回放已归档'); await load() }
async function restore(row) { if (!await confirmed(`恢复归档 #${row.id}？恢复前会检查用户热容量。`)) return; await restoreStorageArchive(row.id); ElMessage.success('归档已恢复'); await load() }
async function confirmed(message) { try { await ElMessageBox.confirm(message, '存储治理确认', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }); return true } catch { return false } }
function outputTitle(row) { try { const spec = typeof row.contentSpec === 'string' ? JSON.parse(row.contentSpec) : row.contentSpec; return spec?.title || spec?.sheetName || `输出 #${row.id}` } catch { return `输出 #${row.id}` } }
function bytes(value) { let size = Number(value || 0), unit = 0; const units = ['B','KB','MB','GB','TB']; while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++ } return `${size.toFixed(unit > 1 ? 1 : 0)} ${units[unit]}` }
function ratio(value, total) { return total > 0 ? Math.min(100, Math.round(Number(value || 0) / Number(total) * 100)) : 0 }
function dateTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
function statusType(status) { return ({ SUCCESS:'success', RUNNING:'warning', COMMITTING:'warning', FAILED:'danger', TIMED_OUT:'danger', CANCELED:'info', QUEUED:'info' }[status] || 'info') }
onMounted(load)
</script>

<style scoped>
.storage-governance{height:100%;overflow:auto;padding:18px 22px}.governance-head{display:flex;align-items:center;gap:10px;margin-bottom:14px}.governance-head>div{flex:1}.governance-head h3{margin:0}.governance-head p,.monitor-note{margin:4px 0 10px;color:var(--text-muted);font-size:var(--font-sm)}.alert-list{display:flex;flex-direction:column;gap:7px;margin-bottom:12px}.summary-grid{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;margin-bottom:16px}.summary-card{display:flex;flex-direction:column;padding:13px;border:1px solid var(--border-light);border-radius:9px;background:var(--surface)}.summary-card strong{font-size:20px;color:var(--brand-primary)}.summary-card span,.policy-form span,.el-table small{color:var(--text-muted);font-size:12px}.policy-form{padding:12px;border:1px solid var(--border-light);border-radius:9px;margin-bottom:14px}.policy-form :deep(.el-form-item){margin-bottom:10px}.policy-form :deep(.el-form-item__content){gap:5px}.storage-governance h4{margin:14px 0 8px;color:var(--text-secondary)}.status-tags{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}@media(max-width:1100px){.summary-grid{grid-template-columns:repeat(3,1fr)}}
</style>
