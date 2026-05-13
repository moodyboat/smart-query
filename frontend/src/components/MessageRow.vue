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
                @error="$event.target.style.display = 'none'"
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
                  <div v-if="(sec.chart_id || sec.chartId) && resolveChartForReport(sec.chart_id || sec.chartId)" class="report-chart">
                    <EChartsRenderer
                      :title="resolveChartForReport(sec.chart_id || sec.chartId).title"
                      :option="resolveChartForReport(sec.chart_id || sec.chartId).echartsOption"
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

        <!-- Generic running indicator for any tool -->
        <div v-if="block.type === 'tool_use' && block.status === 'running' && !['generate_chart','execute_sql','execute_python'].includes(block.name)" class="tool-loading">
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
import { rerenderChart } from '../api'

const props = defineProps({
  msg: { type: Object, required: true },
  pendingCharts: { type: Object, default: () => ({}) },
  updateChartOption: { type: Function, default: null },
  openDashboard: { type: Function, default: null }
})

const emit = defineEmits(['retryPython', 'retryConnection'])

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
  expandedSections[key] = expandedSections[key] === false
}

function isSectionExpanded(key) {
  return expandedSections[key] !== false
}

function toggleOutput(key) {
  expandedOutputs[key] = !expandedOutputs[key]
}

