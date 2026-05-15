import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchConversationMessages,
  fetchReport,
  fetchConversationCharts,
  fetchConversationReports,
  fetchConversationDashboards,
  fetchConversationTraces
} from '../api'

export const useConversationStore = defineStore('conversation', () => {
  const currentConvId = ref(null)
  const currentDsId = ref(null)
  let historyMsgCounter = 0

  // 消息缓存: convId → { formatted, chartsToRestore }
  const messageCache = new Map()

  function setCurrentConversation(convId) {
    currentConvId.value = convId
  }

  function setDataSource(dsId) {
    currentDsId.value = dsId
  }

  function clearCurrent() {
    currentConvId.value = null
  }

  /**
   * Invalidate cache for a specific conversation (e.g. after new message).
   */
  function invalidateCache(convId) {
    messageCache.delete(convId)
  }

  /**
   * Load conversation history with caching.
   * Returns cached data if available, otherwise fetches from API.
   * Falls back to JSONL trace data if API fails.
   */
  async function loadConversationHistory(convId) {
    // Return cached data if available
    const cached = messageCache.get(convId)
    if (cached) {
      return cached
    }

    try {
      const result = await loadFromApi(convId)
      messageCache.set(convId, result)
      return result
    } catch (e) {
      console.warn('API history load failed, trying trace fallback:', e)
      return loadFromTraceFallback(convId)
    }
  }

  async function loadFromApi(convId) {
    const msgs = await fetchConversationMessages(convId)
    if (!msgs?.length) return { formatted: [], chartsToRestore: [] }

    const chartsToRestore = []
    const formatted = msgs.map(m => {
      if (m.role === 'user') {
        return { _id: ++historyMsgCounter, type: 'user_text', content: m.content, loading: false }
      }

      const content = []
      let textContent = m.content || ''

      if (m.metadata) {
        try {
          const blocks = typeof m.metadata === 'string' ? JSON.parse(m.metadata) : m.metadata
          for (const block of blocks) {
            content.push({ ...block })
            if (block.name === 'generate_chart' && block.result?.chartId && block.result?.echartsOption) {
              chartsToRestore.push({
                id: block.result.chartId,
                title: block.result.title,
                echartsOption: block.result.echartsOption
              })
            }
          }
        } catch { /* metadata parse failed */ }
      }

      if (textContent) {
        content.unshift({ type: 'text', text: textContent })
      }

      for (const block of content) {
        if (block.name === 'generate_report' && block.result?.reportId) {
          if (block.result.sections?.length && (block.result.sections[0].section_title || block.result.sections[0].title)) {
            block.result.sections = block.result.sections.map(s => ({
              title: s.section_title || s.title || '',
              content: s.section_content || s.content || '',
              sql: s.sql_used || '',
              chartType: s.chart_type || '',
              chartId: s.chart_id || null
            }))
          } else {
            const rid = block.result.reportId
            fetchReport(rid).then(report => {
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
        }
      }

      return {
        _id: ++historyMsgCounter,
        type: 'assistant',
        content,
        streamingText: '',
        loading: false,
        _streaming: false
      }
    })

    await restoreLegacyArtifacts(convId, formatted, chartsToRestore)
    return { formatted, chartsToRestore }
  }

  /**
   * Fallback: restore conversation from JSONL trace data when API messages are unavailable.
   * Reconstructs a simplified message list from trace events.
   */
  async function loadFromTraceFallback(convId) {
    try {
      const data = await fetchConversationTraces(convId)
      const events = data?.events || []
      if (!events.length) return { formatted: [], chartsToRestore: [] }

      const formatted = []
      const chartsToRestore = []
      let currentAssistantContent = []

      for (const evt of events) {
        if (evt.event === 'user_message' && evt.payload?.content) {
          // Flush previous assistant content
          if (currentAssistantContent.length > 0) {
            formatted.push({
              _id: ++historyMsgCounter,
              type: 'assistant',
              content: currentAssistantContent,
              streamingText: '',
              loading: false,
              _streaming: false
            })
            currentAssistantContent = []
          }
          formatted.push({
            _id: ++historyMsgCounter,
            type: 'user_text',
            content: evt.payload.content,
            loading: false
          })
        } else if (evt.event === 'thinking' && evt.payload?.content) {
          currentAssistantContent.push({ type: 'text', text: evt.payload.content })
        } else if (evt.event === 'sql_executing' && evt.payload?.sql) {
          currentAssistantContent.push({
            type: 'tool_use',
            name: 'execute_sql',
            _id: 'sql-' + ++historyMsgCounter,
            input: { sql: evt.payload.sql },
            status: 'running'
          })
        } else if (evt.event === 'sql_result') {
          const sqlBlock = currentAssistantContent.findLast(b => b.name === 'execute_sql' && b.status === 'running')
          if (sqlBlock) {
            sqlBlock.status = evt.payload?.success !== false ? 'success' : 'error'
            sqlBlock.result = {
              totalRows: evt.payload?.totalRows || 0,
              error: evt.payload?.error,
              summary: evt.payload?.summary || ''
            }
          }
        } else if (evt.event === 'chart_generated' && evt.payload?.chartId) {
          currentAssistantContent.push({
            type: 'tool_use',
            name: 'generate_chart',
            _id: 'chart-' + evt.payload.chartId,
            result: {
              chartId: evt.payload.chartId,
              title: evt.payload.title || '',
              chartType: evt.payload.chartType || ''
            },
            status: 'success'
          })
        } else if (evt.event === 'report_generated' && evt.payload?.reportId) {
          currentAssistantContent.push({
            type: 'tool_use',
            name: 'generate_report',
            _id: 'report-' + evt.payload.reportId,
            result: {
              reportId: evt.payload.reportId,
              title: evt.payload.title || '',
              sectionCount: evt.payload.sectionCount || 0
            },
            status: 'success'
          })
        } else if (evt.event === 'error' && evt.payload?.message) {
          currentAssistantContent.push({ type: 'text', text: `[错误] ${evt.payload.message}` })
        }
      }

      // Flush remaining assistant content
      if (currentAssistantContent.length > 0) {
        formatted.push({
          _id: ++historyMsgCounter,
          type: 'assistant',
          content: currentAssistantContent,
          streamingText: '',
          loading: false,
          _streaming: false
        })
      }

      return { formatted, chartsToRestore }
    } catch (e) {
      console.error('Trace fallback also failed:', e)
      return { formatted: [], chartsToRestore: [] }
    }
  }

  async function restoreLegacyArtifacts(convId, formatted, chartsToRestore) {
    const plainTextAssistants = formatted.filter(
      m => m.type === 'assistant' && m.content.every(b => b.type === 'text')
    )
    if (plainTextAssistants.length === 0) return

    try {
      const [reports, charts, dashboards] = await Promise.all([
        fetchConversationReports(convId).catch(() => []),
        fetchConversationCharts(convId).catch(() => []),
        fetchConversationDashboards(convId).catch(() => [])
      ])

      const knownChartIds = new Set(
        formatted.flatMap(m => m.content)
          .filter(b => b.name === 'generate_chart' && b.result?.chartId)
          .map(b => b.result.chartId)
      )

      const newCharts = (charts || []).filter(c => !knownChartIds.has(c.id))
      if (newCharts.length > 0) {
        for (const chart of newCharts) {
          chartsToRestore.push({ id: chart.id, title: chart.title, echartsOption: chart.echartsOption })
          const msg = plainTextAssistants.find(m =>
            m.content.some(b => b.text?.includes(chart.title))
          )
          if (msg) {
            msg.content.push({
              type: 'tool_use',
              name: 'generate_chart',
              _id: 'chart-' + chart.id,
              input: { title: chart.title },
              result: { chartId: chart.id, title: chart.title, echartsOption: chart.echartsOption },
              status: 'success'
            })
          }
        }
      }

      for (const report of (reports || [])) {
        const msg = plainTextAssistants.find(m =>
          m.content.some(b => b.text?.includes(report.title || '报告'))
        )
        if (!msg) continue
        let sections = []
        let conclusion = ''
        try {
          const full = await fetchReport(report.id)
          if (full?.sections) {
            const parsed = JSON.parse(full.sections)
            sections = parsed.map(s => ({
              title: s.section_title || s.title || '',
              content: s.section_content || s.content || '',
              sql: s.sql_used || '',
              chartType: s.chart_type || '',
              chartId: s.chart_id || null
            }))
            conclusion = full.conclusion || ''
          }
        } catch { /* report fetch failed */ }
        msg.content.push({
          type: 'tool_use',
          name: 'generate_report',
          _id: 'report-' + report.id,
          input: {},
          result: { reportId: report.id, title: report.title, sections, conclusion },
          status: 'success'
        })
      }

      for (const dash of (dashboards || [])) {
        const msg = plainTextAssistants.find(m =>
          m.content.some(b => b.text?.includes(dash.title || '仪表盘'))
        )
        if (!msg) continue
        msg.content.push({
          type: 'tool_use',
          name: 'generate_dashboard',
          _id: 'dash-' + dash.id,
          input: {},
          result: { dashboardId: dash.id, title: dash.title, layout: dash.layout, chartIds: dash.chartIds },
          status: 'success'
        })
      }
    } catch (e) { console.warn('Legacy artifact restore failed:', e) }
  }

  return {
    currentConvId,
    currentDsId,
    setCurrentConversation,
    setDataSource,
    clearCurrent,
    invalidateCache,
    loadConversationHistory
  }
})
