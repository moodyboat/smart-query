<template>
  <div class="workspace-shell">
    <PlatformHeader :active-domain="activeDomain" @selectDomain="selectDomain" />

    <div class="workspace-body">
      <Sidebar
        ref="sidebar"
        :active-domain="activeDomain"
        :active-section="activeSection"
        :active-tool="activeTool"
        :class="{ 'sidebar-open': ui.sidebarOpen }"
        @selectConversation="(id) => { onSelectConversation(id); ui.closeSidebar() }"
        @conversationCreated="onConversationCreated"
        @conversationDeleted="onConversationDeleted"
        @dataSourceChanged="(dsId) => conv.setDataSource(dsId)"
        @openChat="openChat"
        @openWorkbench="openWorkbench"
        @openMonitorSection="openMonitorSection"
        @openDataSource="openDataSource"
        @openPromptManager="openPromptManager"
        @openScenarioManager="openScenarioManager"
        @openUserManagement="openUserManagement"
        @logout="onLogout"
      />

      <main class="workspace-content">
        <DataSourceManager
          v-if="ui.dataSourceVisible"
          :show-sidebar-toggle="ui.isMobile"
          @close="openChat"
          @toggleSidebar="ui.toggleSidebar()"
        />
        <ScenarioManager
          v-else-if="ui.scenarioManagerVisible"
          :show-sidebar-toggle="ui.isMobile"
          @close="openDataSource"
          @toggleSidebar="ui.toggleSidebar()"
        />
        <PromptManager
          v-else-if="ui.promptManagerVisible"
          :show-sidebar-toggle="ui.isMobile"
          @close="openChat"
          @toggleSidebar="ui.toggleSidebar()"
        />
        <UserManagement
          v-else-if="ui.userManagementVisible"
          embedded
          :show-sidebar-toggle="ui.isMobile"
          @close="openChat"
          @toggleSidebar="ui.toggleSidebar()"
        />
        <PenetratingModelMonitor
          v-else-if="selectedDomain === 'monitor'"
          :section="monitorSection"
          :show-sidebar-toggle="ui.isMobile"
          @toggleSidebar="ui.toggleSidebar()"
        />
        <ChatPanel
          v-show="selectedDomain !== 'monitor' && !ui.dataSourceVisible && !ui.scenarioManagerVisible && !ui.promptManagerVisible && !ui.userManagementVisible && !ui.orchestrationHubVisible"
          ref="chatPanel"
          :conversationId="conv.currentConvId"
          :dataSourceId="conv.currentDsId"
          :showSidebarToggle="ui.isMobile"
          @openDashboard="(id) => ui.openDashboard(id)"
          @messageCompleted="onMessageCompleted"
          @conversationCreated="onConversationCreated"
          @toggleSidebar="ui.toggleSidebar()"
        />
        <OrchestrationGovernanceHub
          v-if="!ui.dataSourceVisible && !ui.promptManagerVisible && !ui.userManagementVisible && ui.orchestrationHubVisible"
          :conversation-id="conv.currentConvId"
          :initial-section="ui.orchestrationHubSection"
          :initial-operator-version-id="ui.dagInitialOperatorVersionId"
          :show-sidebar-toggle="ui.isMobile"
          @close="openChat"
          @toggleSidebar="ui.toggleSidebar()"
        />
      </main>
    </div>

    <div v-if="ui.isMobile && ui.sidebarOpen" class="sidebar-backdrop" @click="ui.closeSidebar()"></div>
    <DashboardView
      v-if="ui.dashboardVisible"
      :visible="ui.dashboardVisible"
      :dashboardId="ui.dashboardId"
      @close="ui.closeDashboard()"
    />
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import Sidebar from '../components/Sidebar.vue'
import PlatformHeader from '../components/PlatformHeader.vue'
import ChatPanel from '../components/ChatPanel.vue'
import DashboardView from '../components/DashboardView.vue'
import OrchestrationGovernanceHub from '../components/OrchestrationGovernanceHub.vue'
import PenetratingModelMonitor from '../components/PenetratingModelMonitor.vue'
import DataSourceManager from '../components/DataSourceManager.vue'
import PromptManager from '../components/PromptManager.vue'
import UserManagement from './admin/UserManagement.vue'
import ScenarioManager from '../components/ScenarioManager.vue'
import { useConversationStore } from '../stores/conversation'
import { useUIStore } from '../stores/ui'
import { useUserStore } from '../stores/user'
import { preloadScenarios } from '../config/scenarios.js'
import { fetchConversationMeta } from '../api'
import { ROUTES } from '../constants.js'

const router = useRouter()
const conv = useConversationStore()
const ui = useUIStore()
const userStore = useUserStore()

