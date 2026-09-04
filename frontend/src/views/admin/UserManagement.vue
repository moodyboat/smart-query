<template>
  <section class="um-page" :class="{ embedded: props.embedded }">
    <button v-if="props.showSidebarToggle" type="button" class="module-menu-floating" aria-label="打开导航" @click="emit('toggleSidebar')">☰</button>

    <div class="um-body">
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
        <el-button v-if="userStore.canManageRoles" @click="openRoleManager">角色配置</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
        <el-button v-if="!props.embedded" text :icon="ArrowLeft" @click="$router.push(ROUTES.WORKSPACE)">返回工作台</el-button>
      </div>
      <section class="role-overview" aria-label="平台角色概览">
        <article v-for="role in roleCards" :key="role.value" class="role-card" :class="`role-${role.value}`">
          <div class="role-card-head">
            <span class="role-symbol">{{ role.symbol }}</span>
            <el-tag :type="roleTagType(role.value)" effect="light">{{ role.label }}</el-tag>
          </div>
          <strong>{{ role.count }} 个账号</strong>
        </article>
      </section>

      <el-card shadow="never" class="um-card">
      <el-table class="user-table" :data="users" v-loading="loading" stripe>
        <el-table-column prop="id" label="编号" width="70" />
        <el-table-column prop="username" label="用户名" min-width="120" />
        <el-table-column prop="displayName" label="显示名" min-width="120" />
        <el-table-column prop="email" label="邮箱" min-width="160" />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="roleTagType(row.role)" effect="light">
              {{ roleLabel(row.role) }}
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

      <div v-loading="loading" class="user-card-list">
        <article v-for="row in users" :key="row.id" class="user-item">
          <div class="user-item-head">
            <div>
              <strong>{{ row.displayName || row.username }}</strong>
              <span>@{{ row.username }} · 编号 {{ row.id }}</span>
            </div>
            <el-tag :type="roleTagType(row.role)" effect="light">{{ roleLabel(row.role) }}</el-tag>
          </div>
          <p>{{ row.email || '未设置邮箱' }}</p>
          <div class="user-item-state">
            <span>账号状态</span>
            <el-switch
              :model-value="row.enabled === 1"
              :disabled="row.id === currentUserId"
              @change="(val) => toggleEnabled(row, val)"
            />
          </div>
          <div class="user-item-actions">
            <el-button text type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button text type="warning" :icon="Key" @click="openReset(row)">改密</el-button>
            <el-button text type="danger" :icon="Delete" :disabled="row.id === currentUserId" @click="handleDelete(row)">删除</el-button>
          </div>
        </article>
        <el-empty v-if="!loading && users.length === 0" description="暂无匹配用户" />
      </div>
      </el-card>
    </div>

    <!-- 新建/编辑 对话框 -->
    <el-dialog
      v-model="formVisible"
      :title="editing ? '编辑用户' : '新建用户'"
      width="min(460px, 94vw)"
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
            <el-option v-for="role in roleDefinitions.filter(item => item.enabled === 1)" :key="role.value" :label="role.label" :value="role.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 对话框 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="min(420px, 94vw)">
      <p class="reset-tip">为 <b>{{ resetTarget?.username }}</b> 设置新密码（至少 6 位）</p>
      <el-input v-model="newPassword" type="password" show-password placeholder="新密码" />
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleManagerVisible" title="角色与权限配置" width="min(920px, 95vw)" append-to-body>
      <div class="role-manager-head">
        <el-button type="primary" :icon="Plus" @click="openRoleCreate">新建角色</el-button>
      </div>
      <el-table :data="roleDefinitions" border>
        <el-table-column prop="label" label="角色" min-width="130" />
        <el-table-column prop="value" label="编码" min-width="150" />
        <el-table-column label="权限" min-width="280">
          <template #default="{ row }">
            <div class="permission-tags">
              <el-tag v-for="capability in row.capabilities" :key="capability" size="small" effect="plain">{{ capability }}</el-tag>
              <span v-if="!row.capabilities?.length">基础业务权限</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ row.enabled === 1 ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openRoleEdit(row)">编辑</el-button>
            <el-button link type="danger" :disabled="row.defaultRole === 1" @click="removeRole(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="roleEditorVisible" :title="roleEditing ? '编辑角色' : '新建角色'" width="min(640px, 94vw)" append-to-body>
      <el-form :model="roleForm" label-width="90px">
        <el-form-item label="角色编码" required>
          <el-input v-model="roleForm.value" :disabled="roleEditing" placeholder="例如 finance_reviewer" />
        </el-form-item>
        <el-form-item label="角色名称" required><el-input v-model="roleForm.label" /></el-form-item>
        <el-form-item label="角色说明"><el-input v-model="roleForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="roleForm.enabled" :active-value="1" :inactive-value="0" active-text="启用" />
        </el-form-item>
        <el-form-item label="默认角色">
          <el-switch v-model="roleForm.defaultRole" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="roleForm.sortOrder" :min="0" :max="9999" /></el-form-item>
        <el-form-item label="权限组合">
          <div class="permission-groups">
            <section v-for="group in permissionGroups" :key="group.module">
              <strong>{{ group.module }}</strong>
              <el-checkbox-group v-model="roleForm.permissions">
                <el-checkbox v-for="permission in group.items" :key="permission.code" :value="permission.code">
                  {{ permission.name }}
                </el-checkbox>
              </el-checkbox-group>
            </section>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleEditorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRole">保存角色</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus, Search, Edit, Key, Delete } from '@element-plus/icons-vue'
