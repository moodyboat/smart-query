<template>
  <div class="node-params-editor">
    <template v-if="node.type === 'data_source'">
      <el-form label-width="80px" size="small" :disabled="readonly">
        <el-form-item label="数据表">
          <el-input :model-value="node.config?.table" disabled />
        </el-form-item>
        <el-form-item label="筛选条件">
          <el-input :model-value="node.config?.filter || ''" placeholder="如: status = 1" @update:model-value="update('filter', $event)" />
        </el-form-item>
      </el-form>
      <div class="preview-section">
        <el-button size="small" :loading="previewLoading" @click="loadPreview">
          {{ previewRows.length ? '刷新预览' : '预览数据' }}
        </el-button>
        <div v-if="previewRows.length" class="preview-table-wrap">
          <el-table :data="previewRows" size="small" border stripe max-height="240" style="width: 100%">
            <el-table-column v-for="col in previewColumns" :key="col" :prop="col" :label="col" min-width="100" show-overflow-tooltip />
          </el-table>
          <div class="preview-hint">显示前 {{ previewRows.length }} 行</div>
        </div>
      </div>
    </template>

    <template v-if="node.type === 'preprocessing'">
      <el-form label-width="80px" size="small" :disabled="readonly">
        <el-form-item label="全局缺失值">
          <el-select :model-value="node.config?.handleMissing || 'drop'" style="width: 100%" :teleported="false" @update:model-value="update('handleMissing', $event)">
            <el-option label="删除缺失行" value="drop" />
            <el-option label="填充均值" value="fill_mean" />
            <el-option label="填充中位数" value="fill_median" />
            <el-option label="不处理" value="none" />
          </el-select>
        </el-form-item>
        <el-form-item label="编码">
          <el-select :model-value="node.config?.encoding || 'label'" style="width: 100%" :teleported="false" @update:model-value="update('encoding', $event)">
            <el-option label="Label Encoding" value="label" />
            <el-option label="One-Hot Encoding" value="onehot" />
            <el-option label="不编码" value="none" />
          </el-select>
        </el-form-item>
        <el-form-item label="缩放">
          <el-select :model-value="node.config?.scaling || 'none'" style="width: 100%" :teleported="false" @update:model-value="update('scaling', $event)">
            <el-option label="标准化" value="standard" />
            <el-option label="归一化" value="minmax" />
            <el-option label="不缩放" value="none" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="columnList.length" class="column-strategies">
        <div class="column-strategies-header">逐列缺失值策略</div>
        <div v-for="col in columnList" :key="col" class="column-strategy-row">
          <span class="column-name" :title="col">{{ col }}</span>
          <el-select size="small" :model-value="getColumnStrategy(col)" style="flex: 1" :teleported="false" :disabled="readonly"
            @update:model-value="updateColumnStrategy(col, $event)">
            <el-option label="继承全局" value="inherit" />
            <el-option label="删除缺失行" value="drop" />
            <el-option label="填充均值" value="fill_mean" />
            <el-option label="填充中位数" value="fill_median" />
            <el-option label="填充众数" value="fill_mode" />
            <el-option label="不处理" value="none" />
          </el-select>
        </div>
      </div>
    </template>

    <template v-if="node.type === 'fill_missing'">
      <el-form label-width="80px" size="small" :disabled="readonly">
        <el-form-item label="策略">
          <el-select :model-value="node.config?.strategy || 'auto'" style="width: 100%" :teleported="false" @update:model-value="update('strategy', $event)">
            <el-option label="自动" value="auto" />
            <el-option label="均值" value="mean" />
            <el-option label="中位数" value="median" />
            <el-option label="众数" value="mode" />
          </el-select>
        </el-form-item>
      </el-form>
    </template>

    <template v-if="node.type === 'feature_engineering'">
      <el-form label-width="80px" size="small" :disabled="readonly">
        <el-form-item label="目标列">
          <el-input :model-value="node.config?.targetColumn || '-'" disabled />
        </el-form-item>
        <el-form-item label="特征数">
          <span>{{ featureCount }} 列</span>
        </el-form-item>
        <el-form-item v-if="node.config?.transforms?.length" label="变换">
          <el-tag v-for="(tf, i) in node.config.transforms" :key="i" size="small" style="margin: 2px">{{ transformLabel(tf) }}</el-tag>
        </el-form-item>
      </el-form>
    </template>

    <template v-if="node.type === NODE_TYPES.TRAINING">
      <el-form label-width="80px" size="small">
        <el-form-item label="算法">
          <span>{{ algorithmLabel(node.config?.algorithm) || node.config?.algorithm }}</span>
        </el-form-item>
        <el-form-item label="模型类型">
          <span>{{ modelTypeLabel(node.config?.modelType) }}</span>
        </el-form-item>
        <div v-for="p in params" :key="p.key" class="param-row">
          <label class="param-label">{{ p.label }}</label>
          <el-input-number v-if="p.type === 'int' && !readonly" :model-value="hyperparams[p.key]"
            :min="p.min" :max="p.max" :step="p.step || 1" size="small" style="width: 100%"
            @update:model-value="updateParam(p.key, $event)" />
          <el-input-number v-else-if="p.type === 'float' && !readonly" :model-value="hyperparams[p.key]"
            :min="p.min" :max="p.max" :step="p.step || 0.1" :precision="2" size="small" style="width: 100%"
            @update:model-value="updateParam(p.key, $event)" />
          <el-select v-else-if="p.type === 'select' && !readonly" :model-value="hyperparams[p.key]"
            size="small" style="width: 100%" :teleported="false"
            @update:model-value="updateParam(p.key, $event)">
            <el-option v-for="o in p.options" :key="o" :label="o" :value="o" />
          </el-select>
          <span v-if="readonly">{{ hyperparams[p.key] ?? '-' }}</span>
        </div>
        <div v-if="node.config?.metrics" class="node-metrics">
          <div class="node-metrics-title">训练指标</div>
          <div v-for="(val, key) in node.config.metrics" :key="key" class="node-metric-chip">
            {{ formatMetricName(key) }}: {{ typeof val === 'number' ? (val < 10 ? val.toFixed(4) : (val * 100).toFixed(1) + '%') : val }}
          </div>
        </div>
      </el-form>
    </template>

    <template v-if="node.type === 'evaluation'">
      <el-form label-width="80px" size="small">
        <el-form-item label="验证模式">
          <el-select :model-value="node.config?.validationMode || 'train_test'" style="width: 100%" :teleported="false" :disabled="readonly" @update:model-value="update('validationMode', $event)">
            <el-option label="训练/测试分割" value="train_test" />
            <el-option label="交叉验证" value="cv" />
            <el-option label="独立样本外验证" value="oos" />
            <el-option label="滚动时间外验证" value="temporal" />
          </el-select>
        </el-form-item>
        <el-form-item label="测试比例">
          <el-slider :model-value="node.config?.testSize || 20" :min="10" :max="40" :step="5" :disabled="readonly"
            :format-tooltip="v => v + '%'" @update:model-value="update('testSize', $event)" />
        </el-form-item>
        <el-form-item label="CV折数">
          <el-select :model-value="node.config?.cvFold || 0" style="width: 100%" :teleported="false" :disabled="readonly" @update:model-value="update('cvFold', $event)">
            <el-option label="不使用" :value="0" />
            <el-option v-for="n in [3, 5, 10]" :key="n" :label="`${n}-Fold`" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="实体隔离列">
          <el-select :model-value="node.config?.groupColumns || []" multiple filterable allow-create default-first-option
            placeholder="企业ID/客户ID/合同ID" style="width:100%" :teleported="false" :disabled="readonly"
            @update:model-value="update('groupColumns', $event)" />
        </el-form-item>
        <el-form-item v-if="node.config?.validationMode === 'temporal'" label="时间列">
          <el-input :model-value="node.config?.temporalColumn || ''" :disabled="readonly"
            placeholder="created_at" @update:model-value="update('temporalColumn', $event)" />
        </el-form-item>
        <template v-if="node.config?.validationMode === 'oos'">
          <el-form-item label="OOS表">
            <el-input :model-value="node.config?.oosTable || ''" :disabled="readonly"
              placeholder="独立锁定样本表" @update:model-value="update('oosTable', $event)" />
          </el-form-item>
          <el-form-item label="OOS条件">
            <el-input :model-value="node.config?.oosFilter || ''" :disabled="readonly"
              placeholder="可选快照条件" @update:model-value="update('oosFilter', $event)" />
          </el-form-item>
        </template>
        <el-form-item label="风险正类">
          <el-input :model-value="node.config?.positiveClass || ''" :disabled="readonly"
            placeholder="例如 1 / risk" @update:model-value="update('positiveClass', $event)" />
        </el-form-item>
        <el-form-item label="概率校准">
          <el-select :model-value="node.config?.calibrationMethod || 'none'" style="width:100%"
            :teleported="false" :disabled="readonly" @update:model-value="update('calibrationMethod', $event)">
            <el-option label="不校准" value="none" />
            <el-option label="Platt (sigmoid)" value="sigmoid" />
            <el-option label="Isotonic" value="isotonic" />
          </el-select>
        </el-form-item>
        <el-form-item label="阈值策略">
          <el-select :model-value="node.config?.thresholdPolicy?.mode || 'default'" style="width:100%"
            :teleported="false" :disabled="readonly" @update:model-value="updateThreshold('mode', $event)">
            <el-option label="默认0.5" value="default" />
            <el-option label="最大F1" value="max_f1" />
            <el-option label="最低召回率" value="min_recall" />
            <el-option label="最小业务成本" value="min_cost" />
            <el-option label="固定阈值" value="fixed" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="node.config?.thresholdPolicy?.mode === 'min_recall'" label="目标召回">
          <el-input-number :model-value="node.config?.thresholdPolicy?.targetRecall ?? 0.8" :min="0" :max="1"
            :step="0.05" :disabled="readonly" @update:model-value="updateThreshold('targetRecall', $event)" />
        </el-form-item>
      </el-form>
    </template>

    <template v-if="node.type === 'output'">
      <el-form label-width="80px" size="small">
        <el-form-item label="输出表">
          <el-input :model-value="node.config?.table || ''" placeholder="结果表名" :disabled="readonly" @update:model-value="update('table', $event)" />
        </el-form-item>
        <el-form-item label="写入模式">
          <el-select :model-value="node.config?.mode || 'append'" style="width: 100%" :teleported="false" :disabled="readonly" @update:model-value="update('mode', $event)">
            <el-option label="追加" value="append" />
            <el-option label="替换" value="replace" />
          </el-select>
        </el-form-item>
        <el-form-item label="自动建表">
          <el-switch :model-value="node.config?.autoCreate ?? true" :disabled="readonly" @update:model-value="update('autoCreate', $event)" />
        </el-form-item>
      </el-form>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useAlgorithms } from '../composables/useAlgorithms.js'
