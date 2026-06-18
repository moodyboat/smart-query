<template>
  <div class="message-row">
    <!-- User messages: text input -->
    <div v-if="msg.type === 'user_text'" class="user-message">
      <div class="user-bubble">{{ msg.content }}</div>
    </div>

    <!-- Assistant blocks: iterate content[] like Claude Code -->
    <template v-else-if="msg.type === 'assistant'">
      <div
        v-for="(block, bi) in msg.content"
        :key="bi"
        class="assistant-block"
      >
        <!-- Text block: rendered as Markdown (strip tables when SQL tool has rows) -->
        <div
          v-if="block.type === 'text'"
          class="assistant-text"
          :class="{ 'error-text': block._retryText }"
          v-html="renderTextBlock(block, bi)"
        />
        <div v-if="block.type === 'text' && block._retryText" class="retry-actions">
          <button class="retry-btn" @click="emit('retryConnection', block._retryText)">重试</button>
        </div>

        <!-- Thinking block: collapsible -->
        <div v-if="block.type === 'thinking'" class="thinking-block">
          <div class="thinking-header" @click="toggleSection('thinking-' + bi)">
            <span class="thinking-icon">{{ isSectionExpanded('thinking-' + bi) ? '▼' : '▶' }}</span>
            <span>思考过程</span>
          </div>
          <div v-if="isSectionExpanded('thinking-' + bi)" class="thinking-content">
            {{ block.text }}
          </div>
        </div>

        <!-- Tool use block: SQL -->
        <div v-if="block.type === 'tool_use' && block.name === 'execute_sql'" class="tool-block sql-tool">
          <div class="tool-header">
            <span class="tool-badge sql-badge">SQL</span>
            <span class="tool-status" :class="block.status">{{ statusLabel(block.status) }}</span>
          </div>
          <details v-if="block.input?.sql" class="code-details" open>
            <summary class="code-summary">
              <span class="code-lang sql-lang">SQL</span>
              <span class="code-lines">{{ block.input.sql.split('\n').length }} 行</span>
              <button class="copy-btn" @click.stop="copyCode(block.input.sql, 'sql-' + bi)">
                {{ copiedId === 'sql-' + bi ? '已复制' : '复制' }}
              </button>
            </summary>
            <pre class="code-block sql-code">{{ block.input.sql }}</pre>
          </details>
          <div v-if="block.result" class="tool-result">
            <div v-if="block.result.error" class="sql-error">
              <span class="error-icon">✕</span>
              <span>{{ block.result.error }}</span>
            </div>
            <template v-else>
              <div v-if="!block.result.rows?.length" v-html="renderMarkdown(block.result.summary)" />
              <DataTable v-if="block.result.rows?.length" :rows="block.result.rows" />
            </template>
          </div>
        </div>

        <!-- Tool use block: Python -->
        <div v-if="block.type === 'tool_use' && block.name === 'execute_python'" class="tool-block python-tool">
          <div class="tool-header">
            <span class="tool-badge python-badge">Python</span>
            <span class="tool-status" :class="block.status">{{ statusLabel(block.status) }}</span>
          </div>
          <details v-if="block.input?.code" class="code-details" :open="block.status === 'error'">
            <summary class="code-summary">
              <span class="code-toggle-icon">▶</span>
              <span class="code-lang">Python</span>
              <span class="code-lines">{{ block.input.code.split('\n').length }} 行</span>
              <button class="copy-btn" @click.stop="copyCode(block.input.code, 'py-' + bi)">
                {{ copiedId === 'py-' + bi ? '已复制' : '复制' }}
              </button>
            </summary>
            <pre class="code-block python-code">{{ block.input.code }}</pre>
          </details>
          <div v-if="block.result" class="tool-result">
            <div v-if="block.result.stdout" class="stdout-block" :class="{ expanded: expandedOutputs['py-' + bi] }">
              <pre class="stdout-content">{{ block.result.stdout }}</pre>
              <button v-if="block.result.stdout.split('\n').length > 15" class="expand-btn" @click="toggleOutput('py-' + bi)">
                {{ expandedOutputs['py-' + bi] ? '收起输出' : '展开全部输出' }}
              </button>
            </div>
            <div v-if="block.result.stderr" class="stderr-block">
              <div class="error-header">
                <span class="error-icon">✕</span>
                <span>执行错误</span>
              </div>
              <pre class="error-content">{{ formatPythonError(block.result.stderr) }}</pre>
              <div class="error-actions">
                <button class="retry-btn" @click="retryPython(block.input.code, block.result.stderr)">
                  重新执行
                </button>
                <span class="error-hint-inline">告诉我要怎么修改，我会修正后重新执行</span>
              </div>
            </div>
            <div v-if="block.result.exitCode === 0 && !block.result.stderr && !block.result.stdout?.trim()" class="success-hint">
              代码执行成功，无输出
            </div>
            <div v-for="art in (block.result.artifacts || [])" :key="art" class="artifact-block">
              <img
                :src="'/artifacts/' + sanitizeFilename(art)"
                class="artifact-img"
                :alt="sanitizeFilename(art)"
                @error="$event.target.src = ''; $event.target.parentElement.classList.add('img-failed')"
              />
            </div>
          </div>
        </div>

        <!-- Tool use block: Chart -->
        <div v-if="block.type === 'tool_use' && block.name === 'generate_chart'" class="tool-block chart-tool">
          <div class="tool-header">
            <span class="tool-badge chart-badge">Chart</span>
            <span class="chart-title-inline">{{ block.input?.title || block.result?.title }}</span>
            <button
              v-if="block.status === 'success'"
              class="chart-size-btn"
              @click="toggleChartSize('chart-' + bi)"
            >
              {{ expandedCharts['chart-' + bi] ? '收起' : '放大' }}
            </button>
          </div>
          <div v-if="block.result" class="tool-result">
            <div :class="['chart-wrapper', { 'chart-expanded': expandedCharts['chart-' + bi] }]">
              <EChartsRenderer :title="block.result.title" :option="resolveChartOption(block)" />
            </div>
          </div>
          <div v-else-if="block.status === 'running'" class="tool-loading">
            <span class="spinner"></span> 生成图表中...
          </div>
        </div>

        <!-- Tool use block: Dashboard -->
        <div v-if="block.type === 'tool_use' && block.name === 'generate_dashboard'" class="tool-block">
          <div class="tool-header">
            <span class="tool-badge dashboard-badge">Dashboard</span>
            <button
              v-if="block.result?.dashboardId && openDashboard"
              class="fullscreen-btn"
              @click="openDashboard(block.result.dashboardId)"
            >
              全屏查看
            </button>
          </div>
          <div v-if="block.result" class="tool-result">
            <DashboardRenderer
              :title="block.result.title"
              :layout="block.result.layout"
              :charts="resolveDashboardCharts(block.result)"
              :dashboardId="block.result.dashboardId"
              :pendingCharts="pendingCharts"
              :filterData="findDashboardFilter(block.result.dashboardId)"
            />
          </div>
        </div>

        <!-- Tool use block: Report -->
        <div v-if="block.type === 'tool_use' && block.name === 'generate_report'" class="tool-block report-block">
          <div class="tool-header">
            <span class="tool-badge report-badge">Report</span>
            <span class="report-title-inline">{{ block.result?.title }}</span>
          </div>
          <div v-if="block.result" class="report-content">
            <template v-if="block.result.sections?.length">
              <div v-for="(sec, si) in block.result.sections" :key="si" class="report-section">
                <div class="report-section-header" @click="toggleSection('report-' + bi + '-' + si)">
                  <span class="section-toggle">{{ isSectionExpanded('report-' + bi + '-' + si) ? '▼' : '▶' }}</span>
                  <span class="section-number">{{ si + 1 }}.</span>
                  <span class="section-title">{{ sec.section_title || sec.title }}</span>
                </div>
                <div v-if="isSectionExpanded('report-' + bi + '-' + si)" class="report-section-body">
                  <div v-html="renderMarkdown(sec.section_content || sec.content)" />
                  <details v-if="sec.sql_used || sec.sql" class="section-sql-details">
                    <summary class="section-sql-toggle">查看 SQL</summary>
                    <pre class="section-sql-code">{{ sec.sql_used || sec.sql }}</pre>
                  </details>
                  <div v-if="(sec.chart_id || sec.chartId) && resolveChartForReportSection(sec)" class="report-chart">
                    <EChartsRenderer
                      :title="resolveChartForReportSection(sec).title"
                      :option="resolveChartForReportSection(sec).echartsOption"
                    />
                  </div>
                </div>
              </div>
              <div v-if="block.result.conclusion" class="report-conclusion">
                <div class="conclusion-header">总结与建议</div>
                <div v-html="renderMarkdown(block.result.conclusion)" />
              </div>
            </template>
            <div v-else-if="block.result.content" v-html="renderMarkdown(block.result.content)" />
            <div v-else class="report-loading">加载报告内容...</div>
          </div>
        </div>

        <!-- Tool use block: Filter Widgets -->
        <div v-if="block.type === 'tool_use' && block.name === 'generate_filter_widgets'" class="tool-block">
          <div class="tool-header">
            <span class="tool-badge filter-badge">Filters</span>
            <span v-if="block.result?.targetId" class="filter-target">→ 图表 #{{ block.result.targetId }}</span>
          </div>
          <div v-if="block.result" class="filter-bar">
            <template v-for="(w, wi) in (block.result.widgets || [])" :key="wi">
              <el-select
                v-if="w.type === 'select'"
                :placeholder="w.label"
                v-model="filterValues[w.field]"
                size="small"
                clearable
              >
                <el-option v-for="opt in w.options" :key="opt" :label="opt" :value="opt" />
              </el-select>
              <el-date-picker
                v-if="w.type === 'daterange'"
                type="daterange"
                :start-placeholder="w.label"
                size="small"
                v-model="filterValues[w.field]"
                value-format="YYYY-MM-DD"
              />
              <el-input
                v-if="w.type === 'search'"
                v-model="filterValues[w.field]"
                :placeholder="w.label"
                size="small"
                clearable
                style="width: 160px"
              />
            </template>
            <el-button
              v-if="block.result.targetId"
              type="primary"
              size="small"
              :loading="filterLoading[block.result.targetId]"
              @click="applyFilters(block)"
            >
              应用筛选
            </el-button>
          </div>
        </div>

        <!-- Tool use block: Schema Explore -->
        <div v-if="block.type === 'tool_use' && block.name === 'schema_explore'" class="tool-block">
          <div class="tool-header">
            <span class="tool-badge schema-badge">Schema</span>
          </div>
          <div v-if="block.result" class="tool-result" v-html="renderMarkdown(block.result)" />
        </div>

        <!-- Tool use block: Mining Model -->
        <div v-if="block.type === 'tool_use' && block.name === 'mining_model'" class="tool-block mining-tool">
          <div class="tool-header">
            <span class="tool-badge mining-badge">Mining</span>
            <span v-if="block.result?.action" class="mining-action-label">{{ miningActionLabel(block.result.action) }}</span>
            <span class="tool-status" :class="block.status">{{ statusLabel(block.status) }}</span>
          </div>
          <div v-if="block.result" class="tool-result mining-result">
            <!-- Model list -->
            <div v-if="block.result.action === 'list'" class="mining-list">
              <div v-html="renderMarkdown(block.result.message)" />
            </div>
            <!-- Model created -->
            <div v-else-if="block.result.action === 'create'" class="mining-create">
              <div class="mining-model-card" v-if="block.result.modelId">
                <div class="mining-model-name">{{ block.result.modelName || '新模型' }}</div>
                <div class="mining-model-meta">
                  <span v-if="block.result.algorithm">算法: {{ block.result.algorithm }}</span>
                </div>
                <button class="mining-action-btn" @click="emit('openMining', block.result.modelId)">在挖掘模块中查看</button>
              </div>
              <div v-html="renderMarkdown(block.result.message)" />
            </div>
            <!-- Training result -->
            <div v-else-if="block.result.action === 'train'" class="mining-train">
              <div v-if="block.result.details?.metrics" class="mining-metrics-card">
                <div class="mining-metrics-title">训练指标</div>
                <div class="mining-metrics-grid">
                  <template v-for="(val, key) in parseMetricsObj(block.result.details.metrics)" :key="key">
                    <div class="mining-metric-item" :class="metricQualityClass(val, key)">
                      <span class="metric-val">{{ formatMetricVal(key, val) }}</span>
                      <span class="metric-key">{{ formatMetricName(key) }}</span>
                    </div>
                  </template>
                </div>
              </div>
              <div v-html="renderMarkdown(block.result.message)" />
              <button v-if="block.result.modelId" class="mining-action-btn" @click="emit('openMining', block.result.modelId)">查看模型详情</button>
            </div>
            <!-- Validation result -->
            <div v-else-if="block.result.action === 'validate'" class="mining-validate">
              <div v-html="renderMarkdown(block.result.message)" />
            </div>
            <!-- Predict result -->
            <div v-else-if="block.result.action === 'predict'" class="mining-predict">
              <div v-html="renderMarkdown(block.result.message)" />
            </div>
            <!-- Explore data -->
            <div v-else-if="block.result.action === 'explore_data'" class="mining-explore">
              <div v-html="renderMarkdown(block.result.message)" />
            </div>
            <!-- Publish / Offline / Update / List algorithms -->
            <div v-else class="mining-generic">
              <div v-html="renderMarkdown(block.result.message)" />
            </div>
          </div>
          <div v-else-if="block.status === 'running'" class="tool-loading">
            <span class="spinner"></span> 执行挖掘操作...
          </div>
        </div>

        <!-- Generic running indicator for any tool -->
        <div v-if="block.type === 'tool_use' && block.status === 'running' && !['generate_chart','execute_sql','execute_python','mining_model'].includes(block.name)" class="tool-loading">
          <span class="spinner"></span> 执行 {{ block.name }}...
        </div>
      </div>

      <!-- Streaming text with typing cursor -->
      <div v-if="msg.streamingText" class="assistant-text streaming">
        <div v-html="renderMarkdown(msg.streamingText)" />
        <span class="typing-cursor"></span>
      </div>

      <!-- Loading indicator -->
      <div v-if="msg.loading" class="loading-indicator">
        <span class="spinner"></span>
        <span class="loading-text">{{ msg.spinnerTip || '思考中...' }}</span>
      </div>
    </template>

    <!-- System messages -->
    <div v-else-if="msg.type === 'system'" class="system-message">
      <span class="system-icon">ℹ</span> {{ msg.content }}
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import EChartsRenderer from './EChartsRenderer.vue'
import DashboardRenderer from './DashboardRenderer.vue'
import DataTable from './DataTable.vue'
import { rerenderChart, METRIC_NAMES } from '../api'

