<template>
  <aside :class="['sidebar', { 'navigation-only': props.activeDomain !== 'workbench' }]" aria-label="当前分区侧栏">
    <nav class="section-navigation" :aria-label="`${activeDomainMeta.title}功能导航`">
      <header class="section-navigation-heading">
        <strong>{{ activeDomainMeta.title }}</strong>
        <em>{{ activeDomainMeta.count }} 项功能</em>
      </header>

      <div class="section-links">
        <template v-if="props.activeDomain === 'workbench'">
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'chat' }" @click="emit('openChat')">
            <span class="nav-icon"><el-icon><ChatDotRound /></el-icon></span><span>智能问数</span>
          </button>
        </template>
        <template v-else-if="props.activeDomain === 'data'">
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'data-source' }" @click="emit('openDataSource')"><span class="nav-icon"><el-icon><DataLine /></el-icon></span><span>数据源</span></button>
          <button v-if="userStore.canManageScenarios" type="button" class="section-entry" :class="{ active: props.activeTool === 'scenarios' }" @click="emit('openScenarioManager')"><span class="nav-icon"><el-icon><Star /></el-icon></span><span>业务场景</span></button>
        </template>
        <template v-else-if="props.activeDomain === 'development'">
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'pipelines' }" @click="emit('openWorkbench', 'pipelines')"><span class="nav-icon"><el-icon><Connection /></el-icon></span><span>训练流水线</span></button>
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'operators' }" @click="emit('openWorkbench', 'operators')"><span class="nav-icon"><el-icon><Box /></el-icon></span><span>算子库</span></button>
        </template>
        <template v-else-if="props.activeDomain === 'models'">
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'schedule' }" @click="emit('openWorkbench', 'schedule')"><span class="nav-icon"><el-icon><Calendar /></el-icon></span><span>模型资产</span></button>
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'governance' }" @click="emit('openWorkbench', 'governance')"><span class="nav-icon"><el-icon><Lock /></el-icon></span><span>模型治理</span></button>
        </template>
        <template v-else-if="props.activeDomain === 'monitor'">
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'monitor-operations' }" @click="emit('openMonitorSection', 'operations')"><span class="nav-icon"><el-icon><Calendar /></el-icon></span><span>调度任务与执行</span></button>
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'monitor-view' }" @click="emit('openMonitorSection', 'view')"><span class="nav-icon"><el-icon><View /></el-icon></span><span>穿透式监控视图</span></button>
        </template>
        <template v-else>
          <button type="button" class="section-entry" :class="{ active: props.activeTool === 'prompts' }" @click="emit('openPromptManager')"><span class="nav-icon"><el-icon><EditPen /></el-icon></span><span>提示词</span></button>
          <button v-if="userStore.canManageUsers" type="button" class="section-entry" :class="{ active: props.activeTool === 'users' }" @click="emit('openUserManagement')"><span class="nav-icon"><el-icon><User /></el-icon></span><span>用户管理</span></button>
        </template>
      </div>
    </nav>

    <section v-if="props.activeDomain === 'workbench'" class="sidebar-context chat-context">
      <div class="context-heading">
        <div><small>当前上下文</small><strong>对话与数据</strong></div>
      </div>
      <div class="context-selects">
        <label>
          <span>业务场景</span>
          <el-select v-model="selectedScenarioCode" placeholder="选择场景" class="scenario-select" size="small" @change="onScenarioChange">
            <el-option v-for="s in availableScenarios" :key="s.code" :label="(s.icon ? s.icon + ' ' : '') + (s.name || s.code)" :value="s.code" />
          </el-select>
        </label>
        <label>
          <span>数据源</span>
          <el-tooltip :content="lockedDataSourceId != null ? '当前场景已锁定数据源' : ''" :disabled="lockedDataSourceId == null" placement="right">
            <el-select v-model="selectedDsId" placeholder="选择数据源" class="ds-select" size="small" :disabled="lockedDataSourceId != null">
              <el-option v-for="ds in qaEnabledDataSources" :key="ds.id" :label="ds.name" :value="ds.id" />
            </el-select>
          </el-tooltip>
        </label>
      </div>

      <div class="history-heading">
        <span>最近对话</span>
        <button v-if="conversations.length > 0 && !batchMode" type="button" @click="toggleBatchMode">管理</button>
      </div>
      <div v-if="conversations.length > 5" class="search-bar">
        <el-icon><Search /></el-icon>
        <input v-model="searchQuery" class="search-input" placeholder="搜索对话" />
      </div>

      <div class="conversation-list">
        <div v-if="batchMode" class="batch-actions">
          <el-checkbox v-model="allSelected" :indeterminate="isIndeterminate" @change="handleSelectAll">全选 {{ selectedCount }}/{{ filteredConversations.length }}</el-checkbox>
          <div><button type="button" :disabled="selectedCount === 0" class="danger-action" @click="handleBatchDelete">删除</button><button type="button" @click="exitBatchMode">取消</button></div>
        </div>

        <template v-if="!batchMode">
          <div v-for="g in groupedConversations" :key="g.key" class="conv-group">
            <div v-if="g.items.length" class="conv-group-label">{{ g.label }}</div>
            <div v-for="conv in g.items" :key="conv.id" class="conv-item" :class="{ active: conv.id === currentConvId }" @click="handleConvClick(conv)">
              <el-icon class="conv-icon"><ChatLineSquare /></el-icon>
              <input v-if="editingId === conv.id" ref="editInput" v-model="editTitle" class="conv-edit-input" @keydown.enter="saveRename(conv.id)" @keydown.escape="cancelRename" @blur="saveRename(conv.id)" />
              <span v-else class="conv-title" @dblclick="startRename(conv)">{{ conv.title || '新对话' }}</span>
              <button type="button" class="conv-delete" @click.stop="handleDelete(conv.id)" title="删除对话">×</button>
            </div>
          </div>
        </template>
        <template v-else>
          <div v-for="conv in filteredConversations" :key="conv.id" class="conv-item batch-mode" :class="{ active: conv.id === currentConvId }" @click="handleConvClick(conv)">
            <el-checkbox v-model="selectedConversations[conv.id]" @click.stop class="conv-checkbox" />
            <el-icon class="conv-icon"><ChatLineSquare /></el-icon>
            <span class="conv-title">{{ conv.title || '新对话' }}</span>
          </div>
        </template>
        <div v-if="!filteredConversations.length && conversations.length" class="conv-empty">没有匹配的对话</div>
        <div v-if="!conversations.length" class="conv-empty">发送第一条消息即可创建会话</div>
      </div>
    </section>

    <div v-else class="sidebar-spacer" aria-hidden="true">
      <div class="section-ornament">
        <span class="ornament-orbit orbit-one"></span>
        <span class="ornament-orbit orbit-two"></span>
        <span class="ornament-dot dot-one"></span>
        <span class="ornament-dot dot-two"></span>
        <span class="ornament-dot dot-three"></span>
        <div class="ornament-core"><el-icon :size="24"><component :is="activeDomainIcon" /></el-icon></div>
      </div>
    </div>

    <footer class="sidebar-footer">
      <div class="user-info">
        <el-avatar :size="36" class="user-avatar">{{ avatarText }}</el-avatar>
        <div><strong :title="userStore.displayName">{{ userStore.displayName }}</strong><small>{{ userStore.roleLabel }}</small></div>
      </div>
      <el-tooltip content="退出登录" placement="top">
        <button type="button" class="logout-button" aria-label="退出登录" @click="emit('logout')"><el-icon><SwitchButton /></el-icon></button>
      </el-tooltip>
    </footer>
  </aside>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import {
  Box, Calendar, ChatDotRound, ChatLineSquare, Connection, DataLine,
  EditPen, Lock, Search, Star, SwitchButton, User, View
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchConversations, fetchDataSources, createConversation, deleteConversation, renameConversation, batchDeleteConversations } from '../api'
import { useUserStore } from '../stores/user'
import { useConversationStore } from '../stores/conversation'
import { getScenarioConfig, getAllScenarios } from '../config/scenarios.js'