import { METRIC_NAMES } from '../api'
import { fetchTablePreview, fetchTableColumns } from '../api'
import { NODE_TYPES } from '../constants'

const props = defineProps({
  node: { type: Object, required: true },
  model: { type: Object, default: null },
  readonly: { type: Boolean, default: false }
})

const emit = defineEmits(['update'])

const { getAlgorithmLabel, getAlgorithmParams, getModelTypeLabel } = useAlgorithms()

const algorithmLabel = getAlgorithmLabel
const modelTypeLabel = getModelTypeLabel

const hyperparams = computed(() => props.node.config?.hyperparams || {})

const params = computed(() => {
  const algo = props.node.config?.algorithm
  if (!algo) return []
  return getAlgorithmParams(algo)
})

const featureCount = computed(() => {
  const fc = props.node.config?.featureColumns
  if (!fc) return 0
  try {
    return Array.isArray(fc) ? fc.length : JSON.parse(fc).length
  } catch { return 0 }
})

// Data preview state
const previewLoading = ref(false)
const previewRows = ref([])
const previewColumns = ref([])

// Column list for per-column strategies
const columnList = ref([])
const columnTypes = ref({})

async function loadPreview() {
  const tableName = props.node.config?.table
  const dsId = props.model?.dataSourceId
  if (!tableName || !dsId) return
  previewLoading.value = true
  try {
    const result = await fetchTablePreview(dsId, tableName, 20)
    previewColumns.value = result.columns || []
    previewRows.value = result.rows || []
  } catch {
    previewRows.value = []
    previewColumns.value = []
  } finally {
    previewLoading.value = false
  }
}

