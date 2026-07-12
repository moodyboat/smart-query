<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <div class="header-top">
        <h2>智能问数</h2>
        <el-button
          v-if="conversations.length > 0 && !batchMode"
          @click="toggleBatchMode"
          size="small"
          text
        >
          批量管理
        </el-button>
      </div>
      <el-select
        v-model="selectedScenarioCode"
        placeholder="选择场景"
        class="scenario-select"
        size="small"
        @change="onScenarioChange"
      >
        <el-option
          v-for="s in availableScenarios"
          :key="s.code"
          :label="(s.icon ? s.icon + ' ' : '') + (s.name || s.code)"
          :value="s.code"
        />
      </el-select>
      <el-tooltip
        :content="lockedDataSourceId != null ? '当前场景已锁定数据源，退出场景可切换' : ''"
        :disabled="lockedDataSourceId == null"
        placement="bottom"
      >
        <el-select
          v-model="selectedDsId"
          placeholder="选择数据源"
          class="ds-select"
          size="small"
          :disabled="lockedDataSourceId != null"
        >
          <el-option
            v-for="ds in qaEnabledDataSources"
            :key="ds.id"
            :label="ds.name"
            :value="ds.id"
          />
        </el-select>
      </el-tooltip>
    </div>

    <div v-if="conversations.length > 5" class="search-bar">
      <input
        v-model="searchQuery"
        class="search-input"
        placeholder="搜索对话..."
      />
    </div>

    <div class="conversation-list">
      <!-- 批量操作栏 -->
      <div v-if="batchMode" class="batch-actions">
        <div class="batch-info">
          <el-checkbox
            v-model="allSelected"
            :indeterminate="isIndeterminate"
            @change="handleSelectAll"
          >
            全选 ({{ selectedCount }}/{{ filteredConversations.length }})
          </el-checkbox>
        </div>
        <div class="batch-buttons">
          <el-button
            size="small"
            :disabled="selectedCount === 0"
            @click="handleBatchDelete"
            type="danger"
          >
            删除选中 ({{ selectedCount }})
          </el-button>
          <el-button size="small" @click="exitBatchMode">取消</el-button>
        </div>
      </div>

      <div
        v-for="conv in filteredConversations"
        :key="conv.id"
        class="conv-item"
        :class="{ active: conv.id === currentConvId, 'batch-mode': batchMode }"
        @click="handleConvClick(conv)"
      >
        <el-checkbox
          v-if="batchMode"
          v-model="selectedConversations[conv.id]"
          @click.stop
          class="conv-checkbox"
        />
        <span class="conv-icon">💬</span>
        <template v-if="editingId === conv.id">
          <input
            ref="editInput"
            v-model="editTitle"
            class="conv-edit-input"
            @keydown.enter="saveRename(conv.id)"
            @keydown.escape="cancelRename"
            @blur="saveRename(conv.id)"
          />
        </template>
        <template v-else>
          <span class="conv-title" @dblclick="startRename(conv)">{{ conv.title || '新对话' }}</span>
        </template>
        <span class="conv-delete" @click.stop="handleDelete(conv.id)" title="删除">×</span>
      </div>
      <div v-if="!filteredConversations.length && conversations.length" class="conv-empty">未找到匹配对话</div>
      <div v-if="!conversations.length" class="conv-empty">暂无对话</div>
    </div>

    <div class="sidebar-footer">
      <div class="user-bar">
        <div class="user-info">
          <el-avatar :size="28" class="user-avatar">{{ avatarText }}</el-avatar>
          <span class="user-name" :title="userStore.displayName">{{ userStore.displayName }}</span>
        </div>
        <div class="user-actions">
          <el-tooltip content="退出登录" placement="top">
            <el-button text circle @click="$emit('logout')">
              <el-icon><SwitchButton /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
      <div class="admin-actions">
        <el-button v-if="userStore.isAdmin" @click="$emit('openScenarioManager')">
          <el-icon><Star /></el-icon> 场景
        </el-button>
        <el-button @click="$emit('openPromptManager')">
          <el-icon><EditPen /></el-icon> 提示词
        </el-button>
        <el-button @click="$emit('openMining')">
          <el-icon><TrendCharts /></el-icon> 挖掘
        </el-button>
        <el-button @click="$emit('openDataSource')">
          <el-icon><DataLine /></el-icon> 数据源
        </el-button>
      </div>
      <el-button type="primary" class="new-conv-btn" :loading="creating" @click="handleNewConversation">
        <el-icon><Plus /></el-icon> 新建对话
      </el-button>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { Plus, DataLine, TrendCharts, EditPen, Setting, Delete, SwitchButton, Star } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchConversations, fetchDataSources, createConversation, deleteConversation, renameConversation, batchDeleteConversations } from '../api'
