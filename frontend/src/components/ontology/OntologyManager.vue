<template>
  <div class="ontology-manager">
    <div class="ontology-header">
      <div class="header-left">
        <button class="back-btn" @click="$emit('close')">
          <span class="back-arrow">&larr;</span> 返回
        </button>
        <h3 class="page-title">本体模型配置</h3>
      </div>
      <el-select
        v-model="selectedDsId"
        placeholder="选择数据源"
        size="small"
        style="width: 200px"
      >
        <el-option
          v-for="ds in dataSources"
          :key="ds.id"
          :label="ds.name"
          :value="ds.id"
        />
      </el-select>
    </div>

    <el-tabs v-if="selectedDsId" v-model="activeTab" class="ontology-tabs">
      <el-tab-pane label="指标管理" name="metrics">
        <MetricConfig :data-source-id="selectedDsId" />
      </el-tab-pane>
      <el-tab-pane label="维度管理" name="dimensions">
        <DimensionConfig :data-source-id="selectedDsId" />
      </el-tab-pane>
      <el-tab-pane label="业务术语" name="glossary">
        <GlossaryConfig :data-source-id="selectedDsId" />
      </el-tab-pane>
      <el-tab-pane label="指标定义表" name="indicator">
        <IndicatorTableConfig :data-source-id="selectedDsId" />
      </el-tab-pane>
    </el-tabs>

    <div v-else class="ontology-empty">
      <div class="empty-icon">&#128218;</div>
      <p>请先选择数据源</p>
      <p class="empty-hint">选择一个数据源后，可以配置指标、维度和业务术语</p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import MetricConfig from './MetricConfig.vue'
import DimensionConfig from './DimensionConfig.vue'
import GlossaryConfig from './GlossaryConfig.vue'
import IndicatorTableConfig from './IndicatorTableConfig.vue'

defineProps({
  dataSources: {
    type: Array,
    default: () => []
  }
})

defineEmits(['close'])

const selectedDsId = ref(null)
const activeTab = ref('metrics')
</script>

<style scoped>
.ontology-manager {
  flex: 1;
  min-width: 0;
  background: var(--bg);
  display: flex;
  flex-direction: column;
}

.ontology-header {
  height: 52px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--space-xl);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.back-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: var(--font-md);
  color: var(--primary);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background 0.15s;
}

.back-btn:hover {
  background: var(--primary-light);
}

.back-arrow {
  font-size: var(--font-lg);
}

.page-title {
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--text-primary);
}

.ontology-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0 var(--space-xl);
}

.ontology-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
}

.ontology-tabs :deep(.el-tab-pane) {
  height: 100%;
}

.ontology-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: var(--text-muted);
  font-size: var(--font-md);
}

.empty-icon {
  font-size: 48px;
  margin-bottom: var(--space-md);
}

.empty-hint {
  font-size: var(--font-sm);
  color: var(--text-muted);
  margin-top: var(--space-xs);
}
</style>
