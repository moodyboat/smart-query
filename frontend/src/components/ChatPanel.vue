<template>
  <section class="main-area" :class="{ 'scenario-mode': convStore.getCurrentScenario() }" :style="scenarioTheme.background ? { '--bg': scenarioTheme.background } : {}">
    <header class="chat-header">
      <button v-if="showSidebarToggle" class="hamburger-btn" @click="emit('toggleSidebar')">☰</button>
      <span class="header-title">
        <span v-if="convStore.getCurrentScenario()" class="scenario-icon">{{ currentScenarioConfig.icon }}</span>
        {{ scenarioWelcome.title || '智能数据分析助手' }}
      </span>
      <!-- 当前场景指示器 -->
      <el-tag v-if="convStore.getCurrentScenario()" type="success" size="small" class="scenario-indicator" @click="showScenarioPrompt" style="cursor: pointer" :style="{ 'background-color': scenarioTheme.primary, 'border-color': scenarioTheme.primary }">
        {{ currentScenarioConfig.icon }} {{ currentScenarioConfig.name }} <span style="margin-left: 8px; opacity: 0.8;">查看提示词</span>
      </el-tag>
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
      <button v-if="conversationId && messages.length > 0" class="trace-btn" @click="generateWordReport" title="生成Word报告">
        <el-icon :size="16"><Document /></el-icon>
      </button>
    </header>

    <div class="messages-area" ref="messagesArea">
      <div v-if="messages.length === 0" class="welcome" :style="scenarioTheme.cardBg ? { '--welcome-card-bg': scenarioTheme.cardBg } : {}">
        <div class="welcome-avatar" v-if="convStore.getCurrentScenario()">
          <div class="avatar-circle" :style="{ 'background': scenarioTheme.gradient || 'var(--color-primary)' }">
            <span class="avatar-emoji">{{ currentScenarioConfig.icon }}</span>
          </div>
        </div>
        <h3>{{ scenarioWelcome.title || '欢迎使用智能问数' }}</h3>
        <p class="welcome-desc">{{ scenarioWelcome.subtitle || '不只是查询 — 我可以帮你做完整的数据分析' }}</p>
        <p class="welcome-description" v-if="convStore.getCurrentScenario()">{{ scenarioWelcome.description }}</p>

        <div class="welcome-cards" v-if="scenarioCapabilities.length > 0">
          <div class="welcome-card" v-for="(capability, index) in scenarioCapabilities" :key="index">
            <div class="card-icon" :style="{ 'background': capability.iconColor || 'var(--primary)' }">
              {{ capability.icon }}
            </div>
            <div class="card-title">{{ capability.title }}</div>
            <div class="card-desc">{{ capability.description }}</div>
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

      <!-- 场景提示词模块 -->
      <ScenarioModule
        v-if="showPromptModules"
        :modules="scenarioModules"
        :show-modules="showPromptModules"
        @hide="hidePromptModules"
        @expandAll="expandAllModules"
        @itemClick="handleModuleItemClick"
      />
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
import { ref, reactive, computed, nextTick, onBeforeUnmount, watch } from 'vue'
import { View, Monitor, MagicStick, Document } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import MessageRow from './MessageRow.vue'
import TracePanel from './TracePanel.vue'
import AdminStatsPanel from './AdminStatsPanel.vue'
import ScenarioModule from './ScenarioModule.vue'
import { buildChatUrl, fetchReport, downloadWordReport } from '../api'
import api from '../api'
import { SSE_SAFETY_TIMEOUT_MS, BLOCK_STATUS } from '../constants'
import { useMiningStore } from '../stores/mining'
import { useUIStore } from '../stores/ui'
import { useConversationStore } from '../stores/conversation'
import { getScenarioConfig } from '../config/scenarios.js'

const props = defineProps({
  conversationId: Number,
  dataSourceId: Number,
  showSidebarToggle: Boolean
})

const traceVisible = ref(false)
const adminVisible = ref(false)
const mining = useMiningStore()
const ui = useUIStore()
const convStore = useConversationStore()

// 提示词模块相关
const scenarioModules = ref([])
const showPromptModules = ref(false)
const currentPromptContent = ref('')

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

// 场景化配置
const currentScenarioConfig = computed(() => {
  const scenarioCode = convStore.getCurrentScenario()
  return getScenarioConfig(scenarioCode)
})

// 场景化的示例问题
const exampleQueries = computed(() => {
  return currentScenarioConfig.value.examples || []
})

// 场景化的能力卡片
const scenarioCapabilities = computed(() => {
  return currentScenarioConfig.value.capabilities || []
})

