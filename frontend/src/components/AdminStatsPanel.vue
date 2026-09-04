<template>
  <el-drawer
    v-model="visible"
    title="系统监控"
    direction="rtl"
    size="900px"
    @close="$emit('close')"
  >
    <div v-if="loading" style="text-align: center; padding: 40px;">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <p>加载统计数据...</p>
    </div>

    <div v-else class="admin-stats">
      <div class="stat-section">
        <h4>总览</h4>
        <div class="stat-grid">
          <div class="stat-card">
            <span class="stat-value">{{ totalTokens.toLocaleString() }}</span>
            <span class="stat-label">总 Token 数</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{ stats.totalCost?.toFixed(4) || '0' }}</span>
            <span class="stat-label">总成本 (USD)</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{ sessions.activeConversations || 0 }}</span>
            <span class="stat-label">活跃对话</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{ monitor.business?.models || 0 }}</span>
            <span class="stat-label">模型 / {{ monitor.business?.pipelines || 0 }} 条流程</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{ monitor.business?.users || 0 }}</span>
            <span class="stat-label">系统用户</span>
          </div>
          <div class="stat-card">
            <span class="stat-value">{{ monitor.business?.algorithms || 0 }}</span>
            <span class="stat-label">算法模板</span>
          </div>
        </div>
      </div>

      <el-alert
        title="权限范围：仅具备系统监控权限的用户可查看全局对话、操作、执行和资源监控数据"
        type="info" :closable="false" show-icon class="scope-alert"
      />

      <el-tabs v-model="activeTab" class="monitor-tabs">
        <el-tab-pane label="资源与服务" name="resources">
          <div class="stat-section">
            <h4>告警</h4>
            <div v-if="monitor.alerts?.length" class="alert-list">
              <el-alert v-for="item in monitor.alerts" :key="item.title + item.detail"
                :title="item.title" :description="item.detail"
                :type="item.severity === 'critical' ? 'error' : 'warning'" :closable="false" show-icon />
            </div>
            <el-empty v-else description="当前无活动告警" :image-size="60" />
          </div>

          <div class="stat-section">
            <h4>服务健康</h4>
            <div class="health-grid">
              <div v-for="service in monitor.services || []" :key="service.name" class="health-card">
                <span class="health-dot" :class="service.status.toLowerCase()"></span>
                <div><strong>{{ service.name }}</strong><small>{{ service.detail }}</small></div>
                <el-tag :type="service.status === 'UP' ? 'success' : 'danger'" size="small">{{ service.status }}</el-tag>
              </div>
            </div>
          </div>

          <div class="stat-section">
            <h4>运行资源</h4>
            <div class="resource-row">
              <span>JVM 堆内存</span><el-progress :percentage="ratio(monitor.runtime?.heapUsed, monitor.runtime?.heapMax)" />
              <small>{{ bytes(monitor.runtime?.heapUsed) }} / {{ bytes(monitor.runtime?.heapMax) }}</small>
            </div>
            <div v-if="monitor.runtime?.physicalMemoryTotal" class="resource-row">
              <span>物理内存</span><el-progress :percentage="ratio(monitor.runtime?.physicalMemoryUsed, monitor.runtime?.physicalMemoryTotal)" />
              <small>{{ bytes(monitor.runtime?.physicalMemoryUsed) }} / {{ bytes(monitor.runtime?.physicalMemoryTotal) }}</small>
            </div>
            <div class="resource-row">
              <span>工作磁盘</span><el-progress :percentage="ratio(monitor.runtime?.diskUsed, monitor.runtime?.diskTotal)" />
              <small>{{ bytes(monitor.runtime?.diskUsed) }} / {{ bytes(monitor.runtime?.diskTotal) }}</small>
            </div>
            <div class="runtime-meta">
              <span>进程 CPU {{ cpu(monitor.runtime?.processCpuPercent) }}</span>
              <span>系统 CPU {{ cpu(monitor.runtime?.systemCpuPercent) }}</span>
              <span>线程 {{ monitor.runtime?.threadCount || 0 }}</span>
              <span>运行 {{ duration(monitor.runtime?.uptimeMs) }}</span>
            </div>
          </div>

          <div class="stat-section">
            <h4>训练资源与任务</h4>
            <div class="training-statuses">
              <el-tag v-for="item in monitor.trainingStatuses || []" :key="item.status" :type="statusType(item.status)" effect="plain">
                {{ statusLabel(item.status) }} {{ item.count }}
              </el-tag>
              <span v-if="!monitor.trainingStatuses?.length" class="empty-inline">暂无训练记录</span>
            </div>
            <el-table :data="monitor.trainingResources?.recent || []" size="small" stripe max-height="280">
              <el-table-column prop="resource_name" label="模型" min-width="140" show-overflow-tooltip />
              <el-table-column prop="status" label="状态" width="80"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
              <el-table-column prop="progress_percent" label="进度" width="75"><template #default="{ row }">{{ row.progress_percent ?? 0 }}%</template></el-table-column>
              <el-table-column prop="current_stage" label="阶段" width="120" show-overflow-tooltip />
              <el-table-column prop="execution_time_ms" label="耗时" width="90"><template #default="{ row }">{{ ms(row.execution_time_ms) }}</template></el-table-column>
            </el-table>
            <el-alert v-if="monitor.trainingResources?.telemetryNote" :title="monitor.trainingResources.telemetryNote" type="info" :closable="false" show-icon class="telemetry-note" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="对话与 Token" name="conversations">
          <EChartsRenderer v-if="monitor.trends?.length" title="Token 与对话操作历史趋势" :option="trendOption" />
          <div class="stat-section table-section">
            <h4>对话历史</h4>
            <el-table :data="monitor.conversationHistory || []" size="small" stripe max-height="430">
              <el-table-column prop="conversation_id" label="ID" width="70" />
              <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
              <el-table-column prop="user_id" label="用户" width="80" />
              <el-table-column prop="message_count" label="消息" width="70" />
              <el-table-column prop="total_tokens" label="Token" width="100"><template #default="{ row }">{{ Number(row.total_tokens || 0).toLocaleString() }}</template></el-table-column>
              <el-table-column prop="last_activity" label="最后活动" width="165"><template #default="{ row }">{{ dateTime(row.last_activity) }}</template></el-table-column>
            </el-table>
          </div>
          <div class="stat-section">
            <h4>模型 Token 使用</h4>
            <div v-for="(usage, model) in stats.modelUsage" :key="model" class="model-row">
              <span class="model-name">{{ model }}</span>
              <div class="model-stats"><el-tag size="small" effect="plain">{{ usage.callCount }} 次调用</el-tag><span class="stat-mini">{{ usage.inputTokens?.toLocaleString() }} in / {{ usage.outputTokens?.toLocaleString() }} out</span></div>
            </div>
          </div>
        </el-tab-pane>

        <el-tab-pane label="操作历史" name="operations">
          <el-table :data="monitor.operationHistory || []" size="small" stripe max-height="650">
            <el-table-column prop="created_at" label="时间" width="165"><template #default="{ row }">{{ dateTime(row.created_at) }}</template></el-table-column>
            <el-table-column prop="user_id" label="用户" width="70" />
            <el-table-column prop="conversation_id" label="对话" width="70" />
            <el-table-column prop="question" label="操作/问题" min-width="220" show-overflow-tooltip />
            <el-table-column prop="status" label="状态" width="80" />
            <el-table-column prop="total_tokens" label="Token" width="80" />
            <el-table-column prop="execution_time_ms" label="耗时" width="90"><template #default="{ row }">{{ ms(row.execution_time_ms) }}</template></el-table-column>
            <el-table-column prop="trace_id" label="Trace ID" min-width="160" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="执行记录" name="executions">
          <el-table :data="monitor.executionRecords || []" size="small" stripe max-height="650">
            <el-table-column prop="created_at" label="时间" width="165"><template #default="{ row }">{{ dateTime(row.created_at) }}</template></el-table-column>
            <el-table-column prop="execution_type" label="类型" width="120" />
            <el-table-column prop="resource_name" label="资源" min-width="150" show-overflow-tooltip />
            <el-table-column prop="user_id" label="用户" width="70" />
            <el-table-column prop="status" label="状态" width="90"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="current_stage" label="阶段" width="120" show-overflow-tooltip />
            <el-table-column prop="execution_time_ms" label="耗时" width="90"><template #default="{ row }">{{ ms(row.execution_time_ms) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="链路与错误" name="observability">
          <div class="stat-section">
            <h4>调用链路摘要</h4>
            <el-table :data="monitor.traces || []" size="small" stripe max-height="300">
              <el-table-column prop="trace_id" label="Trace ID" min-width="180" show-overflow-tooltip />
              <el-table-column prop="conversation_id" label="对话" width="70" />
              <el-table-column prop="user_id" label="用户" width="70" />
              <el-table-column prop="event_count" label="事件" width="65" />
              <el-table-column prop="duration_ms" label="耗时" width="90"><template #default="{ row }">{{ ms(row.duration_ms) }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="80" />
            </el-table>
          </div>
          <div class="stat-section">
            <h4>错误聚合</h4>
            <el-table :data="monitor.errors || []" size="small" stripe max-height="250">
              <el-table-column prop="error" label="错误类型" min-width="420" show-overflow-tooltip />
              <el-table-column prop="count" label="次数" width="80" />
            </el-table>
          </div>
          <div class="stat-section">
            <h4>工具调用</h4>
            <el-table :data="stats.toolMetrics || []" size="small" stripe>
              <el-table-column prop="toolName" label="工具" />
              <el-table-column prop="totalCalls" label="调用次数" width="90" />
              <el-table-column prop="successRate" label="成功率" width="90" />
              <el-table-column prop="avgDurationMs" label="平均耗时(ms)" width="120" />
            </el-table>
          </div>
        </el-tab-pane>
      </el-tabs>

      <div class="stat-actions">
        <span class="updated-at">{{ monitor.capturedAt ? `更新于 ${new Date(monitor.capturedAt).toLocaleTimeString()}` : '' }}</span>
        <el-switch v-model="autoRefresh" active-text="自动刷新" />
        <el-button size="small" @click="refresh">立即刷新</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { fetchAdminStats, fetchAdminSessions, fetchSystemMonitor } from '../api'
