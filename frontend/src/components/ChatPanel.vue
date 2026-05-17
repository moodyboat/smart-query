<template>
  <section class="main-area">
    <header class="chat-header">
      <button v-if="showSidebarToggle" class="hamburger-btn" @click="emit('toggleSidebar')">☰</button>
      <span class="header-title">智能数据分析助手</span>
      <span v-if="connectionState === 'connecting'" class="conn-badge connecting">连接中...</span>
      <span v-else-if="connectionState === 'streaming'" class="conn-badge streaming">
        <span class="spinner-sm"></span>接收中
      </span>
      <span v-else-if="connectionState === 'error'" class="conn-badge error">连接中断</span>
      <span v-if="loading && stepInfo.total > 0" class="step-badge">
        步骤 {{ stepInfo.current }}/{{ stepInfo.total }}
      </span>
      <span v-if="cost > 0" class="cost-badge">Token: {{ totalTokens }}</span>
      <button v-if="conversationId" class="trace-btn" @click="traceVisible = true" title="查看执行追踪">
        <el-icon :size="16"><View /></el-icon>
      </button>
      <button class="trace-btn" @click="adminVisible = true" title="系统监控">
        <el-icon :size="16"><Monitor /></el-icon>
      </button>
    </header>

    <div class="messages-area" ref="messagesArea">
      <div v-if="messages.length === 0" class="welcome">
        <h3>欢迎使用智能问数</h3>
        <p class="welcome-desc">不只是查询 — 我可以帮你做完整的数据分析</p>
        <div class="welcome-cards">
          <div class="welcome-card">
            <div class="card-icon sql-icon">SQL</div>
            <div class="card-title">智能查询</div>
            <div class="card-desc">用自然语言查询数据库，自动生成 SQL</div>
          </div>
          <div class="welcome-card">
            <div class="card-icon py-icon">Py</div>
            <div class="card-title">数据挖掘</div>
            <div class="card-desc">Python 分析、建模、预测，支持迭代调试</div>
          </div>
          <div class="welcome-card">
            <div class="card-icon chart-icon">📊</div>
            <div class="card-title">可视化</div>
            <div class="card-desc">ECharts 图表、仪表盘大屏，自动筛选联动</div>
          </div>
          <div class="welcome-card">
            <div class="card-icon report-icon">📋</div>
            <div class="card-title">分析报告</div>
            <div class="card-desc">多表查询、计算分析、结构化报告生成</div>
          </div>
        </div>
        <div class="welcome-examples">
          <div class="example-label">试试问我:</div>
          <div
            v-for="ex in exampleQueries"
            :key="ex"
            class="example-item"
            :class="{ disabled: !conversationId || !dataSourceId }"
            @click="tryExample(ex)"
          >
            "{{ ex }}"
          </div>
        </div>
      </div>

      <MessageRow
        v-for="msg in messages"
        :key="msg._id"
        :msg="msg"
        :pendingCharts="pendingCharts"
        :updateChartOption="updateChartOption"
        :openDashboard="(id) => emit('openDashboard', id)"
        @retryPython="handleRetryPython"
        @retryConnection="retryConnection"
        @openMining="(modelId) => ui.openMining(modelId)"
      />

      <div v-if="showNoResponse" class="no-response-hint">
        <span class="no-response-icon">💬</span>
        <span>该对话暂无回复</span>
        <button class="retry-btn-inline" @click="retryLastMessage">重新发送</button>
      </div>
    </div>

    <div class="input-area">
      <div v-if="!conversationId || !dataSourceId" class="input-hint">
        {{ !dataSourceId ? '请先在左侧选择数据源' : '请新建或选择一个对话' }}
      </div>
      <div v-else class="input-row">
        <el-input
          v-model="inputText"
          placeholder="输入你的问题..."
          size="large"
          @keydown.enter.prevent="sendMessage"
          :disabled="loading"
          clearable
        />
        <el-button type="primary" size="large" :loading="loading" @click="sendMessage">
          发送
        </el-button>
      </div>
    </div>

    <TracePanel
      v-model:visible="traceVisible"
      :conversationId="conversationId"
    />
    <AdminStatsPanel v-model:visible="adminVisible" />
  </section>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onBeforeUnmount } from 'vue'
