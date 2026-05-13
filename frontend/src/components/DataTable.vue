<template>
  <div class="data-table-wrapper" v-if="rows.length">
    <div class="table-toolbar">
      <span class="row-count">共 {{ rows.length }} 行</span>
      <button v-if="rows.length > pageSize" class="toggle-btn" @click="expanded = !expanded">
        {{ expanded ? '收起' : `展开全部 (${rows.length} 行)` }}
      </button>
      <button class="copy-btn" @click="copyAsCSV">复制 CSV</button>
    </div>
    <div class="table-scroll">
      <table class="data-table">
        <thead>
          <tr>
            <th class="row-num">#</th>
            <th v-for="col in columns" :key="col" @click="sortBy(col)" class="sortable">
              {{ col }}
              <span v-if="sortKey === col" class="sort-arrow">{{ sortOrder === 1 ? ' ↑' : ' ↓' }}</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in displayRows" :key="ri">
            <td class="row-num">{{ ri + 1 }}</td>
            <td v-for="col in columns" :key="col">{{ formatCell(row[col]) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="rows.length > pageSize && !expanded" class="table-footer">
      显示前 {{ pageSize }} 行 / 共 {{ rows.length }} 行
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'

const props = defineProps({
  rows: { type: Array, required: true },
  pageSize: { type: Number, default: 50 }
})

const expanded = ref(false)
const sortKey = ref(null)
const sortOrder = ref(1)

const columns = computed(() => {
  if (!props.rows.length) return []
  return Object.keys(props.rows[0])
})

const sortedRows = computed(() => {
  if (!sortKey.value) return props.rows
  const key = sortKey.value
  const order = sortOrder.value
  return [...props.rows].sort((a, b) => {
    const va = a[key], vb = b[key]
    if (va == null) return 1
    if (vb == null) return -1
    if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * order
    return String(va).localeCompare(String(vb)) * order
  })
})

const displayRows = computed(() => {
  if (expanded.value) return sortedRows.value
  return sortedRows.value.slice(0, props.pageSize)
})

function sortBy(col) {
  if (sortKey.value === col) {
    sortOrder.value = sortOrder.value * -1
  } else {
    sortKey.value = col
    sortOrder.value = 1
  }
}

function formatCell(val) {
  if (val == null) return ''
  return String(val)
}

function copyAsCSV() {
  const cols = columns.value
  const header = cols.join(',')
  const body = props.rows.map(r => cols.map(c => {
    const v = r[c] == null ? '' : String(r[c]).replace(/"/g, '""')
    return `"${v}"`
  }).join(','))
  navigator.clipboard.writeText([header, ...body].join('\n'))
}
</script>

<style scoped>
.data-table-wrapper {
  margin-top: 8px;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  overflow: hidden;
  font-size: 12px;
}
.table-toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px; background: #fafafa; border-bottom: 1px solid #e8e8e8;
}
.row-count { color: #999; }
.toggle-btn, .copy-btn {
  padding: 2px 8px; font-size: 11px;
  background: transparent; color: #409eff; border: 1px solid #d9ecff;
  border-radius: 4px; cursor: pointer;
}
.toggle-btn:hover, .copy-btn:hover { background: #f0f7ff; }
.table-scroll { overflow-x: auto; max-height: 400px; overflow-y: auto; }
.data-table {
  width: 100%; border-collapse: collapse; white-space: nowrap;
}
.data-table th {
  position: sticky; top: 0; z-index: 1;
  background: #f5f7fa; padding: 6px 10px; text-align: left;
  font-weight: 600; border-bottom: 2px solid #e0e0e0; user-select: none;
}
.data-table th.sortable { cursor: pointer; }
.data-table th.sortable:hover { background: #ecf0f5; }
.sort-arrow { color: #409eff; font-size: 10px; }
.data-table td {
  padding: 5px 10px; border-bottom: 1px solid #f0f0f0;
  max-width: 300px; overflow: hidden; text-overflow: ellipsis;
}
.row-num { color: #ccc; width: 30px; text-align: right; }
.data-table tr:hover td { background: #f8fbff; }
.table-footer {
  padding: 6px 10px; text-align: center; font-size: 11px; color: #999;
  border-top: 1px solid #f0f0f0; background: #fafafa;
}
</style>