// 场景化主题
const scenarioTheme = computed(() => {
  return currentScenarioConfig.value.theme || {}
})

// 场景化欢迎信息
const scenarioWelcome = computed(() => {
  return currentScenarioConfig.value.welcome || {}
})

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
    status: BLOCK_STATUS.RUNNING
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
    const url = buildChatUrl(convId, dsId, convStore.getCurrentScenario())
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
      block.status = BLOCK_STATUS.RUNNING
      assistantMsg.spinnerTip = '执行SQL...'
      stepInfo.current++
      stepInfo.total = Math.max(stepInfo.total, stepInfo.current)
      break
    }

    case 'Result': {
      const sqlBlocks = assistantMsg.content.filter(b => b.name === 'execute_sql')
      const targetSql = sqlBlocks.find(b => b.status === BLOCK_STATUS.RUNNING) || sqlBlocks[sqlBlocks.length - 1]
      if (targetSql) {
        targetSql.result = { summary: evt.summary, totalRows: evt.totalRows, rows: evt.data || [], error: evt.error }
        targetSql.status = evt.error ? BLOCK_STATUS.ERROR : BLOCK_STATUS.SUCCESS
      }
      break
    }

    case 'PythonExecuting': {
      const block = findOrCreateToolBlock(assistantMsg, 'execute_python', 'py-' + Date.now())
      block.input = { code: evt.code }
      block.status = BLOCK_STATUS.RUNNING
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
        lastPy.status = evt.exitCode === 0 ? BLOCK_STATUS.SUCCESS : BLOCK_STATUS.ERROR
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
      block.status = BLOCK_STATUS.SUCCESS
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
      block.status = BLOCK_STATUS.SUCCESS
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
      block.status = BLOCK_STATUS.SUCCESS
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
      block.status = BLOCK_STATUS.SUCCESS
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
      block.status = evt.success ? BLOCK_STATUS.SUCCESS : BLOCK_STATUS.ERROR
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

// 场景标签映射
function getScenarioLabel(scenarioCode) {
  const labels = {
    'general': '通用查询',
    'sales_analysis': '销售分析',
    'user_analysis': '用户分析',
    'financial_analysis': '财务分析',
    'operations_monitoring': '运营监控',
    'data_mining': '数据挖掘'
  }
  return labels[scenarioCode] || scenarioCode
}

// 解析提示词为可点击模块
function parsePromptToModules(promptContent) {
  if (!promptContent) return []

  const modules = []
  const lines = promptContent.split('\n')
  let currentModule = null
  let currentSubItems = []

  lines.forEach(line => {
    // 检测标题（## 标题）
    const titleMatch = line.match(/^##\s+(.+)$/)
    if (titleMatch) {
      // 保存上一个模块
      if (currentModule && currentSubItems.length > 0) {
        currentModule.items = [...currentSubItems]
        modules.push(currentModule)
      }
      // 创建新模块
      currentModule = {
        title: titleMatch[1].trim(),
        items: []
      }
      currentSubItems = []
    } else if (line.trim().match(/^\d+\.\s+/)) {
      // 检测列表项（1. 功能描述）
      const itemMatch = line.match(/^\d+\.\s+(.+)$/)
      if (itemMatch && currentModule) {
        currentSubItems.push({
          text: itemMatch[1].trim(),
          type: 'instruction'
        })
      }
    } else if (line.trim().match(/\*\*(.+)\*\*/)) {
      // 检测粗体强调（**重要概念**）
      const conceptMatch = line.match(/\*\*(.+)\*\*/)
      if (conceptMatch && currentModule) {
        currentSubItems.push({
          text: conceptMatch[1].trim(),
          type: 'concept',
          content: conceptMatch[1].trim()
        })
      }
    }
  })

  // 保存最后一个模块
  if (currentModule && currentSubItems.length > 0) {
    currentModule.items = [...currentSubItems]
    modules.push(currentModule)
  }

  return modules
}

// 点击提示词模块发送指令
const sendModuleInstruction = (moduleItem) => {
  const instruction = moduleItem.text || moduleItem.content
  if (instruction) {
    inputText.value = instruction
    sendMessage()
  }
}

// 显示场景提示词
async function showScenarioPrompt() {
  const scenarioCode = convStore.getCurrentScenario()
  console.log('当前场景代码:', scenarioCode)

  if (!scenarioCode) {
    ElMessage.warning('当前未选择场景，请先在提示词管理中选择一个场景并开始对话')
    return
  }

  try {
    console.log('正在获取场景提示词，场景代码:', scenarioCode)
    const response = await api.get(`/scenarios/code/${scenarioCode}/prompt`)
    console.log('提示词API响应:', response)

    if (response.data && response.data.code === 200 && response.data.data) {
      ElMessageBox.alert(response.data.data, '当前场景提示词', {
        customClass: 'scenario-prompt-dialog',
        dangerouslyUseHTMLString: false,
        confirmButtonText: '关闭'
      })
    } else {
      console.error('API响应格式不正确:', response)
      ElMessage.warning('获取提示词失败：响应格式不正确')
    }
  } catch (error) {
    console.error('获取提示词失败，详细错误:', error)
    console.error('错误消息:', error.message)
    console.error('错误响应:', error.response)
    ElMessage.error(`获取提示词失败：${error.message || '未知错误'}`)
  }
}

// 加载场景模块
async function loadScenarioModules() {
  const scenarioCode = convStore.getCurrentScenario()
  if (!scenarioCode) {
    showPromptModules.value = false
    return
  }

  try {
    const response = await api.get(`/scenarios/code/${scenarioCode}/prompt`)
    if (response.data.code === 200 && response.data.data) {
      currentPromptContent.value = response.data.data
      scenarioModules.value = parsePromptToModules(response.data.data)
      showPromptModules.value = scenarioModules.value.length > 0
    } else {
      showPromptModules.value = false
    }
  } catch (error) {
    console.error('加载场景模块失败:', error)
    showPromptModules.value = false
  }
}

// 隐藏提示词模块
function hidePromptModules() {
  showPromptModules.value = false
}

// 处理模块项点击
function handleModuleItemClick(item) {
  sendModuleInstruction(item)
}

// 展开所有模块
function expandAllModules() {
  showPromptModules.value = true
}

// 生成Word报告
async function generateWordReport() {
  if (!props.conversationId) {
    ElMessage.warning('请先进行对话后再生成报告')
    return
  }

  if (messages.value.length === 0) {
    ElMessage.warning('当前对话没有内容，无法生成报告')
    return
  }

  try {
    const loading = ElLoading.service({
      lock: true,
      text: '正在生成Word报告...',
      background: 'rgba(0, 0, 0, 0.7)'
    })

    try {
      const reportTitle = `对话报告_${props.conversationId}_${new Date().toLocaleDateString()}`

      // 调用下载API直接在新标签页打开
      downloadWordReport(props.conversationId, reportTitle)

      ElMessage.success('Word报告已生成，正在下载...')

    } finally {
      loading.close()
    }
  } catch (error) {
    console.error('生成Word报告失败:', error)
    ElMessage.error('生成Word报告失败：' + (error.message || '未知错误'))
  }
}

// 监听场景变化，自动加载模块
watch(() => convStore.getCurrentScenario(), (newScenario, oldScenario) => {
  if (newScenario && newScenario !== oldScenario) {
    loadScenarioModules()
  }
}, { immediate: false })

defineExpose({ sendMessage, clearMessages, messages, updateChartOption, pendingCharts, restoreHistory, showScenarioPrompt, loadScenarioModules, parsePromptToModules, sendModuleInstruction, hidePromptModules })
</script>

<style scoped>
.main-area {
  flex: 1; display: flex; flex-direction: column; min-width: 0;
  background: var(--bg);
}

.chat-header {
  height: 52px; background: var(--brand-gradient); border-bottom: none;
  display: flex; align-items: center; padding: 0 var(--space-xl);
  font-size: var(--font-lg); font-weight: 600; flex-shrink: 0;
  justify-content: space-between;
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}
.header-title { color: #fff; }
.scenario-indicator {
  font-weight: 500;
  padding: 4px 12px;
  border-radius: 20px;
  animation: fadeIn 0.3s ease-in;
  margin-left: var(--space-sm);
}
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

/* 场景提示词对话框样式 */
:deep(.scenario-prompt-dialog) {
  max-width: 800px;
}
:deep(.scenario-prompt-dialog .el-message-box__content) {
  white-space: pre-wrap;
  font-family: var(--font-family-mono);
  font-size: var(--font-base);
  line-height: 1.6;
  max-height: 400px;
  overflow-y: auto;
}
.hamburger-btn {
  width: 32px; height: 32px; display: flex; align-items: center; justify-content: center;
  background: transparent; border: none; font-size: var(--font-2xl); cursor: pointer;
  border-radius: var(--radius-md); color: #fff; margin-right: var(--space-sm);
  transition: background var(--transition-fast);
}
.hamburger-btn:hover { background: rgba(255, 255, 255, 0.2); }
.cost-badge { font-size: var(--font-sm); color: rgba(255, 255, 255, 0.85); font-weight: 400; }
.step-badge {
  font-size: var(--font-xs); color: #fff; font-weight: 500;
  padding: 2px var(--space-sm); background: rgba(255, 255, 255, 0.22); border-radius: var(--radius-xl);
  animation: stepPulse 2s ease-in-out infinite;
}
.conn-badge {
  font-size: var(--font-xs); font-weight: 500; padding: 2px var(--space-sm); border-radius: var(--radius-xl);
  display: inline-flex; align-items: center; gap: var(--space-xs);
  color: #fff;
}
.conn-badge.connecting { background: rgba(255, 255, 255, 0.22); }
.conn-badge.streaming { background: rgba(255, 255, 255, 0.22); }
.conn-badge.error { background: rgba(245, 108, 108, 0.85); }
.spinner-sm {
  width: 10px; height: 10px; border: 1.5px solid rgba(255, 255, 255, 0.5); border-top-color: #fff;
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
  border: 1px solid var(--border); transition: all 0.2s;
}
.welcome-card:hover {
  border-color: var(--border-hover);
  transform: none;
  box-shadow: none;
}
.card-icon {
  display: inline-block; font-size: var(--font-xs); font-weight: 700;
  padding: 2px var(--space-sm); border-radius: var(--radius-sm); color: var(--color-primary); margin-bottom: var(--space-xs);
}
.sql-icon { color: var(--color-primary); }
.py-icon { color: var(--color-primary); }
.chart-icon, .report-icon { color: var(--color-primary); font-size: var(--font-md); padding: 2px var(--space-xs); }
.card-title { font-size: var(--font-md); font-weight: 600; color: var(--text-regular); margin-bottom: 2px; }
.card-desc { font-size: var(--font-sm); color: var(--text-muted); }

.welcome-examples { max-width: 500px; margin: 0 auto; }
.example-label { font-size: var(--font-sm); color: var(--text-muted); margin-bottom: var(--space-sm); }
.example-item {
  padding: var(--space-sm) var(--space-lg); background: var(--surface); border: 1px solid var(--border);
  border-radius: var(--radius-lg); font-size: var(--font-md); color: var(--text-secondary); margin-bottom: var(--space-sm);
  cursor: pointer; transition: all 0.2s;
}
.example-item:hover {
  border-color: var(--border-hover);
  color: var(--color-primary);
  background: var(--surface-hover);
  transform: none;
  box-shadow: none;
}
.example-item.disabled { opacity: 0.5; cursor: not-allowed; }
.example-item.disabled:hover { border-color: var(--border); color: var(--text-secondary); background: var(--surface); transform: none; }

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
  background: rgba(255, 255, 255, 0.12); border: 1px solid rgba(255, 255, 255, 0.28); border-radius: var(--radius-md);
  padding: var(--space-xs) var(--space-sm); cursor: pointer; color: #fff; display: flex; align-items: center;
  margin-left: auto;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}
.trace-btn:hover { background: rgba(255, 255, 255, 0.24); border-color: rgba(255, 255, 255, 0.6); color: #fff; }

/* 场景化样式 */
.main-area.scenario-mode {
  transition: background 0.3s ease;
}

.scenario-icon {
  margin-right: var(--space-sm);
  font-size: var(--font-xl);
}

.welcome-avatar {
  margin-bottom: var(--space-xl);
  display: flex;
  justify-content: center;
}

.avatar-circle {
  width: 80px;
  height: 80px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: none;
  animation: none;
}

.avatar-emoji {
  font-size: 40px;
}

/* 动画已移除 */

.welcome-description {
  font-size: var(--font-md);
  color: var(--text-secondary);
  margin-bottom: var(--space-2xl);
  max-width: 600px;
  margin-left: auto;
  margin-right: auto;
  line-height: 1.6;
}

.welcome-card {
  transition: all 0.2s ease;
}

.welcome-card:hover {
  transform: none;
  box-shadow: none;
  border-color: var(--border-hover);
}

.card-icon {
  font-size: var(--font-lg);
  padding: 4px var(--space-sm);
  background: transparent;
}

/* 场景模式下的消息气泡美化 - 动画已移除 */
.scenario-mode .welcome {
  animation: none;
}

/* 场景化示例问题 */
.example-item {
  transition: all 0.2s ease;
}

.scenario-mode .example-item {
  border-left: 3px solid transparent;
}

.scenario-mode .example-item:hover {
  border-left-color: var(--primary);
}
</style>