import { View, Monitor } from '@element-plus/icons-vue'
import MessageRow from './MessageRow.vue'
import TracePanel from './TracePanel.vue'
import AdminStatsPanel from './AdminStatsPanel.vue'
import { buildChatUrl, fetchReport } from '../api'
import { SSE_SAFETY_TIMEOUT_MS } from '../constants'
import { useMiningStore } from '../stores/mining'
import { useUIStore } from '../stores/ui'

const props = defineProps({
  conversationId: Number,
  dataSourceId: Number,
  showSidebarToggle: Boolean
})

const traceVisible = ref(false)
const adminVisible = ref(false)
const mining = useMiningStore()
const ui = useUIStore()

const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const messagesArea = ref(null)
const pendingCharts = reactive({})
const cost = ref(0)
const totalTokens = ref(0)
const stepInfo = reactive({ current: 0, total: 0 })
const connectionState = ref('idle') // idle | connecting | streaming | error
let msgIdCounter = 0

const exampleQueries = [
  '各区域销售额对比，生成柱状图',
  '用Python分析客户流失原因',
  '建一个员工薪资分类预测模型',
  '生成本月销售分析报告',
  '做一个销售仪表盘大屏'
]

const emit = defineEmits(['openDashboard', 'messageCompleted', 'toggleSidebar'])

const activeAbortController = ref(null)

onBeforeUnmount(() => {
  if (activeAbortController.value) {
    activeAbortController.value.abort()
    activeAbortController.value = null
  }
})

function scrollToBottom() {
  nextTick(() => {
    if (messagesArea.value) {
      messagesArea.value.scrollTop = messagesArea.value.scrollHeight
    }
  })
}

function getOrCreateAssistantMsg() {
  const last = messages.value[messages.value.length - 1]
  if (last && last.type === 'assistant' && (last.loading || last._streaming)) return last

  const msg = reactive({
    _id: ++msgIdCounter,
    type: 'assistant',
    content: [],
    streamingText: '',
    loading: true,
    _streaming: true,
    spinnerTip: '思考中...'
  })
  messages.value.push(msg)
  return msg
}

function findOrCreateToolBlock(assistantMsg, toolName, toolUseId) {
  let block = assistantMsg.content.find(b => b.type === 'tool_use' && b._id === toolUseId)
  if (block) return block

  block = reactive({
    type: 'tool_use',
    name: toolName,
    _id: toolUseId || Math.random().toString(36).slice(2),
    input: {},
    result: null,
    status: 'running'
  })
  assistantMsg.content.push(block)
  return block
}

async function sendMessage() {
  const text = inputText.value?.trim()
  if (!text || loading.value) return

  const convId = props.conversationId
  const dsId = props.dataSourceId
  if (!convId || !dsId) {
    console.warn('Missing conversationId or dataSourceId:', { convId, dsId })
    return
  }

  // User message
  messages.value.push({ _id: ++msgIdCounter, type: 'user_text', content: text })
  inputText.value = ''
  loading.value = true
  connectionState.value = 'connecting'
  scrollToBottom()

  const assistantMsg = getOrCreateAssistantMsg()
  scrollToBottom()

  const controller = new AbortController()
  activeAbortController.value = controller
  let safetyTimeout = null
  const resetSafety = () => {
    if (safetyTimeout) clearTimeout(safetyTimeout)
    safetyTimeout = setTimeout(() => {
      if (loading.value) {
        assistantMsg.loading = false
        assistantMsg._streaming = false
        loading.value = false
        connectionState.value = 'error'
        controller.abort()
      }
    }, SSE_SAFETY_TIMEOUT_MS)
  }
  resetSafety()

  try {
    const url = buildChatUrl(convId, dsId)
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text }),
      signal: controller.signal
    })

    if (!response.ok) {
      throw new Error(`服务器返回 ${response.status}`)
    }

    connectionState.value = 'streaming'
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const jsonStr = line.substring(5).trim()
        if (!jsonStr) continue
        try {
          const evt = JSON.parse(jsonStr)
          resetSafety()
          handleEvent(evt, assistantMsg)
          if (evt.type === 'Done') {
            clearTimeout(safetyTimeout)
          }
        } catch (e) { console.warn('SSE event parse error:', e) }
      }
    }
    if (buffer.startsWith('data:')) {
      try { handleEvent(JSON.parse(buffer.substring(5).trim()), assistantMsg) } catch (e) { console.warn('SSE data parse error:', e) }
    }
  } catch (e) {
    if (e.name !== 'AbortError') {
      connectionState.value = 'error'
      assistantMsg.content.push({
        type: 'text',
        text: `连接失败: ${e.message}`,
        _retryText: text
      })
    }
  } finally {
    if (safetyTimeout) clearTimeout(safetyTimeout)
    activeAbortController.value = null
    assistantMsg.loading = false
    assistantMsg._streaming = false
    assistantMsg.spinnerTip = ''
    loading.value = false
    if (connectionState.value !== 'error') connectionState.value = 'idle'
    scrollToBottom()
    emit('messageCompleted')
  }
}