async function loadColumnsForStrategies() {
  const tableName = findSourceTable()
  const dsId = props.model?.dataSourceId
  if (!tableName || !dsId) return
  try {
    const cols = await fetchTableColumns(dsId, tableName)
    columnList.value = (cols || []).map(c => c.name)
    const types = {}
    ;(cols || []).forEach(c => { types[c.name] = c.type })
    columnTypes.value = types
  } catch {
    columnList.value = []
  }
}

function findSourceTable() {
  if (props.node.type === 'preprocessing' && props.model?.sourceTable) {
    return props.model.sourceTable
  }
  return props.node.config?.table
}

// Initialize columns when preprocessing node is shown
if (props.node.type === 'preprocessing') {
  loadColumnsForStrategies()
}

// Also load preview data when data_source node has a table
if (props.node.type === 'data_source' && props.node.config?.table && props.model?.dataSourceId) {
  // Lazy - only on user click
}

const columnStrategies = computed(() => props.node.config?.columnStrategies || {})

function getColumnStrategy(col) {
  return columnStrategies.value[col] || 'inherit'
}

function updateColumnStrategy(col, value) {
  const current = { ...columnStrategies.value }
  if (value === 'inherit') {
    delete current[col]
  } else {
    current[col] = value
  }
  const config = { ...props.node.config }
  if (Object.keys(current).length) {
    config.columnStrategies = current
  } else {
    delete config.columnStrategies
  }
  emit('update', { nodeId: props.node.id, nodeType: props.node.type, config })
}