const props = defineProps({
  msg: { type: Object, required: true },
  pendingCharts: { type: Object, default: () => ({}) },
  updateChartOption: { type: Function, default: null },
  openDashboard: { type: Function, default: null }
})

const emit = defineEmits(['retryPython', 'retryConnection', 'openMining'])

const filterValues = ref({})
const filterLoading = ref({})
const copiedId = ref(null)
const expandedSections = reactive({})
const expandedOutputs = reactive({})
const expandedCharts = reactive({})

function toggleChartSize(key) {
  expandedCharts[key] = !expandedCharts[key]
}

function toggleSection(key) {
  expandedSections[key] = !expandedSections[key]
}

function isSectionExpanded(key) {
  return expandedSections[key] === true
}

function toggleOutput(key) {
  expandedOutputs[key] = !expandedOutputs[key]
}

function copyCode(code, id) {
  if (navigator.clipboard) {
    navigator.clipboard.writeText(code).then(() => {
      copiedId.value = id
      setTimeout(() => { copiedId.value = null }, 2000)
    }).catch(() => fallbackCopy(code, id))
  } else {
    fallbackCopy(code, id)
  }
}

function fallbackCopy(text, id) {
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try {
    document.execCommand('copy')
    copiedId.value = id
    setTimeout(() => { copiedId.value = null }, 2000)
  } catch { /* copy failed */ }
  document.body.removeChild(ta)
}

