<template>
  <div class="metric-config">
    <div class="config-toolbar">
      <el-input
        v-model="searchQuery"
        placeholder="搜索指标名称..."
        size="small"
        clearable
        style="width: 240px"
        prefix-icon="Search"
      />
      <el-button type="primary" size="small" @click="openCreate">新建指标</el-button>
    </div>

    <div v-if="loading" class="config-loading">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="!filteredMetrics.length" class="config-empty">
      <p v-if="searchQuery">没有匹配「{{ searchQuery }}」的指标</p>
      <p v-else>暂无指标配置</p>
    </div>

    <el-table
      v-else
      :data="filteredMetrics"
      size="small"
      stripe
      border
      style="width: 100%"
    >
      <el-table-column prop="businessName" label="业务名称" min-width="120" show-overflow-tooltip />
      <el-table-column prop="metricType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="metricTypeTag(row.metricType)" size="small" effect="plain">
            {{ metricTypeLabel(row.metricType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceTable" label="来源表" min-width="120" show-overflow-tooltip />
      <el-table-column prop="aggregation" label="聚合方式" width="90" />
      <el-table-column prop="formula" label="计算公式" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="info" @click="handleResolveSql(row)">解析SQL</el-button>
          <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="showDialog"
      :title="editingId ? '编辑指标' : '新建指标'"
      width="600px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="英文名称" required>
          <el-input v-model="form.name" placeholder="如: total_revenue" />
        </el-form-item>
        <el-form-item label="业务名称" required>
          <el-input v-model="form.businessName" placeholder="如: 总营收" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="指标用途说明" />
        </el-form-item>
        <el-form-item label="指标类型" required>
          <el-radio-group v-model="form.metricType">
            <el-radio value="basic">基础指标</el-radio>
            <el-radio value="derived">派生指标</el-radio>
            <el-radio value="composite">复合指标</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="来源表">
          <el-input v-model="form.sourceTable" placeholder="如: orders" />
        </el-form-item>
        <el-form-item label="来源列">
          <el-input v-model="form.sourceColumn" placeholder="如: amount" />
        </el-form-item>
        <el-form-item label="聚合方式">
          <el-select v-model="form.aggregation" placeholder="选择聚合方式" style="width: 100%" :teleported="false">
            <el-option label="SUM (求和)" value="SUM" />
            <el-option label="COUNT (计数)" value="COUNT" />
            <el-option label="AVG (平均)" value="AVG" />
            <el-option label="MAX (最大)" value="MAX" />
            <el-option label="MIN (最小)" value="MIN" />
            <el-option label="COUNT DISTINCT (去重计数)" value="COUNT_DISTINCT" />
            <el-option label="无聚合" value="NONE" />
          </el-select>
        </el-form-item>
        <el-form-item label="计算公式">
          <el-input v-model="form.formula" type="textarea" :rows="3" placeholder="如: SUM(amount) / COUNT(DISTINCT user_id)" />
        </el-form-item>
        <el-form-item label="单位">
          <el-input v-model="form.unit" placeholder="如: 元、个、%" style="width: 160px" />
        </el-form-item>
        <el-form-item label="筛选条件">
          <el-input v-model="form.filterCondition" type="textarea" :rows="2" placeholder="如: status = 'completed' AND created_at >= '2024-01-01'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ editingId ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- SQL Preview Dialog -->
    <el-dialog v-model="showSqlDialog" title="解析SQL" width="560px">
      <div v-if="resolvedSql" class="sql-preview">
        <pre class="sql-code">{{ resolvedSql }}</pre>
      </div>
      <div v-else class="config-empty">
        <p>无法解析该指标的SQL</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useOntology } from '../../composables/useOntology'

const props = defineProps({
  dataSourceId: {
    type: [Number, String],
    required: true
  }
})

const { loading, listMetrics, createMetric, updateMetric, deleteMetric, resolveMetricSql } = useOntology()

const metrics = ref([])
const searchQuery = ref('')
const showDialog = ref(false)
const showSqlDialog = ref(false)
const editingId = ref(null)
const saving = ref(false)
const resolvedSql = ref('')

const form = ref(defaultForm())

function defaultForm() {
  return {
    name: '',
    businessName: '',
    description: '',
    metricType: 'basic',
    sourceTable: '',
    sourceColumn: '',
    aggregation: 'SUM',
    formula: '',
    unit: '',
    filterCondition: ''
  }
}

const filteredMetrics = computed(() => {
  if (!searchQuery.value) return metrics.value
  const q = searchQuery.value.toLowerCase()
  return metrics.value.filter(m =>
    m.businessName?.toLowerCase().includes(q) ||
    m.name?.toLowerCase().includes(q) ||
    m.sourceTable?.toLowerCase().includes(q) ||
    m.formula?.toLowerCase().includes(q)
  )
})

function metricTypeLabel(type) {
  return { basic: '基础', derived: '派生', composite: '复合' }[type] || type
}

function metricTypeTag(type) {
  return { basic: '', derived: 'warning', composite: 'success' }[type] || 'info'
}

async function loadMetrics() {
  try {
    metrics.value = await listMetrics(props.dataSourceId) || []
  } catch {
    metrics.value = []
  }
}

function openCreate() {
  editingId.value = null
  form.value = defaultForm()
  showDialog.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    name: row.name || '',
    businessName: row.businessName || '',
    description: row.description || '',
    metricType: row.metricType || 'basic',
    sourceTable: row.sourceTable || '',
    sourceColumn: row.sourceColumn || '',
    aggregation: row.aggregation || 'SUM',
    formula: row.formula || '',
    unit: row.unit || '',
    filterCondition: row.filterCondition || ''
  }
  showDialog.value = true
}

async function handleSave() {
  if (!form.value.name || !form.value.businessName) {
    ElMessage.warning('请填写名称和业务名称')
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value }
    if (editingId.value) {
      await updateMetric(editingId.value, payload)
      ElMessage.success('指标已更新')
    } else {
      await createMetric(props.dataSourceId, payload)
      ElMessage.success('指标已创建')
    }
    showDialog.value = false
    await loadMetrics()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除指标「${row.businessName}」吗？`,
      '确认删除',
      { type: 'warning' }
    )
    await deleteMetric(row.id)
    ElMessage.success('已删除')
    await loadMetrics()
  } catch {
    // user cancelled or delete failed
  }
}

async function handleResolveSql(row) {
  try {
    resolvedSql.value = await resolveMetricSql(row.id)
    showSqlDialog.value = true
  } catch {
    resolvedSql.value = null
    showSqlDialog.value = true
  }
}

watch(() => props.dataSourceId, () => loadMetrics(), { immediate: true })
</script>

<style scoped>
.metric-config {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.config-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.config-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
  color: var(--text-muted);
  font-size: var(--font-md);
  gap: var(--space-sm);
}

.config-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
  color: var(--text-muted);
  font-size: var(--font-md);
}

.sql-preview {
  padding: var(--space-md);
  background: var(--surface);
  border-radius: var(--radius-md);
  border: 1px solid var(--border);
}

.sql-code {
  font-family: var(--font-family-mono);
  font-size: var(--font-sm);
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  line-height: 1.6;
}

.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid var(--border);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
  display: inline-block;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
