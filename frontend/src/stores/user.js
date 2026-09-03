import { defineStore } from 'pinia'
import { login as loginApi, fetchCurrentUser, logout as logoutApi } from '../api/index.js'
import { resetScenarioCache } from '../config/scenarios.js'
import { useConversationStore } from './conversation.js'
import { useMiningStore } from './mining.js'
import { AUTH_STORAGE_KEYS, ROUTES } from '../constants.js'

function readUser() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEYS.USER)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(AUTH_STORAGE_KEYS.TOKEN) || '',
    userInfo: readUser(),
  }),
  getters: {
    isLoggedIn: (state) => !!state.token,
    displayName: (state) => state.userInfo?.displayName || state.userInfo?.username || '用户',
    role: (state) => state.userInfo?.role || '',
    isAdmin: (state) => state.userInfo?.role === 'admin',
    canReviewOperators: (state) => ['admin', 'operator_reviewer'].includes(state.userInfo?.role),
  },
  actions: {
    async login(username, password) {
      const data = await loginApi(username, password)
      this.token = data.token
      this.userInfo = data.userInfo
      localStorage.setItem(AUTH_STORAGE_KEYS.TOKEN, data.token)
      localStorage.setItem(AUTH_STORAGE_KEYS.USER, JSON.stringify(data.userInfo))
      return data
    },
    async refreshUser() {
      if (!this.token) return null
      try {
        const user = await fetchCurrentUser()
        this.userInfo = user
        localStorage.setItem(AUTH_STORAGE_KEYS.USER, JSON.stringify(user))
        return user
      } catch {
        this.clear()
        return null
      }
    },
    async logout() {
      try {
        await logoutApi()
      } catch {
        // 忽略登出接口失败（JWT 无状态，前端清 token 即可）
      }
      this.clear()
    },
    clear() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem(AUTH_STORAGE_KEYS.TOKEN)
      localStorage.removeItem(AUTH_STORAGE_KEYS.USER)
      // 重置场景缓存，避免下一个账号看到上一账号的角色授权场景
      resetScenarioCache()
      // 重置其他业务 store，避免下一个账号在公共电脑上看到上一账号的会话/模型列表
      try {
        const conv = useConversationStore()
        conv.reset()
      } catch { /* store 不可用时忽略 */ }
      try {
        const mining = useMiningStore()
        mining.reset()
      } catch { /* store 不可用时忽略 */ }
    },
    redirectToLogin() {
      // 供 axios 拦截器在 401 时调用
      this.clear()
      if (window.location.pathname !== ROUTES.LOGIN) {
        window.location.href = ROUTES.LOGIN
      }
    },
  },
})
