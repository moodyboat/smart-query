<template>
  <div class="datasource-manager">
    <!-- Header -->
    <div class="datasource-header">
      <button class="back-btn" @click="$emit('close')">
        <span class="back-arrow">&larr;</span> 返回问数
      </button>
      <h2 class="page-title">数据源管理</h2>
    </div>

    <!-- 使用统计卡片 -->
    <el-card shadow="hover" class="stats-card" v-loading="loadingStats">
      <template #header>
        <div class="card-header">
          <span class="card-title">📊 使用统计 ({{ timeRangeLabel }})</span>
          <el-radio-group v-model="timeRange" @change="loadUsageStats" size="small">
            <el-radio-button label="daily">今日</el-radio-button>
            <el-radio-button label="weekly">本周</el-radio-button>
            <el-radio-button label="monthly">本月</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      <div class="stats-content">
        <el-row :gutter="16" v-if="usageStats.length > 0">
          <el-col :span="6" v-for="stat in usageStats.slice(0, 4)" :key="stat.dataSourceId">
            <div class="stat-item">
              <div class="stat-label">{{ stat.dataSourceName }}</div>
              <div class="stat-value">{{ stat.totalQueries }} 次查询</div>
              <div class="stat-meta">
                <span class="success-rate">成功率 {{ stat.successRate?.toFixed(1) }}%</span>
                <span class="avg-time">平均 {{ stat.avgQueryTimeMs?.toFixed(0) || 0 }}ms</span>
              </div>
            </div>
          </el-col>
        </el-row>
        <el-empty v-else description="暂无使用数据" :image-size="60" />
      </div>
    </el-card>

    <!-- 数据源列表 -->
    <el-card shadow="hover">
      <template #header>
        <div class="card-header">
          <el-button type="primary" @click="handleCreate" :icon="Plus">
            添加数据源
          </el-button>
        </div>
      </template>

      <el-table :data="dataSources" v-loading="loading" stripe>
        <el-table-column prop="name" label="名称" min-width="150" />
        <el-table-column prop="type" label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="getTypeColor(row.type)" size="small">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连接信息" min-width="200">
          <template #default="{ row }">
            <span class="connection-info">
              {{ row.host }}:{{ row.port }}/{{ row.databaseName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.system ? 'info' : (row.status === 'active' ? 'success' : 'warning')" size="small">
              {{ row.system ? '系统库' : (row.status === 'active' ? '正常' : '未激活') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="问答功能" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="(row.forQuestionAnswering ?? true) ? 'success' : 'info'" size="small">
              {{ (row.forQuestionAnswering ?? true) ? '可用' : '不可用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleTestDetailed(row)" :loading="testing[row.id]">
              详细测试
            </el-button>
            <el-button link type="primary" @click="handleEdit(row)" :disabled="row.system">
              编辑
            </el-button>
            <el-button link type="primary" @click="handleViewTables(row)">
              查看表
            </el-button>
            <el-popconfirm title="确定删除此数据源吗？" @confirm="handleDelete(row)" v-if="!row.system">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 数据源表单对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑数据源' : '添加数据源'"
      width="600px"
      @close="handleDialogClose"
    >
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="120px"
      >
        <el-form-item label="数据源名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入数据源名称" />
        </el-form-item>

        <el-form-item label="数据库类型" prop="type">
          <el-select v-model="form.type" placeholder="请选择数据库类型" class="w-full">
            <el-option label="MySQL" value="MySQL" />
            <el-option label="PostgreSQL" value="PostgreSQL" />
            <el-option label="Oracle" value="Oracle" />
            <el-option label="SQL Server" value="SQLServer" />
            <el-option label="GBase" value="GBase" />
          </el-select>
        </el-form-item>

        <el-form-item label="主机地址" prop="host">
          <el-input v-model="form.host" placeholder="请输入主机地址，如 localhost 或 IP 地址" />
        </el-form-item>

        <el-form-item label="端口" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" class="w-full" />
        </el-form-item>

        <el-form-item label="数据库名称" prop="databaseName">
          <el-input v-model="form.databaseName" placeholder="请输入数据库名称" />
        </el-form-item>

        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入数据库用户名" />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            :placeholder="isEdit ? '留空则不修改密码' : '请输入数据库密码'"
            show-password
          />
        </el-form-item>

        <el-form-item label="其他配置" prop="extraConfig">
          <el-input
            v-model="form.extraConfig"
            type="textarea"
            :rows="3"
            placeholder="JSON格式的额外配置（可选）"
          />
        </el-form-item>

        <el-form-item label="问答功能">
          <el-switch
            v-model="form.forQuestionAnswering"
            active-text="可用"
            inactive-text="不可用"
          />
          <div class="form-item-tip">关闭后该数据源将不会出现在问答功能的数据源选择中</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave" :loading="saving">
          {{ isEdit ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 详细测试结果对话框 -->
    <el-dialog
      v-model="testResultDialogVisible"
      title="连接测试详情"
      width="700px"
    >
      <div v-if="testResult" class="test-result">
        <el-alert
          :type="testResult.success ? 'success' : 'error'"
          :title="testResult.message"
          :closable="false"
          show-icon
          style="margin-bottom: 20px"
        >
          <template #default>
            <div class="test-summary">
              <div class="test-item">
                <span class="test-label">连接延迟:</span>
                <span class="test-value">{{ testResult.latencyMs }} ms</span>
              </div>
            </div>
          </template>
        </el-alert>

        <el-descriptions :column="1" border v-if="testResult.success">
          <el-descriptions-item label="数据库版本">
            {{ testResult.databaseVersion || 'Unknown' }}
          </el-descriptions-item>
          <el-descriptions-item label="当前数据库">
            {{ testResult.currentSchema || 'Unknown' }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="permissions-section" v-if="testResult.permissions">
          <h4>权限检查</h4>
          <el-table :data="getPermissionsList(testResult.permissions)" size="small">
            <el-table-column prop="name" label="权限" width="120" />
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.granted ? 'success' : 'danger'" size="small">
                  {{ row.granted ? '✓' : '✗' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" />
          </el-table>
        </div>
      </div>
    </el-dialog>

    <!-- 数据表查看对话框 -->
    <el-dialog
      v-model="tablesDialogVisible"
      title="数据表列表"
      width="800px"
    >
      <el-table :data="tables" v-loading="loadingTables" stripe max-height="400">
        <el-table-column prop="name" label="表名" min-width="200" />
        <el-table-column prop="comment" label="注释" min-width="200" />
        <el-table-column prop="rows" label="行数" width="100" align="right" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleViewColumns(row)">
              查看字段
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 字段查看对话框 -->
    <el-dialog
      v-model="columnsDialogVisible"
      :title="`字段列表 - ${currentTable}`"
      width="900px"
    >
      <el-table :data="columns" v-loading="loadingColumns" stripe max-height="400">
        <el-table-column prop="name" label="字段名" min-width="150" />
        <el-table-column prop="type" label="类型" min-width="120" />
        <el-table-column label="可空" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.nullable === 'YES' ? 'warning' : 'success'" size="small">
              {{ row.nullable === 'YES' ? '可空' : '必填' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="key" label="键" width="80" />
        <el-table-column prop="comment" label="注释" min-width="200" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { DataLine, Plus } from '@element-plus/icons-vue'
import api from '../api/index.js'

const loading = ref(false)
const saving = ref(false)
const testing = ref({})
const loadingTables = ref(false)
const loadingColumns = ref(false)
const loadingStats = ref(false)

const dataSources = ref([])
const tables = ref([])
const columns = ref([])
const currentTable = ref('')
const currentDataSourceId = ref(null)
const usageStats = ref([])
const timeRange = ref('weekly')
const testResult = ref(null)

const dialogVisible = ref(false)
const tablesDialogVisible = ref(false)
const columnsDialogVisible = ref(false)
const testResultDialogVisible = ref(false)

const isEdit = ref(false)
const formRef = ref()

const form = reactive({
  id: null,
  name: '',
  type: 'MySQL',
  host: '',
  port: 3306,
  databaseName: '',
  username: '',
  password: '',
  extraConfig: '',
  forQuestionAnswering: true
})

const timeRangeLabel = computed(() => {
  const labels = { daily: '今日', weekly: '本周', monthly: '本月' }
  return labels[timeRange.value] || '本周'
})

const rules = {
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择数据库类型', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口号', trigger: 'blur' }],
  databaseName: [{ required: true, message: '请输入数据库名称', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    {
      validator: (rule, value, callback) => {
        if (!isEdit.value && (!value || value.length === 0)) {
          callback(new Error('请输入密码'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const getTypeColor = (type) => {
  const colors = {
    MySQL: 'primary',
    PostgreSQL: 'success',
    Oracle: 'warning',
    SQLServer: 'danger',
    GBase: 'info'
  }
  return colors[type] || 'info'
}

const getPermissionsList = (permissions) => {
  return [
    { name: 'SELECT', granted: permissions.canSelect, description: '查询数据' },
    { name: 'SHOW', granted: permissions.canShow, description: '显示表列表' },
    { name: 'DESCRIBE', granted: permissions.canDescribe, description: '查看表结构' },
    { name: 'EXPLAIN', granted: permissions.canExplain, description: '执行计划分析' }
  ]
}

const loadDataSources = async () => {
  loading.value = true
  try {
    const { data } = await api.get('/datasource')
    dataSources.value = data.data || []
  } catch (error) {
    ElMessage.error('加载数据源列表失败')
    console.error(error)
  } finally {
    loading.value = false
  }
}

const loadUsageStats = async () => {
  loadingStats.value = true
  try {
    const { data } = await api.get('/datasource/stats/usage', {
      params: { timeRange: timeRange.value }
    })
    usageStats.value = data.data || []
  } catch (error) {
    console.error('加载使用统计失败:', error)
  } finally {
    loadingStats.value = false
  }
}

const handleCreate = () => {
  isEdit.value = false
  Object.assign(form, {
    id: null,
    name: '',
    type: 'MySQL',
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    password: '',
    extraConfig: '',
    forQuestionAnswering: true
  })
  dialogVisible.value = true
}

const handleEdit = (row) => {
  isEdit.value = true
  Object.assign(form, {
    id: row.id,
    name: row.name,
    type: row.type,
    host: row.host,
    port: row.port,
    databaseName: row.databaseName,
    username: row.username,
    password: '',
    extraConfig: row.extraConfig || '',
    forQuestionAnswering: row.forQuestionAnswering ?? true
  })
  dialogVisible.value = true
}

const handleSave = async () => {
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  saving.value = true
  try {
    const data = {
      name: form.name,
      type: form.type,
      host: form.host,
      port: form.port,
      databaseName: form.databaseName,
      username: form.username,
      extraConfig: form.extraConfig || null,
      forQuestionAnswering: form.forQuestionAnswering
    }

    if (!isEdit.value || form.password) {
      data.password = form.password
    }

    if (isEdit.value) {
      await api.put(`/datasource/${form.id}`, data)
      ElMessage.success('数据源更新成功')
    } else {
      await api.post('/datasource', data)
      ElMessage.success('数据源添加成功')
    }

    dialogVisible.value = false
    await loadDataSources()
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '操作失败')
    console.error(error)
  } finally {
    saving.value = false
  }
}

const handleTestDetailed = async (row) => {
  testing.value[row.id] = true
  try {
    const { data } = await api.post(`/datasource/${row.id}/test-detailed`)
    testResult.value = data.data
    testResultDialogVisible.value = true

    if (testResult.value.success) {
      ElMessage.success('连接测试成功')
    } else {
      ElMessage.error(testResult.value.message || '连接测试失败')
    }
  } catch (error) {
    ElMessage.error('连接测试失败')
    console.error(error)
  } finally {
    testing.value[row.id] = false
  }
}

const handleDelete = async (row) => {
  try {
    await api.delete(`/datasource/${row.id}`)
    ElMessage.success('数据源删除成功')
    await loadDataSources()
  } catch (error) {
    ElMessage.error('删除失败')
    console.error(error)
  }
}

const handleViewTables = async (row) => {
  currentDataSourceId.value = row.id
  loadingTables.value = true
  tablesDialogVisible.value = true
  try {
    const { data } = await api.get(`/datasource/${row.id}/tables`)
    tables.value = data.data || []
  } catch (error) {
    ElMessage.error('加载表列表失败')
    console.error(error)
  } finally {
    loadingTables.value = false
  }
}

const handleViewColumns = async (row) => {
  currentTable.value = row.name
  loadingColumns.value = true
  columnsDialogVisible.value = true
  try {
    const { data } = await api.get(`/datasource/${currentDataSourceId.value}/tables/${row.name}/columns`)
    columns.value = data.data || []
  } catch (error) {
    ElMessage.error('加载字段列表失败')
    console.error(error)
  } finally {
    loadingColumns.value = false
  }
}

const handleDialogClose = () => {
  formRef.value?.resetFields()
}

onMounted(() => {
  loadDataSources()
  loadUsageStats()
})
</script>

<style scoped>
.datasource-manager {
  padding: var(--space-xl);
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.datasource-header {
  height: 52px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: 0 var(--space-xl);
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.back-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: var(--font-md);
  color: var(--primary);
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: var(--radius-md);
  transition: background 0.15s;
}

.back-btn:hover {
  background: var(--primary-light);
}

.back-arrow {
  font-size: var(--font-lg);
}

.page-title {
  font-size: var(--font-xl);
  font-weight: 600;
  color: var(--text-primary);
}

.stats-card {
  margin-bottom: 20px;
}

.card-title {
  font-weight: 600;
  font-size: var(--font-xl);
}

.stats-content {
  padding: 10px 0;
}

.stat-item {
  background: var(--el-fill-color-light);
  padding: var(--space-lg);
  border-radius: var(--radius-lg);
  text-align: center;
}

.stat-label {
  font-size: var(--font-base);
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--el-color-primary);
  margin-bottom: 8px;
}

.stat-meta {
  display: flex;
  justify-content: center;
  gap: 12px;
  font-size: var(--font-sm);
  color: var(--el-text-color-secondary);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.connection-info {
  font-family: var(--font-family-mono);
  font-size: var(--font-sm);
}

.w-full {
  width: 100%;
}

.form-item-tip {
  font-size: var(--font-sm);
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}

.test-result {
  padding: 10px 0;
}

.test-summary {
  display: flex;
  gap: 20px;
  margin-top: 10px;
}

.test-item {
  display: flex;
  gap: 8px;
}

.test-label {
  color: var(--el-text-color-secondary);
}

.test-value {
  font-weight: 600;
  color: var(--el-color-primary);
}

.permissions-section {
  margin-top: 20px;
}

.permissions-section h4 {
  margin-bottom: 10px;
  font-size: var(--font-base);
  font-weight: 600;
}
</style>