import { useUserStore } from '../stores/user'
import { useConversationStore } from '../stores/conversation'
import { getScenarioConfig, getAllScenarios } from '../config/scenarios.js'

const userStore = useUserStore()
const convStore = useConversationStore()
const avatarText = computed(() => (userStore.displayName || 'U').charAt(0).toUpperCase())

const conversations = ref([])
const dataSources = ref([])
const currentConvId = ref(null)
const selectedDsId = ref(null)
const searchQuery = ref('')
const editingId = ref(null)
const editTitle = ref('')
const editInput = ref(null)

// 场景锁定数据源：当前场景配置了 dataSourceId 时禁用切换器并强制使用该值
const lockedDataSourceId = computed(() => {
  const code = convStore.getCurrentScenario()
  if (!code) return null
  const cfg = getScenarioConfig(code)
  return cfg?.dataSourceId ?? null
})

// 用户级场景切换：列表来自 scenarios 缓存（基线 6 + DB 覆盖，按用户角色已过滤）
const availableScenarios = computed(() => getAllScenarios())
const selectedScenarioCode = ref(null)

/**
 * 切换场景 = 新建该场景的会话（沿用 PromptManager 的语义：一个会话属于一个场景）。
 * 不在当前会话中途切，避免上下文与提示词错位。
 */
async function onScenarioChange(code) {
  if (!code) return
  try {
    const cfg = getScenarioConfig(code)
    // 场景绑了数据源就用场景的，否则沿用当前选择
    const dsId = cfg?.dataSourceId ?? selectedDsId.value
    const title = cfg?.name ? `${cfg.name}对话` : '场景对话'
    // scenario 字段持久化到 sq_conversation，刷新页面可恢复
    const resp = await createConversation({ title, dataSourceId: dsId, scenario: code })
    const newId = resp?.id ?? resp
    if (!newId) throw new Error('创建会话失败')
    convStore.setCurrentConversation(newId)
    convStore.setScenario(code)
    // 触发父组件加载新会话
    emit('conversationCreated', newId)
    emit('selectConversation', newId)
    ElMessage.success(`已切换到「${cfg?.name || code}」场景`)
  } catch (e) {
    ElMessage.error('切换场景失败：' + (e.message || ''))
    // 回滚选中态
    selectedScenarioCode.value = convStore.getCurrentScenario()
  }
}

// 监听外部场景变化（如 PromptManager 切了），同步下拉显示
watch(() => convStore.getCurrentScenario(), (code) => {
  if (code !== selectedScenarioCode.value) selectedScenarioCode.value = code
}, { immediate: true })

// Batch mode
const batchMode = ref(false)
const selectedConversations = ref({})

const emit = defineEmits(['selectConversation', 'conversationCreated', 'conversationDeleted', 'dataSourceChanged', 'openMining', 'openDataSource', 'openPromptManager', 'openScenarioManager', 'logout'])

const filteredConversations = computed(() => {
  if (!searchQuery.value.trim()) return conversations.value
  const q = searchQuery.value.toLowerCase()
  return conversations.value.filter(c => (c.title || '新对话').toLowerCase().includes(q))
})

