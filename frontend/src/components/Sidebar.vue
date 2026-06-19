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
        v-model="selectedDsId"
        placeholder="选择数据源"
        class="ds-select"
        size="small"
      >
        <el-option
          v-for="ds in qaEnabledDataSources"
          :key="ds.id"
          :label="ds.name"
          :value="ds.id"
        />
      </el-select>
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
          <el-tooltip content="返回首页" placement="top">
            <el-button text circle @click="$emit('goHome')">
              <el-icon><HomeFilled /></el-icon>
            </el-button>
          </el-tooltip>
          <el-tooltip content="退出登录" placement="top">
            <el-button text circle @click="$emit('logout')">
              <el-icon><SwitchButton /></el-icon>
            </el-button>
          </el-tooltip>
        </div>
      </div>
      <el-button style="width: 100%; margin-bottom: var(--space-sm)" @click="$emit('openPromptManager')">
        <el-icon><EditPen /></el-icon> 提示词管理
      </el-button>
      <el-button style="width: 100%; margin-bottom: var(--space-sm)" @click="openMetricEngine">
        <el-icon><DataAnalysis /></el-icon> 指标引擎
      </el-button>
      <el-button style="width: 100%; margin-bottom: var(--space-sm)" @click="$emit('openMining')">
        <el-icon><TrendCharts /></el-icon> 数据挖掘管理
      </el-button>
      <el-button style="width: 100%; margin-bottom: var(--space-sm)" @click="$emit('openDataSource')">
        <el-icon><DataLine /></el-icon> 数据源管理
      </el-button>
      <el-button type="primary" style="width: 100%" :loading="creating" @click="handleNewConversation">
        <el-icon><Plus /></el-icon> 新建对话
      </el-button>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { Plus, DataAnalysis, DataLine, TrendCharts, EditPen, Setting, Delete, HomeFilled, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchConversations, fetchDataSources, createConversation, deleteConversation, renameConversation, batchDeleteConversations } from '../api'
import { useUserStore } from '../stores/user'

const userStore = useUserStore()
const avatarText = computed(() => (userStore.displayName || 'U').charAt(0).toUpperCase())

const conversations = ref([])
const dataSources = ref([])
const currentConvId = ref(null)
const selectedDsId = ref(null)
const searchQuery = ref('')
const editingId = ref(null)
const editTitle = ref('')
const editInput = ref(null)

// Batch mode
const batchMode = ref(false)
const selectedConversations = ref({})

const emit = defineEmits(['selectConversation', 'conversationCreated', 'conversationDeleted', 'dataSourceChanged', 'openMining', 'openDataSource', 'openPromptManager', 'goHome', 'logout'])

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

function openMetricEngine() {
  window.open('http://localhost:5174', '_blank')
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
  width: 260px; background: var(--surface); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; flex-shrink: 0;
  transition: transform 0.25s ease;
}
@media (max-width: 767px) {
  .sidebar {
    position: fixed; left: 0; top: 0; bottom: 0; z-index: 900;
    transform: translateX(-100%);
    box-shadow: var(--shadow-lg);
  }
  .sidebar.sidebar-open {
    transform: translateX(0);
  }
}
.sidebar-header {
  padding: var(--space-lg); border-bottom: 1px solid var(--border);
}
.sidebar-header h2 {
  font-size: var(--font-xl); font-weight: 700; color: var(--text-primary); margin: 0 0 var(--space-sm);
}
.ds-select { width: 100%; }

.search-bar { padding: var(--space-sm) var(--space-md) var(--space-xs); }
.search-input {
  width: 100%; padding: var(--space-xs) var(--space-sm); border: 1px solid var(--border);
  border-radius: var(--radius-md); font-size: var(--font-sm); outline: none;
  transition: border-color 0.2s;
}
.search-input:focus { border-color: var(--primary); }

.conversation-list {
  flex: 1; overflow-y: auto; padding: var(--space-sm);
}
.conv-item {
  display: flex; align-items: center; gap: var(--space-sm);
  padding: var(--space-sm) var(--space-md); border-radius: var(--radius-lg); cursor: pointer;
  transition: background 0.15s; font-size: var(--font-md); color: var(--text-secondary);
}
.conv-item:hover { background: var(--color-info-light); }
.conv-item.active { background: var(--primary-light); color: var(--primary); font-weight: 500; }
.conv-icon { font-size: var(--font-base); flex-shrink: 0; }
.conv-title {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1;
}
.conv-edit-input {
  flex: 1; padding: 2px var(--space-xs); font-size: var(--font-md); border: 1px solid var(--primary);
  border-radius: var(--radius-sm); outline: none; min-width: 0;
}
.conv-delete {
  display: none; width: 20px; height: 20px; border-radius: 50%;
  align-items: center; justify-content: center; font-size: var(--font-base);
  color: var(--text-muted); flex-shrink: 0; cursor: pointer; transition: all 0.15s;
}
.conv-item:hover .conv-delete { display: inline-flex; }
.conv-delete:hover { background: var(--color-danger-light); color: var(--color-danger); }
.conv-empty {
  text-align: center; color: var(--text-muted); font-size: var(--font-md); padding: 30px 0;
}

.sidebar-footer {
  padding: var(--space-md);
  border-top: 1px solid var(--border);
}
.user-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-xs) var(--space-sm);
  margin-bottom: var(--space-sm);
  border-radius: var(--radius-md);
  background: var(--bg, #f9f9fb);
}
.user-info {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
}
.user-avatar {
  flex-shrink: 0;
  background: var(--primary, #409eff);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}
.user-name {
  font-size: var(--font-sm);
  color: var(--text-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-actions {
  display: flex;
  gap: 2px;
}
</style>