async function applyFilters(block) {
  if (!block.result?.targetId || !props.updateChartOption) return
  const chartId = block.result.targetId
  filterLoading.value[chartId] = true
  try {
    const resp = await rerenderChart(chartId, filterValues.value)
    if (resp?.echartsOption) {
      props.updateChartOption(chartId, resp.echartsOption)
    } else if (resp?.rows) {
      const newOption = buildOptionFromRows(resp, block.result)
      if (newOption) {
        props.updateChartOption(chartId, newOption)
      }
    }
  } catch (e) {
    console.warn('Filter apply failed:', e)
  } finally {
    filterLoading.value[chartId] = false
  }
}

function buildOptionFromRows(resp, filterResult) {
  const rows = resp.rows || []
  if (!rows.length) return null
  const original = props.pendingCharts[resp.chartId]?.echartsOption
  if (!original) return null

  const opt = typeof original === 'string' ? JSON.parse(original) : { ...original }
  const columns = Object.keys(rows[0])

  if (opt.series && opt.series.length > 0 && columns.length >= 2) {
    const xCol = columns[0]
    const yCols = columns.slice(1)

    if (opt.xAxis) {
      opt.xAxis = { ...opt.xAxis, data: rows.map(r => r[xCol]) }
    }
    if (opt.series) {
      opt.series = opt.series.map((s, i) => ({
        ...s,
        data: i < yCols.length ? rows.map(r => r[yCols[i]]) : s.data
      }))
    }
  }
  return opt
}