import EChartsRenderer from './EChartsRenderer.vue'

const props = defineProps({
  visible: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'update:visible'])

const visible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
})

const loading = ref(false)
const stats = ref({})
const sessions = ref({})
const monitor = ref({})
const autoRefresh = ref(true)
const activeTab = ref('resources')
let refreshTimer = null

const dailyRows = computed(() => {
  const summaries = stats.value.dailySummaries
  if (!summaries) return []
  return Object.entries(summaries)
    .map(([date, s]) => ({
      date,
      conversationCount: s.conversationCount,
      totalTokens: s.totalTokens?.toLocaleString(),
      totalCost: s.totalCost?.toFixed(4)
    }))
    .sort((a, b) => b.date.localeCompare(a.date))
})

watch(() => props.visible, async (v) => {
  clearRefreshTimer()
  if (v) {
    await refresh()
    refreshTimer = window.setInterval(() => { if (autoRefresh.value) refresh(false) }, 10000)
  }
})

const trendOption = computed(() => {
  const rows = monitor.value.trends || []
  return {
    tooltip: { trigger: 'axis' }, legend: { bottom: 0 },
    grid: { left: 55, right: 55, top: 25, bottom: 55 },
    xAxis: { type: 'category', data: rows.map(row => row.date?.slice(5)) },
    yAxis: [{ type: 'value', name: '次数' }, { type: 'value', name: 'Token' }],
    series: [
      { name: '操作', type: 'bar', data: rows.map(row => row.operations || 0) },
      { name: '训练', type: 'bar', data: rows.map(row => row.trainings || 0) },
      { name: '错误', type: 'line', data: rows.map(row => row.errors || 0), itemStyle: { color: '#f56c6c' } },
      { name: 'Token', type: 'line', yAxisIndex: 1, smooth: true, data: rows.map(row => row.tokens || 0) }
    ]
  }
})

