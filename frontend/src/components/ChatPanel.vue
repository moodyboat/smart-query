<template>
  <section class="main-area" :class="{ 'scenario-mode': convStore.getCurrentScenario() }" :style="scenarioTheme.background ? { '--bg': scenarioTheme.background, '--scenario-header-bg': scenarioTheme.headerBg } : {}">
    <div class="chat-tools">
      <button v-if="showSidebarToggle" class="hamburger-btn" aria-label="打开导航" @click="emit('toggleSidebar')">☰</button>
      <button v-if="convStore.getCurrentScenario()" type="button" class="scenario-indicator" @click="showScenarioPrompt"><span>{{ currentScenarioConfig.icon }}</span>{{ currentScenarioConfig.name }}</button>
      <span class="toolbar-spacer"></span>
      <span v-if="connectionState === 'connecting'" class="conn-badge connecting">连接中</span>
      <span v-else-if="connectionState === 'streaming'" class="conn-badge streaming"><span class="spinner-sm"></span>接收中</span>
      <span v-else-if="connectionState === 'error'" class="conn-badge error">连接中断</span>
      <span v-if="loading && stepInfo.total > 0" class="step-badge">步骤 {{ stepInfo.current }}/{{ stepInfo.total }}</span>
      <button v-if="conversationId" class="trace-btn" @click="traceVisible = true" title="执行追踪"><el-icon :size="16"><View /></el-icon></button>
      <button v-if="userStore.canViewMonitor" class="trace-btn" @click="adminVisible = true" title="系统监控"><el-icon :size="16"><Monitor /></el-icon></button>
      <button v-if="conversationId && messages.length > 0" class="trace-btn" @click="generateWordReport" title="生成报告"><el-icon :size="16"><Document /></el-icon></button>
    </div>

    <div class="messages-area" ref="messagesArea">
      <div v-if="messages.length === 0" class="welcome" :style="scenarioTheme.cardBg ? { '--welcome-card-bg': scenarioTheme.cardBg } : {}">
        <div class="welcome-avatar">
          <div class="avatar-circle" :class="{ 'default-avatar': !convStore.getCurrentScenario() }" :style="convStore.getCurrentScenario() ? { 'background': scenarioTheme.gradient || 'var(--brand-gradient)' } : {}">
            <span class="avatar-emoji">{{ convStore.getCurrentScenario() ? currentScenarioConfig.icon : '✦' }}</span>
          </div>
        </div>
        <h3>{{ scenarioWelcome.title || '今天想分析什么？' }}</h3>

        <div class="welcome-cards" v-if="scenarioCapabilities.length > 0">
          <div class="welcome-card" v-for="(capability, index) in scenarioCapabilities" :key="index">
            <div class="card-icon" :style="{ '--icon-accent': capability.iconColor || 'var(--brand-primary)' }">
              <span class="card-icon-emoji">{{ displayCapabilityIcon(capability.icon) }}</span>
            </div>
            <div class="card-title">{{ capability.title }}</div>
          </div>
        </div>

        <div class="welcome-examples">
          <div class="example-label">试试问我:</div>
          <div class="example-chips">
            <button
              v-for="ex in exampleQueries"
              :key="ex"
              class="chip example-chip"
              :class="{ disabled: !dataSourceId }"
              @click="tryExample(displayExample(ex))"
            >{{ displayExample(ex) }}</button>
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
      <div v-if="!dataSourceId" class="input-hint">
        请先在左侧选择数据源
      </div>
      <div v-else class="composer-wrap">
        <div class="input-row">
          <el-input
            v-model="inputText"
            :placeholder="conversationId ? '继续提问' : '输入问题，发送后自动创建会话'"
            size="large"
            @keydown.enter.prevent="sendMessage"
            :disabled="loading"
            class="chat-input"
          />
          <button type="button" class="send-btn" :disabled="loading || !inputText.trim()" aria-label="发送" @click="sendMessage">
            <span v-if="loading" class="spinner-sm"></span><el-icon v-else><Promotion /></el-icon>
          </button>
        </div>
      </div>
    </div>

    <TracePanel
      v-model:visible="traceVisible"
      :conversationId="conversationId"
      :totalTokens="totalTokens"
    />
    <AdminStatsPanel v-model:visible="adminVisible" />
  </section>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onBeforeUnmount, watch } from 'vue'
