<template>
  <Sidebar
    ref="sidebar"
    @selectConversation="onSelectConversation"
    @conversationCreated="onConversationCreated"
    @conversationDeleted="onConversationDeleted"
    @dataSourceChanged="onDataSourceChanged"
  />
  <ChatPanel
    ref="chatPanel"
    :conversationId="currentConvId"
    :dataSourceId="currentDsId"
    @openDashboard="openDashboard"
    @messageCompleted="onMessageCompleted"
  />
  <DashboardView
    v-show="dashboardVisible"
    :visible="dashboardVisible"
    :dashboardId="dashboardId"
    @close="dashboardVisible = false"
  />
</template>

<script setup>
import { ref, defineAsyncComponent } from 'vue'
import Sidebar from './components/Sidebar.vue'
import ChatPanel from './components/ChatPanel.vue'
const DashboardView = defineAsyncComponent(() => import('./components/DashboardView.vue'))
import { fetchConversationMessages, fetchReport, fetchConversations } from './api'

const sidebar = ref(null)
const chatPanel = ref(null)
const currentConvId = ref(null)
const currentDsId = ref(null)
const dashboardVisible = ref(false)
const dashboardId = ref(null)
let historyMsgCounter = 0

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
      chatPanel.value?.clearMessages()
      // Restore history via exposed method
      chatPanel.value?.restoreHistory(formatted, chartsToRestore)
    } else {
      chatPanel.value?.clearMessages()
    }
  } catch {
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

async function onMessageCompleted() {
  sidebar.value?.refreshConversations()
}
</script>
