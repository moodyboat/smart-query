<template>
  <div class="composed-view">
    <div v-if="filterWidgets.length" class="composed-filters">
      <div v-for="widget in filterWidgets" :key="widget.id" class="filter-control">
        <span>{{ widget.title || '筛选' }}</span>
        <el-select v-for="field in widget.fields" :key="field" v-model="filters[field]" clearable
          :placeholder="field" size="small">
          <el-option v-for="value in options(field)" :key="String(value)" :label="String(value)" :value="value" />
        </el-select>
      </div>
    </div>
    <div :class="['widget-grid', spec.layout === 'vertical' ? 'vertical' : '']">
      <article v-for="widget in contentWidgets" :key="widget.id" :class="['widget-card', widget.type]">
        <header><strong>{{ widget.title || widget.id }}</strong><span>{{ widget.type }}</span></header>
        <div v-if="widget.type === 'metric'" class="metric-value">{{ metric(widget) }}</div>
        <template v-else-if="widget.type === 'chart'">
          <EChartsRenderer :title="widget.title" :option="chart(widget)" />
          <details><summary>图表降级表格</summary><DataTable :widget="widget" :rows="filteredRows" /></details>
        </template>
        <DataTable v-else-if="widget.type === 'table'" :widget="widget" :rows="filteredRows" />
        <p v-else class="container-copy">可信布局容器</p>
      </article>
    </div>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, reactive } from 'vue'
import { ElTable, ElTableColumn } from 'element-plus'
import EChartsRenderer from './EChartsRenderer.vue'
import { buildSafeChartOption, readOutputPath } from '../utils/outputCharts.js'

const props = defineProps({ view: { type: Object, required: true } })
const filters = reactive({})
const spec = computed(() => props.view.contentSpec || {})
const widgets = computed(() => Array.isArray(spec.value.widgets) ? spec.value.widgets : [])
const filterWidgets = computed(() => widgets.value.filter(widget => widget.type === 'filter'))
const contentWidgets = computed(() => widgets.value.filter(widget => widget.type !== 'filter'))
const rawRows = computed(() => (props.view.rows || []).map(row => row.display || {}))
const filteredRows = computed(() => rawRows.value.filter(row => Object.entries(filters)
  .every(([field, value]) => value == null || value === '' || readOutputPath(row, field) === value)))

function options(field) { return [...new Set(rawRows.value.map(row => readOutputPath(row, field)).filter(value => value != null))].slice(0, 200) }
function chart(widget) { return buildSafeChartOption(widget, filteredRows.value, widget.columns || props.view.columns || []) }
function metric(widget) {
  const values = filteredRows.value.map(row => Number(readOutputPath(row, widget.field))).filter(Number.isFinite)
  const aggregation = String(widget.aggregation || 'sum').toLowerCase()
  if (aggregation === 'count') return filteredRows.value.length.toLocaleString('zh-CN')
  if (!values.length) return '—'
  if (aggregation === 'avg') return (values.reduce((a, b) => a + b, 0) / values.length).toLocaleString('zh-CN')
  if (aggregation === 'max') return Math.max(...values).toLocaleString('zh-CN')
  if (aggregation === 'min') return Math.min(...values).toLocaleString('zh-CN')
  return values.reduce((a, b) => a + b, 0).toLocaleString('zh-CN')
}

const DataTable = defineComponent({
  props: { widget: { type: Object, required: true }, rows: { type: Array, required: true } },
  setup(componentProps) {
    return () => {
      const columns = componentProps.widget.columns?.length ? componentProps.widget.columns
        : Object.keys(componentProps.rows[0] || {}).filter(key => typeof componentProps.rows[0]?.[key] !== 'object')
          .slice(0, 12).map(field => ({ field, title: field }))
      return h(ElTable, { data: componentProps.rows, border: true, stripe: true, maxHeight: 360 }, () =>
        columns.map(column => h(ElTableColumn, { key: column.field, label: column.title || column.field,
          minWidth: column.width || 120, showOverflowTooltip: true }, {
          default: ({ row }) => String(readOutputPath(row, column.field) ?? '')
        })))
    }
  }
})
</script>

<style scoped>
.composed-view{min-height:0;overflow:auto}.composed-filters{display:flex;flex-wrap:wrap;gap:10px;margin-bottom:12px;padding:10px;border:1px solid #dce7f5;border-radius:10px;background:#f7faff}.filter-control{display:flex;align-items:center;gap:7px}.filter-control>span{color:#58708e;font-size:11px}.filter-control .el-select{width:150px}.widget-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.widget-grid.vertical{grid-template-columns:1fr}.widget-card{min-width:0;padding:13px;border:1px solid #dfe7f1;border-radius:11px;background:white;box-shadow:0 2px 8px rgba(35,73,120,.04)}.widget-card header{display:flex;align-items:center;justify-content:space-between;margin-bottom:9px}.widget-card header strong{color:#17365d;font-size:13px}.widget-card header span{color:#8a98aa;font-size:9px;text-transform:uppercase}.widget-card.metric{min-height:120px}.metric-value{margin-top:14px;color:#1766bd;font-size:30px;font-weight:700}.widget-card.chart{min-height:390px}.widget-card.chart :deep(.echarts-container){height:300px}.widget-card.table,.widget-card.container{grid-column:1/-1}.widget-card details{margin-top:7px;color:#708097;font-size:11px}.widget-card summary{cursor:pointer}.container-copy{color:#8492a6;font-size:12px}@media(max-width:760px){.widget-grid{grid-template-columns:1fr}.filter-control{width:100%;flex-wrap:wrap}.filter-control .el-select{flex:1;min-width:140px}}
</style>
