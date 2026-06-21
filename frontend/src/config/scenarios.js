/**
 * 场景化配置 - 从后端 /scenarios 拉取，前端缓存兜底。
 *
 * 设计要点：
 *  - 后端 sq_scenario.ui_config 是 single source of truth（在管理页编辑）
 *  - 这里维护响应式 Map 缓存，登录后调用 preloadScenarios() 预热一次
 *  - 同步 getScenarioConfig(code) 给 ChatPanel 等同步消费者使用
 *  - 缓存未命中时返回 FALLBACK_GENERAL，避免阻塞首屏
 */
import { ref } from 'vue'
import api from '../api'

/** 后端不可达或未加载时的最小兜底（仅 general） */
const FALLBACK_GENERAL = {
  code: 'general',
  name: '通用查询',
  icon: '🔍',
  theme: {
    primary: '#409EFF',
    gradient: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    background: '#f5f7fa',
    headerBg: 'linear-gradient(135deg, #667eea 0%, #764ba2 100%)',
    cardBg: 'rgba(255, 255, 255, 0.9)'
  },
  avatar: { emoji: '🔍', fallbackColor: '#667eea', size: 'large' },
  welcome: {
    title: '欢迎使用智能问数',
    subtitle: '不只是查询 — 我可以帮你做完整的数据分析',
    description: '我是你的智能数据分析助手，可以帮助你查询数据库、生成图表、分析数据。'
  },
  capabilities: [
    { icon: 'SQL', iconColor: '#E6A23C', title: '智能查询', description: '用自然语言查询数据库，自动生成 SQL' }
  ],
  examples: []
}

/** 响应式缓存：code -> 场景配置（含 UI） */
const scenariosCache = ref(new Map())
const loaded = ref(false)

/**
 * 把后端 ScenarioDTO 转成前端使用的场景配置结构
 */
function transformBackendScenario(s) {
  const ui = s.uiConfig || {}
  return {
    code: s.code,
    name: s.name,
    icon: ui.avatar?.emoji || s.icon || '🎯',
    theme: ui.theme || {},
    avatar: ui.avatar || { emoji: s.icon || '🎯' },
    welcome: ui.welcome || { title: s.name },
    capabilities: Array.isArray(ui.capabilities) ? ui.capabilities : [],
    examples: Array.isArray(ui.examples) ? ui.examples : []
  }
}

/**
 * 预热场景缓存：登录后调用一次（后端会按当前用户角色过滤）。
 * 重复调用安全（loaded 守卫）。
 */
export async function preloadScenarios() {
  if (loaded.value) return
  try {
    const response = await api.get('/scenarios')
    if (response.data.code === 200) {
      const scenarios = response.data.data || []
      const map = new Map()
      for (const s of scenarios) {
        map.set(s.code, transformBackendScenario(s))
      }
      scenariosCache.value = map
      loaded.value = true
    }
  } catch (e) {
    // 静默失败：getScenarioConfig 会回退到 FALLBACK_GENERAL
    console.warn('[scenarios] preload failed, using fallback', e?.message || e)
  }
}

/**
 * 同步获取场景配置（ChatPanel 等同步消费者使用）。
 * 缓存未命中或后端不可达时回退到 general，再回退到硬编码兜底。
 */
export function getScenarioConfig(scenarioCode) {
  const cache = scenariosCache.value
  return cache.get(scenarioCode) || cache.get('general') || FALLBACK_GENERAL
}

/**
 * 获取所有已加载的场景列表
 */
export function getAllScenarios() {
  return Array.from(scenariosCache.value.values())
}

/**
 * 缓存是否已加载完成
 */
export function isScenarioLoaded() {
  return loaded.value
}

/**
 * 重置缓存（登出时调用，避免账号切换后看到上一账号的场景）
 */
export function resetScenarioCache() {
  scenariosCache.value = new Map()
  loaded.value = false
}
