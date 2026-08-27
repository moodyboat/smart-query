<template>
  <el-dialog
    :model-value="visible"
    title="算法模板库"
    width="920px"
    destroy-on-close
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="library-layout">
      <aside class="algorithm-list">
        <div class="library-actions">
          <el-input v-model="keyword" clearable placeholder="搜索算法" size="small" />
          <el-button v-if="isAdmin" type="primary" size="small" @click="startCreate">新增模板</el-button>
        </div>
        <el-scrollbar height="560px">
          <div v-for="group in filteredGroups" :key="group.category" class="library-group">
            <div class="group-title">{{ group.category }}</div>
            <button
              v-for="algorithm in group.algorithms"
              :key="algorithm.id"
              class="algorithm-item"
              :class="{ active: selected?.id === algorithm.id }"
              @click="selectAlgorithm(algorithm)"
            >
              <span>{{ algorithm.icon || '🤖' }}</span>
              <span class="algorithm-item-name">{{ algorithm.name }}</span>
              <el-tag v-if="algorithm.isBuiltin" size="small" type="info">内置</el-tag>
            </button>
          </div>
        </el-scrollbar>
      </aside>

      <main class="algorithm-detail">
        <el-empty v-if="!selected && !editing" description="请选择一个算法查看实现" />

        <el-form v-else-if="editing" :model="form" label-width="96px" size="small">
          <el-alert type="warning" :closable="false" style="margin-bottom: 14px">
            模板只能导入依赖并构造 sklearn 兼容的 clf；不要执行 fit、读取文件或访问网络。
          </el-alert>
          <div class="form-grid">
            <el-form-item label="算法标识" required>
              <el-input v-model="form.algorithmId" :disabled="!!form.id" placeholder="如 my_classifier" />
            </el-form-item>
            <el-form-item label="显示名称" required><el-input v-model="form.name" /></el-form-item>
            <el-form-item label="图标"><el-input v-model="form.icon" maxlength="4" /></el-form-item>
            <el-form-item label="分类" required>
              <el-select v-model="form.category" allow-create filterable default-first-option style="width: 100%">
                <el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="模型类型" required>
            <el-checkbox-group v-model="form.modelTypesList">
              <el-checkbox v-for="type in modelTypes" :key="type.id" :label="type.id">{{ type.name }}</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="说明"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="参数定义" required>
            <el-input v-model="form.paramsSchema" type="textarea" :rows="8" class="code-input" />
            <div class="field-help">JSON 数组；支持 int、float、select、boolean、string 类型及 min/max/step/defaultValue/options。</div>
          </el-form-item>
          <el-form-item label="Python实现" required>
            <el-input v-model="form.pythonCodeTemplate" type="textarea" :rows="11" class="code-input" />
          </el-form-item>
          <div class="detail-actions">
            <el-button @click="cancelEdit">取消</el-button>
            <el-button type="primary" :loading="saving" @click="save">保存模板</el-button>
          </div>
        </el-form>

        <template v-else>
          <div class="detail-header">
            <div>
              <h3>{{ selected.icon || '🤖' }} {{ selected.name }}</h3>
              <div class="algorithm-id">{{ selected.algorithmId }}</div>
            </div>
            <div v-if="isAdmin">
              <el-button v-if="selected.isBuiltin" size="small" type="primary" @click="startClone">复制为可编辑模板</el-button>
              <template v-else>
                <el-button size="small" @click="startEdit">修改</el-button>
                <el-button size="small" type="danger" plain @click="remove">删除</el-button>
              </template>
            </div>
          </div>
          <p class="description">{{ selected.description || '暂无说明' }}</p>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="分类">{{ selected.category || '其他' }}</el-descriptions-item>
            <el-descriptions-item label="类型">{{ modelTypeNames(selected.modelTypes) }}</el-descriptions-item>
          </el-descriptions>
          <h4>训练超参数</h4>
          <el-table :data="parameterRows" size="small" border max-height="210">
            <el-table-column prop="label" label="名称" min-width="120" />
            <el-table-column prop="key" label="参数键" min-width="130" />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column prop="defaultValue" label="默认值" min-width="100" />
            <el-table-column prop="range" label="范围/选项" min-width="150" />
          </el-table>
          <h4>Python 模板实现</h4>
          <pre class="code-view">{{ selected.pythonCodeTemplate }}</pre>
        </template>
      </main>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createAlgorithm, updateAlgorithm, deleteAlgorithm } from '../../api'

const props = defineProps({
  visible: { type: Boolean, default: false },
  algorithms: { type: Array, default: () => [] },
  categories: { type: Array, default: () => [] },
  modelTypes: { type: Array, default: () => [] },
  modelTypeNames: { type: Function, required: true },
  isAdmin: { type: Boolean, default: false },
  initialAlgorithm: { type: Object, default: null },
  initialMode: { type: String, default: 'view' }
})

const emit = defineEmits(['update:visible', 'refresh'])
const keyword = ref('')
const selected = ref(null)
const editing = ref(false)
const saving = ref(false)
const emptyForm = () => ({
  id: null, algorithmId: '', name: '', icon: '⭐', category: '自定义',
  modelTypesList: ['classification'], description: '',
  paramsSchema: '[\n  {"key":"C","label":"正则化强度","type":"float","min":0.01,"max":100,"step":0.1,"defaultValue":1},\n  {"key":"max_iter","label":"最大迭代次数","type":"int","min":10,"max":10000,"step":10,"defaultValue":200}\n]',
  pythonCodeTemplate: 'from sklearn.linear_model import LogisticRegression\n\nclf = LogisticRegression(**params)'
})
const form = reactive(emptyForm())

