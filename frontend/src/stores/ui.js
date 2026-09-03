import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUIStore = defineStore('ui', () => {
  const sidebarOpen = ref(false)
  const isMobile = ref(window.innerWidth < 768)
  const orchestrationHubVisible = ref(false)
  const orchestrationHubSection = ref('schedule')
  const dagInitialOperatorVersionId = ref(null)
  const dashboardVisible = ref(false)
  const dashboardId = ref(null)
  const dataSourceVisible = ref(false)
  const promptManagerVisible = ref(false)
  const scenarioManagerVisible = ref(false)

  function toggleSidebar() {
    sidebarOpen.value = !sidebarOpen.value
  }

  function closeSidebar() {
    sidebarOpen.value = false
  }

  function setIsMobile(val) {
    isMobile.value = val
    if (!val) sidebarOpen.value = false
  }

  const miningInitialModelId = ref(null)

  function openWorkbench(section = 'schedule') {
    orchestrationHubSection.value = section
    orchestrationHubVisible.value = true
  }

  function closeWorkbench() {
    orchestrationHubVisible.value = false
    dagInitialOperatorVersionId.value = null
  }

  function openMining(modelId) {
    if (modelId) miningInitialModelId.value = modelId
    openWorkbench('schedule')
  }

  function consumeMiningInitialModel() {
    const id = miningInitialModelId.value
    miningInitialModelId.value = null
    return id
  }

  function closeMining() {
    closeWorkbench()
  }

  function openOutputCenter() {
    openWorkbench('pipelines')
  }

  function closeOutputCenter() {
    closeWorkbench()
  }

  function openDependencyCenter() {
    openWorkbench('governance')
  }

  function closeDependencyCenter() {
    closeWorkbench()
  }

  function openDagDesigner(operatorVersionId = null) {
    dagInitialOperatorVersionId.value = operatorVersionId || null
    openWorkbench('pipelines')
  }

  function closeDagDesigner() {
    closeWorkbench()
  }

  function openDashboard(id) {
    dashboardId.value = id
    dashboardVisible.value = true
  }

  function closeDashboard() {
    dashboardVisible.value = false
  }

  function openDataSource() {
    dataSourceVisible.value = true
  }

  function closeDataSource() {
    dataSourceVisible.value = false
  }

  function openPromptManager() {
    promptManagerVisible.value = true
  }

  function closePromptManager() {
    promptManagerVisible.value = false
  }

  function openScenarioManager() {
    scenarioManagerVisible.value = true
  }

  function closeScenarioManager() {
    scenarioManagerVisible.value = false
  }

  return {
    sidebarOpen,
    isMobile,
    orchestrationHubVisible,
    orchestrationHubSection,
    dagInitialOperatorVersionId,
    dashboardVisible,
    dashboardId,
    dataSourceVisible,
    promptManagerVisible,
    scenarioManagerVisible,
    miningInitialModelId,
    toggleSidebar,
    closeSidebar,
    setIsMobile,
    openWorkbench,
    closeWorkbench,
    openMining,
    closeMining,
    openOutputCenter,
    closeOutputCenter,
    openDependencyCenter,
    closeDependencyCenter,
    openDagDesigner,
    closeDagDesigner,
    consumeMiningInitialModel,
    openDashboard,
    closeDashboard,
    openDataSource,
    closeDataSource,
    openPromptManager,
    closePromptManager,
    openScenarioManager,
    closeScenarioManager
  }
})
