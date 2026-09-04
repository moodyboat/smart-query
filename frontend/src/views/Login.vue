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
  position: relative;
  width: 100%;
  min-height: 100vh;
  display: flex;
  overflow: hidden;
  background: #eef2f6;
}
.login-left {
  position: relative;
  z-index: 1;
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(48px, 7vw, 100px);
  color: var(--text-primary);
}
.brand .el-icon {
  width: 58px;
  height: 58px;
  margin-bottom: 24px;
  border-radius: 10px;
  color: white;
  background: #2468f2;
}
.brand h1 { margin: 0 0 8px; font-size: clamp(38px, 4vw, 56px); font-weight: 660; letter-spacing: -.05em; }
.brand-sub { margin: 0; color: #737378; font-size: 16px; }
.brand-points {
  width: min(100%, 540px);
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin: 48px 0 0;
  padding: 0;
  list-style: none;
}
.brand-points li {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 13px;
  border: 1px solid #e4e8ef;
  border-radius: 9px;
  color: #5f5f64;
  background: #fff;
  font-size: 11px;
}
.brand-points li .el-icon { flex-shrink: 0; color: #0071e3; font-size: 16px; }
.login-right {
  position: relative;
  z-index: 1;
  width: min(480px, 42vw);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 30px;
}
.login-card {
  width: min(100%, 390px);
  overflow: hidden;
  border: 1px solid #e4e8ef;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 4px 16px rgba(31,35,41,.05);
}
.login-card :deep(.el-card__body) { padding: 34px; }
.login-title { margin: 0 0 6px; color: #1d1d1f; font-size: 27px; font-weight: 650; letter-spacing: -.035em; }
.login-tip { margin: 0 0 25px; color: #86868b; font-size: 12px; }
.login-card :deep(.el-input__wrapper) {
  min-height: 47px;
  padding: 4px 13px;
  border-radius: 8px !important;
  background: #fff;
  box-shadow: 0 0 0 1px rgba(60,60,67,.11) inset;
}
.login-btn {
  width: 100%;
  min-height: 45px;
  border: 0;
  border-radius: 8px;
  color: white;
  background: #2468f2;
  font-weight: 620;
  letter-spacing: .05em;
}
.login-btn:hover { background: #1f5fdc; }
.login-hint {
  margin-top: 10px;
  padding: 9px;
  border-radius: 10px;
  color: #8e8e93;
  background: rgba(118,118,128,.06);
  font-size: 9px;
  line-height: 1.55;
  text-align: center;
}
@media (max-width: 820px) {
  .login-left { display: none; }
  .login-right { width: 100%; padding: 20px; }
  .login-card :deep(.el-card__body) { padding: 28px; }
}
</style>
