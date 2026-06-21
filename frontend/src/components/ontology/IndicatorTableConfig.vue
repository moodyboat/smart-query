<template>
  <div class="indicator-table-config">
    <div class="config-toolbar">
      <span class="toolbar-label">指标定义表映射配置</span>
      <el-button type="primary" size="small" @click="openCreate">新建配置</el-button>
    </div>

    <div v-if="loading" class="config-loading">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="!configs.length" class="config-empty">
      <p>暂无指标定义表配置</p>
      <p class="empty-hint">创建配置后，系统可以从指定的定义表自动导入指标</p>
    </div>

    <div v-else class="config-list">
      <div v-for="cfg in configs" :key="cfg.id" class="config-card">
        <div class="card-header">
          <span class="card-title">{{ cfg.configName || '未命名配置' }}</span>
          <div class="card-actions">
            <el-button size="small" link type="primary" @click="openEdit(cfg)">编辑</el-button>
            <el-button size="small" link type="danger" @click="handleDelete(cfg)">删除</el-button>
          </div>
        </div>
        <div class="card-body">
          <div class="card-meta">
            <span class="meta-item">
              <span class="meta-label">定义表:</span>
              <span>{{ cfg.indicatorTable || '-' }}</span>
            </span>
          </div>
          <div class="card-columns">
            <div v-for="col in mappedColumns(cfg)" :key="col.key" class="column-mapping">
              <span class="col-label">{{ col.label }}</span>
              <span class="col-value">{{ col.value || '-' }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="showDialog"
      :title="editingId ? '编辑配置' : '新建配置'"
      width="620px"
      destroy-on-close
    >
      <el-form :model="form" label-width="110px" size="default">
        <el-form-item label="配置名称" required>
          <el-input v-model="form.configName" placeholder="如: 销售指标定义" />
        </el-form-item>
        <el-form-item label="指标定义表" required>
          <el-input v-model="form.indicatorTable" placeholder="如: indicator_definitions" />
        </el-form-item>
        <el-divider content-position="left">列映射</el-divider>
        <el-form-item label="名称列">
          <el-input v-model="form.nameColumn" placeholder="指标名称所在列" />
        </el-form-item>
        <el-form-item label="公式列">
          <el-input v-model="form.formulaColumn" placeholder="计算公式所在列" />
        </el-form-item>
        <el-form-item label="分类列">
          <el-input v-model="form.categoryColumn" placeholder="指标分类所在列" />
        </el-form-item>
        <el-form-item label="单位列">
          <el-input v-model="form.unitColumn" placeholder="指标单位所在列" />
        </el-form-item>
        <el-form-item label="描述列">
          <el-input v-model="form.descriptionColumn" placeholder="指标描述所在列" />
        </el-form-item>
        <el-form-item label="明细表列">
          <el-input v-model="form.detailTableColumn" placeholder="明细数据表名所在列" />
        </el-form-item>
        <el-form-item label="筛选条件列">
          <el-input v-model="form.detailFilterColumn" placeholder="筛选条件所在列" />
        </el-form-item>

        <!-- Preview when table is specified -->
        <div v-if="form.indicatorTable && hasAnyColumnMapping" class="preview-section">
          <el-divider content-position="left">映射预览</el-divider>
          <div class="preview-grid">
            <div v-for="col in activeMappings" :key="col.key" class="preview-item">
              <span class="preview-key">{{ col.label }}</span>
              <span class="preview-arrow">&rarr;</span>
              <span class="preview-val">{{ form[col.key] }}</span>
            </div>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          {{ editingId ? '保存' : '创建' }}
        </el-button>
      </template>
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

const { loading, listIndicatorConfigs, createIndicatorConfig, updateIndicatorConfig, deleteIndicatorConfig } = useOntology()

const configs = ref([])
const showDialog = ref(false)
const editingId = ref(null)
const saving = ref(false)

const COLUMN_LABELS = {
  nameColumn: '名称列',
  formulaColumn: '公式列',
  categoryColumn: '分类列',
  unitColumn: '单位列',
  descriptionColumn: '描述列',
  detailTableColumn: '明细表列',
  detailFilterColumn: '筛选条件列'
}

const form = ref(defaultForm())

function defaultForm() {
  return {
    configName: '',
    indicatorTable: '',
    nameColumn: '',
    formulaColumn: '',
    categoryColumn: '',
    unitColumn: '',
    descriptionColumn: '',
    detailTableColumn: '',
    detailFilterColumn: ''
  }
}

function mappedColumns(cfg) {
  return Object.entries(COLUMN_LABELS)
    .map(([key, label]) => ({ key, label, value: cfg[key] }))
    .filter(c => c.value)
}

const hasAnyColumnMapping = computed(() => {
  return Object.keys(COLUMN_LABELS).some(key => form.value[key])
})

const activeMappings = computed(() => {
  return Object.entries(COLUMN_LABELS)
    .map(([key, label]) => ({ key, label }))
    .filter(m => form.value[m.key])
})

async function loadConfigs() {
  try {
    configs.value = await listIndicatorConfigs(props.dataSourceId) || []
  } catch {
    configs.value = []
  }
}

function openCreate() {
  editingId.value = null
  form.value = defaultForm()
  showDialog.value = true
}

function openEdit(cfg) {
  editingId.value = cfg.id
  form.value = {
    configName: cfg.configName || '',
    indicatorTable: cfg.indicatorTable || '',
    nameColumn: cfg.nameColumn || '',
    formulaColumn: cfg.formulaColumn || '',
    categoryColumn: cfg.categoryColumn || '',
    unitColumn: cfg.unitColumn || '',
    descriptionColumn: cfg.descriptionColumn || '',
    detailTableColumn: cfg.detailTableColumn || '',
    detailFilterColumn: cfg.detailFilterColumn || ''
  }
  showDialog.value = true
}

async function handleSave() {
  if (!form.value.configName || !form.value.indicatorTable) {
    ElMessage.warning('请填写配置名称和指标定义表')
    return
  }
  saving.value = true
  try {
    const payload = { ...form.value }
    if (editingId.value) {
      await updateIndicatorConfig(editingId.value, payload)
      ElMessage.success('配置已更新')
    } else {
      await createIndicatorConfig(props.dataSourceId, payload)
      ElMessage.success('配置已创建')
    }
    showDialog.value = false
    await loadConfigs()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleDelete(cfg) {
  try {
    await ElMessageBox.confirm(
      `确定删除配置「${cfg.configName}」吗？`,
      '确认删除',
      { type: 'warning' }
    )
    await deleteIndicatorConfig(cfg.id)
    ElMessage.success('已删除')
    await loadConfigs()
  } catch {
    // user cancelled or delete failed
  }
}

watch(() => props.dataSourceId, () => loadConfigs(), { immediate: true })
</script>

<style scoped>
.indicator-table-config {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.config-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.toolbar-label {
  font-size: var(--font-md);
  color: var(--text-secondary);
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
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
  color: var(--text-muted);
  font-size: var(--font-md);
}

.empty-hint {
  font-size: var(--font-sm);
  margin-top: var(--space-xs);
}

.config-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.config-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  transition: border-color 0.15s;
}

.config-card:hover {
  border-color: var(--primary);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-sm);
}

.card-title {
  font-size: var(--font-lg);
  font-weight: 600;
  color: var(--text-primary);
}

.card-actions {
  display: flex;
  gap: var(--space-xs);
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.card-meta {
  display: flex;
  gap: var(--space-md);
}

.meta-item {
  font-size: var(--font-sm);
  color: var(--text-secondary);
}

.meta-label {
  color: var(--text-muted);
  margin-right: var(--space-xs);
}

.card-columns {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: var(--space-xs) var(--space-md);
}

.column-mapping {
  font-size: var(--font-sm);
  display: flex;
  gap: var(--space-xs);
}

.col-label {
  color: var(--text-muted);
  flex-shrink: 0;
}

.col-value {
  color: var(--text-primary);
  font-weight: 500;
}

.preview-section {
  margin-top: var(--space-sm);
}

.preview-grid {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.preview-item {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--font-sm);
}

.preview-key {
  color: var(--text-secondary);
  min-width: 80px;
}

.preview-arrow {
  color: var(--text-muted);
}

.preview-val {
  color: var(--primary);
  font-weight: 500;
  font-family: var(--font-family-mono);
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