import {
  fetchUsers, fetchUserRoles, createUser, updateUser, resetUserPassword, deleteUser,
  fetchRolePermissions, createRole, updateRole, deleteRole,
} from '../../api/index.js'
import { useUserStore } from '../../stores/user.js'
import { ROUTES } from '../../constants.js'

const props = defineProps({
  embedded: { type: Boolean, default: false },
  showSidebarToggle: { type: Boolean, default: false },
})
const emit = defineEmits(['close', 'toggleSidebar'])

const userStore = useUserStore()
const currentUserId = computed(() => userStore.userInfo?.id)

const users = ref([])
const roleDefinitions = ref([])
const permissionDefinitions = ref([])
const loading = ref(false)
const saving = ref(false)
const keyword = ref('')

const formVisible = ref(false)
const editing = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, username: '', password: '', displayName: '', email: '', role: '' })
const formRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const resetVisible = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')
const roleManagerVisible = ref(false)
const roleEditorVisible = ref(false)
const roleEditing = ref(false)
const roleForm = reactive({ id: null, value: '', label: '', description: '', enabled: 1, defaultRole: 0, sortOrder: 100, permissions: [] })

const roleCards = computed(() => roleDefinitions.value.map((role) => ({
  ...role,
  symbol: role.label?.trim()?.slice(0, 1) || '角',
  count: users.value.filter(user => user.role === role.value).length,
})))
const defaultRole = computed(() => roleDefinitions.value.find(role => role.defaultRole === 1 && role.enabled === 1)
  || roleDefinitions.value.find(role => role.enabled === 1))
const permissionGroups = computed(() => {
  const groups = new Map()
  permissionDefinitions.value.forEach((permission) => {
    const module = permission.module || '其他'
    if (!groups.has(module)) groups.set(module, [])
    groups.get(module).push(permission)
  })
  return [...groups.entries()].map(([module, items]) => ({ module, items }))
})

function roleLabel(role) { return roleDefinitions.value.find(item => item.value === role)?.label || role }
function roleTagType(role) {
  const types = ['primary', 'success', 'warning', 'info']
  const index = Math.max(0, roleDefinitions.value.findIndex(item => item.value === role))
  return types[index % types.length]
}

