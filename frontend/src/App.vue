<template>
  <Sidebar
    ref="sidebar"
    :class="{ 'sidebar-open': sidebarOpen }"
    @selectConversation="(id) => { onSelectConversation(id); sidebarOpen = false }"
    @conversationCreated="onConversationCreated"
    @conversationDeleted="onConversationDeleted"
    @dataSourceChanged="onDataSourceChanged"
    @openMining="miningVisible = true"
  />
  <ChatPanel
    v-show="!miningVisible"
    ref="chatPanel"
    :conversationId="currentConvId"
    :dataSourceId="currentDsId"
    :showSidebarToggle="isMobile"
    @openDashboard="openDashboard"
    @messageCompleted="onMessageCompleted"
    @toggleSidebar="sidebarOpen = !sidebarOpen"
  />
  <div v-if="isMobile && sidebarOpen" class="sidebar-backdrop" @click="sidebarOpen = false"></div>
  <DashboardView
    v-if="dashboardVisible"
    :visible="dashboardVisible"
    :dashboardId="dashboardId"
    @close="dashboardVisible = false"
  />
  <MiningManager
    v-if="miningVisible"
    @close="miningVisible = false"
  />
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import Sidebar from './components/Sidebar.vue'
import ChatPanel from './components/ChatPanel.vue'
import DashboardView from './components/DashboardView.vue'
import MiningManager from './components/MiningManager.vue'
import { fetchConversationMessages, fetchReport, fetchConversations,
  fetchConversationCharts, fetchConversationReports, fetchConversationDashboards } from './api'

const sidebar = ref(null)
const chatPanel = ref(null)
const currentConvId = ref(null)
const currentDsId = ref(null)
const dashboardVisible = ref(false)
const dashboardId = ref(null)
const miningVisible = ref(false)
const sidebarOpen = ref(false)
const isMobile = ref(window.innerWidth < 768)
let historyMsgCounter = 0

function onResize() {
  isMobile.value = window.innerWidth < 768
  if (!isMobile.value) sidebarOpen.value = false
}
onMounted(() => window.addEventListener('resize', onResize))
onUnmounted(() => window.removeEventListener('resize', onResize))

function onDataSourceChanged(dsId) {
  currentDsId.value = dsId
}

async function onSelectConversation(convId) {
  currentConvId.value = convId
  sidebar.value?.setCurrentConversation(convId)

  try {
    const msgs = await fetchConversationMessages(convId)
    if (msgs?.length) {
      const chartsToRestore = []
      const formatted = msgs.map(m => {
        if (m.role === 'user') {
          return { _id: ++historyMsgCounter, type: 'user_text', content: m.content, loading: false }
        }
        // Assistant message: parse metadata for tool blocks
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

        // Text content becomes a proper text block, not streamingText
        if (textContent) {
          content.unshift({ type: 'text', text: textContent })
        }

        // Load report sections if not already in metadata
        for (const block of content) {
          if (block.name === 'generate_report' && block.result?.reportId) {
            // If metadata already has sections with content, map field names
            if (block.result.sections?.length && (block.result.sections[0].section_title || block.result.sections[0].title)) {
              block.result.sections = block.result.sections.map(s => ({
                title: s.section_title || s.title || '',
                content: s.section_content || s.content || '',
                sql: s.sql_used || '',
                chartType: s.chart_type || '',
                chartId: s.chart_id || null
              }))
            } else {
              // Fallback: fetch from API
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
              }).catch(e => console.warn('Failed to fetch report sections:', e))
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
      chatPanel.value?.clearMessages()

      // Fallback: load reports/charts/dashboards for messages without metadata BEFORE rendering
      await restoreLegacyArtifacts(convId, formatted, chartsToRestore)

      // Restore history via exposed method (renders everything)
      chatPanel.value?.restoreHistory(formatted, chartsToRestore)
    } else {
      chatPanel.value?.clearMessages()
    }
  } catch (e) {
    console.error('Failed to load conversation messages:', e)
    chatPanel.value?.clearMessages()
  }
}

function onConversationCreated(convId) {
  currentConvId.value = convId
  chatPanel.value?.clearMessages()
}

function onConversationDeleted(convId) {
  if (currentConvId.value === convId) {
    currentConvId.value = null
    chatPanel.value?.clearMessages()
  }
}

function openDashboard(id) {
  dashboardId.value = id
  dashboardVisible.value = true
}

async function restoreLegacyArtifacts(convId, formatted, chartsToRestore) {
  // Find assistant messages that have no tool blocks (plain text only)
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

    // Already-collected chart IDs from metadata
    const knownChartIds = new Set(
      formatted.flatMap(m => m.content)
        .filter(b => b.name === 'generate_chart' && b.result?.chartId)
        .map(b => b.result.chartId)
    )

    // Add charts not already restored via metadata
    const newCharts = (charts || []).filter(c => !knownChartIds.has(c.id))
    if (newCharts.length > 0) {
      for (const chart of newCharts) {
        chartsToRestore.push({ id: chart.id, title: chart.title, echartsOption: chart.echartsOption })
        // Find the assistant message that mentions this chart's title
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
      // Update pendingCharts
      const pc = chatPanel.value?.pendingCharts || {}
      for (const c of chartsToRestore) pc[c.id] = c
    }

    // Add reports (fetch sections synchronously)
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

    // Add dashboards
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

async function onMessageCompleted() {
  sidebar.value?.refreshConversations()
}
</script>

<style scoped>
.sidebar-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 899;
}
</style>