function transformLabel(tf) {
  const labels = { log: '对数', polynomial: '多项式', binning: '分箱', target_encode: '目标编码', frequency_encode: '频率编码', date_extract: '日期提取', interaction: '交互项', standardize: '标准化' }
  return labels[tf.type] || tf.type
}

function formatMetricName(key) {
  return METRIC_NAMES[key] || key
}

function update(key, value) {
  const config = { ...props.node.config, [key]: value }
  emit('update', { nodeId: props.node.id, nodeType: props.node.type, config })
}

function updateThreshold(key, value) {
  const thresholdPolicy = { ...(props.node.config?.thresholdPolicy || {}), [key]: value }
  update('thresholdPolicy', thresholdPolicy)
}

function updateParam(key, value) {
  const hp = { ...hyperparams.value, [key]: value }
  const config = { ...props.node.config, hyperparams: hp }
  emit('update', { nodeId: props.node.id, nodeType: props.node.type, config })
}
</script>

<style scoped>
.node-params-editor { padding: 4px 0; }
.param-row { margin-bottom: 8px; }
.param-label { display: block; font-size: var(--font-md); color: var(--text-secondary); margin-bottom: 4px; }
.node-metrics { margin-top: 12px; padding-top: 8px; border-top: 1px solid var(--border); }
.node-metrics-title { font-size: var(--font-md); font-weight: 500; margin-bottom: 6px; }
.node-metric-chip { display: inline-block; font-size: var(--font-sm); padding: 2px 8px; margin: 2px; border-radius: var(--radius-sm); background: var(--el-color-success-light-9); color: var(--el-color-success); }

.preview-section { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); }
.preview-table-wrap { margin-top: 8px; }
.preview-hint { font-size: var(--font-sm); color: var(--text-secondary); margin-top: 4px; text-align: right; }

.column-strategies { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); }
.column-strategies-header { font-size: var(--font-md); font-weight: 500; margin-bottom: 8px; color: var(--text-secondary); }
.column-strategy-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.column-name { flex-shrink: 0; width: 100px; font-size: var(--font-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-regular); }
</style>