// 优先使用 section 中嵌入的图表数据，fallback 到 pendingCharts
function resolveChartForReportSection(section) {
  // 首先尝试使用嵌入的图表数据
  if (section.chartOption && section.chartTitle) {
    try {
      let echartsOption = section.chartOption
      // 处理可能的嵌套 JSON 字符串（双重序列化）
      if (typeof echartsOption === 'string') {
        // 尝试解析，如果解析结果还是字符串，再次解析
        let parsed = JSON.parse(echartsOption)
        let depth = 0
        while (typeof parsed === 'string' && depth < 3) {
          parsed = JSON.parse(parsed)
          depth++
        }
        echartsOption = parsed
      }

      return {
        title: section.chartTitle,
        echartsOption: echartsOption
      }
    } catch (e) {
      console.warn('Failed to parse embedded chart option:', e, section.chartOption)
    }
  }

  // Fallback: 从 pendingCharts 中查找
  const chartId = section.chart_id || section.chartId
  if (chartId && props.pendingCharts[chartId]) {
    const chart = props.pendingCharts[chartId]
    try {
      // 处理 pendingCharts 中可能的嵌套 JSON 字符串
      let echartsOption = chart.echartsOption
      if (typeof echartsOption === 'string') {
        let parsed = JSON.parse(echartsOption)
        let depth = 0
        while (typeof parsed === 'string' && depth < 3) {
          parsed = JSON.parse(parsed)
          depth++
        }
        echartsOption = parsed
      }

      return {
        title: chart.title || section.chartTitle,
        echartsOption: echartsOption
      }
    } catch (e) {
      console.warn('Failed to parse chart option from pendingCharts:', e, chart.echartsOption)
    }
  }

  return null
}

