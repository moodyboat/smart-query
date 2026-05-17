<template>
  <div class="dimension-config">
    <div class="config-toolbar">
      <el-input
        v-model="searchQuery"
        placeholder="搜索维度名称..."
        size="small"
        clearable
        style="width: 240px"
        prefix-icon="Search"
      />
      <el-button type="primary" size="small" @click="openCreate">新建维度</el-button>
    </div>

    <div v-if="loading" class="config-loading">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="!treeData.length && !flatList.length" class="config-empty">
      <p>暂无维度配置</p>
    </div>

    <el-table
      v-else
      :data="displayList"
      size="small"
      stripe
      border
      style="width: 100%"
      row-key="id"
      default-expand-all
      :tree-props="{ children: 'children', hasChildren: 'hasChildren' }"
    >
      <el-table-column prop="businessName" label="维度名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="dimensionType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="dimTypeTag(row.dimensionType)" size="small" effect="plain">
            {{ dimTypeLabel(row.dimensionType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceTable" label="来源表" min-width="120" show-overflow-tooltip />
      <el-table-column prop="sourceColumn" label="来源列" min-width="100" show-overflow-tooltip />
      <el-table-column prop="dateFormat" label="日期格式" width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="showDialog"
      :title="editingId ? '编辑维度' : '新建维度'"
      width="560px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="英文名称" required>
          <el-input v-model="form.name" placeholder="如: region" />
        </el-form-item>
        <el-form-item label="业务名称" required>
          <el-input v-model="form.businessName" placeholder="如: 区域" />
        </el-form-item>
        <el-form-item label="维度类型" required>
          <el-select v-model="form.dimensionType" placeholder="选择类型" style="width: 100%" :teleported="false">
            <el-option label="普通维度" value="normal" />
            <el-option label="时间维度" value="time" />
            <el-option label="层级维度" value="hierarchical" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源表">
          <el-input v-model="form.sourceTable" placeholder="如: dim_region" />
        </el-form-item>
        <el-form-item label="来源列">
          <el-input v-model="form.sourceColumn" placeholder="如: region_name" />
        </el-form-item>
        <el-form-item label="父级维度">
          <el-select v-model="form.parentDimensionId" placeholder="无父级（顶级维度）" style="width: 100%" :teleported="false" clearable>
            <el-option
              v-for="d in parentOptions"
              :key="d.id"
              :label="d.businessName || d.name"
              :value="d.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dimensionType === 'time'" label="日期格式">
          <el-select v-model="form.dateFormat" placeholder="选择格式" style="width: 100%" :teleported="false" clearable>
            <el-option label="%Y-%m-%d" value="%Y-%m-%d" />
            <el-option label="%Y-%m" value="%Y-%m" />
            <el-option label="%Y" value="%Y" />
            <el-option label="%Y-%m-%d %H:%i:%s" value="%Y-%m-%d %H:%i:%s" />
          </el-select>
        </el-form-item>
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

const { loading, listDimensions, getDimensionTree, createDimension, updateDimension, deleteDimension } = useOntology()

const flatList = ref([])
const treeData = ref([])
const searchQuery = ref('')
const showDialog = ref(false)
const editingId = ref(null)
const saving = ref(false)

const form = ref(defaultForm())

function defaultForm() {
  return {
    name: '',
    businessName: '',
    dimensionType: 'normal',
    sourceTable: '',
    sourceColumn: '',
    parentDimensionId: null,
    dateFormat: ''
  }
}

const displayList = computed(() => {
  if (treeData.value.length > 0) {
    return filterTree(treeData.value, searchQuery.value)
  }
  if (!searchQuery.value) return flatList.value
  const q = searchQuery.value.toLowerCase()
  return flatList.value.filter(d =>
    d.businessName?.toLowerCase().includes(q) ||
    d.name?.toLowerCase().includes(q)
  )
})

function filterTree(nodes, query) {
  if (!query) return nodes
  const q = query.toLowerCase()
  return nodes.reduce((acc, node) => {
    const matches = node.businessName?.toLowerCase().includes(q) || node.name?.toLowerCase().includes(q)
    const childMatches = node.children ? filterTree(node.children, query) : []
    if (matches || childMatches.length > 0) {
      acc.push({ ...node, children: childMatches.length > 0 ? childMatches : node.children })
    }
    return acc
  }, [])
}

const parentOptions = computed(() => {
  return flatList.value.filter(d => d.id !== editingId.value)
})

function dimTypeLabel(type) {
  return { normal: '普通', time: '时间', hierarchical: '层级' }[type] || type
}

function dimTypeTag(type) {
  return { normal: '', time: 'warning', hierarchical: 'success' }[type] || 'info'
}

async function loadDimensions() {
  try {
    const [list, tree] = await Promise.all([
      listDimensions(props.dataSourceId),
      getDimensionTree(props.dataSourceId)
    ])
    flatList.value = list || []
    treeData.value = tree || []
  } catch {
    flatList.value = []
    treeData.value = []
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
    dimensionType: row.dimensionType || 'normal',
    sourceTable: row.sourceTable || '',
    sourceColumn: row.sourceColumn || '',
    parentDimensionId: row.parentDimensionId || null,
    dateFormat: row.dateFormat || ''
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
      await updateDimension(editingId.value, payload)
      ElMessage.success('维度已更新')
    } else {
      await createDimension(props.dataSourceId, payload)
      ElMessage.success('维度已创建')
    }
    showDialog.value = false
    await loadDimensions()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除维度「${row.businessName}」吗？子维度也会被解除关联。`,
      '确认删除',
      { type: 'warning' }
    )
    await deleteDimension(row.id)
    ElMessage.success('已删除')
    await loadDimensions()
  } catch {
    // user cancelled or delete failed
  }
}

watch(() => props.dataSourceId, () => loadDimensions(), { immediate: true })
</script>

<style scoped>
.dimension-config {
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