const sidebar = ref(null)
const chatPanel = ref(null)
const selectedDomain = ref('workbench')
const monitorSection = ref('operations')
const activeSection = computed(() => ui.orchestrationHubVisible ? ui.orchestrationHubSection : 'chat')
const activeDomain = computed(() => {
  if (ui.dataSourceVisible || ui.scenarioManagerVisible) return 'data'
  if (ui.promptManagerVisible || ui.userManagementVisible) return 'platform'
  if (selectedDomain.value === 'monitor') return 'monitor'
  if (ui.orchestrationHubVisible) {
    return ['pipelines', 'operators'].includes(ui.orchestrationHubSection) ? 'development' : 'models'
  }
  return selectedDomain.value
})
const activeTool = computed(() => {
  if (ui.dataSourceVisible) return 'data-source'
  if (ui.scenarioManagerVisible) return 'scenarios'
  if (ui.promptManagerVisible) return 'prompts'
  if (ui.userManagementVisible) return 'users'
  if (selectedDomain.value === 'monitor') return `monitor-${monitorSection.value}`
  return activeSection.value
})

function selectDomain(domain) {
  selectedDomain.value = domain
  if (domain === 'workbench') openChat()
  else if (domain === 'data') openDataSource()
  else if (domain === 'development') openWorkbench('pipelines')
  else if (domain === 'models') openWorkbench('schedule')
  else if (domain === 'monitor') openMonitor()
  else if (domain === 'platform') openPromptManager()
}

function openMonitor() {
  selectedDomain.value = 'monitor'
  monitorSection.value = 'operations'
  ui.closeDataSource()
  ui.closePromptManager()
  ui.closeUserManagement()
  ui.closeScenarioManager()
  ui.closeWorkbench()
  ui.closeSidebar()
}

function openMonitorSection(section) {
  selectedDomain.value = 'monitor'
  monitorSection.value = section === 'view' ? 'view' : 'operations'
  ui.closeDataSource()
  ui.closePromptManager()
  ui.closeUserManagement()
  ui.closeScenarioManager()
  ui.closeWorkbench()
  ui.closeSidebar()
}

function openChat() {
  selectedDomain.value = 'workbench'
  ui.closeDataSource()
  ui.closePromptManager()
  ui.closeUserManagement()
  ui.closeScenarioManager()
  ui.closeWorkbench()
  ui.closeSidebar()
}

function openWorkbench(section) {
  selectedDomain.value = ['pipelines', 'operators'].includes(section) ? 'development' : 'models'
  ui.closeDataSource()
  ui.closePromptManager()
  ui.closeUserManagement()
  ui.closeScenarioManager()
  ui.openWorkbench(section)
  ui.closeSidebar()
}

function openDataSource() {
  selectedDomain.value = 'data'
  ui.closeWorkbench()
  ui.closePromptManager()
  ui.closeUserManagement()
  ui.closeScenarioManager()
  ui.openDataSource()
  ui.closeSidebar()
}

function openScenarioManager() {
  selectedDomain.value = 'data'
  ui.closeDataSource()
  ui.closeWorkbench()
  ui.closePromptManager()
  ui.closeUserManagement()
  ui.openScenarioManager()
  ui.closeSidebar()
}

function openPromptManager() {
  selectedDomain.value = 'platform'
  ui.closeDataSource()
  ui.closeWorkbench()
  ui.closeUserManagement()
  ui.closeScenarioManager()
  ui.openPromptManager()
  ui.closeSidebar()
}

function openUserManagement() {
  selectedDomain.value = 'platform'
  ui.closeDataSource()
  ui.closeWorkbench()
  ui.closePromptManager()
  ui.closeScenarioManager()
  ui.openUserManagement()
  ui.closeSidebar()
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
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
})

async function onSelectConversation(convId) {
  conv.setCurrentConversation(convId)
  sidebar.value?.setCurrentConversation(convId)

  // 恢复会话绑定的场景（刷新页面/直接 URL 进入时 Sidebar 走不到 handleConvClick）
  try {
    const meta = await fetchConversationMeta(convId)
    if (meta?.scenario) conv.setScenario(meta.scenario)
  } catch (e) { /* scenario 恢复失败不阻塞消息加载 */ }

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
  sidebar.value?.refreshConversations()
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
.workspace-shell {
  --workspace-gap: clamp(8px, .7vw, 12px);
  --sidebar-width: clamp(296px, 22vw, 400px);
  --platform-header-height: clamp(72px, 5.5vw, 90px);
  width: 100%;
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--app-background);
}
.workspace-body {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  overflow: hidden;
}
.workspace-content {
  min-width: 0;
  min-height: 0;
  flex: 1;
  display: flex;
  align-items: stretch;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior-y: contain;
  padding: var(--workspace-gap) var(--workspace-gap) var(--workspace-gap) 0;
}
.sidebar-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  z-index: 899;
}
@media (max-width: 1180px) {
  .workspace-shell {
    --workspace-gap: 8px;
    --sidebar-width: 270px;
    --platform-header-height: 70px;
  }
}
@media (max-width: 920px) {
  .workspace-shell { --sidebar-width: 248px; }
}
@media (max-width: 767px) {
  .workspace-shell {
    --workspace-gap: 0px;
    --platform-header-height: 62px;
  }
  .workspace-body { position: relative; }
  .workspace-content { padding: 0; }
}
</style>
