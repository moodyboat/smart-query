<template>
  <div class="data-table-wrapper" v-if="rows.length">
    <div class="table-toolbar">
      <span class="row-count">共 {{ rows.length }} 行</span>
      <button v-if="rows.length > pageSize" class="toggle-btn" @click="expanded = !expanded">
        {{ expanded ? '收起' : `展开全部 (${rows.length} 行)` }}
      </button>
      <button class="copy-btn" @click="copyAsCSV">{{ csvCopied ? '已复制' : '复制 CSV' }}</button>
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
const csvCopied = ref(false)

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
  const text = [header, ...body].join('\n')
  if (navigator.clipboard) {
    navigator.clipboard.writeText(text).then(() => {
      csvCopied.value = true
      setTimeout(() => { csvCopied.value = false }, 2000)
    }).catch(() => {
      const ta = document.createElement('textarea')
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0'
      document.body.appendChild(ta); ta.select()
      try { document.execCommand('copy'); csvCopied.value = true; setTimeout(() => { csvCopied.value = false }, 2000) } catch { /* copy failed */ }
      document.body.removeChild(ta)
    })
  } else {
    const ta = document.createElement('textarea')
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0'
    document.body.appendChild(ta); ta.select()
    try { document.execCommand('copy'); csvCopied.value = true; setTimeout(() => { csvCopied.value = false }, 2000) } catch { /* copy failed */ }
    document.body.removeChild(ta)
  }
}
</script>

<style scoped>
.data-table-wrapper {
  margin-top: var(--space-sm);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
  font-size: var(--font-sm);
}
.table-toolbar {
  display: flex; align-items: center; gap: var(--space-sm);
  padding: var(--space-xs) var(--space-sm); background: var(--border-lighter); border-bottom: 1px solid var(--border);
}
.row-count { color: var(--text-muted); }
.toggle-btn, .copy-btn {
  padding: 2px var(--space-sm); font-size: var(--font-xs);
  background: transparent; color: var(--primary); border: 1px solid var(--primary-light);
  border-radius: var(--radius-sm); cursor: pointer;
}
.toggle-btn:hover, .copy-btn:hover { background: var(--primary-light); }
.table-scroll { overflow-x: auto; max-height: 400px; overflow-y: auto; }
.data-table {
  width: 100%; border-collapse: collapse; white-space: nowrap;
}
.data-table th {
  position: sticky; top: 0; z-index: 1;
  background: var(--color-info-light); padding: var(--space-xs) var(--space-sm); text-align: left;
  font-weight: 600; border-bottom: 2px solid var(--border); user-select: none;
}
.data-table th.sortable { cursor: pointer; }
.data-table th.sortable:hover { background: var(--border-light); }
.sort-arrow { color: var(--primary); font-size: 10px; }
.data-table td {
  padding: 5px var(--space-sm); border-bottom: 1px solid var(--border-light);
  max-width: 300px; overflow: hidden; text-overflow: ellipsis;
}
.row-num { color: var(--border); width: 30px; text-align: right; }
.data-table tr:hover td { background: var(--primary-light); }
.table-footer {
  padding: var(--space-xs) var(--space-sm); text-align: center; font-size: var(--font-xs); color: var(--text-muted);
  border-top: 1px solid var(--border-light); background: var(--border-lighter);
}
</style>
