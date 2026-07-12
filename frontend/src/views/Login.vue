<template>
  <div class="login-wrap">
    <!-- 左侧品牌区 -->
    <div class="login-left">
      <div class="brand">
        <el-icon :size="40"><DataAnalysis /></el-icon>
        <h1>智能问数</h1>
        <p class="brand-sub">企业级 AI 数据分析平台</p>
      </div>
      <ul class="brand-points">
        <li><el-icon><ChatDotRound /></el-icon> 自然语言查询，秒级出结果</li>
        <li><el-icon><TrendCharts /></el-icon> 数据挖掘建模与可视化</li>
        <li><el-icon><Document /></el-icon> 一键生成 Word 分析报告</li>
        <li><el-icon><Lock /></el-icon> 私有化部署，数据不出域</li>
      </ul>
    </div>

    <!-- 右侧表单区 -->
    <div class="login-right">
      <el-card class="login-card" shadow="never">
        <h2 class="login-title">欢迎登录</h2>
        <p class="login-tip">请输入账号密码登录系统</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          size="large"
          @submit.prevent="handleLogin"
        >
          <el-form-item prop="username">
            <el-input
              v-model="form.username"
              placeholder="用户名"
              :prefix-icon="User"
              clearable
              autocomplete="username"
            />
          </el-form-item>
          <el-form-item prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="密码"
              :prefix-icon="Lock"
              show-password
              autocomplete="current-password"
              @keyup.enter="handleLogin"
            />
          </el-form-item>
          <el-form-item>
            <el-button
              type="primary"
              class="login-btn"
              :loading="loading"
              @click="handleLogin"
            >
              登 录
            </el-button>
          </el-form-item>
        </el-form>

        <div v-if="isDev" class="login-hint">
          默认账号 <b>admin</b> / <b>admin123</b>（首次启动自动创建，请尽快修改密码）
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, DataAnalysis, ChatDotRound, TrendCharts, Document } from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user.js'
import { ROUTES } from '../constants.js'

const router = useRouter()
const userStore = useUserStore()

const isDev = import.meta.env.DEV

const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  if (!formRef.value) return
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    router.push(ROUTES.WORKSPACE)
  } catch (e) {
    // 错误提示已由 axios 拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  display: flex;
  flex: 1;
  min-height: 100vh;
  width: 100%;
  background: var(--bg);
}

/* 左侧品牌区 — slate-900 深色 + 双层径向品牌色发光（跟 Sidebar 一致） */
.login-left {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 80px;
  color: var(--on-dark-text);
  background: var(--sidebar-bg);
  overflow: hidden;
}
.login-left::before {
  content: '';
  position: absolute; inset: 0;
  background:
    radial-gradient(circle at 25% 15%, rgba(99, 102, 241, 0.28), transparent 45%),
    radial-gradient(circle at 75% 85%, rgba(37, 99, 235, 0.22), transparent 55%);
  pointer-events: none;
}
.login-left > * { position: relative; z-index: 1; }

.brand .el-icon {
  margin-bottom: var(--space-lg);
  color: var(--brand-primary);
  background: rgba(99, 102, 241, 0.18);
  padding: 10px;
  border-radius: var(--radius-md);
}
.brand h1 {
  font-size: 42px;
  font-weight: 700;
  margin: 0 0 var(--space-sm);
  letter-spacing: -0.02em;
}
.brand-sub {
  font-size: 18px;
  opacity: 0.7;
  margin: 0;
  font-weight: 400;
}
.brand-points {
  list-style: none;
  padding: 0;
  margin: 56px 0 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  font-size: 15px;
  opacity: 0.85;
  padding: var(--space-sm) var(--space-md);
  background: var(--sidebar-bg-soft);
  border: 1px solid var(--sidebar-border);
  border-radius: var(--radius-md);
  transition: all var(--transition-base);
}
.brand-points li:hover {
  background: var(--sidebar-hover);
  border-color: var(--sidebar-brand-border);
  opacity: 1;
}
.brand-points li .el-icon {
  color: var(--brand-primary);
  font-size: 18px;
}

/* 右侧表单区 */
.login-right {
  width: 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface);
  padding: var(--space-xl);
}
.login-card {
  width: 100%;
  max-width: 360px;
  border: none;
  box-shadow: none;
}
.login-card :deep(.el-card__body) {
  padding: 16px 8px;
}
.login-title {
  font-size: 28px;
  font-weight: 700;
  margin: 0 0 var(--space-xs);
  color: var(--text-primary);
  letter-spacing: -0.02em;
}
.login-tip {
  margin: 0 0 var(--space-2xl);
  color: var(--text-secondary);
  font-size: 14px;
}
.login-card :deep(.el-input__wrapper) {
  border-radius: var(--radius-md);
  padding: 4px 14px;
}
.login-btn {
  width: 100%;
  border-radius: var(--radius-md);
  background: var(--brand-gradient);
  border-color: transparent;
  box-shadow: var(--shadow-brand);
  font-weight: 500;
  letter-spacing: 0.05em;
  transition: all var(--transition-base);
}
.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: var(--shadow-lg), var(--shadow-brand);
}
.login-hint {
  margin-top: var(--space-md);
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
  padding: var(--space-sm);
  background: var(--bg);
  border-radius: var(--radius-md);
  line-height: 1.6;
}

@media (max-width: 768px) {
  .login-left { display: none; }
  .login-right { width: 100%; }
}
</style>