import { View, Monitor, MagicStick, Document, Promotion } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import MessageRow from './MessageRow.vue'
import TracePanel from './TracePanel.vue'
import AdminStatsPanel from './AdminStatsPanel.vue'
import ScenarioModule from './ScenarioModule.vue'
import { buildChatUrl, createConversation, fetchReport, downloadWordReport } from '../api'
import api from '../api'
import { SSE_SAFETY_TIMEOUT_MS, BLOCK_STATUS } from '../constants'
import { useMiningStore } from '../stores/mining'
import { useUIStore } from '../stores/ui'
import { useConversationStore } from '../stores/conversation'
import { useUserStore } from '../stores/user'
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
const userStore = useUserStore()

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

function displayCapabilityIcon(icon) {
  const value = String(icon || '')
  if (/^sql$/i.test(value)) return '数'
  if (/^py(?:thon)?$/i.test(value)) return '学'
  return value
}

function displayExample(text) {
  return String(text || '').replace(/Python/gi, '机器学习')
}

// 场景化主题
const scenarioTheme = computed(() => {
  return currentScenarioConfig.value.theme || {}
})

// 场景化欢迎信息
const scenarioWelcome = computed(() => {
  return currentScenarioConfig.value.welcome || {}
})

const emit = defineEmits(['openDashboard', 'messageCompleted', 'toggleSidebar', 'conversationCreated'])

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

  const dsId = props.dataSourceId
  if (!dsId) {
    ElMessage.warning('请先选择数据源')
    return
  }

  loading.value = true
  connectionState.value = 'connecting'

  let convId = props.conversationId
  if (!convId) {
    try {
      const created = await createConversation({
        title: '新对话',
        dataSourceId: dsId,
        scenario: convStore.getCurrentScenario() || undefined
      })
      convId = created?.id
      if (!convId) throw new Error('服务未返回会话 ID')
      convStore.setCurrentConversation(convId)
      emit('conversationCreated', convId)
    } catch (error) {
      loading.value = false
      connectionState.value = 'idle'
      ElMessage.error(`创建会话失败：${error?.message || '请稍后重试'}`)
      return
    }
  }

  // User message
  messages.value.push({ _id: ++msgIdCounter, type: 'user_text', content: text })
  inputText.value = ''
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
      headers: {
        'Content-Type': 'application/json',
        ...(userStore.token ? { Authorization: `Bearer ${userStore.token}` } : {})
      },
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
  if (!props.dataSourceId) return
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
  position: relative;
  min-width: 0;
  flex: 1;
  max-width: none;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid #e4e8ef;
  border-radius: 12px;
  color: var(--text-primary);
  background: #fff;
  box-shadow: 0 4px 16px rgba(31,35,41,.05);
  margin: 0;
}
.chat-tools {
  position: absolute;
  z-index: 4;
  top: 10px;
  right: 12px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
  pointer-events: none;
}
.chat-tools > * { pointer-events: auto; }
.toolbar-spacer { flex: 1; pointer-events: none; }
.scenario-indicator {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  border: 0;
  border-radius: 999px;
  color: #1762aa;
  background: rgba(0,113,227,.08);
  font: inherit;
  font-size: 9.5px;
  cursor: pointer;
}
.scenario-indicator:hover { background: rgba(0,113,227,.13); }
.hamburger-btn, .trace-btn {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  padding: 0;
  border: 1px solid rgba(60,60,67,.1);
  border-radius: 9px;
  color: #68686d;
  background: rgba(255,255,255,.68);
  cursor: pointer;
}
.hamburger-btn { border: 0; font-size: 15px; }
.hamburger-btn:hover, .trace-btn:hover { color: #0071e3; background: #f0f7ff; border-color: rgba(0,113,227,.18); }
.conn-badge, .step-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border-radius: 999px;
  color: #5e6770;
  background: #f1f1f3;
  font-size: 9px;
  font-weight: 560;
}
.conn-badge.streaming { color: #006edb; background: #eaf5ff; }
.conn-badge.error { color: #c9342f; background: #fff0ef; }
.spinner-sm {
  width: 11px;
  height: 11px;
  display: inline-block;
  flex-shrink: 0;
  border: 1.5px solid rgba(0,113,227,.2);
  border-top-color: #0071e3;
  border-radius: 50%;
  animation: spin .65s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.messages-area {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 52px clamp(18px, 4vw, 56px) 34px;
  scroll-behavior: smooth;
}
.messages-area > :deep(.message-row),
.messages-area > .no-response-hint,
.messages-area > :deep(.scenario-module) {
  width: min(100%, 820px);
  margin-right: auto;
  margin-left: auto;
}
.welcome {
  width: min(100%, 760px);
  min-height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  margin: 0 auto;
  padding: 30px 0 70px;
  color: var(--text-secondary);
  text-align: center;
}
.welcome-avatar { display: flex; justify-content: center; margin-bottom: 17px; }
.avatar-circle {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: white;
  box-shadow: none;
}
.default-avatar { background: #2468f2; }
.avatar-emoji { font-size: 21px; line-height: 1; }
.welcome h3 {
  margin: 0 0 8px;
  color: #1d1d1f;
  font-size: clamp(22px, 2.4vw, 30px);
  font-weight: 660;
  letter-spacing: -.045em;
  line-height: 1.12;
}
.welcome-desc { margin: 0 0 25px; color: #77777d; font-size: 13px; line-height: 1.6; }
.welcome-description { max-width: 620px; margin: -14px auto 24px; color: #77777d; font-size: 11px; line-height: 1.6; }
.welcome-cards {
  width: min(100%, 680px);
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 9px;
  margin: 0 auto 24px;
  text-align: left;
}
.welcome-card {
  min-height: 126px;
  padding: 15px;
  border: 1px solid rgba(60,60,67,.09);
  border-radius: 9px;
  background: #fff;
  box-shadow: none;
  transition: transform .2s ease, border-color .2s ease, box-shadow .2s ease;
}
.welcome-card:hover { transform:none;border-color:#a9c4fb;box-shadow:none; }
.card-icon {
  width: 33px;
  height: 33px;
  display: grid;
  place-items: center;
  margin-bottom: 10px;
  border: 1px solid color-mix(in srgb, var(--icon-accent) 22%, transparent);
  border-radius: 10px;
  background: color-mix(in srgb, var(--icon-accent) 9%, white);
}
.card-icon-emoji { font-size: 17px; }
.card-title { margin-bottom: 4px; color: #29292c; font-size: 11px; font-weight: 620; }
.card-desc { color: #86868b; font-size: 9.5px; line-height: 1.5; }
.welcome-examples { width: min(100%, 640px); margin: 0 auto; }
.example-label { margin-bottom: 9px; color: #9a9aa0; font-size: 9px; }
.example-chips { display: flex; flex-wrap: wrap; justify-content: center; gap: 7px; }
.example-chip {
  padding: 7px 12px;
  border: 1px solid rgba(60,60,67,.09);
  color: #57575c;
  background: #fff;
  font-size: 10px;
  box-shadow: none;
}
.example-chip:hover { transform: none; color: #006edb; border-color: rgba(0,113,227,.18); background: #f1f8ff; box-shadow: none; }
.example-chip.disabled { opacity: .45; pointer-events: none; }

.input-area {
  flex-shrink: 0;
  padding: 10px clamp(16px, 4vw, 48px) 13px;
  border-top: 1px solid #edf0f5;
  background: #fff;
}
.composer-wrap { width: min(100%, 820px); margin: 0 auto; }
.input-row {
  min-height: 53px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 7px 6px 15px;
  border: 1px solid rgba(60,60,67,.14);
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 3px 12px rgba(31,35,41,.06);
  transition: border-color .2s ease, box-shadow .2s ease;
}
.input-row:focus-within { border-color:#91b4f9;box-shadow:0 0 0 3px rgba(36,104,242,.08); }
.chat-input { flex: 1; }
.input-row :deep(.chat-input .el-input__wrapper),
.input-row :deep(.chat-input .el-input__wrapper:hover),
.input-row :deep(.chat-input .el-input__wrapper.is-focus) {
  padding: 0;
  background: transparent;
  box-shadow: none !important;
}
.input-row :deep(.chat-input .el-input__inner) { color: #29292c; font-size: 12px; }
.send-btn {
  width: 39px;
  height: 39px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border: 0;
  border-radius: 8px;
  color: white;
  background: #2468f2;
  box-shadow: none;
  cursor: pointer;
}
.send-btn:hover:not(:disabled) { filter: brightness(1.04); transform: translateY(-1px); }
.send-btn:disabled { color: #aaaab0; background: #e7e7ea; box-shadow: none; cursor: default; }
.input-hint {
  width: min(100%, 820px);
  margin: 0 auto;
  padding: 15px;
  border: 1px solid rgba(0,113,227,.1);
  border-radius: 14px;
  color: #6f7d8b;
  background: #f1f8ff;
  font-size: 10px;
  text-align: center;
}
.no-response-hint { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 20px; color: #86868b; font-size: 10px; }
.retry-btn-inline { padding: 4px 8px; border: 0; border-radius: 7px; color: #0071e3; background: #edf6ff; cursor: pointer; }

:deep(.scenario-prompt-dialog) { max-width: 800px; }
:deep(.scenario-prompt-dialog .el-message-box__content) {
  max-height: 400px;
  overflow-y: auto;
  white-space: pre-wrap;
  font-family: var(--font-family-mono);
  font-size: 12px;
  line-height: 1.65;
}

@media (max-width: 1023px) {
  .welcome-cards { grid-template-columns: repeat(2, 1fr); }
}
@media (min-width: 1600px) and (min-height: 850px) {
  .messages-area > :deep(.message-row),
  .messages-area > .no-response-hint,
  .messages-area > :deep(.scenario-module),
  .composer-wrap,
  .input-hint { width: min(100%, 920px); }
  .welcome { width: min(100%, 900px); }
  .welcome h3 { font-size: 42px; }
  .welcome-desc { font-size: 14px; }
  .welcome-cards { width: min(100%, 820px); gap: 12px; }
  .welcome-card { min-height: 145px; padding: 18px; }
  .card-icon { width: 38px; height: 38px; }
  .card-title { font-size: 12.5px; }
  .card-desc { font-size: 10.5px; }
  .welcome-examples { width: min(100%, 760px); }
  .example-chip { padding: 8px 13px; font-size: 11px; }
  .input-row { min-height: 58px; }
}
@media (max-width: 680px) {
  .main-area { border: 0; border-radius: 0; }
  .chat-tools { top: 8px; right: 9px; left: 9px; }
  .chat-tools .conn-badge, .chat-tools .step-badge { display: none; }
  .messages-area { padding: 48px 13px 24px; }
  .welcome { justify-content: flex-start; padding-top: 36px; }
  .welcome-cards { grid-template-columns: 1fr 1fr; }
  .input-area { padding-inline: 10px; }
}
@media (max-width: 480px) {
  .welcome-cards { grid-template-columns: 1fr; }
  .welcome-card { min-height: 0; }
  .trace-btn:not(:last-child) { display: none; }
}
</style>