const totalTokens = computed(() => {
  const persisted = Number(monitor.value.historyTotals?.queryTokens || monitor.value.historyTotals?.messageTokens || 0)
  return persisted || Number(stats.value.totalTokens || 0)
})

onBeforeUnmount(clearRefreshTimer)

function clearRefreshTimer() {
  if (refreshTimer) window.clearInterval(refreshTimer)
  refreshTimer = null
}

async function refresh(showLoading = true) {
  if (showLoading) loading.value = true
  try {
    const [s, sess, sys] = await Promise.all([fetchAdminStats(), fetchAdminSessions(), fetchSystemMonitor()])
    stats.value = s || {}
    sessions.value = sess || {}
    monitor.value = sys || {}
  } catch (e) {
    console.error('Failed to load admin stats:', e)
  } finally {
    if (showLoading) loading.value = false
  }
}

function ratio(used, total) {
  if (!used || !total || total <= 0) return 0
  return Math.min(100, Math.round(used / total * 100))
}
function bytes(value) {
  if (!value || value < 0) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let n = Number(value); let i = 0
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++ }
  return `${n.toFixed(i > 1 ? 1 : 0)} ${units[i]}`
}
function cpu(value) { return value == null || value < 0 ? '-' : `${Number(value).toFixed(1)}%` }
function ms(value) { return value == null ? '-' : value >= 1000 ? `${(value / 1000).toFixed(1)}s` : `${value}ms` }
function dateTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-' }
function duration(ms) {
  if (!ms) return '-'
  const totalMinutes = Math.floor(ms / 60000)
  const days = Math.floor(totalMinutes / 1440)
  const hours = Math.floor((totalMinutes % 1440) / 60)
  const minutes = totalMinutes % 60
  return `${days ? days + '天 ' : ''}${hours}小时 ${minutes}分`
}
function statusLabel(status) {
  return { queued: '排队', pending: '等待', running: '运行中', success: '成功', failed: '失败', canceled: '已取消' }[status] || status
}
function statusType(status) {
  return { success: 'success', running: 'warning', failed: 'danger', canceled: 'info' }[status] || 'info'
}
</script>

