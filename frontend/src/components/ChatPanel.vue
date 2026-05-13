<template>
  <section class="main-area">
    <header class="chat-header">
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
          <div class="example-item" @click="tryExample('各区域销售额对比，生成柱状图')">"各区域销售额对比，生成柱状图"</div>
          <div class="example-item" @click="tryExample('用Python分析客户流失原因')">"用Python分析客户流失原因"</div>
          <div class="example-item" @click="tryExample('生成本月销售分析报告')">"生成本月销售分析报告"</div>
          <div class="example-item" @click="tryExample('做一个销售仪表盘大屏')">"做一个销售仪表盘大屏"</div>
          <div class="example-item" @click="tryExample('用桑基图分析用户转化路径')">"用桑基图分析用户转化路径"</div>
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
      />
    </div>

    <div class="input-area">
      <div v-if="!conversationId || !dataSourceId" class="input-hint">
        请先选择数据源和数据集
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
  </section>
</template>

<script setup>
import { ref, reactive, nextTick } from 'vue'
import MessageRow from './MessageRow.vue'
import { buildChatUrl, fetchReport } from '../api'

const props = defineProps({
  conversationId: Number,
  dataSourceId: Number
})

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

const emit = defineEmits(['openDashboard', 'messageCompleted'])

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
  const safetyTimeout = setTimeout(() => {
    if (loading.value) {
      assistantMsg.loading = false
      assistantMsg._streaming = false
      loading.value = false
      connectionState.value = 'error'
      controller.abort()
    }
  }, 180000)

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
          handleEvent(evt, assistantMsg)
          if (evt.type === 'Done') {
            clearTimeout(safetyTimeout)
          }
        } catch { /* skip */ }
      }
    }
    if (buffer.startsWith('data:')) {
      try { handleEvent(JSON.parse(buffer.substring(5).trim()), assistantMsg) } catch { /* skip */ }
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
    clearTimeout(safetyTimeout)
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
      const lastSql = sqlBlocks[sqlBlocks.length - 1]
      if (lastSql) {
        lastSql.result = { summary: evt.summary, totalRows: evt.totalRows, rows: evt.data || [], error: evt.error }
        lastSql.status = evt.error ? 'error' : 'success'
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
        }).catch(() => {})
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

    case 'Done': {
      if (assistantMsg.streamingText) {
        const lastBlock = assistantMsg.content[assistantMsg.content.length - 1]
        if (!lastBlock || lastBlock.type !== 'text') {
          assistantMsg.content.push({ type: 'text', text: assistantMsg.streamingText })
        }
        assistantMsg.streamingText = ''
      }
      if (evt.totalTokens) totalTokens.value = evt.totalTokens
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

function clearMessages() {
  messages.value = []
  Object.keys(pendingCharts).forEach(k => delete pendingCharts[k])
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
  background: #f9f9fb;
}

.chat-header {
  height: 52px; background: #fff; border-bottom: 1px solid #e8e8e8;
  display: flex; align-items: center; padding: 0 20px;
  font-size: 15px; font-weight: 600; flex-shrink: 0;
  justify-content: space-between;
}
.header-title { color: #1d1e2c; }
.cost-badge { font-size: 12px; color: #999; font-weight: 400; }
.step-badge {
  font-size: 11px; color: #409eff; font-weight: 500;
  padding: 2px 8px; background: #f0f7ff; border-radius: 10px;
  animation: stepPulse 2s ease-in-out infinite;
}
.conn-badge {
  font-size: 11px; font-weight: 500; padding: 2px 8px; border-radius: 10px;
  display: inline-flex; align-items: center; gap: 4px;
}
.conn-badge.connecting { color: #e6a23c; background: #fdf6ec; }
.conn-badge.streaming { color: #409eff; background: #f0f7ff; }
.conn-badge.error { color: #f56c6c; background: #fef0f0; }
.spinner-sm {
  width: 10px; height: 10px; border: 1.5px solid #ddd; border-top-color: #409eff;
  border-radius: 50%; animation: spin 0.6s linear infinite;
}
@keyframes stepPulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.messages-area {
  flex: 1; overflow-y: auto; padding: 16px 24px;
}

.welcome {
  text-align: center; color: #999; padding: 60px 20px 30px;
}
.welcome h3 { font-size: 22px; margin-bottom: 6px; color: #333; }
.welcome-desc { font-size: 14px; color: #888; margin-bottom: 24px; }

.welcome-cards {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px;
  max-width: 500px; margin: 0 auto 24px; text-align: left;
}
.welcome-card {
  padding: 12px 14px; background: #fff; border-radius: 8px;
  border: 1px solid #e8e8e8; transition: border-color 0.2s;
}
.welcome-card:hover { border-color: #409eff; }
.card-icon {
  display: inline-block; font-size: 11px; font-weight: 700;
  padding: 2px 8px; border-radius: 4px; color: #fff; margin-bottom: 6px;
}
.sql-icon { background: #e6a23c; }
.py-icon { background: #409eff; }
.chart-icon, .report-icon { background: #67c23a; font-size: 13px; padding: 2px 6px; }
.card-title { font-size: 13px; font-weight: 600; color: #333; margin-bottom: 2px; }
.card-desc { font-size: 12px; color: #999; }

.welcome-examples { max-width: 500px; margin: 0 auto; }
.example-label { font-size: 12px; color: #bbb; margin-bottom: 8px; }
.example-item {
  padding: 8px 14px; background: #fff; border: 1px dashed #e0e0e0;
  border-radius: 6px; font-size: 13px; color: #666; margin-bottom: 6px;
  cursor: pointer; transition: all 0.2s;
}
.example-item:hover { border-color: #409eff; color: #409eff; background: #f0f7ff; }

.input-area {
  padding: 12px 20px; background: #fff;
  border-top: 1px solid #e8e8e8; flex-shrink: 0;
}
.input-row { display: flex; gap: 10px; }
.input-row .el-input { flex: 1; }
.input-hint {
  text-align: center; padding: 10px; color: #bbb; font-size: 13px;
}
</style>