function handleEvent(evt, assistantMsg) {
  switch (evt.type) {
    case 'ThinkingDelta': {
      assistantMsg.streamingText = (assistantMsg.streamingText || '') + (evt.content || '')
      assistantMsg.spinnerTip = '分析中...'
      break
    }

    case 'Thinking': {
      assistantMsg.streamingText = evt.content || ''
      assistantMsg.spinnerTip = '分析中...'
      break
    }

    case 'SqlExecuting': {
      const block = findOrCreateToolBlock(assistantMsg, 'execute_sql', 'sql-' + Date.now())
      if (evt.sql && evt.sql !== 'executed') block.input = { sql: evt.sql }
      block.status = 'running'
      assistantMsg.spinnerTip = '执行SQL...'
      stepInfo.current++
      stepInfo.total = Math.max(stepInfo.total, stepInfo.current)
      break
    }

    case 'Result': {
      const sqlBlocks = assistantMsg.content.filter(b => b.name === 'execute_sql')
      const targetSql = sqlBlocks.find(b => b.status === 'running') || sqlBlocks[sqlBlocks.length - 1]
      if (targetSql) {
        targetSql.result = { summary: evt.summary, totalRows: evt.totalRows, rows: evt.data || [], error: evt.error }
        targetSql.status = evt.error ? 'error' : 'success'
      }
      break
    }

    case 'PythonExecuting': {
      const block = findOrCreateToolBlock(assistantMsg, 'execute_python', 'py-' + Date.now())
      block.input = { code: evt.code }
      block.status = 'running'
      block.result = { stdout: '', stderr: '', exitCode: null, artifacts: [] }
      assistantMsg.spinnerTip = '执行Python...'
      break
    }

    case 'PythonProgress': {
      const pyBlocks = assistantMsg.content.filter(b => b.name === 'execute_python')
      const lastPy = pyBlocks[pyBlocks.length - 1]
      if (lastPy && lastPy.result) {
        lastPy.result = { ...lastPy.result, stdout: evt.output }
        assistantMsg.spinnerTip = `执行Python... (${(evt.elapsedMs / 1000).toFixed(0)}s)`
      }
      break
    }

    case 'PythonResultEvent': {
      const pyBlocks = assistantMsg.content.filter(b => b.name === 'execute_python')
      const lastPy = pyBlocks[pyBlocks.length - 1]
      if (lastPy) {
        lastPy.result = {
          stdout: evt.stdout,
          stderr: evt.stderr,
          exitCode: evt.exitCode,
          artifacts: evt.artifacts
        }
        lastPy.status = evt.exitCode === 0 ? 'success' : 'error'
      }
      break
    }

    case 'ChartGenerated': {
      const block = findOrCreateToolBlock(assistantMsg, 'generate_chart', 'chart-' + evt.chartId)
      block.result = {
        chartId: evt.chartId,
        title: evt.title,
        chartType: evt.chartType,
        echartsOption: evt.echartsOption
      }
      block.status = 'success'
      pendingCharts[evt.chartId] = { id: evt.chartId, title: evt.title, echartsOption: evt.echartsOption }
      break
    }

    case 'DashboardGenerated': {
      const block = findOrCreateToolBlock(assistantMsg, 'generate_dashboard', 'dash-' + evt.dashboardId)
      block.result = {
        dashboardId: evt.dashboardId,
        title: evt.title,
        layout: evt.layout,
        chartIds: evt.chartIds
      }
      block.status = 'success'
      break
    }

    case 'ReportGenerated': {
      const block = findOrCreateToolBlock(assistantMsg, 'generate_report', 'report-' + evt.reportId)
      const sseSections = evt.sections && evt.sections.length > 0
        ? evt.sections.map(s => ({
            title: s.section_title || s.title || '',
            content: s.section_content || s.content || '',
            sql: s.sql_used || '',
            chartType: s.chart_type || '',
            chartId: s.chart_id || null
          }))
        : null
      block.result = {
        reportId: evt.reportId,
        title: evt.title,
        sections: sseSections || [],
        conclusion: evt.conclusion || ''
      }
      block.status = 'success'
      // Fallback: fetch full report from API if SSE didn't include sections
      if (!sseSections && evt.reportId) {
        fetchReport(evt.reportId).then(report => {
          if (report?.sections) {
            try {
              const sections = JSON.parse(report.sections)
              block.result = {
                ...block.result,
                sections: sections.map(s => ({
                  title: s.section_title || s.title || '',
                  content: s.section_content || s.content || '',
                  sql: s.sql_used || '',
                  chartType: s.chart_type || '',
                  chartId: s.chart_id || null
                })),
                conclusion: report.conclusion || ''
              }
            } catch {
              block.result = { ...block.result, content: report.sections }
            }
          }
        }).catch((e) => {
          console.warn('Report fetch failed after SSE partial:', e)
          const block = findOrCreateToolBlock(assistantMsg, 'generate_report', 'report-' + Date.now())
          if (block && block.result) {
            block.result._loadError = true
          }
        })
      }
      break
    }

    case 'FilterWidgetsGenerated': {
      const block = findOrCreateToolBlock(assistantMsg, 'generate_filter_widgets', 'filter-' + Date.now())
      let widgets = evt.widgets
      let bindings = evt.bindings
      let baseSql = evt.baseSql
      let targetType = evt.targetType
      let targetId = evt.targetId
      if (!widgets && evt.widgetsJson) {
        try {
          const parsed = JSON.parse(evt.widgetsJson)
          widgets = parsed.widgets
          bindings = parsed.bindings
          baseSql = parsed.baseSql
          targetType = parsed.targetType
          targetId = parsed.targetId
        } catch { widgets = [] }
      }
      block.result = {
        widgets: widgets || [],
        bindings: bindings || [],
        baseSql: baseSql || '',
        targetType: targetType || null,
        targetId: targetId || null
      }
      block.status = 'success'
      break
    }

    case 'MiningModelEvent': {
      const block = findOrCreateToolBlock(assistantMsg, 'mining_model', 'mining-' + Date.now())
      block.result = {
        action: evt.action,
        modelId: evt.modelId,
        modelName: evt.modelName,
        algorithm: evt.algorithm,
        success: evt.success,
        message: evt.message,
        details: evt.details
      }
      block.status = evt.success ? 'success' : 'error'
      assistantMsg.spinnerTip = '数据挖掘...'
      if (evt.success && evt.modelId) mining.refreshModel(evt.modelId)
      break
    }

    case 'Error': {
      assistantMsg.content.push({
        type: 'text',
        text: `处理出错: ${evt.message || '未知错误'}${evt.detail ? '\n' + evt.detail : ''}`
      })
      assistantMsg.loading = false
      assistantMsg._streaming = false
      assistantMsg.spinnerTip = ''
      loading.value = false
      connectionState.value = 'error'
      break
    }

    case 'Done': {
      if (assistantMsg.streamingText) {
        const lastBlock = assistantMsg.content[assistantMsg.content.length - 1]
        if (!lastBlock || lastBlock.type !== 'text') {
          assistantMsg.content.push({ type: 'text', text: assistantMsg.streamingText })
        }
        assistantMsg.streamingText = ''
      }
      if (evt.totalTokens) totalTokens.value = evt.totalTokens
      if (evt.cost !== undefined && evt.cost > 0) cost.value = evt.cost
      if (evt.totalSteps) {
        stepInfo.current = evt.totalSteps
        stepInfo.total = evt.totalSteps
      }
      assistantMsg.loading = false
      assistantMsg._streaming = false
      assistantMsg.spinnerTip = ''
      loading.value = false
      break
    }

    default:
      break
  }
  // Don't scroll on progress updates to avoid jitter
  if (evt.type !== 'PythonProgress') {
    scrollToBottom()
  }
}