const userStore = useUserStore()
const convStore = useConversationStore()
const avatarText = computed(() => (userStore.displayName || 'U').charAt(0).toUpperCase())
const props = defineProps({
  activeDomain: { type: String, default: 'workbench' },
  activeSection: { type: String, default: 'chat' },
  activeTool: { type: String, default: 'chat' }
})
const domainMeta = {
  workbench: { title: 'AI 工作台', count: 1 },
  data: { title: '数据中心', count: 2 },
  development: { title: '开发中心', count: 2 },
  models: { title: '模型中心', count: 2 },
  monitor: { title: '穿透式监控模型', count: 2 },
  platform: { title: '平台配置', count: 2 }
}
const activeDomainMeta = computed(() => domainMeta[props.activeDomain] || domainMeta.workbench)
const activeDomainIcon = computed(() => ({
  data: DataLine,
  development: Connection,
  models: Box,
  monitor: View,
  platform: EditPen
}[props.activeDomain] || ChatDotRound))

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

const emit = defineEmits(['selectConversation', 'conversationCreated', 'conversationDeleted', 'dataSourceChanged', 'openChat', 'openWorkbench', 'openMonitorSection', 'openDataSource', 'openPromptManager', 'openScenarioManager', 'openUserManagement', 'logout'])

const filteredConversations = computed(() => {
  if (!searchQuery.value.trim()) return conversations.value
  const q = searchQuery.value.toLowerCase()
  return conversations.value.filter(c => (c.title || '新对话').toLowerCase().includes(q))
})

