import { ref, computed } from 'vue'
import { fetchAlgorithms, fetchAlgorithmCategories, fetchModelTypes } from '../api/index.js'

const algorithms = ref([])
const modelTypes = ref([])
const categories = ref([])
const loaded = ref(false)
const loadedWithDisabled = ref(false)

function parseJson(val, fallback) {
  if (!val) return fallback
  if (typeof val === 'string') {
    try { return JSON.parse(val) } catch { return fallback }
  }
  return val
}

export function useAlgorithms() {
  async function loadAlgorithms(force = false, includeDisabled = false) {
    if (loaded.value && !force && (!includeDisabled || loadedWithDisabled.value)) return
    try {
      const [algos, cats, types] = await Promise.all([
        fetchAlgorithms(undefined, includeDisabled),
        fetchAlgorithmCategories(),
        fetchModelTypes()
      ])
      algorithms.value = algos || []
      categories.value = cats || []
      modelTypes.value = types || []
      loaded.value = true
      loadedWithDisabled.value = includeDisabled
    } catch (e) {
      console.error('Failed to load algorithm definitions:', e)
    }
  }

  const algorithmGroups = computed(() => {
    const groups = {}
    for (const algo of algorithms.value.filter(a => a.enabled !== 0)) {
      const cat = algo.category || '其他'
      if (!groups[cat]) groups[cat] = { category: cat, algorithms: [] }
      groups[cat].algorithms.push(algo)
    }
    const order = ['集成学习', '树模型', '线性模型', '概率模型', '核方法', '近邻方法', '神经网络', '聚类', '异常检测', '自定义', '其他']
    return Object.values(groups).sort((a, b) => {
      const ai = order.indexOf(a.category)
      const bi = order.indexOf(b.category)
      return (ai < 0 ? order.length : ai) - (bi < 0 ? order.length : bi)
    })
  })

  function getAlgorithmDef(algorithmId) {
    return algorithms.value.find(a => a.algorithmId === algorithmId && a.enabled !== 0) || null
  }

  function getAlgorithmLabel(algorithmId) {
    const def = getAlgorithmDef(algorithmId)
    return def ? def.name : algorithmId
  }

  function getAlgorithmsForModelType(modelType) {
    return algorithms.value.filter(a => {
      if (a.enabled === 0) return false
      const types = parseJson(a.modelTypes, [])
      return types.includes(modelType)
    })
  }

  function getAlgorithmParams(algorithmId) {
    const def = getAlgorithmDef(algorithmId)
    if (!def) return []
    const parsed = parseJson(def.paramsSchema, [])
    // Handle both flat array format [{key, defaultValue, ...}] and JSON Schema object format
    if (Array.isArray(parsed)) return parsed
    if (parsed && typeof parsed === 'object' && parsed.properties) {
      return Object.entries(parsed.properties).map(([key, schema]) => ({
        key,
        type: schema.type === 'integer' ? 'int' : schema.type === 'number' ? 'float' : schema.type,
        defaultValue: schema.default !== undefined ? schema.default : undefined
      }))
    }
    return []
  }

  function getDefaultHyperparams(algorithmId) {
    const params = getAlgorithmParams(algorithmId)
    const result = {}
    for (const p of params) {
      result[p.key] = p.defaultValue !== undefined && p.defaultValue !== null
        ? p.defaultValue
        : (p.type === 'int' ? 100 : p.type === 'float' ? 0.1 : p.options?.[0] || '')
    }
    return result
  }

  function getModelTypeLabel(modelType) {
    const t = modelTypes.value.find(m => m.id === modelType)
    return t ? t.name : modelType
  }

  function modelTypeNames(modelTypesJson) {
    const types = parseJson(modelTypesJson, [])
    return types.map(t => getModelTypeLabel(t)).join('、')
  }

  return {
    algorithms,
    modelTypes,
    categories,
    loaded,
    algorithmGroups,
    loadAlgorithms,
    getAlgorithmDef,
    getAlgorithmLabel,
    getAlgorithmsForModelType,
    getAlgorithmParams,
    getDefaultHyperparams,
    getModelTypeLabel,
    modelTypeNames
  }
}