const showNoResponse = computed(() => {
  if (loading.value || messages.value.length === 0) return false
  const last = messages.value[messages.value.length - 1]
  if (last.type === 'assistant') {
    return last.content.length === 0 && !last.streamingText
  }
  return last.type === 'user_text'
})

function retryLastMessage() {
  const last = messages.value[messages.value.length - 1]
  if (last?.type === 'user_text') {
    inputText.value = last.content
    sendMessage()
  }
}

function clearMessages() {
  messages.value = []
  Object.keys(pendingCharts).forEach(k => delete pendingCharts[k])
  stepInfo.current = 0
  stepInfo.total = 0
  cost.value = 0
  totalTokens.value = 0
}

function updateChartOption(chartId, newOption) {
  if (pendingCharts[chartId]) {
    pendingCharts[chartId] = { ...pendingCharts[chartId], echartsOption: newOption }
  }
}

function restoreHistory(formattedMessages, charts) {
  messages.value = formattedMessages
  Object.keys(pendingCharts).forEach(k => delete pendingCharts[k])
  if (charts) {
    for (const c of charts) {
      pendingCharts[c.id] = c
    }
  }
}

function tryExample(text) {
  if (!props.conversationId || !props.dataSourceId) return
  inputText.value = text
  sendMessage()
}

