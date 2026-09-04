<template>
  <header class="platform-header">
    <div class="platform-brand">
      <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
      <div class="brand-copy">
        <strong>财务数智模型平台</strong>
        <small>财务数据 · 智能分析 · 模型治理</small>
      </div>
    </div>

    <nav class="platform-navigation" aria-label="机器学习平台主导航">
      <button
        v-for="item in navigation"
        :key="item.value"
        type="button"
        class="platform-nav-item"
        :class="{ active: props.activeDomain === item.value }"
        :aria-current="props.activeDomain === item.value ? 'page' : undefined"
        @click="emit('selectDomain', item.value)"
      >
        <span class="nav-symbol">
          <el-icon :size="21"><component :is="item.icon" /></el-icon>
          <em>{{ item.index }}</em>
        </span>
        <span class="nav-copy"><strong>{{ item.label }}</strong></span>
      </button>
    </nav>
  </header>
</template>

<script setup>
import { computed } from 'vue'
import { Box, ChatDotRound, Connection, DataLine, Setting, View } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user.js'

const props = defineProps({
  activeDomain: { type: String, default: 'workbench' }
})
const emit = defineEmits(['selectDomain'])

const user = useUserStore()
const showMonitor = computed(() => user.canViewMonitor || user.canManageRuntime || user.isAdmin)
const navigation = computed(() => [
  { index: '01', value: 'workbench', label: 'AI 工作台', icon: ChatDotRound },
  { index: '02', value: 'data', label: '数据中心', icon: DataLine },
  { index: '03', value: 'development', label: '开发中心', icon: Connection },
  { index: '04', value: 'models', label: '模型中心', icon: Box },
  ...(showMonitor.value ? [{ index: '05', value: 'monitor', label: '穿透式监控模型', icon: View }] : []),
  { index: showMonitor.value ? '06' : '05', value: 'platform', label: '平台配置', icon: Setting }
])
</script>

<style scoped>
.platform-header {
  min-height: var(--platform-header-height, 76px);
  display: flex;
  align-items: center;
  gap: clamp(12px, 1.1vw, 18px);
  flex-shrink: 0;
  margin: var(--workspace-gap, 10px) var(--workspace-gap, 10px) 0;
  padding: 8px clamp(12px, 1vw, 16px);
  overflow: hidden;
  border: 1px solid rgba(255,255,255,.92);
  border-color: #e4e8ef;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(31,35,41,.05);
}
.platform-brand {
  width: clamp(210px, 16vw, 250px);
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
  padding-left: 3px;
}
.brand-mark {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 3px;
  padding: 10px;
  border-radius: 9px;
  background: #2468f2;
  box-shadow: none;
}
.brand-mark i { width: 3px; border-radius: 2px; background: white; }
.brand-mark i:nth-child(1) { height: 9px; opacity: .74; }
.brand-mark i:nth-child(2) { height: 17px; }
.brand-mark i:nth-child(3) { height: 13px; opacity: .86; }
.brand-copy { min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.brand-copy strong { color: #1d1d1f; font-size: 16px; font-weight: 680; letter-spacing: -.015em; }
.brand-copy small { overflow: hidden; color: #8b8b91; font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.platform-navigation {
  min-width: 0;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: stretch;
  gap: clamp(5px, .55vw, 10px);
  max-width: none;
  margin: 0;
}
.platform-nav-item {
  min-width: 122px;
  max-width: none;
  height: 60px;
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 clamp(10px, 1vw, 16px);
  border: 1px solid transparent;
  position: relative;
  border-radius: 9px;
  color: #68686d;
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: color .16s ease, background .16s ease, border-color .16s ease, transform .16s ease;
}
.platform-nav-item:hover { color: #1d1d1f; background: #f5f7fa; transform: none; }
.platform-nav-item.active {
  color: #006edb;
  border-color: #d7e3fb;
  background: #edf3ff;
  box-shadow: none;
}
.nav-symbol {
  position: relative;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 10px;
  color: #8b8b91;
  background: rgba(118,118,128,.075);
  transition: color .16s ease, background .16s ease, box-shadow .16s ease;
}
.nav-symbol em {
  position: absolute;
  right: -3px;
  bottom: -3px;
  min-width: 17px;
  height: 17px;
  display: grid;
  place-items: center;
  padding: 0 3px;
  border: 2px solid rgba(252,253,255,.96);
  border-radius: 9px;
  color: #7b8492;
  background: #f1f4f8;
  font-size: 7px;
  font-style: normal;
  font-weight: 750;
}
.platform-nav-item.active .nav-symbol { color: white; background: #2468f2; box-shadow: none; }
.platform-nav-item.active .nav-symbol em { color: #2468f2; background: white; }
.nav-copy { min-width: 0; display: flex; align-items: center; }
.nav-copy strong { overflow: hidden; font-size: 13.5px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.platform-nav-item:focus-visible { outline: 0; box-shadow: 0 0 0 3px rgba(36,104,242,.14); }

@media (max-width: 1180px) {
  .platform-header { gap: 10px; padding-inline: 12px; }
  .platform-brand { width: 190px; }
  .brand-mark { width: 38px; height: 38px; padding: 9px; }
  .brand-copy strong { font-size: 15px; }
  .brand-copy small { font-size: 9.5px; }
  .platform-nav-item { min-width: 0; height: 56px; gap: 8px; padding-inline: 8px; }
  .nav-copy strong { font-size: 12px; }
  .nav-symbol { width: 34px; height: 34px; }
}
@media (min-width: 1600px) {
  .platform-brand { width: 280px; }
  .brand-mark { width: 48px; height: 48px; padding: 11px; border-radius: 15px; }
  .brand-copy strong { font-size: 18px; }
  .brand-copy small { font-size: 11px; }
  .platform-navigation { max-width: none; gap: 12px; }
  .platform-nav-item { max-width: none; height: 64px; padding-inline: 16px; }
  .nav-symbol { width: 42px; height: 42px; }
  .nav-copy strong { font-size: 14px; }
}
@media (max-width: 920px) {
  .platform-brand { width: 182px; }
  .brand-copy small { display: none; }
  .platform-navigation { justify-content: flex-start; overflow-x: auto; scrollbar-width: none; }
  .platform-navigation::-webkit-scrollbar { display: none; }
  .platform-nav-item { min-width: 120px; flex: 0 0 auto; }
}
@media (max-width: 767px) {
  .platform-header { min-height: var(--platform-header-height, 62px); margin: 0; padding: 7px 9px; border-width: 0 0 1px; border-radius: 0; }
  .platform-brand { width: auto; }
  .brand-copy { display: none; }
  .brand-mark { width: 36px; height: 36px; padding: 9px; border-radius: 11px; }
  .platform-navigation { justify-content: flex-start; overflow-x: auto; scrollbar-width: none; }
  .platform-navigation::-webkit-scrollbar { display: none; }
  .platform-nav-item { min-width: 116px; height: 50px; padding-inline: 8px; }
  .nav-symbol { width: 32px; height: 32px; }
  .nav-copy strong { font-size: 11px; }
}
</style>
