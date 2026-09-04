export function readOutputPath(root, path) {
  if (!path) return undefined
  return String(path).split('.').reduce((value, key) => {
    if (value == null) return undefined
    if (Array.isArray(value) && /^\d+$/.test(key)) return value[Number(key)]
    return value[key]
  }, root)
}

function field(raw) { return typeof raw === 'string' ? raw : raw?.field }
function number(value) { return Number.isFinite(Number(value)) ? Number(value) : 0 }

/** Builds ECharts options only from the platform's declarative field vocabulary. */
export function buildSafeChartOption(spec = {}, rows = [], columns = []) {
  const dimensions = (Array.isArray(spec.dimensions) ? spec.dimensions : [spec.dimension]).filter(Boolean).map(field)
  const rawMeasures = (Array.isArray(spec.measures) ? spec.measures : [spec.measure]).filter(Boolean)
  const measures = rawMeasures.map(field)
  const dimension = dimensions[0] || columns[0]?.field
  const valueFields = measures.length ? measures : columns.slice(1).map(column => column.field)
  const type = String(spec.chartType || 'bar').toLowerCase()
  const base = { tooltip: { trigger: 'item' }, animation: false }
  const chartRows = aggregateRows(rows, dimensions, rawMeasures)

  if (type === 'pie' || type === 'treemap' || type === 'sunburst') {
    const data = chartRows.map(row => ({ name: readOutputPath(row, dimension),
      value: number(readOutputPath(row, valueFields[0])) }))
    if (type === 'pie') return { ...base, legend: { type: 'scroll', bottom: 0 },
      series: [{ type: 'pie', radius: ['35%', '68%'], data }] }
    return { ...base, series: [{ type, radius: type === 'sunburst' ? ['15%', '82%'] : undefined, data }] }
  }
  if (type === 'sankey' || type === 'graph') {
    const sourceField = dimensions[0], targetField = dimensions[1]
    const links = chartRows.map(row => ({ source: String(readOutputPath(row, sourceField) ?? ''),
      target: String(readOutputPath(row, targetField) ?? ''), value: number(readOutputPath(row, valueFields[0])) }))
      .filter(link => link.source && link.target)
    const nodes = [...new Set(links.flatMap(link => [link.source, link.target]))].map(name => ({ name }))
    return type === 'sankey'
      ? { ...base, series: [{ type: 'sankey', data: nodes, links, emphasis: { focus: 'adjacency' } }] }
      : { ...base, series: [{ type: 'graph', layout: 'force', roam: true, data: nodes,
          links, label: { show: true }, force: { repulsion: 180 } }] }
  }
  if (type === 'heatmap') {
    const xs = [...new Set(chartRows.map(row => readOutputPath(row, dimensions[0])))]
    const ys = [...new Set(chartRows.map(row => readOutputPath(row, dimensions[1])))]
    const data = chartRows.map(row => [xs.indexOf(readOutputPath(row, dimensions[0])),
      ys.indexOf(readOutputPath(row, dimensions[1])), number(readOutputPath(row, valueFields[0]))])
    return { ...base, tooltip: { position: 'top' }, xAxis: { type: 'category', data: xs },
      yAxis: { type: 'category', data: ys }, visualMap: { min: 0, max: Math.max(1, ...data.map(item => item[2])),
        calculable: true, orient: 'horizontal', left: 'center', bottom: 0 },
      series: [{ type: 'heatmap', data }] }
  }
  if (type === 'radar') {
    const maxima = valueFields.map(valueField => Math.max(1, ...chartRows.map(row => number(readOutputPath(row, valueField)))))
    return { ...base, legend: { bottom: 0 }, radar: { indicator: valueFields.map((name, index) => ({ name, max: maxima[index] })) },
      series: [{ type: 'radar', data: chartRows.map(row => ({ name: String(readOutputPath(row, dimension) ?? ''),
        value: valueFields.map(valueField => number(readOutputPath(row, valueField))) })) }] }
  }
  if (type === 'map') {
    return { ...base, visualMap: { min: 0, max: Math.max(1, ...chartRows.map(row => number(readOutputPath(row, valueFields[0])))),
      calculable: true }, series: [{ type: 'map', map: String(spec.mapName || 'china'),
      data: chartRows.map(row => ({ name: readOutputPath(row, dimension), value: number(readOutputPath(row, valueFields[0])) })) }] }
  }
  return {
    tooltip: { trigger: 'axis' }, legend: { top: 6 }, grid: { left: 48, right: 24, top: 48, bottom: 48 },
    xAxis: { type: 'category', data: chartRows.map(row => readOutputPath(row, dimension)) },
    yAxis: { type: 'value' },
    series: valueFields.map(valueField => ({ name: valueField,
      type: ['line', 'scatter'].includes(type) ? type : 'bar',
      data: chartRows.map(row => readOutputPath(row, valueField)) }))
  }
}

function aggregateRows(rows, dimensions, measures) {
  const normalized = measures.map(item => ({ field: field(item), aggregation: String(item?.aggregation || 'none').toLowerCase() }))
  if (!normalized.some(item => item.aggregation !== 'none')) return rows
  const groups = new Map()
  for (const row of rows) {
    const key = JSON.stringify(dimensions.map(dimension => readOutputPath(row, dimension)))
    if (!groups.has(key)) groups.set(key, { rows: [], dimensions: dimensions.map(dimension => readOutputPath(row, dimension)) })
    groups.get(key).rows.push(row)
  }
  return [...groups.values()].map(group => {
    const result = {}
    dimensions.forEach((dimension, index) => setPath(result, dimension, group.dimensions[index]))
    normalized.forEach(measure => {
      const values = group.rows.map(row => Number(readOutputPath(row, measure.field))).filter(Number.isFinite)
      let value
      if (measure.aggregation === 'count') value = group.rows.length
      else if (!values.length) value = 0
      else if (measure.aggregation === 'avg') value = values.reduce((a, b) => a + b, 0) / values.length
      else if (measure.aggregation === 'min') value = Math.min(...values)
      else if (measure.aggregation === 'max') value = Math.max(...values)
      else value = values.reduce((a, b) => a + b, 0)
      setPath(result, measure.field, value)
    })
    return result
  })
}

function setPath(root, path, value) {
  const parts = String(path).split('.')
  let current = root
  parts.slice(0, -1).forEach(part => { current[part] ||= {}; current = current[part] })
  current[parts.at(-1)] = value
}