function handleRetryPython(code, errorLine) {
  if (loading.value || !props.conversationId || !props.dataSourceId) return
  inputText.value = `上次执行出错${errorLine ? ' (' + errorLine.split(':')[0] + ')' : ''}，请修正以下代码后重新执行:\n\n${code}`
  sendMessage()
}

function retryConnection(text) {
  if (loading.value || !props.conversationId || !props.dataSourceId) return
  inputText.value = text
  sendMessage()
}

defineExpose({ sendMessage, clearMessages, messages, updateChartOption, pendingCharts, restoreHistory })
</script>

<style scoped>
.main-area {
  flex: 1; display: flex; flex-direction: column; min-width: 0;
  background: var(--bg);
}

.chat-header {
  height: 52px; background: var(--surface); border-bottom: 1px solid var(--border);
  display: flex; align-items: center; padding: 0 var(--space-xl);
  font-size: var(--font-lg); font-weight: 600; flex-shrink: 0;
  justify-content: space-between;
}
.header-title { color: var(--text-primary); }
.hamburger-btn {
  width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
  background: transparent; border: none; font-size: var(--font-2xl); cursor: pointer;
  border-radius: var(--radius-md); color: var(--text-secondary); margin-right: var(--space-sm);
}
.hamburger-btn:hover { background: var(--border-lighter); }
.cost-badge { font-size: var(--font-sm); color: var(--text-muted); font-weight: 400; }
.step-badge {
  font-size: var(--font-xs); color: var(--primary); font-weight: 500;
  padding: 2px var(--space-sm); background: var(--primary-light); border-radius: var(--radius-xl);
  animation: stepPulse 2s ease-in-out infinite;
}
.conn-badge {
  font-size: var(--font-xs); font-weight: 500; padding: 2px var(--space-sm); border-radius: var(--radius-xl);
  display: inline-flex; align-items: center; gap: var(--space-xs);
}
.conn-badge.connecting { color: var(--color-warning); background: var(--color-warning-light); }
.conn-badge.streaming { color: var(--primary); background: var(--primary-light); }
.conn-badge.error { color: var(--color-danger); background: var(--color-danger-light); }
.spinner-sm {
  width: 10px; height: 10px; border: 1.5px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; animation: spin 0.6s linear infinite;
}
@keyframes stepPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.messages-area {
  flex: 1; overflow-y: auto; padding: var(--space-lg) var(--space-2xl);
}