function resolveChartOption(block) {
  const chartId = block.result?.chartId
  if (chartId && props.pendingCharts[chartId]?.echartsOption) {
    return parseChartOption(props.pendingCharts[chartId].echartsOption)
  }
  // 处理 block.result 中的 echartsOption
  if (block.result?.echartsOption) {
    return parseChartOption(block.result.echartsOption)
  }
  return {}
}

// 解析图表配置，处理可能的嵌套 JSON 字符串
function parseChartOption(option) {
  if (!option) return {}

  let echartsOption = option
  // 处理可能的嵌套 JSON 字符串（双重序列化）
  if (typeof echartsOption === 'string') {
    try {
      // 尝试解析，如果解析结果还是字符串，再次解析
      let parsed = JSON.parse(echartsOption)
      let depth = 0
      while (typeof parsed === 'string' && depth < 3) {
        parsed = JSON.parse(parsed)
        depth++
      }
      echartsOption = parsed
    } catch (e) {
      console.warn('Failed to parse chart option:', e, option)
      return {}
    }
  }

  return echartsOption
}

function renderTextBlock(block, blockIndex) {
  const text = block._retryText || block.text || ''
  if (!text) return ''
  const hasSqlRows = props.msg.content?.some(
    (b, i) => i !== blockIndex && b.type === 'tool_use' && b.name === 'execute_sql' && b.result?.rows?.length
  )
  const processed = hasSqlRows ? stripMarkdownTables(text) : text
  const html = marked.parse(processed, { breaks: true })
  return DOMPurify.sanitize(html)
}

