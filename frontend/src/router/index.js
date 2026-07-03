import { createRouter, createWebHistory } from 'vue-router'
import { ROUTES } from '../constants.js'
import { useUserStore } from '../stores/user.js'

const routes = [
  {
    path: ROUTES.LOGIN,
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { public: true },
  },
  {
    path: ROUTES.WORKSPACE,
    name: 'Workspace',
    component: () => import('../views/Workspace.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: ROUTES.USER_MANAGEMENT,
    name: 'UserManagement',
    component: () => import('../views/admin/UserManagement.vue'),
    meta: { requiresAuth: true, requiresAdmin: true },
  },
  {
    path: '/',
    redirect: () => {
      const user = useUserStore()
      return user.isLoggedIn ? ROUTES.WORKSPACE : ROUTES.LOGIN
    },
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: ROUTES.WORKSPACE,
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const user = useUserStore()
  // 已登录访问登录页 → 跳工作台
  if (to.meta.public && user.isLoggedIn && to.path === ROUTES.LOGIN) {
    return { path: ROUTES.WORKSPACE }
  }
  // 需鉴权但未登录 → 跳登录页
  if (to.meta.requiresAuth && !user.isLoggedIn) {
    return { path: ROUTES.LOGIN }
  }
  // 需要管理员但当前非管理员 → 跳工作台
  if (to.meta.requiresAdmin && !user.isAdmin) {
    return { path: ROUTES.WORKSPACE }
  }
  return true
})

export default router