function copyCode(code, id) {
  navigator.clipboard.writeText(code).then(() => {
    copiedId.value = id
    setTimeout(() => { copiedId.value = null }, 2000)
  })
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

function resolveChartForReport(chartId) {
  if (!chartId) return null
  const chart = props.pendingCharts[chartId]
  if (!chart) return null
  return { title: chart.title, echartsOption: chart.echartsOption }
}

function resolveChartOption(block) {
  const chartId = block.result?.chartId
  if (chartId && props.pendingCharts[chartId]?.echartsOption) {
    return props.pendingCharts[chartId].echartsOption
  }
  return block.result?.echartsOption
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

function resolveDashboardCharts(result) {
  return (result.chartIds || []).map(id => props.pendingCharts[id]).filter(Boolean)
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
.message-row { margin-bottom: 4px; }

/* User */
.user-message { display: flex; justify-content: flex-end; margin: 8px 0; }
.user-bubble {
  background: #409eff; color: #fff; padding: 8px 14px; border-radius: 14px 14px 4px 14px;
  font-size: 14px; max-width: 70%; word-break: break-word; line-height: 1.5;
}

/* Assistant */
.assistant-block { margin: 2px 0; }
.assistant-text {
  font-size: 14px; line-height: 1.6; color: #333; padding: 6px 0;
  max-width: 90%;
}
.assistant-text.streaming {
  display: flex; flex-wrap: wrap; align-items: baseline;
}
.assistant-text.streaming > div { flex: 1; min-width: 0; }
.typing-cursor {
  display: inline-block; width: 2px; height: 1em;
  background: #409eff; margin-left: 2px; vertical-align: text-bottom;
  animation: cursorBlink 1s step-end infinite;
}
@keyframes cursorBlink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}
.assistant-text :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 13px; }
.assistant-text :deep(th), .assistant-text :deep(td) { border: 1px solid #ddd; padding: 6px 10px; text-align: left; }
.assistant-text :deep(th) { background: #f5f7fa; font-weight: 600; }

/* Thinking */
.thinking-block { margin: 4px 0; }
.thinking-header {
  display: inline-flex; align-items: center; gap: 4px; padding: 4px 10px;
  background: #f0f0f0; border-radius: 6px; font-size: 12px; color: #888;
  cursor: pointer; user-select: none;
}
.thinking-content {
  margin: 6px 0; padding: 8px 12px; background: #fafafa; border-left: 3px solid #ddd;
  font-size: 13px; color: #666; white-space: pre-wrap;
}

/* Tool blocks */
.tool-block {
  margin: 6px 0; border: 1px solid #e8e8e8; border-radius: 8px; overflow: hidden;
  background: #fff; max-width: 95%;
}
.tool-header {
  display: flex; align-items: center; gap: 8px; padding: 6px 12px;
  background: #fafafa; border-bottom: 1px solid #e8e8e8; font-size: 13px;
}
.tool-badge {
  display: inline-block; padding: 2px 8px; border-radius: 4px;
  font-size: 11px; font-weight: 700; text-transform: uppercase; color: #fff;
}
.sql-badge { background: #e6a23c; }
.python-badge { background: #409eff; }
.chart-badge { background: #67c23a; }
.dashboard-badge { background: #9b59b6; }
.report-badge { background: #e74c3c; }
.filter-badge { background: #f59e0b; }
.schema-badge { background: #8c8c8c; }

.tool-status { font-size: 12px; }
.tool-status.running { color: #409eff; }
.tool-status.success { color: #67c23a; }
.tool-status.error { color: #f56c6c; }

.chart-title-inline, .report-title-inline { font-weight: 500; color: #333; }

.tool-input { padding: 0; }
.tool-result { padding: 10px 12px; font-size: 13px; }
.sql-error {
  display: flex; align-items: flex-start; gap: 6px;
  color: #f56c6c; font-size: 13px;
}
.result-meta { font-size: 12px; color: #999; margin-top: 4px; }

.code-block {
  padding: 10px 14px; font-family: 'Menlo','Monaco',monospace; font-size: 12px;
  overflow-x: auto; white-space: pre-wrap; margin: 0;
}
.sql-code { background: #1e1e1e; color: #d4d4d4; }
.python-code { background: #1e1e1e; color: #d4d4d4; }

.python-tool .code-details {
  border-top: 1px solid #e8e8e8; border-bottom: 1px solid #e8e8e8;
}
.code-summary {
  display: flex; align-items: center; gap: 8px; padding: 6px 12px;
  cursor: pointer; user-select: none; font-size: 12px; color: #888;
  background: #fafafa;
}
.code-summary:hover { background: #f0f0f0; }
.code-toggle-icon { font-size: 9px; }
details[open] .code-toggle-icon { display: none; }
.code-lang { color: #409eff; font-weight: 500; }
.code-lines { color: #bbb; }
.copy-btn {
  margin-left: auto; padding: 2px 8px; font-size: 11px;
  background: transparent; color: #999; border: 1px solid #ddd; border-radius: 4px;
  cursor: pointer; transition: all 0.15s;
}
.copy-btn:hover { color: #409eff; border-color: #409eff; }

.error-header {
  display: flex; align-items: center; gap: 6px; margin-bottom: 6px;
  color: #f56c6c; font-weight: 500; font-size: 12px;
}
.error-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 16px; height: 16px; border-radius: 50%;
  background: #f56c6c; color: #fff; font-size: 10px;
}
.error-content {
  margin: 0; font-family: 'Menlo',monospace; font-size: 12px;
  white-space: pre-wrap; max-height: 200px; overflow-y: auto;
}
.error-hint {
  margin-top: 6px; font-size: 11px; color: #e6a23c;
  padding: 4px 8px; background: #fdf6ec; border-radius: 4px;
}
.error-actions {
  display: flex; align-items: center; gap: 8px; margin-top: 8px;
}
.retry-btn {
  padding: 4px 12px; font-size: 12px; font-weight: 500;
  background: #409eff; color: #fff; border: none; border-radius: 4px;
  cursor: pointer; transition: background 0.15s;
}
.retry-btn:hover { background: #66b1ff; }
.error-hint-inline { font-size: 11px; color: #e6a23c; }
.error-text { color: #e6a23c; background: #fdf6ec; padding: 10px 14px; border-radius: 6px; }
.retry-actions { padding: 4px 0 8px; }

.stdout-block {
  background: #f8f8f8; border: 1px solid #e0e0e0; padding: 8px 12px;
  border-radius: 4px; font-family: 'Menlo',monospace; font-size: 12px;
  white-space: pre-wrap; max-height: 300px; overflow-y: auto;
  position: relative;
}
.stdout-block.expanded { max-height: none; }
.stdout-content { margin: 0; }
.expand-btn {
  display: block; width: 100%; padding: 4px 0; margin-top: 6px;
  font-size: 11px; color: #409eff; background: transparent;
  border: 1px solid #ecf5ff; border-radius: 4px; cursor: pointer;
}
.expand-btn:hover { background: #f0f7ff; }
.success-hint {
  font-size: 12px; color: #67c23a; padding: 4px 8px;
  background: #f0f9eb; border-radius: 4px;
}
.stderr-block {
  background: #fef0f0; border: 1px solid #f56c6c; padding: 8px 12px;
  border-radius: 4px; font-family: 'Menlo',monospace; font-size: 12px;
  white-space: pre-wrap; color: #f56c6c; max-height: 200px; overflow-y: auto;
}

.artifact-block { margin: 8px 0; }
.artifact-img { max-width: 100%; border-radius: 6px; border: 1px solid #e8e8e8; }

.chart-tool { max-width: 700px; }
.chart-size-btn {
  margin-left: auto; padding: 2px 8px; font-size: 11px;
  background: transparent; color: #67c23a; border: 1px solid #e1f3d8;
  border-radius: 4px; cursor: pointer;
}
.chart-size-btn:hover { background: #f0f9eb; }
.chart-wrapper :deep(.chart-wrapper) { transition: height 0.3s; }
.chart-expanded :deep(.chart-wrapper) { height: 520px !important; }

.report-block { max-width: 95%; }
.report-content { padding: 0; }
.report-section { border-bottom: 1px solid #f0f0f0; }
.report-section:last-child { border-bottom: none; }
.report-section-header {
  display: flex; align-items: center; gap: 6px; padding: 10px 14px;
  cursor: pointer; user-select: none; font-size: 14px;
}
.report-section-header:hover { background: #fafbfc; }
.section-toggle { font-size: 10px; color: #999; }
.section-number { color: #409eff; font-weight: 600; }
.section-title { font-weight: 500; color: #333; }
.report-section-body {
  padding: 0 14px 12px 30px; font-size: 13px; line-height: 1.7; color: #555;
}
.report-section-body :deep(table) { border-collapse: collapse; width: 100%; margin: 8px 0; font-size: 12px; }
.report-section-body :deep(th), .report-section-body :deep(td) { border: 1px solid #ddd; padding: 5px 8px; text-align: left; }
.report-section-body :deep(th) { background: #f5f7fa; font-weight: 600; }
.report-chart {
  margin: 10px 0; padding: 8px; background: #fafbfc;
  border: 1px solid #e8e8e8; border-radius: 6px; max-width: 600px;
}
.section-sql-details {
  margin: 8px 0; border: 1px solid #f0f0f0; border-radius: 4px;
}
.section-sql-toggle {
  padding: 4px 10px; font-size: 11px; color: #999; cursor: pointer;
  user-select: none;
}
.section-sql-toggle:hover { color: #409eff; }
.section-sql-code {
  margin: 0; padding: 8px 12px; font-family: 'Menlo', monospace;
  font-size: 11px; background: #1e1e1e; color: #d4d4d4;
  white-space: pre-wrap; border-top: 1px solid #f0f0f0;
}
.report-conclusion {
  margin: 8px 14px 12px; padding: 12px; background: #f0f7ff;
  border-radius: 6px; border-left: 3px solid #409eff;
}
.conclusion-header { font-weight: 600; color: #333; margin-bottom: 6px; font-size: 13px; }
.report-loading { padding: 16px 14px; color: #999; font-size: 13px; text-align: center; }

.filter-bar {
  display: flex; gap: 10px; padding: 10px 12px; flex-wrap: wrap; align-items: center;
}
.filter-target { font-size: 12px; color: #999; }
.fullscreen-btn {
  margin-left: auto; padding: 2px 10px; font-size: 11px;
  background: #9b59b6; color: #fff; border: none; border-radius: 4px;
  cursor: pointer; font-weight: 500;
}
.fullscreen-btn:hover { background: #8e44ad; }

.tool-loading {
  padding: 10px 14px; font-size: 13px; color: #888; display: flex; align-items: center; gap: 8px;
}

/* Loading */
.loading-indicator {
  display: flex; align-items: center; gap: 8px; padding: 8px 0; color: #999; font-size: 13px;
}

.spinner {
  width: 14px; height: 14px; border: 2px solid #ddd; border-top-color: #409eff;
  border-radius: 50%; animation: spin 0.6s linear infinite; flex-shrink: 0;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* System */
.system-message {
  font-size: 12px; color: #999; padding: 4px 8px; display: flex; align-items: center; gap: 4px;
}
.system-icon { color: #c0c0c0; }
</style>
