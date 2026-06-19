<template>
  <div class="training-code-viewer">
    <div class="code-header">
      <div class="code-title">
        <span class="code-lang-badge">Python</span>
        <span class="code-filename">{{ filename || 'training_script.py' }}</span>
      </div>
      <div class="code-status">
        <span v-if="currentStep" class="current-step-badge">
          {{ currentStep.label }}
        </span>
        <el-button size="small" @click="copyCode">复制</el-button>
      </div>
    </div>

    <div class="code-container" ref="codeContainer">
      <div class="line-numbers">
        <div
          v-for="line in lines"
          :key="line.number"
          :class="['line-number', { 'active': isLineActive(line.number) }]"
          :style="{ height: lineHeight + 'px' }"
        >
          {{ line.number }}
        </div>
      </div>

      <div class="code-content">
        <div
          v-for="line in lines"
          :key="line.number"
          :class="['code-line', { 'active': isLineActive(line.number), 'step-marker': isStepMarker(line.number) }]"
          :style="{ minHeight: lineHeight + 'px' }"
          @click="goToLine(line.number)"
        >
          <span v-if="isStepMarker(line.number)" class="step-indicator" :title="getStepLabel(line.number)">
            <span class="step-dot"></span>
          </span>
          <code v-html="highlightLine(line.content, line.number)"></code>
        </div>
      </div>
    </div>

    <div v-if="steps.length > 0" class="steps-panel">
      <div class="steps-title">训练步骤</div>
      <div class="steps-list">
        <div
          v-for="(step, index) in steps"
          :key="index"
          :class="['step-item', { 'active': currentStep?.id === step.id, 'completed': isStepCompleted(step.id) }]"
          @click="highlightStep(step.id)"
        >
          <span class="step-icon">
            <span v-if="isStepCompleted(step.id)" class="completed-icon">✓</span>
            <span v-else-if="currentStep?.id === step.id" class="active-spinner"></span>
            <span v-else class="pending-number">{{ index + 1 }}</span>
          </span>
          <div class="step-info">
            <span class="step-label">{{ step.label }}</span>
            <span class="step-lines">行 {{ step.startLine }}-{{ step.endLine }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  code: { type: String, default: '' },
  filename: { type: String, default: '' },
  steps: { type: Array, default: () => [] },
  currentStepId: { type: [String, Number], default: null },
  completedSteps: { type: Array, default: () => [] },
  algorithm: { type: String, default: '' }
})

const emit = defineEmits(['stepClick', 'lineClick'])

const codeContainer = ref(null)
const lineHeight = ref(24)
const currentStep = ref(null)
const scrolledToLine = ref(false)

// 将代码分割成行
const lines = computed(() => {
  if (!props.code) return []
  return props.code.split('\n').map((content, index) => ({
    number: index + 1,
    content
  }))
})

// 当前步骤对象
watch(() => props.currentStepId, (newId) => {
  currentStep.value = props.steps.find(s => s.id === newId) || null
  if (currentStep.value && !scrolledToLine.value) {
    scrollToLine(currentStep.value.startLine)
    scrolledToLine.value = true
    setTimeout(() => { scrolledToLine.value = false }, 1000)
  }
}, { immediate: true })

// 判断某行是否在当前步骤范围内
function isLineActive(lineNumber) {
  if (!currentStep.value) return false
  return lineNumber >= currentStep.value.startLine && lineNumber <= currentStep.value.endLine
}

// 判断某行是否是步骤标记行
function isStepMarker(lineNumber) {
  return props.steps.some(step => step.startLine === lineNumber)
}

// 获取步骤标签
function getStepLabel(lineNumber) {
  const step = props.steps.find(s => s.startLine === lineNumber)
  return step ? step.label : ''
}

// 判断步骤是否已完成
function isStepCompleted(stepId) {
  return props.completedSteps.includes(stepId)
}