async function loadRoles() {
  try {
    const roles = await fetchUserRoles()
    roleDefinitions.value = Array.isArray(roles) ? roles : []
  } catch (e) {
    roleDefinitions.value = []
  }
}

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
  Object.assign(form, { id: null, username: '', password: '', displayName: '', email: '', role: defaultRole.value?.value || '' })
  formVisible.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, username: row.username, password: '', displayName: row.displayName || '', email: row.email || '', role: row.role || defaultRole.value?.value || '' })
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

async function openRoleManager() {
  try {
    const [, permissions] = await Promise.all([loadRoles(), fetchRolePermissions()])
    permissionDefinitions.value = Array.isArray(permissions) ? permissions : []
    roleManagerVisible.value = true
  } catch (e) {
    permissionDefinitions.value = []
  }
}

function openRoleCreate() {
  roleEditing.value = false
  Object.assign(roleForm, { id: null, value: '', label: '', description: '', enabled: 1, defaultRole: 0, sortOrder: 100, permissions: [] })
  roleEditorVisible.value = true
}

function openRoleEdit(role) {
  roleEditing.value = true
  Object.assign(roleForm, {
    id: role.id, value: role.value, label: role.label, description: role.description || '',
    enabled: role.enabled, defaultRole: role.defaultRole, sortOrder: role.sortOrder,
    permissions: [...(role.permissions || [])],
  })
  roleEditorVisible.value = true
}

async function saveRole() {
  if (!roleForm.value.trim() || !roleForm.label.trim()) {
    ElMessage.error('请填写角色编码和名称')
    return
  }
  saving.value = true
  const payload = {
    value: roleForm.value.trim(), label: roleForm.label.trim(), description: roleForm.description,
    enabled: roleForm.enabled, defaultRole: roleForm.defaultRole, sortOrder: roleForm.sortOrder,
    permissions: [...roleForm.permissions],
  }
  try {
    if (roleEditing.value) await updateRole(roleForm.id, payload)
    else await createRole(payload)
    await loadRoles()
    roleEditorVisible.value = false
    ElMessage.success('角色配置已保存')
  } finally {
    saving.value = false
  }
}

async function removeRole(role) {
  try {
    await ElMessageBox.confirm(`确定删除角色「${role.label}」吗？`, '删除角色', { type: 'warning' })
  } catch { return }
  await deleteRole(role.id)
  await loadRoles()
  ElMessage.success('角色已删除')
}

onMounted(() => Promise.all([loadRoles(), loadUsers()]))
</script>

<style scoped>
.um-page {
  position: relative;
  flex: 1;
  min-width: 0;
  width: 100%;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 8px;
  background: var(--app-background);
}
.module-menu-floating { position:absolute; z-index:5; top:10px; left:10px; width:34px; height:34px; display:grid; place-items:center; padding:0; border:1px solid #d8e1ee; border-radius:8px; color:var(--text-regular); background:#fff; cursor:pointer; }

.um-page.embedded {
  min-height: 0;
  height: 100%;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: #f5f7fa;
}

.um-header {
  flex: 0 0 auto;
  min-height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 10px clamp(16px, 2vw, 28px);
  border: 1px solid var(--border);
  border-radius: 10px;
  background: rgba(255,255,255,.96);
}

.embedded .um-header {
  border-width: 0 0 1px;
  border-radius: 0;
}

.um-identity,
.um-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.um-actions { justify-content: flex-end; flex-wrap: wrap; margin-bottom: 14px; }

.um-identity {
  min-width: 240px;
}

.module-mark {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: #2468f2;
  color: #fff;
  font-size: 12px;
  font-weight: 750;
  letter-spacing: .06em;
}

.module-menu,
.return-button {
  border: 1px solid #d8e1ee;
  background: #fff;
  color: var(--text-regular);
  cursor: pointer;
}

.module-menu {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  border-radius: 8px;
  font-size: 18px;
}

.return-button {
  min-height: 32px;
  padding: 0 11px;
  border-radius: 8px;
  font-size: 12px;
}

.return-button:hover,
.module-menu:hover {
  border-color: #2468f2;
  color: #2468f2;
}

.header-copy {
  min-width: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: baseline;
  column-gap: 10px;
}

.header-copy small {
  color: #2468f2;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: .12em;
}

.header-copy strong {
  color: var(--text-primary);
  font-size: 18px;
}

.header-copy p {
  grid-column: 1 / -1;
  margin: 2px 0 0;
  color: var(--text-secondary);
  font-size: 12px;
}

.um-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: clamp(14px, 1.6vw, 24px);
}