function stripMarkdownTables(text) {
  return text.replace(/(\|[^\n]+\|\r?\n)((?:\|[\s:|-]+\|\r?\n)?(\|[^\n]+\|\r?\n)*)+/g, '')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

function renderMarkdown(text) {
  if (!text) return ''
  const html = marked.parse(text, { breaks: true })
  return DOMPurify.sanitize(html)
}

function statusLabel(status) {
  switch (status) {
    case 'running': return '执行中...'
    case 'success': return '完成'
    case 'error': return '失败'
    default: return status || ''
  }
}

function miningActionLabel(action) {
  const labels = {
    list: '模型列表',
    get: '模型详情',
    create: '创建模型',
    update: '更新配置',
    update_params: '修改参数',
    train: '训练',
    validate: '验证',
    publish: '发布',
    offline: '下线',
    predict: '预测',
    batch_predict: '批量预测',
    explore_data: '数据探索',
    list_algorithms: '算法列表',
    create_algorithm: '创建算法',
    history: '执行历史',
    unknown: '挖掘操作'
  }
  return labels[action] || action
}

function parseMetricsObj(metrics) {
  if (!metrics) return {}
  if (typeof metrics === 'string') { try { return JSON.parse(metrics) } catch { return {} } }
  return metrics
}

function formatMetricVal(key, val) {
  if (val == null) return '-'
  const pctKeys = ['accuracy', 'precision', 'recall', 'f1', 'r2']
  return pctKeys.includes(key) ? (val * 100).toFixed(1) + '%' : Number(val).toFixed(4)
}

function formatMetricName(key) {
  return METRIC_NAMES[key] || key
}

function metricQualityClass(val, key) {
  if (val == null) return ''
  if (['rmse', 'mse', 'mae'].includes(key)) return ''
  if (val >= 0.9) return 'metric-good'
  if (val >= 0.7) return 'metric-moderate'
  return val >= 0.4 ? 'metric-poor' : ''
}

function resolveDashboardCharts(result) {
  let ids = result.chartIds
  if (typeof ids === 'string') {
    try { ids = JSON.parse(ids) } catch { return [] }
  }
  if (!Array.isArray(ids)) return []
  return ids.map(id => props.pendingCharts[id]).filter(Boolean)
}

function findDashboardFilter(dashboardId) {
  if (!dashboardId || !props.msg.content) return null
  const filterBlock = props.msg.content.find(
    b => b.type === 'tool_use' && b.name === 'generate_filter_widgets'
      && b.result?.targetType === 'dashboard' && b.result?.targetId === dashboardId
  )
  return filterBlock?.result || null
}

function formatPythonError(stderr) {
  if (!stderr) return ''
  const lines = stderr.split('\n')
  const errorLines = []
  let foundError = false
  for (let i = lines.length - 1; i >= 0; i--) {
    errorLines.unshift(lines[i])
    if (lines[i].startsWith('Traceback') || lines[i].match(/^\w+Error:/)) {
      foundError = true
      break
    }
  }
  if (foundError && errorLines.length < lines.length) {
    return errorLines.join('\n')
  }
  return stderr
}

function sanitizeFilename(path) {
  const name = path.split('/').pop()
  return name.replace(/[^a-zA-Z0-9._-]/g, '')
}

function retryPython(code, stderr) {
  const errorLine = stderr?.split('\n').find(l => l.match(/^\w+Error:/)) || ''
  emit('retryPython', code, errorLine)
}
</script>

<style scoped>
.message-row { margin-bottom: var(--space-lg); }

/* User */
.user-message { display: flex; justify-content: flex-end; margin: var(--space-md) 0 var(--space-lg); }
.user-bubble {
  background: var(--primary); color: var(--surface); padding: var(--space-sm) var(--space-lg); border-radius: var(--radius-pill) var(--radius-pill) var(--radius-sm) var(--radius-pill);
  font-size: var(--font-base); max-width: 70%; word-break: break-word; line-height: 1.5;
}

/* Assistant */
.assistant-block { margin: var(--space-xs) 0; }
.assistant-text {
  font-size: var(--font-base); line-height: 1.7; color: var(--text-regular); padding: var(--space-sm) 0;
  max-width: 90%;
}
.assistant-text.streaming {
  display: flex; flex-wrap: wrap; align-items: baseline;
}
.assistant-text.streaming > div { flex: 1; min-width: 0; }
.typing-cursor {
  display: inline-block; width: 2px; height: 1em;
  background: var(--primary); margin-left: 2px; vertical-align: text-bottom;
  animation: cursorBlink 1s step-end infinite;
}
@keyframes cursorBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.assistant-text :deep(table) { border-collapse: collapse; width: 100%; margin: var(--space-sm) 0; font-size: var(--font-md); }
.assistant-text :deep(th), .assistant-text :deep(td) { border: 1px solid var(--border); padding: var(--space-xs) var(--space-sm); text-align: left; }
.assistant-text :deep(th) { background: var(--color-info-light); font-weight: 600; }

/* Thinking */
.thinking-block { margin: var(--space-xs) 0; }
.thinking-header {
  display: inline-flex; align-items: center; gap: var(--space-xs); padding: var(--space-xs) var(--space-sm);
  background: var(--border-lighter); border-radius: var(--radius-md); font-size: var(--font-sm); color: var(--text-muted);
  cursor: pointer; user-select: none;
}
.thinking-content {
  margin: var(--space-xs) 0; padding: var(--space-sm) var(--space-md); background: var(--surface); border-left: 3px solid var(--border);
  font-size: var(--font-md); color: var(--text-secondary); white-space: pre-wrap;
}

/* Tool blocks */
.tool-block {
  margin: var(--space-sm) 0; border: 1px solid var(--border); border-radius: var(--radius-lg); overflow: hidden;
  background: var(--surface); max-width: 95%;
  box-shadow: var(--shadow-sm);
}
.tool-header {
  display: flex; align-items: center; gap: var(--space-sm); padding: var(--space-sm) 14px;
  background: var(--surface); border-bottom: 1px solid var(--border); font-size: var(--font-md);
}
.tool-badge {
  display: inline-block; padding: 2px var(--space-sm); border-radius: var(--radius-sm);
  font-size: var(--font-xs); font-weight: 700; text-transform: uppercase; color: var(--surface);
}
.sql-badge { background: var(--color-warning); }
.python-badge { background: var(--primary); }
.chart-badge { background: var(--color-success); }
.dashboard-badge { background: var(--badge-dashboard); }
.report-badge { background: var(--badge-report); }
.filter-badge { background: var(--badge-filter); }
.schema-badge { background: var(--color-info); }
.mining-badge { background: var(--badge-mining); }
.mining-action-label {
  font-size: var(--font-sm);
  color: var(--badge-mining);
  font-weight: 500;
  margin-left: 6px;
}
.mining-result :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 8px 0;
}
.mining-result :deep(th),
.mining-result :deep(td) {
  padding: 4px 8px;
  border: 1px solid var(--border-color);
  text-align: left;
  font-size: var(--font-sm);
}
.mining-model-card {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 8px;
}
.mining-model-name {
  font-weight: 600;
  font-size: 15px;
  color: var(--badge-mining);
}
.mining-model-meta {
  font-size: var(--font-sm);
  color: var(--text-secondary);
  margin-top: 4px;
}
.mining-action-btn {
  margin-top: 8px;
  background: none;
  border: 1px solid var(--badge-mining);
  color: var(--badge-mining);
  padding: 4px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.15s;
}
.mining-action-btn:hover { background: var(--badge-mining); color: #fff; }
.mining-metrics-card {
  background: var(--bg-primary);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 8px;
}
.mining-metrics-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--badge-mining);
  margin-bottom: 6px;
}
.mining-metrics-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.mining-metric-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 10px;
  border-radius: 6px;
  background: var(--bg-secondary);
  min-width: 60px;
}
.mining-metric-item .metric-val {
  font-size: 16px;
  font-weight: 700;
}
.mining-metric-item .metric-key {
  font-size: 11px;
  color: var(--text-muted);
}
.mining-metric-item.metric-good .metric-val { color: var(--color-success); }
.mining-metric-item.metric-moderate .metric-val { color: var(--color-warning); }
.mining-metric-item.metric-poor .metric-val { color: var(--color-danger); }

