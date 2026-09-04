<template>
  <div v-if="showModules && modules.length > 0" class="scenario-modules-panel">
    <div class="modules-header">
      <span class="modules-title">🎯 智能助手指令模块</span>
      <el-button text @click="hideModules" :icon="Close">隐藏</el-button>
    </div>

    <div class="modules-container">
      <div
        v-for="(module, index) in modules"
        :key="index"
        class="module-card"
      >
        <div class="module-title">{{ module.title }}</div>
        <div class="module-items">
          <div
            v-for="(item, itemIndex) in module.items"
            :key="itemIndex"
            class="module-item"
            :class="getItemClass(item.type)"
            @click="handleItemClick(item)"
          >
            <span v-if="item.type === 'instruction'" class="item-bullet">▸</span>
            <span v-else-if="item.type === 'concept'" class="item-star">★</span>
            <span class="item-text">{{ item.text }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="modules-footer">
      <el-button type="primary" size="small" @click="expandAllModules" :icon="Plus">
        展开所有模块
      </el-button>
      <el-button size="small" @click="hideModules">
        暂时隐藏
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Close, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  modules: {
    type: Array,
    default: () => []
  },
  showModules: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['hide', 'expandAll', 'itemClick'])

const getItemClass = (type) => {
  return type === 'concept' ? 'concept-item' : 'instruction-item'
}

const handleItemClick = (item) => {
  emit('itemClick', item)
}

const hideModules = () => {
  emit('hide')
}

const expandAllModules = () => {
  emit('expandAll')
}
</script>

<style scoped>
.scenario-modules-panel {
  margin: 18px 0 4px;
  padding: 15px;
  border: 1px solid #e4e8ef;
  border-radius: 9px;
  color: var(--text-primary);
  background: #fafbfc;
}

.modules-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 11px;
}

.modules-title {
  color: #235f99;
  font-size: 11px;
  font-weight: 650;
}

.modules-container {
  max-height: 250px;
  overflow-y: auto;
  margin-bottom: 11px;
}

.module-card {
  margin-bottom: 8px;
  padding: 10px;
  border: 1px solid #e4e8ef;
  border-radius: 8px;
  background: #fff;
}

.module-title {
  margin-bottom: 7px;
  color: #333336;
  font-size: 10px;
  font-weight: 620;
}

.module-items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.module-item {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 5px 7px;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
  color: #66666b;
  background: transparent;
}

.module-item:hover {
  color: #1d1d1f;
  background: rgba(0,113,227,.065);
}

.instruction-item:hover {
  border-left: 2px solid #0071e3;
}

.concept-item {
  border-left: 2px solid var(--color-warning);
}

.item-bullet {
  color: #0071e3;
  font-weight: bold;
}

.item-star {
  color: var(--color-warning);
  font-weight: bold;
}

.item-text {
  flex: 1;
  font-size: 10px;
  line-height: 1.45;
}

.modules-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
