<template>
  <div v-if="view" class="artifact-viewer">
    <div class="viewer-heading">
      <div>
        <div class="viewer-title">{{ title }}</div>
        <div class="viewer-meta">
          {{ kindLabel }} · {{ view.totalRows }} 条结果 · 运行 #{{ view.artifact.runId }}
        </div>
      </div>
      <div class="viewer-actions">
        <el-tag :type="kindTagType" effect="plain">{{ rendererLabel }}</el-tag>
        <el-button v-if="canDownload" size="small" type="primary" :loading="downloading" @click="download">下载文件</el-button>
      </div>
    </div>

    <ComposedOutputView v-if="isDashboard" :view="view" />
    <el-tabs v-else-if="isChart" v-model="chartTab" class="chart-tabs">
      <el-tab-pane :label="chartPageLimited ? '图表（当前页）' : '图表'" name="chart">
        <EChartsRenderer :title="title" :option="chartOption" />
      </el-tab-pane>
      <el-tab-pane label="数据与原始输入" name="data">
        <ResultGrid :view="view" :server-driven="serverDriven" :sort="sort" @sort-change="$emit('sort-change', $event)" />
      </el-tab-pane>
    </el-tabs>
    <ResultGrid v-else :view="view" :excel-style="view.artifact.outputKind === 'EXCEL'"
      :server-driven="serverDriven" :sort="sort" @sort-change="$emit('sort-change', $event)" />
  </div>
  <el-empty v-else description="请选择一个输出结果" />
</template>

<script setup>
import { computed, defineComponent, h, ref } from 'vue'
import { ElMessage, ElTable, ElTableColumn, ElTag } from 'element-plus'
import EChartsRenderer from './EChartsRenderer.vue'
import ComposedOutputView from './ComposedOutputView.vue'
import { downloadOutputArtifact } from '../api/orchestration.js'
import { buildSafeChartOption } from '../utils/outputCharts.js'

const props = defineProps({
  view: { type: Object, default: null },
  serverDriven: { type: Boolean, default: false },
  sort: { type: Object, default: null }
})
defineEmits(['sort-change'])

const chartTab = ref('chart')
const downloading = ref(false)
const isChart = computed(() => props.view?.artifact?.outputKind === 'CHART')
const isDashboard = computed(() => props.view?.artifact?.outputKind === 'DASHBOARD')
const isExport = computed(() => String(props.view?.artifact?.outputKind || '').startsWith('EXPORT_'))
const canDownload = computed(() => isExport.value && Number.isFinite(Number(props.view?.artifact?.id)))
const chartPageLimited = computed(() => props.serverDriven
  && Number(props.view?.totalRows || 0) > Number(props.view?.rows?.length || 0))
const title = computed(() => props.view?.contentSpec?.title || props.view?.contentSpec?.sheetName || '流程输出')
const kindLabel = computed(() => ({
  LEAD: '线索', CHART: '图表', TABLE: '数据表', EXCEL: 'Excel 表格视图',
  DASHBOARD: '组合页面', ARTIFACT: '运行制品', TEMP_RESULT: '临时结果',
  EXPORT_XLSX: 'XLSX 文件', EXPORT_CSV: 'CSV 文件', EXPORT_PDF: 'PDF 文件',
  EXPORT_JSON: 'JSON 文件', EXPORT_PNG: 'PNG 图片'
}[props.view?.artifact?.outputKind] || props.view?.artifact?.outputKind))
const rendererLabel = computed(() => props.view?.summary?.renderer || kindLabel.value)
const kindTagType = computed(() => ({ LEAD: 'danger', CHART: 'success', TABLE: 'info', EXCEL: 'primary',
  DASHBOARD: 'success', ARTIFACT: 'warning', TEMP_RESULT: 'warning', EXPORT_XLSX: 'primary',
  EXPORT_CSV: 'primary', EXPORT_PDF: 'primary', EXPORT_JSON: 'primary', EXPORT_PNG: 'primary'
}[props.view?.artifact?.outputKind] || 'info'))

async function download() {
  downloading.value = true
  try {
    const { blob } = await downloadOutputArtifact(props.view.artifact.id)
    const kind = String(props.view.artifact.outputKind).replace('EXPORT_', '').toLowerCase()
    const fileName = props.view.contentSpec?.fileName || `result.${kind}`
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(error.message || '文件导出失败')
  } finally { downloading.value = false }
}

function specField(raw) {
  return typeof raw === 'string' ? raw : raw?.field
}

function readPath(root, path) {
  if (!path) return undefined
  return String(path).split('.').reduce((value, key) => {
    if (value == null) return undefined
    if (Array.isArray(value) && /^\d+$/.test(key)) return value[Number(key)]
    return value[key]
  }, root)
}

function readColumn(row, field) {
  if (String(field).startsWith('result.') || String(field).startsWith('sources.')) {
    return readPath(row, field)
  }
  return readPath(row.display, field)
}

const chartOption = computed(() => {
  const spec = props.view?.contentSpec || {}
  if (spec.echartsOption) return spec.echartsOption
  const rows = props.view?.rows?.map(row => row.display) || []
  return buildSafeChartOption(spec, rows, props.view?.columns || [])
})

function formatCell(value, format) {
  if (value == null) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  if (format === 'percent' && !Number.isNaN(Number(value))) return `${(Number(value) * 100).toFixed(2)}%`
  return String(value)
}

