<template>
  <el-drawer
    v-model="visible"
    title="系统监控"
    direction="rtl"
    size="480px"
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
            <span class="stat-value">{{ stats.totalTokens?.toLocaleString() || 0 }}</span>
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
        </div>
      </div>

      <div class="stat-section">
        <h4>模型使用</h4>
        <div v-for="(usage, model) in stats.modelUsage" :key="model" class="model-row">
          <span class="model-name">{{ model }}</span>
          <div class="model-stats">
            <el-tag size="small" effect="plain">{{ usage.callCount }} 次调用</el-tag>
            <span class="stat-mini">{{ usage.inputTokens?.toLocaleString() }} in / {{ usage.outputTokens?.toLocaleString() }} out</span>
          </div>
        </div>
        <div v-if="!stats.modelUsage || Object.keys(stats.modelUsage).length === 0" class="empty-hint">
          暂无模型调用记录
        </div>
      </div>

      <div class="stat-section">
        <h4>工具调用</h4>
        <el-table :data="stats.toolMetrics || []" size="small" stripe>
          <el-table-column prop="toolName" label="工具" />
          <el-table-column prop="totalCalls" label="调用次数" width="80" />
          <el-table-column prop="successRate" label="成功率" width="80" />
          <el-table-column prop="avgDurationMs" label="平均耗时(ms)" width="100" />
        </el-table>
      </div>

      <div class="stat-section">
        <h4>每日摘要</h4>
        <el-table :data="dailyRows" size="small" stripe>
          <el-table-column prop="date" label="日期" />
          <el-table-column prop="conversationCount" label="对话数" width="70" />
          <el-table-column prop="totalTokens" label="Token" width="90" />
          <el-table-column prop="totalCost" label="成本" width="70" />
        </el-table>
      </div>

      <div class="stat-actions">
        <el-button size="small" @click="refresh">刷新</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { fetchAdminStats, fetchAdminSessions } from '../api'

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
  if (v) await refresh()
})

async function refresh() {
  loading.value = true
  try {
    const [s, sess] = await Promise.all([fetchAdminStats(), fetchAdminSessions()])
    stats.value = s || {}
    sessions.value = sess || {}
  } catch (e) {
    console.error('Failed to load admin stats:', e)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.admin-stats {
  padding: 0 4px;
}
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
  text-align: center;
  padding: var(--space-md) 0;
}
</style>