.tool-status { font-size: var(--font-sm); }
.tool-status.running { color: var(--primary); }
.tool-status.success { color: var(--color-success); }
.tool-status.error { color: var(--color-danger); }

.chart-title-inline, .report-title-inline { font-weight: 500; color: var(--text-regular); }

.tool-input { padding: 0; }
.tool-result { padding: var(--space-md) 14px; font-size: var(--font-md); }
.sql-error {
  display: flex; align-items: flex-start; gap: var(--space-xs);
  color: var(--color-danger); font-size: var(--font-md);
}
.result-meta { font-size: var(--font-sm); color: var(--text-muted); margin-top: var(--space-xs); }

.code-block {
  padding: var(--space-sm) 14px; font-family: 'Menlo','Monaco',monospace; font-size: var(--font-sm);
  overflow-x: auto; white-space: pre-wrap; margin: 0;
}
.sql-code { background: var(--code-bg); color: var(--code-fg); }
.python-code { background: var(--code-bg); color: var(--code-fg); }

.python-tool .code-details {
  border-top: 1px solid var(--border); border-bottom: 1px solid var(--border);
}
.code-summary {
  display: flex; align-items: center; gap: var(--space-sm); padding: var(--space-xs) var(--space-md);
  cursor: pointer; user-select: none; font-size: var(--font-sm); color: var(--text-muted);
  background: var(--surface);
}
.code-summary:hover { background: var(--border-lighter); }
.code-toggle-icon { font-size: 9px; }
details[open] .code-toggle-icon { display: none; }
.code-lang { color: var(--primary); font-weight: 500; }
.code-lines { color: var(--text-muted); }
.copy-btn {
  margin-left: auto; padding: 2px var(--space-sm); font-size: var(--font-xs);
  background: transparent; color: var(--text-muted); border: 1px solid var(--border); border-radius: var(--radius-sm);
  cursor: pointer; transition: all 0.15s;
}
.copy-btn:hover { color: var(--primary); border-color: var(--primary); }

.error-header {
  display: flex; align-items: center; gap: var(--space-xs); margin-bottom: var(--space-xs);
  color: var(--color-danger); font-weight: 500; font-size: var(--font-sm);
}
.error-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; border-radius: 50%;
  background: var(--color-danger); color: var(--surface); font-size: var(--font-xs);
}
.error-content {
  margin: 0; font-family: 'Menlo',monospace; font-size: var(--font-sm);
  white-space: pre-wrap; max-height: 200px; overflow-y: auto;
}
.error-hint {
  margin-top: var(--space-xs); font-size: var(--font-xs); color: var(--color-warning);
  padding: var(--space-xs) var(--space-sm); background: var(--color-warning-light); border-radius: var(--radius-sm);
}
.error-actions {
  display: flex; align-items: center; gap: var(--space-sm); margin-top: var(--space-sm);
}
.retry-btn {
  padding: var(--space-xs) var(--space-md); font-size: var(--font-sm); font-weight: 500;
  background: var(--primary); color: var(--surface); border: none; border-radius: var(--radius-sm);
  cursor: pointer; transition: background 0.15s;
}
.retry-btn:hover { background: var(--retry-hover); }
.error-hint-inline { font-size: var(--font-xs); color: var(--color-warning); }
.error-text { color: var(--color-warning); background: var(--color-warning-light); padding: var(--space-sm) 14px; border-radius: var(--radius-md); }
.retry-actions { padding: var(--space-xs) 0 var(--space-sm); }

.stdout-block {
  background: var(--surface); border: 1px solid var(--border); padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm); font-family: 'Menlo',monospace; font-size: var(--font-sm);
  white-space: pre-wrap; max-height: 300px; overflow-y: auto;
  position: relative;
}
.stdout-block.expanded { max-height: none; }
.stdout-content { margin: 0; }
.expand-btn {
  display: block; width: 100%; padding: var(--space-xs) 0; margin-top: var(--space-xs);
  font-size: var(--font-xs); color: var(--primary); background: transparent;
  border: 1px solid var(--primary-light); border-radius: var(--radius-sm); cursor: pointer;
}
.expand-btn:hover { background: var(--primary-light); }
.success-hint {
  font-size: var(--font-sm); color: var(--color-success); padding: var(--space-xs) var(--space-sm);
  background: var(--color-success-light); border-radius: var(--radius-sm);
}
.stderr-block {
  background: var(--color-danger-light); border: 1px solid var(--color-danger); padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-sm); font-family: 'Menlo',monospace; font-size: var(--font-sm);
  white-space: pre-wrap; color: var(--color-danger); max-height: 200px; overflow-y: auto;
}

