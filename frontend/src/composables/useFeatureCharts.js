import { computed } from 'vue'

export function useFeatureCharts(previewResult) {
  const importanceOption = computed(() => {
    const fi = previewResult.value?.quickImportance || previewResult.value?.featureImportance
    if (!fi || typeof fi !== 'object') return null
    const sorted = Object.entries(fi)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 15)
    if (!sorted.length) return null
    const maxVal = sorted[0][1]
    return {
      title: { text: '特征重要性', left: 'center', textStyle: { fontSize: 13, fontWeight: 600 } },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 100, right: 30, top: 36, bottom: 20 },
      xAxis: { type: 'value', max: maxVal * 100, axisLabel: { formatter: v => v.toFixed(0) + '%' } },
      yAxis: { type: 'category', data: sorted.map(s => s[0]).reverse(), axisLabel: { fontSize: 11 } },
      series: [{
        type: 'bar',
        data: sorted.map(s => (s[1] * 100).toFixed(2)).reverse(),
        itemStyle: {
          color: (params) => {
            const ratio = params.value / (maxVal * 100)
            return ratio > 0.7 ? '#5470c6' : ratio > 0.4 ? '#91cc75' : '#fac858'
          },
          borderRadius: [0, 3, 3, 0]
        },
        barMaxWidth: 16
      }]
    }
  })

  const correlationOption = computed(() => {
    const cm = previewResult.value?.correlationMatrix
    if (!cm || typeof cm !== 'object') return null
    const features = Object.keys(cm)
    if (features.length < 2) return null
    const data = []
    let minVal = 1, maxVal = -1
    for (let i = 0; i < features.length; i++) {
      for (let j = 0; j < features.length; j++) {
        const v = cm[features[i]]?.[features[j]]
        if (v != null) {
          data.push([i, j, v])
          if (v < minVal) minVal = v
          if (v > maxVal) maxVal = v
        }
      }
    }
    return {
      title: { text: '特征相关性矩阵', left: 'center', textStyle: { fontSize: 13, fontWeight: 600 } },
      tooltip: {
        formatter: (p) => `${features[p.data[0]]} vs ${features[p.data[1]]}: ${p.data[2].toFixed(3)}`
      },
      grid: { left: 80, right: 60, top: 36, bottom: 60 },
      xAxis: { type: 'category', data: features, axisLabel: { rotate: 45, fontSize: 10 } },
      yAxis: { type: 'category', data: features, axisLabel: { fontSize: 10 } },
      visualMap: {
        min: Math.min(minVal, -1), max: Math.max(maxVal, 1),
        inRange: { color: ['#313695', '#4575b4', '#74add1', '#abd9e9', '#e0f3f8',
                            '#ffffbf', '#fee090', '#fdae61', '#f46d43', '#d73027', '#a50026'] },
        right: 0, top: 'center', itemHeight: 120, itemWidth: 10
      },
      series: [{
        type: 'heatmap',
        data,
        label: { show: features.length <= 8, fontSize: 9, formatter: p => p.data[2].toFixed(2) },
        emphasis: { itemStyle: { shadowBlur: 5, shadowColor: 'rgba(0,0,0,0.3)' } }
      }]
    }
  })

  const histogramOptions = computed(() => {
    const hists = previewResult.value?.histograms
    if (!hists || typeof hists !== 'object') return []
    return Object.entries(hists).map(([name, data]) => {
      const bins = data.bins || []
      const counts = data.counts || []
      const labels = bins.slice(0, -1).map((b, i) =>
        `${b.toFixed(1)}~${bins[i + 1]?.toFixed(1) || ''}`
      )
      return {
        name,
        option: {
          title: { text: name, left: 'center', textStyle: { fontSize: 12, fontWeight: 500 } },
          tooltip: { trigger: 'axis' },
          grid: { left: 40, right: 10, top: 30, bottom: 24 },
          xAxis: { type: 'category', data: labels, axisLabel: { fontSize: 9, rotate: 30 } },
          yAxis: { type: 'value', axisLabel: { fontSize: 9 } },
          series: [{
            type: 'bar',
            data: counts,
            itemStyle: { color: '#5470c6', borderRadius: [2, 2, 0, 0] },
            barMaxWidth: 24
          }]
        }
      }
    })
  })

  const hasChartData = computed(() =>
    !!(importanceOption.value || correlationOption.value || histogramOptions.value.length)
  )

  return { importanceOption, correlationOption, histogramOptions, hasChartData }
}
