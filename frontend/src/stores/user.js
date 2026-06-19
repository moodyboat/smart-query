import { defineStore } from 'pinia'
import { login as loginApi, fetchCurrentUser, logout as logoutApi } from '../api/index.js'
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
