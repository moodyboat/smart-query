<template>
  <div class="page-container scenario-manager">
    <div class="page-header">
      <button class="back-btn" @click="$emit('close')">
        <span class="back-arrow">&larr;</span> 返回问数
      </button>
      <h2 class="page-title">场景管理</h2>
    </div>

    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <span class="card-title">场景列表（{{ scenarios.length }}）</span>
          <el-button type="primary" size="small" :icon="Plus" @click="handleCreate">
            新增场景
          </el-button>
        </div>
      </template>

      <el-table :data="scenarios" v-loading="loading" stripe>
        <el-table-column prop="sortOrder" label="排序" width="70" />
        <el-table-column label="图标" width="70">
          <template #default="{ row }">
            <span class="scenario-icon-preview">{{ row.uiConfig?.avatar?.emoji || row.icon || '🎯' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="code" label="编码" width="170" />
        <el-table-column prop="category" label="分类" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ row.category || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色授权" min-width="200">
          <template #default="{ row }">
            <el-tag
              v-for="role in (roleMap[row.id] || [])"
              :key="role"
              size="small"
              :type="role === 'admin' ? 'danger' : 'primary'"
              style="margin: 2px"
            >{{ role }}</el-tag>
            <span v-if="!roleMap[row.id]?.length" class="muted">未授权</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.isSystem" type="info" size="small">系统</el-tag>
            <el-tag v-if="!row.isEnabled" type="danger" size="small">禁用</el-tag>
            <el-tag v-else type="success" size="small">启用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
            <el-button link type="warning" :icon="User" @click="handleAuth(row)">角色授权</el-button>
            <el-button
              link
              type="danger"
              :icon="Delete"
              :disabled="row.isSystem"
              @click="handleDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 场景编辑对话框 -->
    <el-dialog v-model="editDialogVisible" :title="editDialogTitle" width="960px" top="3vh">
      <el-form :model="editForm" label-width="120px">
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="名称">
              <el-input v-model="editForm.name" placeholder="如：供应链分析" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="编码">
              <el-input v-model="editForm.code" placeholder="如：supply_chain" :disabled="isEditMode" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="分类">
              <el-select v-model="editForm.category" placeholder="选择分类" allow-create filterable>
                <el-option label="查询分析" value="query" />
                <el-option label="业务分析" value="business" />
                <el-option label="运维监控" value="ops" />
                <el-option label="数据挖掘" value="mining" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="6">
            <el-form-item label="排序">
              <el-input-number v-model="editForm.sortOrder" :min="0" />
            </el-form-item>
          </el-col>
          <el-col :span="5">
            <el-form-item label="启用">
              <el-switch v-model="editForm.isEnabled" />
            </el-form-item>
          </el-col>
          <el-col :span="5">
            <el-form-item label="Emoji">
              <el-input v-model="editForm.uiConfig.avatar.emoji" placeholder="🎯" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="2" />
        </el-form-item>

        <el-divider content-position="left">数据范围（场景隔离）</el-divider>
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="绑定数据源">
              <el-select
                v-model="editForm.dataSourceId"
                clearable
                filterable
                placeholder="不绑=用户自由切换"
                style="width: 100%"
              >
                <el-option
                  v-for="ds in dataSources"
                  :key="ds.id"
                  :label="ds.name + (ds.system ? '（系统库）' : '')"
                  :value="ds.id"
                  :disabled="!!ds.system"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="Schema 标识">
              <el-input
                v-model="editForm.schemaName"
                placeholder="如 ods_dm；仅配置标识，不切换连接"
              />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="表清单">
          <el-select
            v-model="editForm.allowedTableList"
            multiple
            filterable
            allow-create
            default-first-option
            collapse-tags
            collapse-tags-tooltip
            :disabled="!editForm.dataSourceId"
            placeholder="留空=该数据源全部表可见；支持手动输入跨库表名(如 dim.dw_code_dict)"
            style="width: 100%"
          >
            <el-option
              v-for="t in tableCandidates"
              :key="t"
              :label="t"
              :value="t"
            />
          </el-select>
          <div class="muted" style="margin-top: 4px; font-size: 12px">
            选了数据源但未选表 = 该数据源全部表可见；选了表 = 仅这些表对 LLM 可见，SQL 越界会被执行层拦截。支持手动输入跨库表名。
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 角色授权对话框 -->
    <el-dialog v-model="authDialogVisible" title="角色授权" width="500px">
      <div v-if="authTarget" class="auth-content">
        <p class="auth-tip">
          为场景 <strong>{{ authTarget.name }}</strong>（{{ authTarget.code }}）授权可访问的角色。
          <code>admin</code> 默认拥有所有场景权限。
        </p>
        <div class="auth-roles-current">
          <el-tag
            v-for="role in authRoles"
            :key="role"
            closable
            :type="role === 'admin' ? 'danger' : 'primary'"
            style="margin: 4px"
            @close="removeRole(role)"
          >{{ role }}</el-tag>
          <span v-if="!authRoles.length" class="muted">未授权任何角色</span>
        </div>
        <el-divider />
        <div class="auth-add">
          <el-input
            v-model="newRole"
            placeholder="输入角色名（如 user/analyst/finance）"
            @keyup.enter="addRole"
          />
          <el-button type="primary" @click="addRole">添加</el-button>
        </div>
        <div class="auth-quick">
          <span class="muted">快捷：</span>
          <el-button size="small" @click="quickAddRole('user')">+ user</el-button>
          <el-button size="small" @click="quickAddRole('analyst')">+ analyst</el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="authDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="savingAuth" @click="handleSaveAuth">保存授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, User } from '@element-plus/icons-vue'
import {
  fetchAllScenariosForAdmin,
  createScenario,
  updateScenario,
  deleteScenario,
  fetchScenarioRoles,
  setScenarioRoles,
  fetchDataSources,
  fetchDataSourceTables
} from '../api'

const emit = defineEmits(['close'])

const scenarios = ref([])
const roleMap = ref({})
const loading = ref(false)
const saving = ref(false)
const editDialogVisible = ref(false)
const editDialogTitle = ref('')
const isEditMode = ref(false)

// 数据范围相关
const dataSources = ref([])
const tableCandidates = ref([])

const editForm = reactive({
  id: null,
  name: '',
  code: '',
  description: '',
  category: 'business',
  isEnabled: true,
  sortOrder: 99,
  uiConfig: emptyUiConfig(),
  dataSourceId: null,
  schemaName: '',
  allowedTableList: []
})

function emptyUiConfig() {
  return {
    theme: { primary: '#2563eb', gradient: '', background: '#f5f7fa', headerBg: '', cardBg: 'rgba(255, 255, 255, 0.9)' },
    avatar: { emoji: '🎯', fallbackColor: '#2563eb', size: 'large' },
    welcome: { title: '', subtitle: '', description: '' },
    capabilities: [],
    examples: []
  }
}

// 角色授权相关
const authDialogVisible = ref(false)
const authTarget = ref(null)
const authRoles = ref([])
const newRole = ref('')
const savingAuth = ref(false)

async function loadScenarios() {
  loading.value = true
  try {
    const data = await fetchAllScenariosForAdmin()
    scenarios.value = data || []
    // 并行加载每个场景的角色
    const entries = await Promise.all(
      scenarios.value.map(async s => {
        try {
          const roles = await fetchScenarioRoles(s.id)
          return [s.id, roles || []]
        } catch {
          return [s.id, []]
        }
      })
    )
    roleMap.value = Object.fromEntries(entries)
  } catch (e) {
    ElMessage.error('加载场景失败')
  } finally {
    loading.value = false
  }
}

function handleCreate() {
  isEditMode.value = false
  editDialogTitle.value = '新增场景'
  Object.assign(editForm, {
    id: null,
    name: '',
    code: '',
    description: '',
    category: 'business',
    isEnabled: true,
    sortOrder: (scenarios.value.at(-1)?.sortOrder || 0) + 1,
    uiConfig: emptyUiConfig(),
    dataSourceId: null,
    schemaName: '',
    allowedTableList: []
  })
  tableCandidates.value = []
  editDialogVisible.value = true
}

function handleEdit(row) {
  isEditMode.value = true
  editDialogTitle.value = '编辑场景'
  const ui = row.uiConfig || emptyUiConfig()
  Object.assign(editForm, {
    id: row.id,
    name: row.name,
    code: row.code,
    description: row.description || '',
    category: row.category || 'business',
    isEnabled: row.isEnabled,
    sortOrder: row.sortOrder,
    // uiConfig 不再 UI 编辑，但保留原值用于保存时透传（避免清空主题/欢迎语等存量配置）
    uiConfig: {
      theme: { ...emptyUiConfig().theme, ...(ui.theme || {}) },
      avatar: { ...emptyUiConfig().avatar, ...(ui.avatar || {}) },
      welcome: { ...emptyUiConfig().welcome, ...(ui.welcome || {}) },
      capabilities: Array.isArray(ui.capabilities) ? ui.capabilities.map(c => ({ ...c })) : [],
      examples: Array.isArray(ui.examples) ? [...ui.examples] : []
    },
    dataSourceId: row.dataSourceId ?? null,
    schemaName: row.schemaName || '',
    allowedTableList: Array.isArray(row.allowedTableList) ? [...row.allowedTableList] : []
  })
  editDialogVisible.value = true
  if (editForm.dataSourceId) {
    refreshTableCandidates(editForm.dataSourceId)
  } else {
    tableCandidates.value = []
  }
}

async function refreshTableCandidates(dsId) {
  if (!dsId) {
    tableCandidates.value = []
    return
  }
  try {
    const tables = await fetchDataSourceTables(dsId)
    tableCandidates.value = (tables || [])
      .map(t => (typeof t === 'string' ? t : (t?.tableName || t?.name || t?.table_name)))
      .filter(Boolean)
      .sort()
  } catch (e) {
    tableCandidates.value = []
  }
}

watch(() => editForm.dataSourceId, (newDs, oldDs) => {
  if (newDs === oldDs) return
  if (!newDs) {
    tableCandidates.value = []
    editForm.allowedTableList = []
    return
  }
  refreshTableCandidates(newDs)
})

async function handleSave() {
  if (!editForm.name?.trim() || !editForm.code?.trim()) {
    ElMessage.warning('名称和编码不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: editForm.name,
      code: editForm.code,
      description: editForm.description,
      category: editForm.category,
      isEnabled: editForm.isEnabled,
      sortOrder: editForm.sortOrder,
      uiConfig: editForm.uiConfig,
      dataSourceId: editForm.dataSourceId ?? null,
      schemaName: editForm.schemaName?.trim() || null,
      allowedTableList: Array.isArray(editForm.allowedTableList) ? editForm.allowedTableList : []
    }
    if (isEditMode.value) {
      await updateScenario(editForm.id, payload)
      ElMessage.success('更新成功')
    } else {
      await createScenario(payload)
      ElMessage.success('创建成功')
    }
    editDialogVisible.value = false
    await loadScenarios()
  } catch (e) {
    ElMessage.error('保存失败：' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定要删除场景 "${row.name}" 吗？关联的角色授权也会一并清除。`, '确认删除', {
      type: 'warning'
    })
    await deleteScenario(row.id)
    ElMessage.success('已删除')
    await loadScenarios()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

function handleAuth(row) {
  authTarget.value = row
  authRoles.value = [...(roleMap.value[row.id] || [])]
  newRole.value = ''
  authDialogVisible.value = true
}

function addRole() {
  const r = newRole.value.trim()
  if (!r) return
  if (!authRoles.value.includes(r)) {
    authRoles.value.push(r)
  }
  newRole.value = ''
}

function quickAddRole(r) {
  if (!authRoles.value.includes(r)) authRoles.value.push(r)
}

function removeRole(r) {
  authRoles.value = authRoles.value.filter(x => x !== r)
}

async function handleSaveAuth() {
  if (!authTarget.value) return
  savingAuth.value = true
  try {
    await setScenarioRoles(authTarget.value.id, authRoles.value)
    roleMap.value[authTarget.value.id] = [...authRoles.value]
    ElMessage.success('授权已保存')
    authDialogVisible.value = false
  } catch (e) {
    ElMessage.error('保存授权失败')
  } finally {
    savingAuth.value = false
  }
}

onMounted(() => {
  loadScenarios()
  loadDataSources()
})

async function loadDataSources() {
  try {
    const list = await fetchDataSources()
    dataSources.value = list || []
  } catch (e) {
    dataSources.value = []
  }
}
</script>

<style scoped>
.scenario-manager {
  /* 复用全局 .page-container 规则：max-width 1400px + margin auto + padding var(--space-xl) */
}

/* .prompt-header / .back-btn / .back-arrow / .page-title 复用全局公共类（style.css）*/

/* 整体卡片弱化边框、加柔和阴影 */
.scenario-manager :deep(.el-card) {
  border: 1px solid var(--border-light);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-sm);
}
.scenario-manager :deep(.el-card__header) {
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--border-lighter);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-title {
  font-weight: 600;
  font-size: var(--font-xl);
  color: var(--text-primary);
}

/* el-table 头部轻量化、行间距放大 */
.scenario-manager :deep(.el-table) {
  --el-table-border-color: var(--border-lighter);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--brand-primary-lighter);
}
.scenario-manager :deep(.el-table th.el-table__cell) {
  background: transparent;
  font-weight: 600;
  font-size: var(--font-sm);
  color: var(--text-muted);
  letter-spacing: 0.02em;
  border-bottom: 1px solid var(--border);
  padding: 10px 0;
}
.scenario-manager :deep(.el-table td.el-table__cell) {
  border-bottom: 1px solid var(--border-lighter);
  padding: 12px 0;
}
.scenario-manager :deep(.el-table .cell) {
  padding: 0 12px;
}

.scenario-icon-preview {
  font-size: 20px;
}

.muted {
  color: var(--text-muted);
  font-size: 12px;
}

.example-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.auth-content {
  padding: 0 8px;
}

.auth-tip {
  font-size: 14px;
  color: var(--text-regular);
  line-height: 1.6;
  margin: 0 0 16px 0;
}

.auth-tip code {
  background: var(--color-info-light);
  padding: 2px 6px;
  border-radius: 4px;
  font-family: var(--font-family-mono);
}

.auth-roles-current {
  min-height: 40px;
  padding: 12px;
  background: var(--color-info-light);
  border-radius: var(--radius-md);
}

.auth-add {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.auth-quick {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