<style scoped>
.admin-stats {
  padding: 0 4px;
}
.scope-alert { margin-bottom: var(--space-md); }
.monitor-tabs { min-height: 560px; }
.table-section { margin-top: var(--space-md); }
.alert-list { display: flex; flex-direction: column; gap: 8px; }
.telemetry-note { margin-top: 10px; }
.monitor-tabs :deep(.chart-wrapper) { height: 280px; min-height: 250px; }
.stat-section {
  margin-bottom: var(--space-xl);
}
.stat-section h4 {
  font-size: var(--font-sm);
  color: var(--text-muted);
  margin: 0 0 var(--space-sm) 0;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border-lighter);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  font-weight: 600;
}
.stat-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-md);
}
.stat-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: var(--space-md) var(--space-lg);
  background: var(--surface);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  transition: all var(--transition-base);
}
.stat-card:hover {
  border-color: var(--brand-primary-light);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}
.stat-value {
  font-size: 26px;
  font-weight: 700;
  color: var(--brand-primary);
  line-height: 1.2;
  letter-spacing: -0.02em;
}
.stat-label {
  font-size: var(--font-sm);
  color: var(--text-muted);
  margin-top: 4px;
  font-weight: 500;
}
.model-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-sm) 0;
  border-bottom: 1px solid var(--border-lighter);
}
.model-row:last-child { border-bottom: none; }
.model-name {
  font-size: var(--font-md);
  font-weight: 500;
  color: var(--text-primary);
}
.model-stats {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.stat-mini {
  font-size: var(--font-xs);
  color: var(--text-muted);
  padding: 2px 8px;
  background: var(--bg);
  border-radius: var(--radius-pill);
}
.empty-hint {
  text-align: center;
  color: var(--text-muted);
  font-size: var(--font-md);
  padding: var(--space-xl);
}
.stat-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: var(--space-md) 0;
}
.health-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.health-card { display: flex; align-items: center; gap: 9px; border: 1px solid var(--border-light); border-radius: 8px; padding: 10px; }
.health-card > div { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.health-card small { color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.health-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--el-color-danger); }
.health-dot.up { background: var(--el-color-success); box-shadow: 0 0 0 4px var(--color-success-light); }
.resource-row { display: grid; grid-template-columns: 90px 1fr 120px; align-items: center; gap: 10px; margin-bottom: 10px; font-size: var(--font-sm); }
.resource-row small { color: var(--text-muted); text-align: right; }
.runtime-meta, .training-statuses { display: flex; flex-wrap: wrap; gap: 8px; }
.runtime-meta span { background: var(--bg); border-radius: 5px; padding: 5px 8px; font-size: var(--font-xs); color: var(--text-secondary); }
.empty-inline, .updated-at { color: var(--text-muted); font-size: var(--font-xs); }
</style>
