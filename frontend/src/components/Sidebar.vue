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

    <div class="conversation-list">
      <div
        v-for="conv in conversations"
        :key="conv.id"
        class="conv-item"
        :class="{ active: conv.id === currentConvId }"
        @click="$emit('selectConversation', conv.id)"
      >
        <span class="conv-icon">💬</span>
        <span class="conv-title">{{ conv.title || '新对话' }}</span>
        <span class="conv-delete" @click.stop="handleDelete(conv.id)" title="删除">×</span>
      </div>
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
import { ref, watch, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { fetchConversations, fetchDataSources, createConversation, deleteConversation } from '../api'

const conversations = ref([])
const dataSources = ref([])
const currentConvId = ref(null)
const selectedDsId = ref(null)

const emit = defineEmits(['selectConversation', 'conversationCreated', 'conversationDeleted', 'dataSourceChanged'])

watch(selectedDsId, (val) => {
  emit('dataSourceChanged', val)
})

onMounted(async () => {
  try {
    const [convs, dss] = await Promise.all([fetchConversations(), fetchDataSources()])
    conversations.value = convs || []
    dataSources.value = dss || []
    if (dss?.length > 0) {
      // Prefer non-system data sources (business data)
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
