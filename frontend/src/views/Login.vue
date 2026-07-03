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
      <div class="brand-decor decor-1"></div>
      <div class="brand-decor decor-2"></div>
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

        <div class="login-hint">
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

/* 左侧品牌区 */
.login-left {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 64px;
  color: var(--surface);
  background: var(--brand-gradient);
  overflow: hidden;
}
.brand .el-icon {
  margin-bottom: 16px;
}
.brand h1 {
  font-size: 38px;
  font-weight: 700;
  margin: 0 0 8px;
}
.brand-sub {
  font-size: 18px;
  opacity: 0.9;
  margin: 0;
}
.brand-points {
  list-style: none;
  padding: 0;
  margin: 48px 0 0;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 16px;
  margin-bottom: 18px;
  opacity: 0.95;
}
.brand-decor {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.decor-1 {
  width: 320px;
  height: 320px;
  top: -80px;
  right: -60px;
}
.decor-2 {
  width: 200px;
  height: 200px;
  bottom: -60px;
  left: 10%;
}

/* 右侧表单区 */
.login-right {
  width: 480px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--surface);
}
.login-card {
  width: 100%;
  max-width: 360px;
  border: none;
}
.login-card :deep(.el-card__body) {
  padding: 16px 8px;
}
.login-title {
  font-size: 26px;
  font-weight: 700;
  margin: 0 0 8px;
  color: var(--text-primary);
}
.login-tip {
  margin: 0 0 28px;
  color: var(--text-secondary);
  font-size: 14px;
}
.login-btn {
  width: 100%;
}
.login-hint {
  margin-top: 12px;
  text-align: center;
  font-size: 12px;
  color: var(--text-muted);
}

@media (max-width: 768px) {
  .login-left {
    display: none;
  }
  .login-right {
    width: 100%;
  }
}
</style>