const qaEnabledDataSources = computed(() => {
  return dataSources.value.filter(ds => ds.forQuestionAnswering ?? true)
})

// Batch mode computed properties
const selectedCount = computed(() => {
  return Object.values(selectedConversations.value).filter(v => v).length
})

const allSelected = computed({
  get() {
    return filteredConversations.value.length > 0 && selectedCount.value === filteredConversations.value.length
  },
  set(val) {
    // Handled in handleSelectAll
  }
})

const isIndeterminate = computed(() => {
  return selectedCount.value > 0 && selectedCount.value < filteredConversations.value.length
})

watch(selectedDsId, (val) => {
  emit('dataSourceChanged', val)
})

// 场景变化时若锁定数据源，强制覆盖本地选择；后端 ChatController 会再校验一次
watch(lockedDataSourceId, (newVal) => {
  if (newVal != null && newVal !== selectedDsId.value) {
    selectedDsId.value = newVal
  }
})

onMounted(async () => {
  try {
    const [convs, dss] = await Promise.all([fetchConversations(), fetchDataSources()])
    conversations.value = convs || []
    dataSources.value = dss || []
    if (dss?.length > 0) {
      const qaEnabled = qaEnabledDataSources.value
      const business = qaEnabled.find(ds => !ds.system) || qaEnabled[0]
      if (business) {
        selectedDsId.value = business.id
        emit('dataSourceChanged', business.id)
      }
    }
  } catch (e) {
    console.error('Failed to load sidebar data', e)
  }
})

const creating = ref(false)

async function handleNewConversation() {
  if (creating.value) return
  creating.value = true
  try {
    const conv = await createConversation('新对话')
    conversations.value.unshift(conv)
    currentConvId.value = conv.id
    emit('conversationCreated', conv.id)
  } catch (e) {
    ElMessage.error('创建对话失败，请重试')
  } finally {
    creating.value = false
  }
}