const categoryOptions = computed(() => [...new Set([...props.categories, '集成学习', '线性模型', '树模型', '核方法', '近邻方法', '聚类', '神经网络', '异常检测', '自定义'])])
const filteredGroups = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const groups = new Map()
  for (const algorithm of props.algorithms) {
    if (q && !`${algorithm.name} ${algorithm.algorithmId} ${algorithm.description || ''}`.toLowerCase().includes(q)) continue
    const category = algorithm.category || '其他'
    if (!groups.has(category)) groups.set(category, [])
    groups.get(category).push(algorithm)
  }
  return [...groups.entries()].map(([category, algorithms]) => ({ category, algorithms }))
})

function parseJson(value, fallback) {
  if (value == null || value === '') return fallback
  if (typeof value !== 'string') return value
  try { return JSON.parse(value) } catch { return fallback }
}

const parameterRows = computed(() => parseJson(selected.value?.paramsSchema, []).map(p => ({
  ...p,
  defaultValue: p.defaultValue == null ? '-' : Array.isArray(p.defaultValue) ? p.defaultValue.join(', ') : String(p.defaultValue),
  range: p.options?.join(', ') || (p.min != null || p.max != null ? `${p.min ?? '-∞'} ～ ${p.max ?? '+∞'}` : '-')
})))

watch(() => props.visible, visible => {
  if (!visible) return
  editing.value = false
  selected.value = props.initialAlgorithm || selected.value || props.algorithms[0] || null
  if (props.initialMode === 'create' && props.isAdmin) startCreate()
  else if (props.initialMode === 'edit' && props.isAdmin && selected.value && !selected.value.isBuiltin) startEdit()
  else if (props.initialMode === 'clone' && props.isAdmin && selected.value?.isBuiltin) startClone()
})
watch(() => props.initialAlgorithm, value => { if (value) selected.value = value })

function selectAlgorithm(algorithm) { selected.value = algorithm; editing.value = false }
function assignForm(value) {
  Object.assign(form, emptyForm(), value, {
    modelTypesList: parseJson(value?.modelTypes, ['classification']),
    paramsSchema: typeof value?.paramsSchema === 'string' ? value.paramsSchema : JSON.stringify(value?.paramsSchema || [], null, 2)
  })
}
function startCreate() { selected.value = null; assignForm(null); editing.value = true }
function startEdit() { assignForm(selected.value); editing.value = true }
function startClone() {
  const source = selected.value
  if (!source) return
  assignForm({
    ...source,
    id: null,
    algorithmId: `${source.algorithmId}_custom`,
    name: `${source.name}（自定义）`,
    category: '自定义'
  })
  editing.value = true
}
function cancelEdit() { editing.value = false; if (!selected.value) selected.value = props.algorithms[0] || null }

async function save() {
  if (!form.algorithmId.trim() || !form.name.trim() || !form.modelTypesList.length) {
    ElMessage.warning('请填写算法标识、名称和模型类型'); return
  }
  try {
    JSON.parse(form.paramsSchema)
  } catch { ElMessage.error('参数定义不是有效 JSON'); return }
  if (!form.pythonCodeTemplate.trim()) { ElMessage.warning('请填写 Python 模板实现'); return }
  saving.value = true
  try {
    const payload = {
      algorithmId: form.algorithmId.trim(), name: form.name.trim(), icon: form.icon,
      category: form.category, modelTypes: JSON.stringify(form.modelTypesList),
      description: form.description, paramsSchema: form.paramsSchema,
      pythonCodeTemplate: form.pythonCodeTemplate
    }
    const saved = form.id ? await updateAlgorithm(form.id, payload) : await createAlgorithm(payload)
    selected.value = saved
    editing.value = false
    emit('refresh', saved.id)
    ElMessage.success('算法模板已保存')
  } catch (error) { ElMessage.error(error.message || '保存失败') }
  finally { saving.value = false }
}

async function remove() {
  try {
    await ElMessageBox.confirm(`确定删除自定义算法“${selected.value.name}”吗？`, '删除算法模板', { type: 'warning' })
    await deleteAlgorithm(selected.value.id)
    const deletedId = selected.value.id
    selected.value = props.algorithms.find(a => a.id !== deletedId) || null
    emit('refresh')
    ElMessage.success('算法模板已删除')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error.message || '删除失败') }
}
</script>

<style scoped>
.library-layout { display: grid; grid-template-columns: 280px 1fr; gap: 18px; min-height: 580px; }
.algorithm-list { border-right: 1px solid var(--border); padding-right: 14px; }
.library-actions { display: flex; gap: 8px; margin-bottom: 12px; }
.group-title { padding: 9px 8px 5px; color: var(--text-muted); font-size: 12px; font-weight: 600; }
.algorithm-item { width: 100%; border: 0; background: transparent; display: flex; align-items: center; gap: 8px; padding: 9px; border-radius: 7px; cursor: pointer; color: var(--text-primary); text-align: left; }
.algorithm-item:hover, .algorithm-item.active { background: var(--hover); }
.algorithm-item.active { color: var(--brand-primary); }
.algorithm-item-name { flex: 1; }
.algorithm-detail { min-width: 0; }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-header h3 { margin: 0 0 4px; }
.algorithm-id { color: var(--text-muted); font-family: monospace; }
.description { line-height: 1.65; color: var(--text-secondary); }
.algorithm-detail h4 { margin: 18px 0 8px; }
.code-view { background: #111827; color: #d1e7dd; padding: 14px; border-radius: 8px; overflow: auto; max-height: 260px; white-space: pre-wrap; line-height: 1.5; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.code-input :deep(textarea) { font-family: Consolas, monospace; line-height: 1.45; }
.field-help { color: var(--text-muted); font-size: 12px; margin-top: 4px; }
.detail-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
