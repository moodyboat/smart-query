<template>
  <div class="editor-toolbar">
    <el-button size="small" @click="$emit('close')">← 返回</el-button>
    <el-input :model-value="name" size="small" style="width: 200px" @update:model-value="$emit('update:name', $event)" />
    <div v-if="sourceType === 'chat'" style="display: flex; align-items: center; gap: 4px;">
      <el-tag type="info" size="small" effect="plain">对话构建</el-tag>
    </div>
    <div v-if="linkedModel" style="display: flex; align-items: center; gap: 4px; margin-left: 8px;">
      <span style="font-size: var(--font-sm); color: var(--text-muted);">关联{{ operatorMode ? '算子' : '模型' }}:</span>
      <el-tag size="small" type="success" style="cursor: pointer" @click="$emit('goToModel', linkedModel.id)">{{ linkedModel.name }}</el-tag>
    </div>
    <el-tooltip v-if="syncStatus && !syncStatus.inSync" :content="syncStatus.reason || `${operatorMode ? '算子' : '模型'}与流程未同步`" placement="bottom">
      <el-tag type="warning" size="small" effect="plain" style="margin-left: 8px; cursor: pointer">未同步</el-tag>
    </el-tooltip>
    <div class="toolbar-actions">
      <el-button size="small" @click="$emit('viewScript')">技术详情</el-button>
      <el-button size="small" @click="$emit('save')" :loading="saving">保存</el-button>
      <el-button size="small" type="primary" @click="$emit('run')" :loading="running" :disabled="!canRun">
        {{ running ? '运行中...' : '运行流程' }}
      </el-button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  name: { type: String, default: '' },
  sourceType: { type: String, default: '' },
  linkedModel: { type: Object, default: null },
  syncStatus: { type: Object, default: null },
  operatorMode: { type: Boolean, default: false },
  saving: { type: Boolean, default: false },
  running: { type: Boolean, default: false },
  canRun: { type: Boolean, default: false }
})

defineEmits(['close', 'update:name', 'save', 'run', 'goToModel', 'viewScript'])
</script>

<style scoped>
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-sm) var(--space-lg);
  border-bottom: 1px solid var(--border);
  background: var(--surface);
  flex-shrink: 0;
}

.toolbar-actions {
  margin-left: auto;
  display: flex;
  gap: var(--space-xs);
}
</style>
