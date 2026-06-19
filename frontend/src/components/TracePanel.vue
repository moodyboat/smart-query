<template>
  <el-drawer
    v-model="visible"
    title="执行追踪"
    direction="rtl"
    size="520px"
    @close="$emit('close')"
  >
    <div v-if="loading" style="text-align: center; padding: 40px;">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
      <p>加载追踪数据...</p>
    </div>

    <div v-else-if="events.length === 0" style="text-align: center; padding: 40px; color: var(--text-muted);">
      <el-icon :size="40"><Document /></el-icon>
      <p>暂无追踪记录</p>
    </div>

    <div v-else class="trace-timeline">
      <div class="trace-summary">
        共 {{ events.length }} 个事件 &middot;
        <span v-if="totalDuration > 0">{{ (totalDuration / 1000).toFixed(1) }}s</span>
      </div>

      <el-timeline>
        <el-timeline-item
          v-for="(evt, idx) in events"
          :key="idx"
          :type="eventColor(evt)"
          :timestamp="evt.ts"
          placement="top"
        >
          <div class="trace-event">
            <div class="trace-header">
              <el-tag :type="eventTagType(evt)" size="small" effect="plain">
                {{ eventName(evt) }}
              </el-tag>
              <span v-if="evt.duration" class="trace-duration">
                {{ evt.duration }}ms
              </span>
            </div>

            <div v-if="evt.event === 'user_message'" class="trace-body">
              {{ truncate(evt.payload?.content, 200) }}
            </div>

            <div v-else-if="evt.event === 'thinking'" class="trace-body thinking">
              {{ truncate(evt.payload?.content, 300) }}
            </div>

            <div v-else-if="evt.event === 'sql_executing'" class="trace-body">
              <code>{{ truncate(evt.payload?.sql, 300) }}</code>
            </div>

            <div v-else-if="evt.event === 'sql_result'" class="trace-body">
              <span v-if="evt.payload?.rows">{{ evt.payload.rows }} 行</span>
              <span v-if="evt.payload?.error" class="trace-error">{{ evt.payload.error }}</span>
            </div>

            <div v-else-if="evt.event === 'python_executing'" class="trace-body">
              <code>{{ truncate(evt.payload?.code, 200) }}</code>
            </div>

            <div v-else-if="evt.event === 'tool_call'" class="trace-body">
              <strong>{{ evt.payload?.toolName }}</strong>
              <span v-if="evt.payload?.success === false" class="trace-error"> 失败</span>
              <span v-if="evt.payload?.duration" class="trace-duration">{{ evt.payload.duration }}ms</span>
            </div>

            <div v-else-if="evt.event === 'mining_model'" class="trace-body">
              <strong>{{ evt.payload?.action }}</strong>
              <span v-if="evt.payload?.modelName"> {{ evt.payload.modelName }}</span>
              <span v-if="evt.payload?.success === false" class="trace-error"> 失败</span>
            </div>

            <div v-else-if="evt.event === 'done'" class="trace-body">
              步骤: {{ evt.payload?.totalSteps }} &middot;
              Tokens: {{ evt.payload?.totalTokens }}
            </div>

            <div v-else-if="evt.event === 'span_start'" class="trace-body trace-span-start">
              <strong>{{ evt.payload?.name }}</strong>
              <span class="trace-duration"> 开始</span>
            </div>

            <div v-else-if="evt.event === 'span_end'" class="trace-body">
              <span :class="evt.payload?.status === 'success' ? 'trace-success' : 'trace-error'">
                {{ evt.payload?.status === 'success' ? '完成' : '失败' }}
              </span>
              <span v-if="evt.payload?.durationMs" class="trace-duration">
                {{ evt.payload.durationMs }}ms
              </span>
            </div>

            <div v-else-if="evt.event === 'error'" class="trace-body trace-error">
              {{ evt.payload?.message }}
            </div>
          </div>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { Loading, Document } from '@element-plus/icons-vue'
import { fetchConversationTraces } from '../api'

const props = defineProps({
  conversationId: { type: Number, default: null },
  visible: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'update:visible'])

const events = ref([])
const loading = ref(false)

const visible = computed({
  get: () => props.visible,
  set: (v) => emit('update:visible', v)
})

const totalDuration = computed(() => {
  if (events.value.length < 2) return 0
  const first = new Date(events.value[0].ts)
  const last = new Date(events.value[events.value.length - 1].ts)
  return last - first
})

watch(() => props.visible, async (v) => {
  if (v && props.conversationId) {
    loading.value = true
    try {
      const data = await fetchConversationTraces(props.conversationId)
      events.value = data.events || []
    } catch (e) {
      console.error('Failed to load traces:', e)
      events.value = []
    } finally {
      loading.value = false
    }
  }
})

function eventName(evt) {
  const map = {
    trace_start: '开始',
    span_start: 'Span 开始',
    span_end: 'Span 结束',
    user_message: '用户输入',
    thinking: '思考',
    sql_executing: 'SQL 执行',
    sql_result: 'SQL 结果',
    python_executing: 'Python 执行',
    python_progress: 'Python 输出',
    python_result: 'Python 结果',
    tool_call: '工具调用',
    mining_model: '挖掘模型',
    chart_generated: '图表生成',
    report_generated: '报告生成',
    dashboard_generated: '仪表盘生成',
    done: '完成',
    error: '错误'
  }
  return map[evt.event] || evt.event
}

function eventColor(evt) {
  if (evt.event === 'error') return 'danger'
  if (evt.event === 'done') return 'success'
  if (evt.event === 'span_end') return evt.payload?.status === 'success' ? 'success' : 'danger'
  if (evt.event === 'span_start') return 'primary'
  if (evt.event === 'sql_executing' || evt.event === 'python_executing') return 'primary'
  if (evt.event === 'user_message') return 'warning'
  return 'info'
}

function eventTagType(evt) {
  if (evt.event === 'error') return 'danger'
  if (evt.event === 'done') return 'success'
  if (evt.event === 'sql_executing') return ''
  if (evt.event === 'tool_call') return evt.payload?.success === false ? 'danger' : ''
  return 'info'
}

function truncate(str, max) {
  if (!str) return ''
  return str.length > max ? str.substring(0, max) + '...' : str
}
</script>

<style scoped>
.trace-timeline {
  padding: 0 4px;
}
.trace-summary {
  font-size: var(--font-md);
  color: var(--text-muted);
  margin-bottom: 16px;
}
.trace-event {
  font-size: var(--font-md);
}
.trace-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.trace-duration {
  font-size: var(--font-sm);
  color: var(--text-muted);
}
.trace-body {
  color: var(--text-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.trace-body code {
  background: var(--hover);
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  font-size: var(--font-sm);
  font-family: monospace;
}
.trace-body.thinking {
  color: var(--color-info);
  font-style: italic;
}
.trace-error { color: var(--color-danger); }
.trace-success { color: var(--color-success); }
.trace-span-start { color: var(--primary); }
</style>
