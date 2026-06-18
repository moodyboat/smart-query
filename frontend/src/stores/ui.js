import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUIStore = defineStore('ui', () => {
  const sidebarOpen = ref(false)
  const isMobile = ref(window.innerWidth < 768)
  const miningVisible = ref(false)
  const dashboardVisible = ref(false)
  const dashboardId = ref(null)
  const dataSourceVisible = ref(false)
  const promptManagerVisible = ref(false)

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

  function openMining(modelId) {
    miningVisible.value = true
    if (modelId) miningInitialModelId.value = modelId
  }

  function consumeMiningInitialModel() {
    const id = miningInitialModelId.value
    miningInitialModelId.value = null
    return id
  }

  function closeMining() {
    miningVisible.value = false
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

  return {
    sidebarOpen,
    isMobile,
    miningVisible,
    dashboardVisible,
    dashboardId,
    dataSourceVisible,
    promptManagerVisible,
    miningInitialModelId,
    toggleSidebar,
    closeSidebar,
    setIsMobile,
    openMining,
    closeMining,
    consumeMiningInitialModel,
    openDashboard,
    closeDashboard,
    openDataSource,
    closeDataSource,
    openPromptManager,
    closePromptManager
  }
})
