import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useUIStore = defineStore('ui', () => {
  const sidebarOpen = ref(false)
  const isMobile = ref(window.innerWidth < 768)
  const miningVisible = ref(false)
  const dashboardVisible = ref(false)
  const dashboardId = ref(null)

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

  function openMining() {
    miningVisible.value = true
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

  return {
    sidebarOpen,
    isMobile,
    miningVisible,
    dashboardVisible,
    dashboardId,
    toggleSidebar,
    closeSidebar,
    setIsMobile,
    openMining,
    closeMining,
    openDashboard,
    closeDashboard
  }
})