.artifact-block { margin: var(--space-sm) 0; }
.artifact-img { max-width: 100%; border-radius: var(--radius-md); border: 1px solid var(--border); }
.img-failed::after { content: '图片加载失败'; display: block; padding: var(--space-lg); color: var(--text-muted); font-size: var(--font-md); text-align: center; border: 1px dashed var(--border); border-radius: var(--radius-md); }

.chart-tool { max-width: 700px; }
.chart-size-btn {
  margin-left: auto; padding: 3px var(--space-sm); font-size: var(--font-xs);
  background: transparent; color: var(--color-success); border: 1px solid var(--success-border);
  border-radius: var(--radius-sm); cursor: pointer; transition: all 0.2s;
}
.chart-size-btn:hover { background: var(--color-success-light); border-color: var(--success-border-hover); }
.chart-wrapper :deep(.chart-wrapper) { transition: height 0.3s ease; }
.chart-expanded :deep(.chart-wrapper) { height: 520px !important; }

.report-block { max-width: 95%; }
.report-content { padding: 0; }
.report-section { border-bottom: 1px solid var(--border-light); }
.report-section:last-child { border-bottom: none; }
.report-section-header {
  display: flex; align-items: center; gap: var(--space-xs); padding: var(--space-sm) 14px;
  cursor: pointer; user-select: none; font-size: var(--font-base);
}
.report-section-header:hover { background: var(--surface); }
.section-toggle { font-size: var(--font-xs); color: var(--text-muted); }
.section-number { color: var(--primary); font-weight: 600; }
.section-title { font-weight: 500; color: var(--text-regular); }
.report-section-body {
  padding: 0 14px var(--space-md) 30px; font-size: var(--font-md); line-height: 1.7; color: var(--text-secondary);
}
.report-section-body :deep(table) { border-collapse: collapse; width: 100%; margin: var(--space-sm) 0; font-size: var(--font-sm); }
.report-section-body :deep(th), .report-section-body :deep(td) { border: 1px solid var(--border); padding: 5px var(--space-sm); text-align: left; }
.report-section-body :deep(th) { background: var(--color-info-light); font-weight: 600; }
.report-chart {
  margin: var(--space-sm) 0; padding: var(--space-sm); background: var(--surface);
  border: 1px solid var(--border); border-radius: var(--radius-md); max-width: 600px;
}
.section-sql-details {
  margin: var(--space-sm) 0; border: 1px solid var(--border-light); border-radius: var(--radius-sm);
}
.section-sql-toggle {
  padding: var(--space-xs) var(--space-sm); font-size: var(--font-xs); color: var(--text-muted); cursor: pointer;
  user-select: none;
}
.section-sql-toggle:hover { color: var(--primary); }
.section-sql-code {
  margin: 0; padding: var(--space-sm) var(--space-md); font-family: 'Menlo', monospace;
  font-size: var(--font-xs); background: var(--code-bg); color: var(--code-fg);
  white-space: pre-wrap; border-top: 1px solid var(--border-light);
}
.report-conclusion {
  margin: var(--space-sm) 14px var(--space-md); padding: var(--space-md); background: var(--primary-light);
  border-radius: var(--radius-md); border-left: 3px solid var(--primary);
}
.conclusion-header { font-weight: 600; color: var(--text-regular); margin-bottom: var(--space-xs); font-size: var(--font-md); }
.report-loading { padding: var(--space-lg) 14px; color: var(--text-muted); font-size: var(--font-md); text-align: center; }

.filter-bar {
  display: flex; gap: var(--space-sm); padding: var(--space-sm) var(--space-md); flex-wrap: wrap; align-items: center;
}
.filter-target { font-size: var(--font-sm); color: var(--text-muted); }
.fullscreen-btn {
  margin-left: auto; padding: 2px var(--space-sm); font-size: var(--font-xs);
  background: var(--fullscreen-btn); color: var(--surface); border: none; border-radius: var(--radius-sm);
  cursor: pointer; font-weight: 500;
}
.fullscreen-btn:hover { background: var(--fullscreen-btn-hover); }

.tool-loading {
  padding: var(--space-sm) 14px; font-size: var(--font-md); color: var(--text-muted); display: flex; align-items: center; gap: var(--space-sm);
}

/* Loading */
.loading-indicator {
  display: flex; align-items: center; gap: var(--space-sm); padding: var(--space-sm) 0; color: var(--text-muted); font-size: var(--font-md);
}

.spinner {
  width: 14px; height: 14px; border: 2px solid var(--border); border-top-color: var(--primary);
  border-radius: 50%; animation: spin 0.6s linear infinite; flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* System */
.system-message {
  font-size: var(--font-sm); color: var(--text-muted); padding: var(--space-xs) var(--space-sm); display: flex; align-items: center; gap: var(--space-xs);
}
.system-icon { color: var(--text-muted); }
</style>
