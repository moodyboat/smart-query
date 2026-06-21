<template>
  <el-drawer
    :model-value="show"
    title="Python 脚本 (按节点)"
    size="640px"
    direction="rtl"
    :modal="false"
    @update:model-value="$emit('update:show', $event)"
  >
    <div v-if="loading" style="text-align:center;padding:40px">
      <p style="color:var(--text-muted)">加载脚本中...</p>
    </div>
    <div v-else-if="segments.length" class="script-tabs">
      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane
          v-for="seg in segments"
          :key="seg.nodeType"
          :label="seg.title"
          :name="seg.nodeType"
        >
          <div class="script-tab-header">
            <span class="script-node-type">{{ seg.nodeType }}</span>
            <el-button size="small" @click="copyCode(seg.code)">复制</el-button>
          </div>
          <pre class="script-code"><code v-html="highlightPython(seg.code)"></code></pre>
        </el-tab-pane>
        <el-tab-pane label="完整脚本" name="__full__">
          <div class="script-tab-header">
            <span class="script-node-type">full</span>
            <el-button size="small" @click="copyCode(fullScript)">复制全部</el-button>
          </div>
          <pre class="script-code"><code v-html="highlightPython(fullScript)"></code></pre>
        </el-tab-pane>
      </el-tabs>
    </div>
    <div v-else style="text-align:center;padding:40px;color:var(--text-muted)">
      请先配置流水线节点以生成脚本
    </div>
  </el-drawer>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  show: { type: Boolean, default: false },
  segments: { type: Array, default: () => [] },
  fullScript: { type: String, default: '' },
  loading: { type: Boolean, default: false }
})

defineEmits(['update:show'])

const activeTab = ref('')

function copyCode(code) {
  navigator.clipboard.writeText(code).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

function highlightPython(code) {
  if (!code) return ''
  return code
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/(#[^\n]*)/g, '<span style="color:#6a9955">$1</span>')
    .replace(/('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")/g, '<span style="color:#ce9178">$1</span>')
    .replace(/\b(import|from|as|def|return|if|else|elif|for|in|try|except|with|class|raise|print|True|False|None|and|or|not|is|lambda|yield|pass|break|continue)\b/g,
      '<span style="color:#569cd6">$1</span>')
}
</script>

<style scoped>
.script-tabs {
  padding: 0;
}

.script-tabs :deep(.el-tabs__content) {
  padding: 0;
}

.script-tabs :deep(.el-tab-pane) {
  padding: 0;
}

.script-tab-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 8px;
  border-bottom: 1px solid var(--border);
}

.script-node-type {
  font-size: var(--font-xs);
  color: var(--text-muted);
  font-family: var(--font-family-mono);
}

.script-code {
  background: var(--code-bg);
  color: var(--code-fg);
  padding: var(--space-md);
  border-radius: 0;
  overflow-x: auto;
  font-size: var(--font-sm);
  line-height: 1.5;
  white-space: pre;
  margin: 0;
  max-height: calc(100vh - 220px);
  overflow-y: auto;
}

.script-code code {
  font-family: var(--font-family-mono);
}
</style>