.welcome {
  text-align: center; color: var(--text-muted); padding: 60px var(--space-xl) 30px;
}
.welcome h3 { font-size: 22px; margin-bottom: var(--space-xs); color: var(--text-primary); }
.welcome-desc { font-size: var(--font-base); color: var(--text-muted); margin-bottom: var(--space-2xl); }

.welcome-cards {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: var(--space-md);
  max-width: 500px; margin: 0 auto var(--space-2xl); text-align: left;
}
@media (max-width: 767px) {
  .welcome-cards { grid-template-columns: 1fr; }
}
.welcome-card {
  padding: var(--space-md) 14px; background: var(--surface); border-radius: var(--radius-lg);
  border: 1px solid var(--border); transition: border-color 0.2s;
}
.welcome-card:hover { border-color: var(--primary); }
.card-icon {
  display: inline-block; font-size: var(--font-xs); font-weight: 700;
  padding: 2px var(--space-sm); border-radius: var(--radius-sm); color: var(--surface); margin-bottom: var(--space-xs);
}
.sql-icon { background: var(--color-warning); }
.py-icon { background: var(--primary); }
.chart-icon, .report-icon { background: var(--color-success); font-size: var(--font-md); padding: 2px var(--space-xs); }
.card-title { font-size: var(--font-md); font-weight: 600; color: var(--text-regular); margin-bottom: 2px; }
.card-desc { font-size: var(--font-sm); color: var(--text-muted); }

.welcome-examples { max-width: 500px; margin: 0 auto; }
.example-label { font-size: var(--font-sm); color: var(--text-muted); margin-bottom: var(--space-sm); }
.example-item {
  padding: var(--space-sm) var(--space-lg); background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-lg); font-size: var(--font-md); color: var(--text-secondary); margin-bottom: var(--space-sm);
  cursor: pointer; transition: all 0.2s;
}
.example-item:hover { border-color: var(--primary); color: var(--primary); background: var(--primary-light); transform: translateY(-1px); box-shadow: var(--shadow-sm); }
.example-item.disabled { opacity: 0.5; cursor: not-allowed; }
.example-item.disabled:hover { border-color: var(--border); color: var(--text-secondary); background: var(--surface); }

.input-area {
  padding: var(--space-lg) var(--space-xl); background: var(--surface);
  border-top: 1px solid var(--border); flex-shrink: 0;
}
.input-row { display: flex; gap: var(--space-sm); }
.input-row .el-input { flex: 1; }
.input-hint {
  text-align: center; padding: var(--space-sm); color: var(--text-muted); font-size: var(--font-md);
}
.no-response-hint {
  text-align: center; padding: var(--space-xl); color: var(--text-muted); font-size: var(--font-md);
  display: flex; align-items: center; justify-content: center; gap: var(--space-sm);
}
.retry-btn-inline {
  padding: 2px var(--space-sm); font-size: var(--font-sm); background: transparent; color: var(--primary);
  border: 1px solid var(--primary-light); border-radius: var(--radius-sm); cursor: pointer;
}
.retry-btn-inline:hover { background: var(--primary-light); }
.trace-btn {
  background: none; border: 1px solid var(--border); border-radius: var(--radius-md);
  padding: var(--space-xs) var(--space-sm); cursor: pointer; color: var(--text-secondary); display: flex; align-items: center;
  margin-left: auto;
}
.trace-btn:hover { background: var(--hover); border-color: var(--primary); color: var(--primary); }
</style>