// 滚动到指定行
function scrollToLine(lineNumber) {
  if (!codeContainer.value) return
  const lineElement = codeContainer.value.querySelector(`.code-line:nth-child(${lineNumber})`)
  if (lineElement) {
    lineElement.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

// 跳转到指定行
function goToLine(lineNumber) {
  emit('lineClick', lineNumber)
}

// 高亮步骤
function highlightStep(stepId) {
  emit('stepClick', stepId)
}

// 复制代码
function copyCode() {
  navigator.clipboard.writeText(props.code).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

// Python 语法高亮
function highlightLine(line, lineNumber) {
  if (!line) return ''

  // HTML 转义
  let escaped = line
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 注释高亮
  escaped = escaped.replace(/(#.*)$/g, '<span class="syntax-comment">$1</span>')

  // 字符串高亮
  escaped = escaped.replace(/('(?:[^'\\]|\\.)*')/g, '<span class="syntax-string">$1</span>')
  escaped = escaped.replace(/("(?:[^"\\]|\\.)*")/g, '<span class="syntax-string">$1</span>')

  // 关键字高亮
  const keywords = /\b(import|from|as|def|return|if|else|elif|for|in|try|except|with|class|raise|print|True|False|None|and|or|not|is|lambda|yield|pass|break|continue|while|async|await|finally)\b/g
  escaped = escaped.replace(keywords, '<span class="syntax-keyword">$1</span>')

  // 函数调用高亮
  escaped = escaped.replace(/\b([a-zA-Z_][a-zA-Z0-9_]*)\s*\(/g, '<span class="syntax-function">$1</span>(')

  // 数字高亮
  escaped = escaped.replace(/\b(\d+\.?\d*)\b/g, '<span class="syntax-number">$1</span>')

  return escaped
}

onMounted(() => {
  // 自动计算行高
  if (codeContainer.value) {
    const firstLine = codeContainer.value.querySelector('.code-line')
    if (firstLine) {
      lineHeight.value = firstLine.offsetHeight
    }
  }
})
</script>

<style scoped>
.training-code-viewer {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--code-bg, #1e1e1e);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.code-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid var(--border);
}

.code-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.code-lang-badge {
  background: var(--python-color, #3776ab);
  color: white;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--font-xs);
  font-weight: 600;
}

.code-filename {
  color: var(--code-fg, #d4d4d4);
  font-size: var(--font-md);
  font-family: monospace;
}

.code-status {
  display: flex;
  align-items: center;
  gap: 12px;
}

.current-step-badge {
  background: var(--primary-light, rgba(64, 158, 255, 0.2));
  color: var(--primary, #409eff);
  padding: 4px 12px;
  border-radius: 12px;
  font-size: var(--font-sm);
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-container {
  display: flex;
  flex: 1;
  overflow: auto;
  position: relative;
}

.line-numbers {
  background: var(--code-line-numbers-bg, #2d2d2d);
  border-right: 1px solid var(--border);
  user-select: none;
  min-width: 50px;
  text-align: right;
  padding-right: 12px;
}

.line-number {
  color: var(--code-line-number, #858585);
  font-size: var(--font-sm);
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  line-height: var(--line-height, 24px);
  padding-right: 8px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  transition: all 0.2s;
}

.line-number.active {
  color: var(--primary, #409eff);
  background: var(--primary-light, rgba(64, 158, 255, 0.1));
  font-weight: 600;
}

.line-number.context {
  color: var(--text-secondary);
  background: rgba(64, 158, 255, 0.05);
}

.code-content {
  flex: 1;
  position: relative;
}

.code-line {
  display: flex;
  align-items: flex-start;
  font-family: 'Menlo', 'Monaco', 'Courier New', monospace;
  font-size: var(--font-md);
  line-height: var(--line-height, 24px);
  padding: 0 16px;
  position: relative;
  transition: all 0.3s;
}

.code-line.active {
  background: linear-gradient(90deg, rgba(64, 158, 255, 0.2) 0%, rgba(64, 158, 255, 0.1) 50%, rgba(64, 158, 255, 0.05) 100%);
  border-left: 3px solid var(--primary, #409eff);
  padding-left: 13px;
  box-shadow: 0 0 10px rgba(64, 158, 255, 0.1);
}

.code-line.context {
  background: rgba(64, 158, 255, 0.03);
  opacity: 0.7;
}

.code-line.step-marker {
  cursor: pointer;
}

.code-line.step-marker:hover {
  background: var(--code-line-hover, rgba(255, 255, 255, 0.05));
}

.code-line.function-def {
  position: relative;
}

.code-line.function-def::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: rgba(103, 194, 58, 0.5);
}

.step-indicator {
  position: absolute;
  left: 4px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--primary, #409eff);
  border: 2px solid var(--code-bg, #1e1e1e);
  transition: all 0.2s;
}

.code-line.step-marker:hover .step-dot {
  width: 12px;
  height: 12px;
}

.function-icon {
  position: absolute;
  left: 6px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 10px;
  color: rgba(103, 194, 58, 0.8);
  opacity: 0;
  transition: opacity 0.2s;
}

.code-line.function-def:hover .function-icon {
  opacity: 1;
}

.code-line code {
  color: var(--code-fg, #d4d4d4);
  white-space: pre;
  flex: 1;
}

/* 语法高亮样式 */
.syntax-comment {
  color: #6a9955;
  font-style: italic;
}

.syntax-string {
  color: #ce9178;
}

.syntax-keyword {
  color: #569cd6;
  font-weight: 500;
}

.syntax-function {
  color: #dcdcaa;
}

.syntax-function-def {
  color: #4ec9b0;
  font-weight: 600;
}

.syntax-number {
  color: #b5cea8;
}

/* 步骤面板 */
.steps-panel {
  border-top: 1px solid var(--border);
  background: var(--bg-secondary, #f5f7fa);
  max-height: 200px;
  overflow-y: auto;
}

.steps-title {
  padding: 8px 16px;
  font-size: var(--font-sm);
  font-weight: 600;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  border-bottom: 1px solid var(--border);
}

.steps-list {
  padding: var(--space-sm);
}

.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all 0.2s;
  margin-bottom: 2px;
}

.step-item:hover {
  background: var(--border-light, #e4e7ed);
}

.step-item.active {
  background: var(--primary-light, rgba(64, 158, 255, 0.1));
  border-left: 3px solid var(--primary, #409eff);
}

.step-item.completed {
  opacity: 0.6;
}

.step-icon {
  width: 20px;
  height: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.completed-icon {
  color: var(--el-color-success, #67c23a);
  font-size: var(--font-base);
  font-weight: bold;
}

.active-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--primary, #409eff);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.pending-number {
  color: var(--text-muted, #909399);
  font-size: var(--font-xs);
  font-weight: 500;
}

.step-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.step-label {
  font-size: var(--font-md);
  font-weight: 500;
  color: var(--text-primary, #303133);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.step-description {
  font-size: var(--font-xs);
  color: var(--text-muted, #909399);
  margin-top: 2px;
  display: block;
}

.step-functions {
  font-size: 10px;
  color: var(--text-muted, #909399);
  font-family: monospace;
  margin-top: 2px;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-lines {
  font-size: var(--font-xs);
  color: var(--text-muted, #909399);
}

.step-item.active .step-label {
  color: var(--primary, #409eff);
}

.step-item.active .step-description {
  color: var(--primary, #409eff);
  opacity: 0.8;
}
</style>