/**
 * 按时间分组（今天/昨天/更早），仅非批量模式 + 无搜索时启用。
 * 后端按 created_at 倒序返回，所以同组内仍保持最新在最上。
 */
const groupedConversations = computed(() => {
  if (batchMode.value || searchQuery.value.trim()) {
    return [{ key: 'all', label: '', items: filteredConversations.value }]
  }
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfYesterday = startOfToday - 86400000
  const today = [], yesterday = [], earlier = []
  for (const c of filteredConversations.value) {
    const t = c.createdAt ? new Date(c.createdAt).getTime() : 0
    if (t >= startOfToday) today.push(c)
    else if (t >= startOfYesterday) yesterday.push(c)
    else earlier.push(c)
  }
  return [
    { key: 'today', label: '今天', items: today },
    { key: 'yesterday', label: '昨天', items: yesterday },
    { key: 'earlier', label: '更早', items: earlier }
  ]
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
    emit('openChat')
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
    emit('openChat')
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

defineExpose({ setCurrentConversation, getSelectedDataSourceId, conversations, refreshConversations, createConversation: handleNewConversation })
</script>

<style scoped>
.sidebar {
  width: var(--sidebar-width, 316px);
  height: calc(100% - var(--workspace-gap, 10px) - var(--workspace-gap, 10px));
  margin: var(--workspace-gap, 10px);
  padding: clamp(11px, 1vw, 14px);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  overflow: hidden;
  color: var(--text-primary);
  background: #fff;
  border: 1px solid #e4e8ef;
  border-radius: 12px;
  box-shadow: 0 4px 16px rgba(31,35,41,.05);
  transition: transform .25s ease;
}
.sidebar.navigation-only {
  width: clamp(252px, 18vw, 300px);
}
.sidebar.navigation-only .section-links { grid-template-columns: 1fr; }
.sidebar.navigation-only .section-entry {
  min-height: 48px;
  padding-inline: 12px;
  font-size: 12px;
}
.sidebar.navigation-only .nav-icon {
  width: 28px;
  height: 28px;
  background: rgba(118,118,128,.055);
}
.sidebar.navigation-only .section-entry.active .nav-icon { background: white; }

.section-navigation {
  flex-shrink: 0;
  padding: 10px;
  border: 1px solid rgba(60,60,67,.08);
  border-radius: 10px;
  background: #fafbfc;
  box-shadow: none;
}
.section-navigation-heading {
  min-height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 2px 6px 9px;
}
.section-navigation-heading strong { color: #1d1d1f; font-size: 17px; font-weight: 670; letter-spacing: -.025em; }
.section-navigation-heading > em {
  flex-shrink: 0;
  padding: 5px 8px;
  border-radius: 999px;
  color: #797980;
  background: rgba(118,118,128,.07);
  font-size: 9px;
  font-style: normal;
}
.section-links { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.section-entry {
  position: relative;
  min-width: 0;
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 9px;
  border: 1px solid transparent;
  border-radius: 8px;
  outline: none;
  color: #64646a;
  background: rgba(247,248,250,.82);
  font: inherit;
  font-size: 11.5px;
  text-align: left;
  cursor: pointer;
  transition: border-color .15s ease, background .15s ease, color .15s ease, transform .15s ease;
}
.section-entry:hover { color: #1d1d1f; border-color: #e4e8ef; background: #fff; transform: none; }
.section-entry.active { color: #2468f2; border-color: #d7e3fb; background: #edf3ff; box-shadow: none; font-weight: 620; }
.section-entry.active::after { content:'';position:absolute;top:10px;bottom:10px;left:-2px;width:3px;border-radius:3px;background:#2468f2;box-shadow:0 2px 7px rgba(36,104,242,.2); }
.section-entry em { margin-left: auto; color: #2468f2; font-size: 8px; font-style: normal; font-weight: 750; }

.new-conv-btn {
  width: 100%;
  height: 46px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 10px 0 0;
  padding: 0 13px;
  border: 1px solid rgba(36, 104, 242, .16);
  border-radius: 8px;
  color: #fff;
  background: #2468f2;
  box-shadow: none;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}
.new-conv-btn:hover { background: #1f5fdc; }
.new-conv-btn:disabled { opacity: .65; cursor: wait; transform: none; }
.new-conv-btn kbd { margin-left: auto; color: rgba(255,255,255,.64); font: 10px var(--font-family-sans); }

.section-entry:focus-visible,
.new-conv-btn:focus-visible,
.logout-button:focus-visible,
.conv-delete:focus-visible {
  outline: 0;
  box-shadow: 0 0 0 3px rgba(36,104,242,.14);
}
.nav-icon {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border-radius: 7px;
  color: #74747b;
}
.section-entry.active .nav-icon { color: #2468f2; background: rgba(255,255,255,.7); }

.sidebar-context {
  min-height: 0;
  flex: 1;
  margin-top: 12px;
  border-top: 1px solid rgba(60, 60, 67, .1);
}
.sidebar-spacer { min-height: 0; flex: 1; display: grid; place-items: center; }
.section-ornament {
  position: relative;
  width: min(148px, 68%);
  aspect-ratio: 1;
  border: 1px solid rgba(36,104,242,.07);
  border-radius: 34px;
  overflow: hidden;
  background: #f7f9fc;
  box-shadow: none;
  opacity: .48;
  transform: rotate(-2deg);
}
.section-ornament::before {
  content: '';
  position: absolute;
  inset: 16px;
  border-radius: 27px;
  background-image: radial-gradient(rgba(36,104,242,.1) 1px, transparent 1px);
  background-size: 15px 15px;
  mask-image: linear-gradient(135deg, rgba(0,0,0,.9), transparent 72%);
}
.ornament-core {
  position: absolute;
  inset: 50% auto auto 50%;
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  border: 1px solid rgba(255,255,255,.8);
  border-radius: 17px;
  color: #5f8fce;
  background: rgba(255,255,255,.8);
  box-shadow: 0 7px 18px rgba(36,104,242,.08), inset 0 0 0 1px rgba(36,104,242,.06);
  transform: translate(-50%, -50%) rotate(2deg);
}
.ornament-orbit { position: absolute; border: 1px solid rgba(36,104,242,.09); border-radius: 50%; }
.orbit-one { inset: 21px; }
.orbit-two { inset: 43px -21px -25px 48px; }
.ornament-dot { position: absolute; width: 7px; height: 7px; border: 2px solid rgba(255,255,255,.85); border-radius: 50%; background: #91b7e8; }
.dot-one { top: 25px; right: 34px; }
.dot-two { right: 18px; bottom: 39px; width: 6px; height: 6px; background: #78a6df; }
.dot-three { left: 28px; bottom: 31px; width: 5px; height: 5px; background: #b2ccea; }
.chat-context { display: flex; flex-direction: column; overflow: hidden; }
.context-heading, .history-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.context-heading { padding: 13px 9px 9px; }
.context-heading > div { display: flex; flex-direction: column; gap: 1px; }
.context-heading small { color: var(--text-muted); font-size: 10.5px; }
.context-heading strong { font-size: 14px; font-weight: 630; }
.context-selects {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
  padding: 0 8px 10px;
}
.context-selects label { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.context-selects label > span { color: var(--text-muted); font-size: 10.5px; }
.scenario-select, .ds-select { width: 100%; }
.sidebar :deep(.el-select__wrapper) {
  min-height: 35px;
  padding: 0 9px;
  border-radius: 8px !important;
  background: rgba(255,255,255,.75);
  box-shadow: 0 0 0 1px rgba(60,60,67,.11) inset !important;
}
.sidebar :deep(.el-select__selected-item),
.sidebar :deep(.el-select__placeholder) { font-size: 11px; }
.sidebar :deep(.el-select__wrapper:hover) { box-shadow: 0 0 0 1px rgba(36,104,242,.35) inset !important; }

.history-heading {
  padding: 9px 9px 6px;
  color: #727278;
  font-size: 10px;
  font-weight: 650;
  letter-spacing: .04em;
  text-transform: uppercase;
}
.history-heading button {
  border: 0;
  color: #86868b;
  background: transparent;
  font: inherit;
  font-size: 10px;
  cursor: pointer;
}
.history-heading button:hover { color: #2468f2; }
.search-bar {
  height: 34px;
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 3px 6px;
  padding: 0 8px;
  border: 1px solid rgba(60,60,67,.1);
  border-radius: 8px;
  color: #8e8e93;
  background: rgba(255,255,255,.62);
}
.search-input { min-width: 0; flex: 1; border: 0; outline: 0; color: var(--text-primary); background: transparent; font: inherit; font-size: 11.5px; }
.search-input::placeholder { color: #aaaab0; }

.conversation-list { min-height: 0; flex: 1; overflow-y: auto; padding: 2px 4px 7px; }
.conv-group { margin-bottom: 3px; }
.conv-group-label { padding: 8px 8px 4px; color: #a1a1a6; font-size: 10px; font-weight: 600; }
.conv-item {
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  color: #65656b;
  font-size: 12px;
  cursor: pointer;
}
.conv-item:hover { color: #1d1d1f; background: rgba(118,118,128,.075); }
.conv-item.active { color: #174f88; background: rgba(36,104,242,.09); font-weight: 560; }
.conv-icon { width: 16px; flex-shrink: 0; color: #929298; }
.conv-item.active .conv-icon { color: #2468f2; }
.conv-title { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conv-edit-input {
  min-width: 0;
  flex: 1;
  padding: 2px 4px;
  border: 1px solid #61a8ef;
  border-radius: 5px;
  outline: 0;
  color: var(--text-primary);
  background: white;
  font: inherit;
}
.conv-delete {
  width: 18px;
  height: 18px;
  display: none;
  place-items: center;
  flex-shrink: 0;
  padding: 0;
  border: 0;
  border-radius: 6px;
  color: #99999f;
  background: transparent;
  cursor: pointer;
}
.conv-item:hover .conv-delete { display: grid; }
.conv-delete:hover { color: #d70015; background: rgba(215,0,21,.08); }
.conv-empty { padding: 20px 8px; color: #aaaab0; font-size: 11.5px; text-align: center; }
.batch-actions { padding: 7px; border-radius: 9px; background: rgba(255,255,255,.65); }
.batch-actions > div { display: flex; gap: 6px; margin-top: 6px; }
.batch-actions button { border: 0; color: #2468f2; background: transparent; font: inherit; font-size: 9px; cursor: pointer; }
.batch-actions .danger-action { color: #d70015; }
.batch-actions button:disabled { opacity: .4; cursor: default; }

.section-context {
  position: relative;
  overflow: hidden;
  padding: 24px 18px;
  border: 1px solid #e4e8ef;
  border-radius: 9px;
  background: #fafbfc;
}
.section-context::after {
  content: '';
  position: absolute;
  right: -34px;
  bottom: -45px;
  width: 115px;
  height: 115px;
  border-radius: 50%;
  background: rgba(36,104,242,.07);
  filter: blur(2px);
}
.section-orb {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  margin-bottom: 17px;
  border-radius: 12px;
  color: #2468f2;
  background: white;
  box-shadow: 0 6px 16px rgba(36,104,242,.12);
}
.section-context > small { color: #2468f2; font-size: 9px; font-weight: 750; letter-spacing: .14em; }
.section-context h2 { margin: 6px 0 10px; font-size: 22px; font-weight: 660; letter-spacing: -.03em; }
.section-context p { margin: 0; color: #6e6e73; font-size: 12px; line-height: 1.65; }
.section-context ul { position: relative; z-index: 1; display: grid; gap: 8px; margin: 16px 0 0; padding: 0; list-style: none; }
.section-context li { display: flex; gap: 8px; color: #4d4d52; font-size: 11.5px; }
.section-context li::before { content: '✓'; color: #2468f2; font-weight: 700; }

.sidebar-footer {
  min-height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-top: 8px;
  padding: 8px 7px;
  border: 1px solid rgba(60,76,98,.075);
  border-radius: 9px;
  background: #fafbfc;
}
.user-info { min-width: 0; flex: 1; display: flex; align-items: center; gap: 8px; }
.user-avatar { flex-shrink: 0; color: white; background: #2468f2; font-size: 11px; font-weight: 650; }
.user-info > div { min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.user-info strong { overflow: hidden; color: #3a3a3c; font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.user-info small { color: #98989e; font-size: 9.5px; }
.logout-button {
  width: 29px;
  height: 29px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  border: 0;
  border-radius: 9px;
  color: #8e8e93;
  background: transparent;
  cursor: pointer;
}
.logout-button:hover { color: #d70015; background: rgba(215,0,21,.07); }

@media (max-width: 767px) {
  .sidebar {
    position: fixed;
    inset: var(--platform-header-height, 62px) auto 0 0;
    z-index: 900;
    width: min(320px, calc(100vw - 28px));
    height: calc(100vh - var(--platform-header-height, 62px));
    margin: 0;
    border-radius: 0 20px 20px 0;
    transform: translateX(-105%);
    box-shadow: 16px 0 48px rgba(14,35,64,.18);
  }
  .sidebar.navigation-only { width: min(320px, calc(100vw - 28px)); }
  .sidebar.sidebar-open { transform: translateX(0); }
}
@media (min-width: 768px) and (max-width: 1180px) {
  .sidebar { padding: 9px; }
  .section-navigation { padding: 7px; }
  .section-navigation-heading { min-height: 43px; padding-bottom: 7px; }
  .section-navigation-heading strong { font-size: 15px; }
  .section-entry { min-height: 38px; gap: 5px; padding-inline: 6px; font-size: 10.5px; }
  .nav-icon { width: 20px; height: 20px; }
  .new-conv-btn { height: 42px; font-size: 12px; }
  .context-heading { padding-top: 10px; }
  .context-heading strong { font-size: 12.5px; }
  .context-selects { gap: 6px; padding-inline: 6px; }
  .sidebar :deep(.el-select__wrapper) { min-height: 31px; }
  .conv-item { min-height: 34px; font-size: 11px; }
  .section-context { padding: 20px 15px; }
  .section-context h2 { font-size: 19px; }
}
@media (min-width: 1600px) and (min-height: 850px) {
  .sidebar { padding: 16px; }
  .section-navigation { padding: 12px; }
  .section-navigation-heading { min-height: 55px; }
  .section-navigation-heading small { font-size: 9px; }
  .section-navigation-heading strong { font-size: 19px; }
  .section-entry { min-height: 48px; padding-inline: 11px; font-size: 12.5px; }
  .new-conv-btn { height: 50px; font-size: 14px; }
  .context-heading { padding-top: 15px; }
  .context-heading small { font-size: 11px; }
  .context-heading strong { font-size: 15px; }
  .context-selects label > span { font-size: 11px; }
  .sidebar :deep(.el-select__wrapper) { min-height: 38px; }
  .sidebar :deep(.el-select__selected-item),
  .sidebar :deep(.el-select__placeholder) { font-size: 12px; }
  .history-heading, .history-heading button { font-size: 11px; }
  .search-bar { height: 37px; }
  .conv-item { min-height: 41px; font-size: 12.5px; }
  .section-context { padding: 28px 21px; }
  .section-context h2 { font-size: 24px; }
  .section-context p { font-size: 13px; }
  .section-context li { font-size: 12px; }
}
@media (max-height: 760px) {
  .section-navigation { padding: 5px; }
  .section-navigation-heading { min-height: 35px; padding-bottom: 5px; }
  .section-entry { min-height: 34px; padding-block: 3px; }
  .new-conv-btn { height: 38px; margin-top: 6px; }
  .sidebar-context { margin-top: 7px; }
  .context-heading { padding-block: 8px 5px; }
  .sidebar :deep(.el-select__wrapper) { min-height: 29px; }
  .history-heading { padding-top: 6px; }
  .conv-item { min-height: 31px; padding-block: 4px; }
  .section-ornament { width: 118px; border-radius: 28px; }
  .ornament-core { width: 44px; height: 44px; border-radius: 15px; }
}
</style>