const ResultGrid = defineComponent({
  name: 'ResultGrid',
  props: { view: { type: Object, required: true }, excelStyle: Boolean,
    serverDriven: Boolean, sort: { type: Object, default: null } },
  emits: ['sort-change'],
  setup(componentProps, { emit }) {
    const sourcePanel = ({ row }) => h('div', { class: 'lineage-panel' }, [
      h('div', { class: 'lineage-section' }, [
        h('div', { class: 'lineage-title' }, `原始输入（${row.sources?.length || 0} 条）`),
        ...(row.sources?.length ? row.sources.map((source, index) => h('div', { class: 'source-card', key: index }, [
          row.sources.length > 1 ? h('div', { class: 'source-index' }, `输入 ${index + 1}`) : null,
          h('div', { class: 'kv-grid' }, Object.entries(source).map(([key, value]) =>
            h('div', { class: 'kv-item', key }, [h('span', { class: 'kv-key' }, key), h('span', { class: 'kv-value' }, formatCell(value))])
          ))
        ])) : [h('span', { class: 'empty-hint' }, '无原始输入快照')])
      ]),
      row.evidence?.length ? h('div', { class: 'lineage-section' }, [
        h('div', { class: 'lineage-title' }, '判断依据'),
        h('div', { class: 'evidence-list' }, row.evidence.map((item, index) => h('div', { class: 'evidence-item', key: index }, [
          h(ElTag, { size: 'small', type: 'warning', effect: 'plain' }, () => item.kind || 'EVIDENCE'),
          h('span', item.name || item.field || '判断依据'),
          item.actualValue != null ? h('strong', formatCell(item.actualValue)) : null,
          item.condition ? h('code', item.condition) : null
        ])))
      ]) : null,
      row.sourceRefs?.length ? h('div', { class: 'source-refs' }, `血缘：${row.sourceRefs.join('、')}`) : null
    ])

    return () => h('div', { class: ['result-grid', componentProps.excelStyle ? 'excel-grid' : ''] }, [
      h(ElTable, { data: componentProps.view.rows, border: true, stripe: !componentProps.excelStyle,
        rowKey: 'rowIndex', height: 'calc(100vh - 260px)', emptyText: '暂无结果',
        defaultSort: componentProps.sort?.field ? { prop: componentProps.sort.field,
          order: componentProps.sort.direction === 'DESC' ? 'descending' : 'ascending' } : undefined,
        onSortChange: event => emit('sort-change', event) }, () => [
        h(ElTableColumn, { type: 'expand', width: 46 }, { default: sourcePanel }),
        h(ElTableColumn, { type: 'index', label: '#', width: 58, fixed: 'left',
          index: index => componentProps.view.rows[index]?.rowIndex + 1 }),
        ...componentProps.view.columns.map(column => h(ElTableColumn, {
          key: column.field, prop: column.field, label: column.title || column.field,
          minWidth: column.width || 140,
          sortable: componentProps.serverDriven
            ? (componentProps.view.queryFields?.some(field => field.field === column.field && field.sortable) ? 'custom' : false)
            : true,
          sortMethod: componentProps.serverDriven ? undefined : ((left, right) => {
            const first = readColumn(left, column.field), second = readColumn(right, column.field)
            if (first == null && second == null) return 0
            if (first == null) return 1
            if (second == null) return -1
            return typeof first === 'number' && typeof second === 'number'
              ? first - second : String(first).localeCompare(String(second), 'zh-CN')
          }),
          showOverflowTooltip: true
        }, { default: ({ row }) => formatCell(readColumn(row, column.field), column.format) }))
      ])
    ])
  }
})
</script>

<style scoped>
.artifact-viewer { min-width: 0; height: 100%; display: flex; flex-direction: column; }
.viewer-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.viewer-actions { display:flex;align-items:center;gap:8px; }
.viewer-title { font-size: var(--font-xl); font-weight: 700; color: var(--text-primary); }
.viewer-meta { margin-top: 4px; color: var(--text-muted); font-size: var(--font-sm); }
.chart-tabs { flex: 1; min-height: 0; }
.result-grid { flex: 1; min-height: 0; }
.excel-grid :deep(.el-table__header th) { background: #e8f1e7; color: #26382a; }
.excel-grid :deep(.el-table__cell) { padding: 5px 0; }
:deep(.lineage-panel) { padding: 14px 20px 18px; background: var(--bg); }
:deep(.lineage-section + .lineage-section) { margin-top: 14px; }
:deep(.lineage-title) { font-size: var(--font-sm); font-weight: 700; margin-bottom: 8px; color: var(--text-secondary); }
:deep(.source-card) { padding: 10px 12px; border: 1px solid var(--border-light); border-radius: var(--radius-md); background: var(--surface); margin-bottom: 8px; }
:deep(.source-index) { color: var(--brand-primary); font-weight: 600; font-size: var(--font-xs); margin-bottom: 7px; }
:deep(.kv-grid) { display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 7px 14px; }
:deep(.kv-item) { display: flex; gap: 8px; min-width: 0; font-size: var(--font-sm); }
:deep(.kv-key) { color: var(--text-muted); flex-shrink: 0; }
:deep(.kv-value) { color: var(--text-primary); word-break: break-all; }
:deep(.evidence-list) { display: flex; flex-direction: column; gap: 7px; }
:deep(.evidence-item) { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; font-size: var(--font-sm); }
:deep(.evidence-item code) { padding: 2px 5px; border-radius: 4px; background: var(--border-lighter); }
:deep(.source-refs) { margin-top: 12px; color: var(--text-muted); font-size: var(--font-xs); }
:deep(.empty-hint) { color: var(--text-muted); font-size: var(--font-sm); }
</style>
