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
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-radius: 12px;
  padding: var(--space-xl);
  margin: 0 20px 20px 20px;
  color: white;
  box-shadow: 0 8px 16px rgba(0,0,0,0.2);
}

.modules-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.modules-title {
  font-size: var(--font-2xl);
  font-weight: 600;
}

.modules-container {
  max-height: 400px;
  overflow-y: auto;
  margin-bottom: 16px;
}

.module-card {
  background: rgba(255, 255, 255, 0.1);
  border: 1px solid rgba(255, 255, 255, 0.2);
  border-radius: var(--radius-lg);
  padding: var(--space-md);
  margin-bottom: 12px;
}

.module-title {
  font-size: var(--font-xl);
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--surface);
}

.module-items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.module-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.2s;
  background: rgba(255, 255, 255, 0.05);
}

.module-item:hover {
  background: rgba(255, 255, 255, 0.15);
  transform: translateX(4px);
}

.instruction-item:hover {
  border-left: 2px solid var(--color-success);
}

.concept-item {
  border-left: 2px solid var(--color-warning);
}

.item-bullet {
  color: var(--color-success);
  font-weight: bold;
}

.item-star {
  color: var(--color-warning);
  font-weight: bold;
}

.item-text {
  flex: 1;
  font-size: var(--font-base);
  line-height: 1.4;
}

.modules-footer {
  display: flex;
  gap: 8px;
  justify-content: center;
}
</style>