.role-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.role-card {
  min-width: 0;
  padding: 15px;
  border: 1px solid #dce4ef;
  border-radius: 11px;
  background: #fff;
}

.role-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 13px;
}

.role-symbol {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: #2468f2;
  background: #edf3ff;
  font-size: 12px;
  font-weight: 750;
}

.role-card > strong { display: block; color: var(--text-primary); font-size: 20px; }
.role-card > p { min-height: 38px; margin: 7px 0; color: var(--text-secondary); font-size: 12px; line-height: 1.55; }
.role-card > small { color: #8a8f98; font-size: 10px; line-height: 1.55; }

.um-card {
  margin: 0;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: #fff;
}

.user-card-list {
  display: none;
  gap: 10px;
}

.user-item {
  min-width: 0;
  padding: 14px;
  border: 1px solid #dce4ef;
  border-radius: 9px;
  background: #fff;
}

.user-item-head,
.user-item-state,
.user-item-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-item-head {
  justify-content: space-between;
}

.user-item-head > div {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.user-item-head strong {
  color: var(--text-primary);
}

.user-item-head span,
.user-item > p,
.user-item-state {
  color: var(--text-secondary);
  font-size: 12px;
}

.user-item > p {
  margin: 12px 0;
}

.user-item-state {
  justify-content: space-between;
  min-height: 38px;
  padding: 0 10px;
  border-radius: 7px;
  background: #f5f7fa;
}

.user-item-actions {
  flex-wrap: wrap;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px solid #edf1f6;
}

.reset-tip {
  margin: 0 0 16px;
  color: var(--text-secondary);
}

.role-manager-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.role-manager-head p { margin: 0; color: var(--text-secondary); font-size: 13px; }
.permission-tags { display: flex; flex-wrap: wrap; gap: 6px; color: var(--text-secondary); font-size: 12px; }
.permission-groups { width: 100%; display: grid; gap: 14px; }
.permission-groups section { display: grid; gap: 7px; }
.permission-groups strong { color: var(--text-primary); font-size: 13px; }
.permission-groups :deep(.el-checkbox-group) { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 14px; }
.permission-groups :deep(.el-checkbox) { margin-right: 0; }

@media (max-width: 1180px) {
  .role-overview { grid-template-columns: 1fr; }

  .um-header {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
    padding-top: 12px;
    padding-bottom: 12px;
  }

  .um-actions {
    width: 100%;
  }

  .um-actions :deep(.el-input) {
    width: auto !important;
    flex: 1;
  }

  .user-table { display: none; }
  .user-card-list { display: grid; }
}

@media (max-width: 767px) {
  .um-page.embedded {
    border: 0;
    border-radius: 0;
  }

  .um-header {
    gap: 8px;
    padding: 8px 10px 10px;
  }

  .um-identity {
    min-width: 0;
    gap: 9px;
  }

  .module-mark {
    width: 34px;
    height: 34px;
  }

  .header-copy {
    display: block;
  }

  .header-copy small,
  .header-copy p {
    display: none;
  }

  .header-copy strong {
    font-size: 16px;
  }

  .um-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 8px;
  }

  .um-actions :deep(.el-input) {
    grid-column: 1 / -1;
    width: 100% !important;
  }

  .return-button {
    min-height: 32px;
  }

  .um-body {
    padding: 54px 10px 10px;
  }

  .user-item-actions :deep(.el-button) {
    margin-left: 0;
  }
}
</style>