async function handleDelete(convId) {
  try {
    await ElMessageBox.confirm('确定要删除这个对话吗？此操作不可撤销。', '删除对话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await deleteConversation(convId)
    conversations.value = conversations.value.filter(c => c.id !== convId)
    if (currentConvId.value === convId) {
      currentConvId.value = null
    }
    emit('conversationDeleted', convId)
  } catch { /* user cancelled */ }
}

function startRename(conv) {
  editingId.value = conv.id
  editTitle.value = conv.title || '新对话'
  nextTick(() => {
    const input = document.querySelector('.conv-edit-input')
    if (input) { input.focus(); input.select() }
  })
}

async function saveRename(convId) {
  const title = editTitle.value.trim()
  if (!title || editingId.value !== convId) {
    cancelRename()
    return
  }
  const conv = conversations.value.find(c => c.id === convId)
  if (conv && conv.title !== title) {
    try {
      await renameConversation(convId, title)
      conv.title = title
    } catch {
      ElMessage.error('重命名失败')
    }
  }
  editingId.value = null
}

function cancelRename() {
  editingId.value = null
}

// Batch mode functions
function toggleBatchMode() {
  batchMode.value = true
  selectedConversations.value = {}
}

function exitBatchMode() {
  batchMode.value = false
  selectedConversations.value = {}
}

function handleSelectAll(val) {
  filteredConversations.value.forEach(conv => {
    selectedConversations.value[conv.id] = val
  })
}

function handleConvClick(conv) {
  if (batchMode.value) {
    // Toggle selection in batch mode
    selectedConversations.value[conv.id] = !selectedConversations.value[conv.id]
  } else {
    // Normal conversation selection
    currentConvId.value = conv.id
    // 恢复会话绑定的场景（刷新页面/切换会话后场景跟着会话走）
    const scenario = conv?.scenario || null
    convStore.setScenario(scenario)
    selectedScenarioCode.value = scenario
    emit('selectConversation', conv.id)
  }
}

async function handleBatchDelete() {
  const idsToDelete = Object.keys(selectedConversations.value).filter(id => selectedConversations.value[id])
  if (idsToDelete.length === 0) return

  try {
    await ElMessageBox.confirm(
      `确定要删除选中的 ${idsToDelete.length} 个对话吗？此操作不可撤销。`,
      '批量删除对话',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )

    await batchDeleteConversations(idsToDelete)
    conversations.value = conversations.value.filter(c => !idsToDelete.includes(c.id))

    // Clear current conversation if it was deleted
    if (idsToDelete.includes(currentConvId.value)) {
      currentConvId.value = null
      emit('conversationDeleted', currentConvId.value)
    }

    selectedConversations.value = {}
    batchMode.value = false

    ElMessage.success(`成功删除 ${idsToDelete.length} 个对话`)
  } catch (error) {
    if (error !== 'cancel') {
      console.error('Batch delete failed:', error)
      // Show detailed error message
      const errorMsg = error.response?.data?.message || error.message || '批量删除失败，请重试'
      ElMessage.error(errorMsg)
    }
  }
}

function setCurrentConversation(id) {
  currentConvId.value = id
}

function getSelectedDataSourceId() {
  return selectedDsId.value
}

async function refreshConversations() {
  try {
    const convs = await fetchConversations()
    conversations.value = convs || []
  } catch (e) {
    console.error('Failed to refresh conversations:', e)
    ElMessage.error('刷新对话列表失败')
  }
}

defineExpose({ setCurrentConversation, getSelectedDataSourceId, conversations, refreshConversations })
</script>

<style scoped>
.sidebar {
  width: 260px;
  background: var(--sidebar-bg);
  color: var(--sidebar-fg);
  display: flex; flex-direction: column; flex-shrink: 0;
  transition: transform 0.25s ease;
  background-image: radial-gradient(circle at 30% 0%, var(--sidebar-brand-glow), transparent 60%);
}
@media (max-width: 767px) {
  .sidebar {
    position: fixed; left: 0; top: 0; bottom: 0; z-index: 900;
    transform: translateX(-100%);
    box-shadow: var(--shadow-xl);
  }
  .sidebar.sidebar-open { transform: translateX(0); }
}
.sidebar-header {
  padding: var(--space-lg);
  border-bottom: 1px solid var(--sidebar-border);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}
.sidebar-header h2 {
  font-size: var(--font-xl); font-weight: 700; color: var(--sidebar-fg-strong);
  margin: 0;
  letter-spacing: 0.02em;
}
.sidebar-header h2::before {
  content: '◆';
  margin-right: 6px;
  color: var(--brand-primary);
  font-size: 0.85em;
}
.sidebar :deep(.el-button.is-text),
.sidebar :deep(.el-button.is-text:hover) {
  color: var(--sidebar-fg);
}
.sidebar :deep(.el-button.is-text:hover) {
  background: var(--sidebar-hover);
}
.scenario-select,
.ds-select {
  width: 100%;
  margin: 0;
}
.sidebar :deep(.el-select .el-select__wrapper) {
  background: var(--sidebar-bg-soft);
  box-shadow: none;
  border: 1px solid var(--sidebar-border);
  color: var(--sidebar-fg);
  min-height: 32px;
}
.sidebar :deep(.el-select .el-select__wrapper:hover) {
  border-color: var(--sidebar-brand-border);
}
.sidebar :deep(.el-select .el-select__placeholder),
.sidebar :deep(.el-select .el-select__selected-item) {
  color: var(--sidebar-fg-strong);
}
.sidebar :deep(.el-select.is-disabled .el-select__wrapper) {
  background: var(--sidebar-bg);
  color: var(--sidebar-fg-muted);
  cursor: not-allowed;
}

.search-bar { padding: var(--space-sm) var(--space-md) var(--space-xs); }
.search-input {
  width: 100%; padding: var(--space-xs) var(--space-sm);
  background: var(--sidebar-bg-soft);
  border: 1px solid var(--sidebar-border);
  color: var(--sidebar-fg-strong);
  border-radius: var(--radius-md); font-size: var(--font-sm); outline: none;
  transition: border-color 0.2s;
}
.search-input::placeholder { color: var(--sidebar-fg-muted); }
.search-input:focus { border-color: var(--brand-primary); background: var(--sidebar-bg); }

.conversation-list {
  flex: 1; overflow-y: auto; padding: var(--space-sm);
}
.conv-item {
  display: flex; align-items: center; gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md); border-radius: var(--radius-lg);
  cursor: pointer; transition: background 0.15s;
  font-size: var(--font-md); color: var(--sidebar-fg);
  border-left: 2px solid transparent;
}
.conv-item:hover { background: var(--sidebar-hover); }
.conv-item.active {
  background: var(--sidebar-active);
  color: var(--sidebar-fg-strong);
  font-weight: 500;
  border-left-color: var(--brand-primary);
}
.conv-icon {
  font-size: var(--font-base); flex-shrink: 0;
  width: 18px; height: 18px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%;
  background: var(--sidebar-icon-bg);
  color: var(--sidebar-fg-muted);
  font-size: 10px;
}
.conv-item.active .conv-icon {
  background: var(--brand-primary);
  color: var(--on-dark-text);
}
.conv-title {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1;
}
.conv-edit-input {
  flex: 1; padding: 2px var(--space-xs); font-size: var(--font-md);
  background: var(--sidebar-bg);
  color: var(--sidebar-fg-strong);
  border: 1px solid var(--brand-primary);
  border-radius: var(--radius-sm); outline: none; min-width: 0;
}
.conv-delete {
  display: none; width: 20px; height: 20px; border-radius: 50%;
  align-items: center; justify-content: center; font-size: var(--font-base);
  color: var(--sidebar-fg-muted); flex-shrink: 0; cursor: pointer; transition: all 0.15s;
}
.conv-item:hover .conv-delete { display: inline-flex; }
.conv-delete:hover { background: var(--sidebar-danger-hover-bg); color: var(--sidebar-danger-hover-fg); }
.conv-empty {
  text-align: center; color: var(--sidebar-fg-muted); font-size: var(--font-md); padding: 30px 0;
}

