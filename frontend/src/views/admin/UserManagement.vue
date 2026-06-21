<template>
  <div class="um-page">
    <header class="um-header">
      <div class="um-left">
        <el-button text :icon="ArrowLeft" @click="$router.push(ROUTES.HOME)">返回首页</el-button>
        <h2>用户管理</h2>
      </div>
      <div class="um-actions">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名/显示名"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          @keyup.enter="loadUsers"
          @clear="loadUsers"
        />
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </header>

    <el-card shadow="never" class="um-card">
      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="160" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="row.role === 'admin' ? 'danger' : 'info'" effect="light">
              {{ row.role === 'admin' ? '管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1"
              :disabled="row.id === currentUserId"
              @change="(val) => toggleEnabled(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button text type="warning" :icon="Key" @click="openReset(row)">改密</el-button>
            <el-button
              text
              type="danger"
              :icon="Delete"
              :disabled="row.id === currentUserId"
              @click="handleDelete(row)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建/编辑 对话框 -->
    <el-dialog
      v-model="formVisible"
      :title="editing ? '编辑用户' : '新建用户'"
      width="460px"
      @closed="resetForm"
    >
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="80px">
        <el-form-item v-if="!editing" label="用户名" prop="username">
          <el-input v-model="form.username" autocomplete="off" />
        </el-form-item>
        <el-form-item v-if="!editing" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="普通用户" value="user" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 对话框 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="420px">
      <p class="reset-tip">为 <b>{{ resetTarget?.username }}</b> 设置新密码（至少 6 位）</p>
      <el-input v-model="newPassword" type="password" show-password placeholder="新密码" />
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus, Search, Edit, Key, Delete } from '@element-plus/icons-vue'
import {
  fetchUsers, createUser, updateUser, resetUserPassword, deleteUser,
} from '../../api/index.js'
import { useUserStore } from '../../stores/user.js'
import { ROUTES } from '../../constants.js'

const userStore = useUserStore()
const currentUserId = computed(() => userStore.userInfo?.id)

const users = ref([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')

const formVisible = ref(false)
const editing = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, username: '', password: '', displayName: '', email: '', role: 'user' })
const formRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const resetVisible = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')

async function loadUsers() {
  loading.value = true
  try {
    users.value = await fetchUsers(keyword.value || undefined)
  } catch (e) {
    // axios 拦截器已提示
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, username: '', password: '', displayName: '', email: '', role: 'user' })
  formVisible.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, username: row.username, password: '', displayName: row.displayName || '', email: row.email || '', role: row.role || 'user' })
  formVisible.value = true
}

function resetForm() {
  formRef.value?.resetFields()
}

async function submitForm() {
  if (!editing.value && formRef.value) {
    try { await formRef.value.validate() } catch { return }
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateUser(form.id, {
        displayName: form.displayName,
        email: form.email,
        role: form.role,
      })
      ElMessage.success('已更新')
    } else {
      await createUser({
        username: form.username,
        password: form.password,
        displayName: form.displayName,
        email: form.email,
        role: form.role,
      })
      ElMessage.success('已创建用户')
    }
    formVisible.value = false
    await loadUsers()
  } catch (e) {
    // 已提示
  } finally {
    saving.value = false
  }
}

function openReset(row) {
  resetTarget.value = row
  newPassword.value = ''
  resetVisible.value = true
}

async function submitReset() {
  if (!newPassword.value || newPassword.value.length < 6) {
    ElMessage.error('新密码至少 6 位')
    return
  }
  saving.value = true
  try {
    await resetUserPassword(resetTarget.value.id, newPassword.value)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch (e) {
    // 已提示
  } finally {
    saving.value = false
  }
}

async function toggleEnabled(row, val) {
  try {
    await updateUser(row.id, { enabled: val ? 1 : 0 })
    row.enabled = val ? 1 : 0
    ElMessage.success(val ? '已启用' : '已禁用')
  } catch (e) {
    // 已提示；失败时不改变
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${row.username}」吗？`, '删除用户', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  try {
    await deleteUser(row.id)
    ElMessage.success('已删除')
    await loadUsers()
  } catch (e) {
    // 已提示
  }
}

onMounted(loadUsers)
</script>

<style scoped>
.um-page {
  flex: 1;
  width: 100%;
  min-height: 100vh;
  overflow-y: auto;
  background: var(--bg);
}
.um-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}
.um-left {
  display: flex;
  align-items: center;
  gap: 16px;
}
.um-left h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-primary);
}
.um-actions {
  display: flex;
  gap: 12px;
}
.um-card {
  margin: 24px 32px;
  border-radius: 10px;
}
.reset-tip {
  margin: 0 0 16px;
  color: var(--text-secondary);
}
</style>
