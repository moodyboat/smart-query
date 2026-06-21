<template>
  <Sidebar
    ref="sidebar"
    :class="{ 'sidebar-open': ui.sidebarOpen }"
    @selectConversation="(id) => { onSelectConversation(id); ui.closeSidebar() }"
    @conversationCreated="onConversationCreated"
    @conversationDeleted="onConversationDeleted"
    @dataSourceChanged="(dsId) => conv.setDataSource(dsId)"
    @openMining="ui.openMining()"
    @openDataSource="ui.openDataSource()"
    @openPromptManager="ui.openPromptManager()"
    @openScenarioManager="ui.openScenarioManager()"
    @goHome="goHome"
    @logout="onLogout"
  />
  <ChatPanel
    v-show="!ui.miningVisible"
    ref="chatPanel"
    :conversationId="conv.currentConvId"
    :dataSourceId="conv.currentDsId"
    :showSidebarToggle="ui.isMobile"
    @openDashboard="(id) => ui.openDashboard(id)"
    @messageCompleted="onMessageCompleted"
    @toggleSidebar="ui.toggleSidebar()"
  />
  <div v-if="ui.isMobile && ui.sidebarOpen" class="sidebar-backdrop" @click="ui.closeSidebar()"></div>
  <DashboardView
    v-if="ui.dashboardVisible"
    :visible="ui.dashboardVisible"
    :dashboardId="ui.dashboardId"
    @close="ui.closeDashboard()"
  />
  <MiningManager
    v-if="ui.miningVisible"
    @close="ui.closeMining()"
  />
  <DataSourceManager
    v-if="ui.dataSourceVisible"
    @close="ui.closeDataSource()"
  />
  <PromptManager
    v-if="ui.promptManagerVisible"
    @close="ui.closePromptManager()"
  />
  <ScenarioManager
    v-if="ui.scenarioManagerVisible"
    @close="ui.closeScenarioManager()"
  />
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Sidebar from '../components/Sidebar.vue'
import ChatPanel from '../components/ChatPanel.vue'
import DashboardView from '../components/DashboardView.vue'
import MiningManager from '../components/MiningManager.vue'
import DataSourceManager from '../components/DataSourceManager.vue'
import PromptManager from '../components/PromptManager.vue'
import ScenarioManager from '../components/ScenarioManager.vue'
import { useConversationStore } from '../stores/conversation'
import { useUIStore } from '../stores/ui'
import { useUserStore } from '../stores/user'
import { preloadScenarios } from '../config/scenarios.js'
import { ROUTES } from '../constants.js'

const router = useRouter()
const conv = useConversationStore()
const ui = useUIStore()
const userStore = useUserStore()

const sidebar = ref(null)
const chatPanel = ref(null)

function goHome() {
  router.push(ROUTES.HOME)
}

async function onLogout() {
  await userStore.logout()
  router.push(ROUTES.LOGIN)
}

function onResize() {
  ui.setIsMobile(window.innerWidth < 768)
}
onMounted(() => {
  window.addEventListener('resize', onResize)
  // 预热场景缓存（后端按当前用户角色返回可用场景）
  preloadScenarios()
})
onUnmounted(() => window.removeEventListener('resize', onResize))

async function onSelectConversation(convId) {
  conv.setCurrentConversation(convId)
  sidebar.value?.setCurrentConversation(convId)

  try {
    const { formatted, chartsToRestore } = await conv.loadConversationHistory(convId)
    chatPanel.value?.clearMessages()
    chatPanel.value?.restoreHistory(formatted, chartsToRestore)
  } catch (e) {
    console.error('Failed to load conversation messages:', e)
    chatPanel.value?.clearMessages()
  }
}

function onConversationCreated(convId) {
  conv.setCurrentConversation(convId)
  chatPanel.value?.clearMessages()
}

function onConversationDeleted(convId) {
  if (conv.currentConvId === convId) {
    conv.clearCurrent()
    chatPanel.value?.clearMessages()
  }
}

function onMessageCompleted() {
  sidebar.value?.refreshConversations()
  conv.invalidateCache(conv.currentConvId)
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
