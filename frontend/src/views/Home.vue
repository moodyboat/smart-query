<template>
  <div class="home">
    <!-- 顶栏 -->
    <header class="home-header">
      <div class="logo">
        <el-icon :size="26" color="var(--brand-primary)"><DataAnalysis /></el-icon>
        <span>智能问数</span>
      </div>
      <el-dropdown trigger="click" @command="onUserCommand">
        <span class="user-trigger">
          <el-avatar :size="32" class="user-avatar">{{ avatarText }}</el-avatar>
          <span class="user-name">{{ userStore.displayName }}</span>
          <el-icon><CaretBottom /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item v-if="userStore.isAdmin" command="users">
              <el-icon><UserFilled /></el-icon> 用户管理
            </el-dropdown-item>
            <el-dropdown-item command="workspace">
              <el-icon><ChatDotRound /></el-icon> 进入工作台
            </el-dropdown-item>
            <el-dropdown-item divided command="logout">
              <el-icon><SwitchButton /></el-icon> 退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <main class="home-main">
      <!-- 欢迎横幅 -->
      <section class="welcome">
        <div>
          <h2>{{ greeting }}，{{ userStore.displayName }}</h2>
          <p>欢迎使用智能问数平台，用自然语言获取数据洞察。</p>
        </div>
        <el-button type="primary" size="large" :icon="ChatDotRound" @click="goWorkspace">
          开始问数
        </el-button>
      </section>

      <!-- 统计卡片 -->
      <section class="overview">
        <el-row :gutter="20">
          <el-col v-for="card in statCards" :key="card.key" :xs="12" :sm="6">
            <el-card shadow="hover" class="stat-card" :body-style="{ padding: '20px 24px' }">
              <div class="stat-item">
                <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
                  <el-icon :size="24"><component :is="card.icon" /></el-icon>
                </div>
                <div class="stat-info">
                  <div class="stat-value">{{ card.loading ? '—' : card.value }}</div>
                  <div class="stat-label">{{ card.label }}</div>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </section>

      <!-- 功能模块 -->
      <section class="modules">
        <h3 class="section-title">功能模块</h3>
        <el-row :gutter="20">
          <el-col v-for="m in modules" :key="m.title" :xs="12" :sm="8" :md="6">
            <div class="module-card" @click="goWorkspace">
              <div class="module-icon" :style="{ background: m.bg, color: m.color }">
                <el-icon :size="26"><component :is="m.icon" /></el-icon>
              </div>
              <div class="module-body">
                <div class="module-title">{{ m.title }}</div>
                <div class="module-desc">{{ m.desc }}</div>
              </div>
              <el-icon class="module-arrow"><ArrowRight /></el-icon>
            </div>
          </el-col>
        </el-row>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight, CaretBottom, ChatDotRound, Connection, DataAnalysis,
  Document, SwitchButton, TrendCharts, SetUp, UserFilled,
} from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user.js'
import { fetchConversations, fetchDataSources, fetchMiningModels } from '../api/index.js'
import { ROUTES } from '../constants.js'

const router = useRouter()
const userStore = useUserStore()

const stats = reactive({ conversations: 0, dataSources: 0, models: 0, published: 0 })
const loading = reactive({ conversations: true, dataSources: true, models: true })

const avatarText = computed(() => (userStore.displayName || 'U').charAt(0).toUpperCase())
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '凌晨好'
  if (h < 12) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const statCards = computed(() => [
  { key: 'conv', label: '会话数', value: stats.conversations, loading: loading.conversations, icon: ChatDotRound, color: 'var(--brand-primary)', bg: 'var(--brand-primary-light)' },
  { key: 'ds', label: '数据源', value: stats.dataSources, loading: loading.dataSources, icon: Connection, color: 'var(--color-success)', bg: 'var(--color-success-light)' },
  { key: 'model', label: '挖掘模型', value: stats.models, loading: loading.models, icon: TrendCharts, color: 'var(--color-warning)', bg: 'var(--color-warning-light)' },
  { key: 'pub', label: '已发布模型', value: stats.published, loading: loading.models, icon: SetUp, color: 'var(--color-pink)', bg: 'var(--color-pink-light)' },
])

const modules = [
  { title: '智能问数', desc: '自然语言查询数据库', icon: ChatDotRound, color: 'var(--brand-primary)', bg: 'var(--brand-primary-light)' },
  { title: '数据挖掘', desc: '建模、训练、预测、调度', icon: TrendCharts, color: 'var(--color-success)', bg: 'var(--color-success-light)' },
  { title: '可视化分析', desc: '图表与仪表盘', icon: DataAnalysis, color: 'var(--color-warning)', bg: 'var(--color-warning-light)' },
  { title: 'Word 报告', desc: '一键生成分析报告', icon: Document, color: 'var(--color-pink)', bg: 'var(--color-pink-light)' },
]

function goWorkspace() {
  router.push(ROUTES.WORKSPACE)
}

async function onUserCommand(cmd) {
  if (cmd === 'workspace') {
    goWorkspace()
  } else if (cmd === 'users') {
    router.push(ROUTES.USER_MANAGEMENT)
  } else if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
    } catch {
      return
    }
    await userStore.logout()
    ElMessage.success('已退出登录')
    router.push(ROUTES.LOGIN)
  }
}

async function loadStats() {
  const [conv, ds, models] = await Promise.allSettled([
    fetchConversations(),
    fetchDataSources(),
    fetchMiningModels(),
  ])
  if (conv.status === 'fulfilled') {
    stats.conversations = Array.isArray(conv.value) ? conv.value.length : 0
  }
  loading.conversations = false
  if (ds.status === 'fulfilled') {
    stats.dataSources = Array.isArray(ds.value) ? ds.value.length : 0
  }
  loading.dataSources = false
  if (models.status === 'fulfilled') {
    const list = Array.isArray(models.value) ? models.value : []
    stats.models = list.length
    stats.published = list.filter((m) => m.status === 'published').length
  }
  loading.models = false
}

onMounted(() => {
  userStore.refreshUser().catch(() => {})
  loadStats()
})
</script>

<style scoped>
.home {
  flex: 1;
  width: 100%;
  min-height: 100vh;
  overflow-y: auto;
  background: var(--bg);
}

.home-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}
.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
}
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--text-regular);
}
.user-avatar {
  background: var(--brand-primary);
  color: var(--surface);
  font-weight: 600;
}

.home-main {
  max-width: 1200px;
  margin: 0 auto;
  padding: 32px;
}

.welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 32px;
  margin-bottom: 24px;
  border-radius: 12px;
  background: var(--brand-gradient);
  color: var(--surface);
}
.welcome h2 {
  margin: 0 0 8px;
  font-size: 26px;
}
.welcome p {
  margin: 0;
  opacity: 0.9;
}

.overview {
  margin-bottom: 32px;
}
.stat-card {
  border-radius: 10px;
  margin-bottom: 20px;
}
.stat-item {
  display: flex;
  align-items: center;
  gap: 16px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
}
.stat-label {
  font-size: 14px;
  color: var(--text-secondary);
  margin-top: 4px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 16px;
  color: var(--text-primary);
}
.module-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 20px;
  background: var(--surface);
  border-radius: 10px;
  border: 1px solid var(--border);
  margin-bottom: 20px;
  cursor: pointer;
  transition: all var(--transition-base);
}
.module-card:hover {
  box-shadow: var(--shadow-md);
  transform: translateY(-2px);
}
.module-icon {
  width: 52px;
  height: 52px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.module-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}
.module-desc {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 4px;
}
.module-arrow {
  color: var(--text-muted);
}
</style>
