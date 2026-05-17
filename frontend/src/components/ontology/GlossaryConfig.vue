<template>
  <div class="glossary-config">
    <div class="config-toolbar">
      <el-input
        v-model="searchQuery"
        placeholder="搜索术语、同义词..."
        size="small"
        clearable
        style="width: 240px"
        prefix-icon="Search"
      />
      <el-button type="primary" size="small" @click="openCreate">新建术语</el-button>
    </div>

    <div v-if="loading" class="config-loading">
      <span class="spinner"></span> 加载中...
    </div>

    <div v-else-if="!filteredTerms.length" class="config-empty">
      <p v-if="searchQuery">没有匹配「{{ searchQuery }}」的术语</p>
      <p v-else>暂无业务术语</p>
    </div>

    <el-table
      v-else
      :data="filteredTerms"
      size="small"
      stripe
      border
      style="width: 100%"
    >
      <el-table-column prop="term" label="术语" min-width="100" show-overflow-tooltip />
      <el-table-column label="同义词" min-width="150">
        <template #default="{ row }">
          <template v-if="parseSynonyms(row.synonyms).length">
            <el-tag
              v-for="(syn, i) in parseSynonyms(row.synonyms)"
              :key="i"
              size="small"
              effect="plain"
              style="margin: 2px"
            >
              {{ syn }}
            </el-tag>
          </template>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.category" size="small" type="info" effect="plain">{{ row.category }}</el-tag>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="关联指标" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.mappedMetricName">{{ row.mappedMetricName }}</span>
          <span v-else-if="row.mappedMetricId" class="text-muted">#{{ row.mappedMetricId }}</span>
          <span v-else class="text-muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button size="small" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" link type="danger" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Create/Edit Dialog -->
    <el-dialog
      v-model="showDialog"
      :title="editingId ? '编辑术语' : '新建术语'"
      width="560px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="术语名称" required>
          <el-input v-model="form.term" placeholder="如: GMV" />
        </el-form-item>
        <el-form-item label="同义词">
          <div class="synonym-input">
            <div class="synonym-tags">
              <el-tag
                v-for="(syn, i) in form.synonymsList"
                :key="i"
                closable
                size="small"
                @close="removeSynonym(i)"
              >
                {{ syn }}
              </el-tag>
            </div>
            <el-input
              v-model="newSynonym"
              placeholder="输入同义词后按回车"
              size="small"
              @keyup.enter="addSynonym"
            >
              <template #append>
                <el-button @click="addSynonym" :disabled="!newSynonym.trim()">添加</el-button>
              </template>
            </el-input>
          </div>
        </el-form-item>
        <el-form-item label="定义">
          <el-input v-model="form.definition" type="textarea" :rows="3" placeholder="术语的定义和解释" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" placeholder="选择分类" style="width: 100%" :teleported="false" clearable filterable allow-create>
            <el-option label="财务" value="finance" />
            <el-option label="运营" value="operation" />
            <el-option label="用户" value="user" />
            <el-option label="产品" value="product" />
            <el-option label="技术" value="tech" />
          </el-select>
        </el-form-item>
        <el-form-item label="映射表">
          <el-input v-model="form.mappedTable" placeholder="如: orders" />
        </el-form-item>
        <el-form-item label="映射列">
          <el-input v-model="form.mappedColumn" placeholder="如: gmv_amount" />
        </el-form-item>
        <el-form-item label="映射规则">
          <el-input v-model="form.mappingRule" type="textarea" :rows="2" placeholder="如: SUM(gmv_amount) WHERE status='paid'" />
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

const { loading, listGlossary, createGlossary, updateGlossary, deleteGlossary } = useOntology()

const terms = ref([])
const searchQuery = ref('')
const showDialog = ref(false)
const editingId = ref(null)
const saving = ref(false)
const newSynonym = ref('')

const form = ref(defaultForm())

function defaultForm() {
  return {
    term: '',
    synonymsList: [],
    definition: '',
    category: '',
    mappedTable: '',
    mappedColumn: '',
    mappingRule: ''
  }
}

const filteredTerms = computed(() => {
  if (!searchQuery.value) return terms.value
  const q = searchQuery.value.toLowerCase()
  return terms.value.filter(t =>
    t.term?.toLowerCase().includes(q) ||
    t.synonyms?.toLowerCase().includes(q) ||
    t.category?.toLowerCase().includes(q)
  )
})

function parseSynonyms(val) {
  if (!val) return []
  if (Array.isArray(val)) return val
  if (typeof val === 'string') {
    try {
      const parsed = JSON.parse(val)
      return Array.isArray(parsed) ? parsed : []
    } catch {
      return val.split(',').map(s => s.trim()).filter(Boolean)
    }
  }
  return []
}

function addSynonym() {
  const val = newSynonym.value.trim()
  if (!val) return
  if (!form.value.synonymsList.includes(val)) {
    form.value = {
      ...form.value,
      synonymsList: [...form.value.synonymsList, val]
    }
  }
  newSynonym.value = ''
}

function removeSynonym(index) {
  form.value = {
    ...form.value,
    synonymsList: form.value.synonymsList.filter((_, i) => i !== index)
  }
}

async function loadTerms() {
  try {
    terms.value = await listGlossary(props.dataSourceId) || []
  } catch {
    terms.value = []
  }
}

function openCreate() {
  editingId.value = null
  form.value = defaultForm()
  newSynonym.value = ''
  showDialog.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.value = {
    term: row.term || '',
    synonymsList: parseSynonyms(row.synonyms),
    definition: row.definition || '',
    category: row.category || '',
    mappedTable: row.mappedTable || '',
    mappedColumn: row.mappedColumn || '',
    mappingRule: row.mappingRule || ''
  }
  newSynonym.value = ''
  showDialog.value = true
}

async function handleSave() {
  if (!form.value.term) {
    ElMessage.warning('请填写术语名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      term: form.value.term,
      synonyms: JSON.stringify(form.value.synonymsList),
      definition: form.value.definition,
      category: form.value.category,
      mappedTable: form.value.mappedTable,
      mappedColumn: form.value.mappedColumn,
      mappingRule: form.value.mappingRule
    }
    if (editingId.value) {
      await updateGlossary(editingId.value, payload)
      ElMessage.success('术语已更新')
    } else {
      await createGlossary(props.dataSourceId, payload)
      ElMessage.success('术语已创建')
    }
    showDialog.value = false
    await loadTerms()
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.message || ''))
  } finally {
    saving.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除术语「${row.term}」吗？`,
      '确认删除',
      { type: 'warning' }
    )
    await deleteGlossary(row.id)
    ElMessage.success('已删除')
    await loadTerms()
  } catch {
    // user cancelled or delete failed
  }
}

watch(() => props.dataSourceId, () => loadTerms(), { immediate: true })
</script>

<style scoped>
.glossary-config {
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

.text-muted {
  color: var(--text-muted);
}

.synonym-input {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.synonym-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
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
