<template>
  <div class="prompt-manager">
    <!-- Header -->
    <div class="prompt-header">
      <button class="back-btn" @click="$emit('close')">
        <span class="back-arrow">&larr;</span> 返回问数
      </button>
      <h2 class="page-title">提示词管理</h2>
    </div>

    <!-- 场景选择 -->
    <el-card shadow="hover" class="scenario-selector-card">
      <div class="selector-content">
        <span class="selector-label">选择场景：</span>
        <el-select v-model="selectedScenarioId" placeholder="请选择场景" @change="handleScenarioChange" style="width: 300px">
          <el-option v-for="scenario in scenarios" :key="scenario.id" :label="scenario.name" :value="scenario.id">
            <div class="scenario-option">
              <el-icon><Document /></el-icon>
              <span>{{ scenario.name }}</span>
            </div>
          </el-option>
        </el-select>
      </div>
    </el-card>

    <!-- 场景详情和提示词列表 -->
    <div v-if="selectedScenarioId" class="content-area">
      <!-- 场景信息卡片 -->
      <el-card shadow="hover" class="scenario-info-card">
        <div class="scenario-info">
          <div class="scenario-icon">
            <el-icon :size="40"><Document /></el-icon>
          </div>
          <div class="scenario-details">
            <h3>{{ currentScenario?.name }}</h3>
            <p class="description">{{ currentScenario?.description }}</p>
            <div class="scenario-tags">
              <el-tag :type="currentScenario?.category === 'business' ? 'success' : 'primary'" size="small">
                {{ getCategoryLabel(currentScenario?.category) }}
              </el-tag>
              <el-tag v-if="currentScenario?.isSystem" type="info" size="small">系统预设</el-tag>
            </div>
            <div class="scenario-actions">
              <el-button @click="previewCurrentPrompt" :icon="View">
                查看当前提示词
              </el-button>
              <el-button type="primary" @click="startScenarioChat" :icon="ChatDotRound">
                开始{{ currentScenario?.name }}对话
              </el-button>
            </div>
          </div>
        </div>
      </el-card>

      <!-- 提示词列表 -->
      <el-card shadow="hover" class="prompts-card">
        <template #header>
          <div class="card-header">
            <span class="card-title">提示词模板</span>
            <el-button type="primary" @click="handleCreatePrompt" :icon="Plus" size="small">
              添加提示词
            </el-button>
          </div>
        </template>

        <el-table :data="prompts" v-loading="loadingPrompts" stripe>
          <el-table-column prop="name" label="名称" width="180" />
          <el-table-column prop="code" label="编码" width="150" />
          <el-table-column prop="type" label="类型" width="100">
            <template #default="{ row }">
              <el-tag :type="getTypeColor(row.type)" size="small">{{ getTypeLabel(row.type) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
          <el-table-column label="变量" width="150">
            <template #default="{ row }">
              <el-tag v-for="variable in (row.variables || []).slice(0, 2)" :key="variable.name" size="small" style="margin: 2px">
                {{ variable.name }}
              </el-tag>
              <el-tag v-if="(row.variables || []).length > 2" size="small" type="info">
                +{{ (row.variables || []).length - 2 }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.isDefault" type="success" size="small">默认</el-tag>
              <el-tag v-if="row.isSystem" type="info" size="small">系统</el-tag>
              <el-tag v-if="!row.isEnabled" type="danger" size="small">禁用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="80" prop="version" />
          <el-table-column label="操作" width="250" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="handleViewPrompt(row)" :icon="View">查看</el-button>
              <el-button link type="primary" @click="handleEditPrompt(row)" :icon="Edit" :disabled="row.isSystem">编辑</el-button>
              <el-button link type="primary" @click="handleSetDefault(row)" :disabled="row.isDefault || row.isSystem">设为默认</el-button>
              <el-button link type="danger" @click="handleDeletePrompt(row)" :icon="Delete" :disabled="row.isSystem">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- 提示词编辑对话框 -->
    <el-dialog v-model="editDialogVisible" :title="editDialogTitle" width="900px" top="5vh">
      <el-form :model="editForm" label-width="120px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="名称">
              <el-input v-model="editForm.name" placeholder="请输入提示词名称" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="编码">
              <el-input v-model="editForm.code" placeholder="请输入提示词编码" :disabled="isEditMode" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="类型">
              <el-select v-model="editForm.type" placeholder="请选择类型" :disabled="isEditMode">
                <el-option label="系统提示词" value="system" />
                <el-option label="用户提示词" value="user" />
                <el-option label="助手提示词" value="assistant" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="版本">
              <el-input v-model="editForm.version" placeholder="请输入版本号" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="描述">
          <el-input v-model="editForm.description" placeholder="请输入提示词描述" />
        </el-form-item>

        <el-form-item label="提示词内容">
          <el-input v-model="editForm.content" type="textarea" :rows="12" placeholder="请输入提示词内容，支持 {{variable_name}} 格式的变量" />
        </el-form-item>

        <el-divider content-position="left">变量配置</el-divider>

        <el-button @click="handleAddVariable" :icon="Plus" size="small" style="margin-bottom: 16px">添加变量</el-button>

        <el-table :data="editForm.variables" border size="small">
          <el-table-column label="变量名" width="180">
            <template #default="{ row }">
              <el-input v-model="row.name" placeholder="variable_name" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="类型" width="120">
            <template #default="{ row }">
              <el-select v-model="row.type" size="small" placeholder="类型">
                <el-option label="字符串" value="string" />
                <el-option label="数字" value="number" />
                <el-option label="布尔" value="boolean" />
                <el-option label="JSON" value="json" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="默认值" width="150">
            <template #default="{ row }">
              <el-input v-model="row.defaultValue" placeholder="默认值" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="200">
            <template #default="{ row }">
              <el-input v-model="row.description" placeholder="变量描述" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="60">
            <template #default="{ $index }">
              <el-button link type="danger" @click="handleRemoveVariable($index)" :icon="Delete" />
            </template>
          </el-table-column>
        </el-table>

        <el-divider content-position="left">模型配置</el-divider>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="模型">
              <el-select v-model="editForm.modelConfig.model" placeholder="请选择模型">
                <el-option label="GLM-5.1" value="glm-5.1" />
                <el-option label="GPT-4o" value="gpt-4o" />
                <el-option label="DeepSeek" value="deepseek" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="温度">
              <el-input-number v-model="editForm.modelConfig.temperature" :min="0" :max="2" :step="0.1" :precision="1" />
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="最大Token">
              <el-input-number v-model="editForm.modelConfig.maxTokens" :min="100" :max="8000" :step="100" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item>
          <el-checkbox v-model="editForm.isEnabled">启用</el-checkbox>
          <el-checkbox v-model="editForm.isDefault" :disabled="!editForm.isEnabled">设为默认</el-checkbox>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSavePrompt" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 查看提示词对话框 -->
    <el-dialog v-model="viewDialogVisible" title="提示词详情" width="800px">
      <div v-if="currentPrompt" class="prompt-detail">
        <div class="detail-header">
          <h3>{{ currentPrompt.name }}</h3>
          <div class="tags">
            <el-tag :type="getTypeColor(currentPrompt.type)">{{ getTypeLabel(currentPrompt.type) }}</el-tag>
            <el-tag v-if="currentPrompt.isDefault" type="success">默认</el-tag>
            <el-tag v-if="currentPrompt.isSystem" type="info">系统</el-tag>
            <el-tag>{{ currentPrompt.version }}</el-tag>
          </div>
        </div>

        <div class="detail-section">
          <h4>描述</h4>
          <p>{{ currentPrompt.description }}</p>
        </div>

        <div class="detail-section">
          <h4>内容</h4>
          <div class="content-box">{{ currentPrompt.content }}</div>
        </div>

        <div v-if="currentPrompt.variables && currentPrompt.variables.length > 0" class="detail-section">
          <h4>变量</h4>
          <el-table :data="currentPrompt.variables" size="small" border>
            <el-table-column prop="name" label="变量名" width="150" />
            <el-table-column prop="type" label="类型" width="100" />
            <el-table-column prop="defaultValue" label="默认值" width="150" />
            <el-table-column prop="description" label="描述" />
          </el-table>
        </div>

        <div v-if="currentPrompt.modelConfig" class="detail-section">
          <h4>模型配置</h4>
          <div class="model-config">
            <p><strong>模型：</strong>{{ currentPrompt.modelConfig.model }}</p>
            <p><strong>温度：</strong>{{ currentPrompt.modelConfig.temperature }}</p>
            <p><strong>最大Token：</strong>{{ currentPrompt.modelConfig.maxTokens }}</p>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, View, Document, ChatDotRound } from '@element-plus/icons-vue'
import api from '../api'
import { useConversationStore } from '../stores/conversation'

const emit = defineEmits(['close'])

// 引入对话存储
const convStore = useConversationStore()

// 场景相关
const scenarios = ref([])
const selectedScenarioId = ref(null)

// 提示词相关
const prompts = ref([])
const loadingPrompts = ref(false)
const currentPrompt = ref(null)

// 编辑相关
const editDialogVisible = ref(false)
const viewDialogVisible = ref(false)
const editDialogTitle = ref('')
const isEditMode = ref(false)
const saving = ref(false)

const editForm = ref({
  name: '',
  code: '',
  description: '',
  type: 'system',
  content: '',
  variables: [],
  modelConfig: {
    model: 'glm-5.1',
    temperature: 0.7,
    maxTokens: 2000
  },
  version: '1.0',
  isEnabled: true,
  isDefault: false
})

// 计算属性
const currentScenario = computed(() => {
  return scenarios.value.find(s => s.id === selectedScenarioId.value)
})

// 获取场景分类标签
const getCategoryLabel = (category) => {
  const labels = {
    'query': '查询分析',
    'business': '业务分析',
    'ops': '运维监控',
    'mining': '数据挖掘'
  }
  return labels[category] || category
}

// 获取类型标签
const getTypeLabel = (type) => {
  const labels = {
    'system': '系统',
    'user': '用户',
    'assistant': '助手'
  }
  return labels[type] || type
}

// 获取类型颜色
const getTypeColor = (type) => {
  const colors = {
    'system': 'success',
    'user': 'primary',
    'assistant': 'warning'
  }
  return colors[type] || ''
}

// 加载场景列表
const loadScenarios = async () => {
  try {
    const response = await api.get('/scenarios')
    if (response.data.code === 200) {
      scenarios.value = response.data.data
      if (scenarios.value.length > 0) {
        selectedScenarioId.value = scenarios.value[0].id
        await handleScenarioChange(selectedScenarioId.value)
      }
    }
  } catch (error) {
    ElMessage.error('加载场景失败')
  }
}

// 场景变更
const handleScenarioChange = async (scenarioId) => {
  if (!scenarioId) return
  await loadPrompts(scenarioId)
}

// 加载提示词列表
const loadPrompts = async (scenarioId) => {
  loadingPrompts.value = true
  try {
    const response = await api.get(`/prompt-templates/scenario/${scenarioId}`)
    if (response.data.code === 200) {
      prompts.value = response.data.data
    }
  } catch (error) {
    ElMessage.error('加载提示词失败')
  } finally {
    loadingPrompts.value = false
  }
}

// 创建提示词
const handleCreatePrompt = () => {
  isEditMode.value = false
  editDialogTitle.value = '添加提示词'

  editForm.value = {
    scenarioId: selectedScenarioId.value,
    name: '',
    code: '',
    description: '',
    type: 'system',
    content: '',
    variables: [],
    modelConfig: {
      model: 'glm-5.1',
      temperature: 0.7,
      maxTokens: 2000
    },
    version: '1.0',
    isEnabled: true,
    isDefault: false
  }

  editDialogVisible.value = true
}

// 编辑提示词
const handleEditPrompt = (prompt) => {
  isEditMode.value = true
  editDialogTitle.value = '编辑提示词'

  editForm.value = {
    id: prompt.id,
    scenarioId: prompt.scenarioId,
    name: prompt.name,
    code: prompt.code,
    description: prompt.description,
    type: prompt.type,
    content: prompt.content,
    variables: [...(prompt.variables || [])],
    modelConfig: prompt.modelConfig ? { ...prompt.modelConfig } : {
      model: 'glm-5.1',
      temperature: 0.7,
      maxTokens: 2000
    },
    version: prompt.version,
    isEnabled: prompt.isEnabled,
    isDefault: prompt.isDefault
  }

  editDialogVisible.value = true
}

// 查看提示词
const handleViewPrompt = (prompt) => {
  currentPrompt.value = prompt
  viewDialogVisible.value = true
}

// 添加变量
const handleAddVariable = () => {
  if (!editForm.value.variables) {
    editForm.value.variables = []
  }
  editForm.value.variables.push({
    name: '',
    type: 'string',
    defaultValue: '',
    description: ''
  })
}

// 删除变量
const handleRemoveVariable = (index) => {
  editForm.value.variables.splice(index, 1)
}

// 设置默认提示词
const handleSetDefault = async (prompt) => {
  try {
    await ElMessageBox.confirm(`确定将 "${prompt.name}" 设为默认提示词吗？`, '确认设置', {
      type: 'warning'
    })

    const response = await api.put(`/prompt-templates/${prompt.id}/set-default`)
    if (response.data.code === 200) {
      ElMessage.success('设置成功')
      await loadPrompts(selectedScenarioId.value)
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('设置失败')
    }
  }
}

// 删除提示词
const handleDeletePrompt = async (prompt) => {
  try {
    await ElMessageBox.confirm(`确定要删除提示词 "${prompt.name}" 吗？`, '确认删除', {
      type: 'warning'
    })

    const response = await api.delete(`/prompt-templates/${prompt.id}`)
    if (response.data.code === 200) {
      ElMessage.success('删除成功')
      await loadPrompts(selectedScenarioId.value)
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

// 保存提示词
const handleSavePrompt = async () => {
  saving.value = true
  try {
    const data = { ...editForm.value }

    let response
    if (isEditMode.value) {
      response = await api.put(`/prompt-templates/${data.id}`, data)
    } else {
      response = await api.post('/prompt-templates', data)
    }

    if (response.data.code === 200) {
      ElMessage.success(isEditMode.value ? '更新成功' : '创建成功')
      editDialogVisible.value = false
      await loadPrompts(selectedScenarioId.value)
    }
  } catch (error) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

// 预览当前场景的提示词
const previewCurrentPrompt = async () => {
  if (!currentScenario.value) {
    ElMessage.warning('请先选择一个场景')
    return
  }

  try {
    const response = await api.get(`/prompt-templates/default/scenario/${currentScenario.value.code}`)
    if (response.data.code === 200 && response.data.data) {
      const prompt = response.data.data
      ElMessageBox.alert(
        prompt.content || '暂无提示词内容',
        `${currentScenario.value.name}场景提示词预览`,
        {
          customClass: 'scenario-prompt-dialog',
          dangerouslyUseHTMLString: false,
          confirmButtonText: '关闭'
        }
      )
    } else {
      ElMessage.error('获取提示词失败')
    }
  } catch (error) {
    console.error('获取提示词失败:', error)
    ElMessage.error('获取提示词失败')
  }
}

// 开始场景对话
const startScenarioChat = async () => {
  console.log('=== 开始场景对话被调用 ===')
  console.log('当前场景:', currentScenario.value)
  console.log('选择的场景ID:', selectedScenarioId.value)
  console.log('所有场景:', scenarios.value)

  // 使用selectedScenarioId直接查找场景，避免计算属性问题
  const scenario = scenarios.value.find(s => s.id === selectedScenarioId.value)

  if (!scenario) {
    console.error('找不到场景，selectedScenarioId:', selectedScenarioId.value)
    ElMessage.warning('场景加载中，请稍后重试')
    return
  }

  console.log('找到场景:', scenario)

  try {
    // 创建新对话
    console.log('创建新对话...')
    const response = await api.post('/conversation', {
      title: `${scenario.name}对话`,
      dataSourceId: convStore.currentDsId
    })

    if (response.data.code === 200 || response.data.success) {
      const newConversationId = response.data.data?.id || response.data.data

      console.log('新对话创建成功:', newConversationId)

      // 设置当前场景和新对话
      convStore.setCurrentConversation(newConversationId)
      convStore.setScenario(scenario.code)

      // 关闭提示词管理界面
      emit('close')

      // 提示用户
      ElMessage.success(`已创建${scenario.name}专用对话，可以开始专业对话了！`)

      console.log('=== 场景和新对话创建完成 ===')
    } else {
      throw new Error('创建对话失败')
    }
  } catch (error) {
    console.error('场景切换失败:', error)
    ElMessage.error('场景切换失败，请重试')
  }
}

onMounted(() => {
  loadScenarios()
})
</script>

<style scoped>
/* 场景提示词对话框样式 */
:deep(.scenario-prompt-dialog .el-message-box__content) {
  white-space: pre-wrap;
  font-family: 'Courier New', monospace;
  font-size: var(--font-base);
  line-height: 1.6;
  max-height: 500px;
  overflow-y: auto;
  background: #f5f7fa;
  padding: 15px;
  border-radius: var(--radius-sm);
  color: var(--text-regular);
}

.prompt-manager {
  padding: var(--space-xl);
  max-width: 1400px;
  margin: 0 auto;
}

.prompt-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.back-btn {
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: var(--font-xl);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  border-radius: var(--radius-md);
  transition: background var(--transition-fast);
}

.back-btn:hover {
  background: var(--color-primary-light);
}

.back-arrow {
  font-size: var(--font-2xl);
}

.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
}

.scenario-selector-card {
  margin-bottom: 20px;
}

.selector-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.selector-label {
  font-weight: 500;
  color: #606266;
}

.scenario-option {
  display: flex;
  align-items: center;
  gap: 8px;
}

.content-area {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.scenario-info-card {
  background: var(--surface);
  border: 1px solid var(--border);
}

.scenario-info-card :deep(.el-card__body) {
  padding: var(--space-2xl);
}

.scenario-info {
  display: flex;
  align-items: center;
  gap: 20px;
}

.scenario-icon {
  background: var(--color-primary-light);
  color: var(--color-primary);
  border-radius: 12px;
  padding: var(--space-lg);
  display: flex;
  align-items: center;
  justify-content: center;
}

.scenario-details h3 {
  margin: 0 0 8px 0;
  font-size: 20px;
  color: var(--text-regular);
}

.description {
  margin: 4px 0 12px 0;
  opacity: 0.7;
  font-size: var(--font-base);
  color: var(--text-secondary);
}

.scenario-tags {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}

.scenario-actions {
  margin-top: 16px;
  display: flex;
  gap: 12px;
}

.scenario-actions .el-button {
  height: 44px;
  font-size: var(--font-xl);
  font-weight: 500;
}

.scenario-actions .el-button:first-child {
  flex: 1;
}

.scenario-actions .el-button:last-child {
  flex: 2;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-weight: 600;
  font-size: var(--font-xl);
}

.prompt-detail {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.detail-header h3 {
  margin: 0;
  font-size: var(--font-2xl);
}

.tags {
  display: flex;
  gap: 8px;
}

.detail-section h4 {
  margin: 0 0 12px 0;
  font-size: var(--font-base);
  font-weight: 600;
  color: #606266;
}

.detail-section p {
  margin: 0;
  color: var(--color-info);
}

.content-box {
  background: #f5f7fa;
  padding: var(--space-lg);
  border-radius: var(--radius-lg);
  white-space: pre-wrap;
  line-height: 1.6;
  max-height: 400px;
  overflow-y: auto;
}

.model-config {
  display: flex;
  gap: 24px;
}

.model-config p {
  margin: 0;
}
</style>