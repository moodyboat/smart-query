<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <h2>智能问数</h2>
      <el-select
        v-model="selectedDsId"
        placeholder="选择数据源"
        class="ds-select"
        size="small"
      >
        <el-option
          v-for="ds in dataSources"
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
      <div
        v-for="conv in filteredConversations"
        :key="conv.id"
        class="conv-item"
        :class="{ active: conv.id === currentConvId }"
        @click="$emit('selectConversation', conv.id)"
      >
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
      <el-button type="primary" style="width: 100%" @click="handleNewConversation">
        <el-icon><Plus /></el-icon> 新建对话
      </el-button>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { fetchConversations, fetchDataSources, createConversation, deleteConversation, renameConversation } from '../api'

const conversations = ref([])
const dataSources = ref([])
const currentConvId = ref(null)
const selectedDsId = ref(null)
const searchQuery = ref('')
const editingId = ref(null)
const editTitle = ref('')
const editInput = ref(null)

const emit = defineEmits(['selectConversation', 'conversationCreated', 'conversationDeleted', 'dataSourceChanged'])

const filteredConversations = computed(() => {
  if (!searchQuery.value.trim()) return conversations.value
  const q = searchQuery.value.toLowerCase()
  return conversations.value.filter(c => (c.title || '新对话').toLowerCase().includes(q))
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
      const business = dss.find(ds => ds.databaseName !== 'smart_query') || dss[0]
      selectedDsId.value = business.id
      emit('dataSourceChanged', business.id)
    }
  } catch (e) {
    console.error('Failed to load sidebar data', e)
  }
})

async function handleNewConversation() {
  const conv = await createConversation('新对话')
  conversations.value.unshift(conv)
  currentConvId.value = conv.id
  emit('conversationCreated', conv.id)
}

async function handleDelete(convId) {
  try {
    await deleteConversation(convId)
    conversations.value = conversations.value.filter(c => c.id !== convId)
    if (currentConvId.value === convId) {
      currentConvId.value = null
    }
    emit('conversationDeleted', convId)
  } catch { /* ignore */ }
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
    } catch { /* ignore */ }
  }
  editingId.value = null
}

function cancelRename() {
  editingId.value = null
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
  } catch { /* ignore */ }
}

defineExpose({ setCurrentConversation, getSelectedDataSourceId, conversations, refreshConversations })
</script>

<style scoped>
.sidebar {
  width: 260px; background: #fff; border-right: 1px solid #e8e8e8;
  display: flex; flex-direction: column; flex-shrink: 0;
}
.sidebar-header {
  padding: 16px; border-bottom: 1px solid #e8e8e8;
}
.sidebar-header h2 {
  font-size: 16px; font-weight: 700; color: #1d1e2c; margin: 0 0 10px;
}
.ds-select { width: 100%; }

.search-bar { padding: 8px 12px 4px; }
.search-input {
  width: 100%; padding: 6px 10px; border: 1px solid #e0e0e0;
  border-radius: 6px; font-size: 12px; outline: none;
  transition: border-color 0.2s;
}
.search-input:focus { border-color: #409eff; }

.conversation-list {
  flex: 1; overflow-y: auto; padding: 8px;
}
.conv-item {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px; border-radius: 8px; cursor: pointer;
  transition: background 0.15s; font-size: 13px; color: #555;
}
.conv-item:hover { background: #f5f7fa; }
.conv-item.active { background: #ecf5ff; color: #409eff; font-weight: 500; }
.conv-icon { font-size: 14px; flex-shrink: 0; }
.conv-title {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1;
}
.conv-edit-input {
  flex: 1; padding: 2px 6px; font-size: 13px; border: 1px solid #409eff;
  border-radius: 4px; outline: none; min-width: 0;
}
.conv-delete {
  display: none; width: 20px; height: 20px; border-radius: 50%;
  align-items: center; justify-content: center; font-size: 14px;
  color: #999; flex-shrink: 0; cursor: pointer; transition: all 0.15s;
}
.conv-item:hover .conv-delete { display: inline-flex; }
.conv-delete:hover { background: #fee; color: #f56c6c; }
.conv-empty {
  text-align: center; color: #ccc; font-size: 13px; padding: 30px 0;
}

.sidebar-footer {
  padding: 12px; border-top: 1px solid #e8e8e8;
}
</style>