.sidebar-footer {
  padding: var(--space-md);
  border-top: 1px solid var(--sidebar-border);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.user-bar {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-md);
  background: var(--sidebar-bg-soft);
}
.user-info {
  display: flex; align-items: center; gap: var(--space-sm); min-width: 0;
}
.user-avatar {
  flex-shrink: 0;
  background: var(--brand-gradient);
  color: var(--on-dark-text);
  font-size: 13px; font-weight: 600;
}
.user-name {
  font-size: var(--font-sm);
  color: var(--sidebar-fg-strong);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.user-actions { display: flex; gap: 2px; }

/* 2x2 网格管理按钮，节省垂直空间 */
.admin-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-xs);
}
.admin-actions :deep(.el-button) {
  margin: 0 !important;
  padding: 0 var(--space-sm);
  height: 30px;
  font-size: var(--font-sm);
  background: var(--sidebar-bg-soft);
  border: 1px solid var(--sidebar-border);
  color: var(--sidebar-fg);
}
.admin-actions :deep(.el-button:hover) {
  background: var(--sidebar-hover);
  border-color: var(--sidebar-brand-border);
  color: var(--sidebar-fg-strong);
}
.admin-actions :deep(.el-button .el-icon) {
  font-size: 14px;
  margin-right: 2px;
}
.new-conv-btn {
  width: 100%;
  margin: 0 !important;
}

/* 深色 Sidebar 里 el-button 主色按钮（新建对话）保持醒目 */
.sidebar-footer :deep(.el-button--primary) {
  background: var(--brand-gradient);
  border-color: transparent;
  box-shadow: var(--shadow-brand);
}
</style>
