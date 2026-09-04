<template>
  <section class="data-center">
    <button v-if="props.showSidebarToggle" type="button" class="module-menu-floating" aria-label="打开导航" @click="emit('toggleSidebar')">☰</button>

    <div class="data-center-body">
      <div class="summary-grid" aria-label="数据源概览">
        <article class="summary-card"><span class="summary-icon blue"><DataLine /></span><div><small>数据源总数</small><strong>{{ summary.total }}</strong></div></article>
        <article class="summary-card"><span class="summary-icon green"><CircleCheck /></span><div><small>正常连接</small><strong>{{ summary.active }}</strong></div></article>
        <article class="summary-card"><span class="summary-icon cyan"><ChatDotRound /></span><div><small>问数可用</small><strong>{{ summary.qaEnabled }}</strong></div></article>
        <article class="summary-card"><span class="summary-icon amber"><TrendCharts /></span><div><small>{{ timeRangeLabel }}查询量</small><strong>{{ formatNumber(summary.queries) }}</strong></div></article>
      </div>

      <div class="asset-layout">
        <section class="asset-panel">
          <div class="panel-heading"><div><h2>数据源目录</h2><span>{{ filteredDataSources.length }} 个连接</span></div><div class="heading-actions"><el-button v-if="userStore.canManageDataSources" type="primary" :icon="Plus" @click="handleCreate">新增数据源</el-button><el-button :icon="Refresh" circle plain :loading="loading" aria-label="刷新数据源" @click="refreshAll" /></div></div>
          <div class="asset-toolbar">
            <el-input v-model="searchQuery" :prefix-icon="Search" clearable placeholder="搜索名称、地址或数据库" />
            <el-select v-model="typeFilter" clearable placeholder="全部类型"><el-option v-for="type in sourceTypes" :key="type" :label="type" :value="type" /></el-select>
            <el-select v-model="statusFilter" clearable placeholder="全部状态"><el-option label="正常连接" value="active" /><el-option label="未激活" value="inactive" /><el-option label="系统库" value="system" /></el-select>
          </div>

          <div class="source-table-wrap">
            <el-table :data="filteredDataSources" v-loading="loading" class="source-table" empty-text="暂无符合条件的数据源">
              <el-table-column label="数据源" min-width="190"><template #default="{ row }"><div class="source-name-cell"><span class="type-avatar">{{ shortType(row.type) }}</span><div><strong>{{ row.name }}</strong><small>{{ row.databaseName || '未配置数据库' }}</small></div></div></template></el-table-column>
              <el-table-column label="类型" width="110"><template #default="{ row }"><el-tag :type="getTypeColor(row.type)" effect="plain">{{ row.type }}</el-tag></template></el-table-column>
              <el-table-column label="连接地址" min-width="190"><template #default="{ row }"><code>{{ connectionLabel(row) }}</code></template></el-table-column>
              <el-table-column label="运行状态" width="112"><template #default="{ row }"><span class="status-label" :class="sourceStatus(row).tone">{{ sourceStatus(row).label }}</span></template></el-table-column>
              <el-table-column label="问数权限" width="105"><template #default="{ row }"><el-tag :type="(row.forQuestionAnswering ?? true) ? 'success' : 'info'" effect="plain">{{ (row.forQuestionAnswering ?? true) ? '已开放' : '未开放' }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="236" fixed="right"><template #default="{ row }">
                <el-button link type="primary" :loading="testing[row.id]" @click="handleTestDetailed(row)">连接测试</el-button><el-button link type="primary" @click="handleViewTables(row)">查看表</el-button>
                <el-dropdown v-if="userStore.canManageDataSources" trigger="click"><el-button link type="primary">更多</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item :disabled="row.system" @click="handleEdit(row)">编辑配置</el-dropdown-item><el-dropdown-item v-if="!row.system" divided class="danger-item" @click="confirmDelete(row)">删除数据源</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
              </template></el-table-column>
            </el-table>

            <div class="source-card-list">
              <article v-for="row in filteredDataSources" :key="row.id" class="source-card">
                <div class="source-card-head"><span class="type-avatar">{{ shortType(row.type) }}</span><div><strong>{{ row.name }}</strong><small>{{ row.type }} · {{ row.databaseName || '未配置数据库' }}</small></div><span class="status-label" :class="sourceStatus(row).tone">{{ sourceStatus(row).label }}</span></div>
                <code>{{ connectionLabel(row) }}</code><div class="source-card-meta"><span>问数权限</span><strong>{{ (row.forQuestionAnswering ?? true) ? '已开放' : '未开放' }}</strong></div>
                <div class="source-card-actions"><el-button link type="primary" :loading="testing[row.id]" @click="handleTestDetailed(row)">连接测试</el-button><el-button link type="primary" @click="handleViewTables(row)">查看表</el-button><el-button v-if="userStore.canManageDataSources" link type="primary" :disabled="row.system" @click="handleEdit(row)">编辑</el-button><el-button v-if="userStore.canManageDataSources && !row.system" link type="danger" @click="confirmDelete(row)">删除</el-button></div>
              </article>
              <el-empty v-if="!loading && !filteredDataSources.length" description="暂无符合条件的数据源" :image-size="72" />
            </div>
          </div>
        </section>

        <aside class="insight-panel">
          <div class="panel-heading insight-heading"><div><h2>使用概览</h2></div><el-radio-group v-model="timeRange" size="small" @change="loadUsageStats"><el-radio-button value="daily">日</el-radio-button><el-radio-button value="weekly">周</el-radio-button><el-radio-button value="monthly">月</el-radio-button></el-radio-group></div>
          <div v-loading="loadingStats" class="usage-content">
            <div v-if="usageLeaderboard.length" class="usage-list"><article v-for="(stat, index) in usageLeaderboard" :key="stat.dataSourceId || index"><div class="usage-row"><span><em>{{ index + 1 }}</em>{{ stat.dataSourceName }}</span><strong>{{ formatNumber(stat.totalQueries) }} 次</strong></div><div class="usage-track"><i :style="{ width: usageWidth(stat.totalQueries) }"></i></div><div class="usage-meta"><span>成功率 {{ Number(stat.successRate || 0).toFixed(1) }}%</span><span>平均 {{ Math.round(stat.avgQueryTimeMs || 0) }} 毫秒</span></div></article></div>
            <el-empty v-else description="当前周期暂无使用记录" :image-size="72" />
          </div>
        </aside>
      </div>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑数据源' : '新增数据源'" width="min(620px, 92vw)" @close="handleDialogClose">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="112px">
        <el-form-item label="数据源名称" prop="name"><el-input v-model="form.name" placeholder="请输入数据源名称" /></el-form-item>
        <el-form-item label="数据库类型" prop="type"><el-select v-model="form.type" placeholder="请选择数据库类型" class="w-full"><el-option label="MySQL" value="MySQL" /><el-option label="PostgreSQL" value="PostgreSQL" /><el-option label="Oracle" value="Oracle" /><el-option label="SQL Server" value="SQLServer" /><el-option label="GBase" value="GBase" /></el-select></el-form-item>
        <el-form-item label="主机地址" prop="host"><el-input v-model="form.host" placeholder="例如 localhost 或 IP 地址" /></el-form-item><el-form-item label="端口" prop="port"><el-input-number v-model="form.port" :min="1" :max="65535" class="w-full" /></el-form-item>
        <el-form-item label="数据库名称" prop="databaseName"><el-input v-model="form.databaseName" placeholder="请输入数据库名称" /></el-form-item><el-form-item label="用户名" prop="username"><el-input v-model="form.username" placeholder="请输入数据库用户名" /></el-form-item>
        <el-form-item label="密码" prop="password"><el-input v-model="form.password" type="password" :placeholder="isEdit ? '留空则不修改密码' : '请输入数据库密码'" show-password /></el-form-item><el-form-item label="其他配置" prop="extraConfig"><el-input v-model="form.extraConfig" type="textarea" :rows="3" placeholder="JSON 格式的额外配置（可选）" /></el-form-item>
        <el-form-item label="问数功能"><el-switch v-model="form.forQuestionAnswering" active-text="开放" inactive-text="关闭" /><div class="form-item-tip">关闭后，该数据源不会出现在 AI 问数的数据源选择中。</div></el-form-item>
      </el-form><template #footer><el-button @click="dialogVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="handleSave">{{ isEdit ? '保存修改' : '确认新增' }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="testResultDialogVisible" title="连接测试详情" width="min(700px, 92vw)"><div v-if="testResult" class="test-result"><el-alert :type="testResult.success ? 'success' : 'error'" :title="testResult.message" :closable="false" show-icon><template #default><div class="test-summary"><span>连接延迟</span><strong>{{ testResult.latencyMs }} 毫秒</strong></div></template></el-alert><el-descriptions v-if="testResult.success" :column="1" border><el-descriptions-item label="数据库版本">{{ testResult.databaseVersion || '未知' }}</el-descriptions-item><el-descriptions-item label="当前数据库">{{ testResult.currentSchema || '未知' }}</el-descriptions-item></el-descriptions><div v-if="testResult.permissions" class="permissions-section"><h4>权限检查</h4><el-table :data="getPermissionsList(testResult.permissions)" size="small"><el-table-column prop="name" label="权限" width="120" /><el-table-column label="状态" width="80"><template #default="{ row }"><el-tag :type="row.granted ? 'success' : 'danger'" size="small">{{ row.granted ? '通过' : '失败' }}</el-tag></template></el-table-column><el-table-column prop="description" label="说明" /></el-table></div></div></el-dialog>
    <el-dialog v-model="tablesDialogVisible" title="数据表列表" width="min(820px, 94vw)"><el-table :data="tables" v-loading="loadingTables" max-height="430"><el-table-column prop="name" label="表名" min-width="200" /><el-table-column prop="comment" label="注释" min-width="200" /><el-table-column prop="rows" label="行数" width="100" align="right" /><el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" @click="handleViewColumns(row)">查看字段</el-button></template></el-table-column></el-table></el-dialog>
    <el-dialog v-model="columnsDialogVisible" :title="`字段列表 · ${currentTable}`" width="min(900px, 95vw)"><el-table :data="columns" v-loading="loadingColumns" max-height="430"><el-table-column prop="name" label="字段名" min-width="150" /><el-table-column prop="type" label="类型" min-width="120" /><el-table-column label="可空" width="80"><template #default="{ row }"><el-tag :type="row.nullable === 'YES' ? 'warning' : 'success'" size="small">{{ row.nullable === 'YES' ? '可空' : '必填' }}</el-tag></template></el-table-column><el-table-column prop="key" label="键" width="80" /><el-table-column prop="comment" label="注释" min-width="200" /></el-table></el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, CircleCheck, DataLine, Plus, Refresh, Search, TrendCharts } from '@element-plus/icons-vue'
import api from '../api/index.js'
import { useUserStore } from '../stores/user.js'

const props = defineProps({ showSidebarToggle: { type: Boolean, default: false } })
const emit = defineEmits(['close', 'toggleSidebar'])
const userStore = useUserStore()
const loading = ref(false), saving = ref(false), testing = ref({}), loadingTables = ref(false), loadingColumns = ref(false), loadingStats = ref(false)
const dataSources = ref([]), tables = ref([]), columns = ref([]), currentTable = ref(''), currentDataSourceId = ref(null), usageStats = ref([]), timeRange = ref('weekly'), testResult = ref(null)
const searchQuery = ref(''), typeFilter = ref(''), statusFilter = ref('')
const dialogVisible = ref(false), tablesDialogVisible = ref(false), columnsDialogVisible = ref(false), testResultDialogVisible = ref(false), isEdit = ref(false), formRef = ref()
const form = reactive({ id: null, name: '', type: 'MySQL', host: '', port: 3306, databaseName: '', username: '', password: '', extraConfig: '', forQuestionAnswering: true })
const timeRangeLabel = computed(() => ({ daily: '今日', weekly: '本周', monthly: '本月' }[timeRange.value] || '本周'))
const sourceTypes = computed(() => [...new Set(dataSources.value.map(item => item.type).filter(Boolean))])
const filteredDataSources = computed(() => dataSources.value.filter(item => {
  const query = searchQuery.value.trim().toLowerCase()
  const matchesQuery = !query || [item.name, item.host, item.databaseName, item.type].some(value => String(value || '').toLowerCase().includes(query))
  const matchesType = !typeFilter.value || item.type === typeFilter.value
  const status = item.system ? 'system' : (item.status === 'active' ? 'active' : 'inactive')
  return matchesQuery && matchesType && (!statusFilter.value || status === statusFilter.value)
}))
const summary = computed(() => {
  const totalQueries = usageStats.value.reduce((sum, item) => sum + Number(item.totalQueries || 0), 0)
  const weightedSuccess = totalQueries ? usageStats.value.reduce((sum, item) => sum + Number(item.totalQueries || 0) * Number(item.successRate || 0), 0) / totalQueries : 0
  return { total: dataSources.value.length, active: dataSources.value.filter(item => item.system || item.status === 'active').length, qaEnabled: dataSources.value.filter(item => item.forQuestionAnswering ?? true).length, queries: totalQueries, successRate: weightedSuccess.toFixed(1) }
})
const usageLeaderboard = computed(() => [...usageStats.value].sort((a, b) => Number(b.totalQueries || 0) - Number(a.totalQueries || 0)).slice(0, 5))
const maxQueries = computed(() => Math.max(...usageLeaderboard.value.map(item => Number(item.totalQueries || 0)), 1))
const rules = {
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }], type: [{ required: true, message: '请选择数据库类型', trigger: 'change' }], host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }], port: [{ required: true, message: '请输入端口号', trigger: 'blur' }], databaseName: [{ required: true, message: '请输入数据库名称', trigger: 'blur' }], username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ validator: (_rule, value, callback) => !isEdit.value && !value ? callback(new Error('请输入密码')) : callback(), trigger: 'blur' }]
}
const getTypeColor = type => ({ MySQL: 'primary', PostgreSQL: 'success', Oracle: 'warning', SQLServer: 'danger', GBase: 'info' }[type] || 'info')
const shortType = type => ({ MySQL: 'MY', PostgreSQL: 'PG', Oracle: 'OR', SQLServer: 'MS', GBase: 'GB' }[type] || String(type || 'DB').slice(0, 2).toUpperCase())
const sourceStatus = row => row.system ? { label: '系统库', tone: 'system' } : row.status === 'active' ? { label: '正常', tone: 'active' } : { label: '未激活', tone: 'inactive' }
const connectionLabel = row => `${row.host || '—'}:${row.port || '—'}/${row.databaseName || '—'}`
const formatNumber = value => new Intl.NumberFormat('zh-CN').format(Number(value || 0))
const usageWidth = value => `${Math.max(8, Math.round(Number(value || 0) / maxQueries.value * 100))}%`
const getPermissionsList = permissions => [{ name: 'SELECT', granted: permissions.canSelect, description: '查询数据' }, { name: 'SHOW', granted: permissions.canShow, description: '显示表列表' }, { name: 'DESCRIBE', granted: permissions.canDescribe, description: '查看表结构' }, { name: 'EXPLAIN', granted: permissions.canExplain, description: '执行计划分析' }]
async function loadDataSources() { loading.value = true; try { const { data } = await api.get('/datasource'); dataSources.value = data.data || [] } catch (error) { ElMessage.error('加载数据源列表失败'); console.error(error) } finally { loading.value = false } }
async function loadUsageStats() { loadingStats.value = true; try { const { data } = await api.get('/datasource/stats/usage', { params: { timeRange: timeRange.value } }); usageStats.value = data.data || [] } catch (error) { console.error('加载使用统计失败:', error) } finally { loadingStats.value = false } }
async function refreshAll() { await Promise.all([loadDataSources(), loadUsageStats()]) }
function handleCreate() { isEdit.value = false; Object.assign(form, { id: null, name: '', type: 'MySQL', host: '', port: 3306, databaseName: '', username: '', password: '', extraConfig: '', forQuestionAnswering: true }); dialogVisible.value = true }
function handleEdit(row) { isEdit.value = true; Object.assign(form, { id: row.id, name: row.name, type: row.type, host: row.host, port: row.port, databaseName: row.databaseName, username: row.username, password: '', extraConfig: row.extraConfig || '', forQuestionAnswering: row.forQuestionAnswering ?? true }); dialogVisible.value = true }
async function handleSave() {
  try { await formRef.value.validate() } catch { return }
  saving.value = true
  try { const payload = { name: form.name, type: form.type, host: form.host, port: form.port, databaseName: form.databaseName, username: form.username, extraConfig: form.extraConfig || null, forQuestionAnswering: form.forQuestionAnswering }; if (!isEdit.value || form.password) payload.password = form.password; if (isEdit.value) await api.put(`/datasource/${form.id}`, payload); else await api.post('/datasource', payload); ElMessage.success(isEdit.value ? '数据源更新成功' : '数据源添加成功'); dialogVisible.value = false; await loadDataSources() }
  catch (error) { ElMessage.error(error.response?.data?.message || '操作失败'); console.error(error) } finally { saving.value = false }
}
async function handleTestDetailed(row) { testing.value[row.id] = true; try { const { data } = await api.post(`/datasource/${row.id}/test-detailed`); testResult.value = data.data; testResultDialogVisible.value = true; testResult.value.success ? ElMessage.success('连接测试成功') : ElMessage.error(testResult.value.message || '连接测试失败') } catch (error) { ElMessage.error('连接测试失败'); console.error(error) } finally { testing.value[row.id] = false } }
async function confirmDelete(row) { try { await ElMessageBox.confirm(`确定删除数据源“${row.name}”吗？此操作不可撤销。`, '删除数据源', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }); await handleDelete(row) } catch (error) { if (error !== 'cancel' && error !== 'close') console.error(error) } }
async function handleDelete(row) { try { await api.delete(`/datasource/${row.id}`); ElMessage.success('数据源删除成功'); await loadDataSources() } catch (error) { ElMessage.error(error.response?.data?.message || '删除失败'); console.error(error) } }
async function handleViewTables(row) { currentDataSourceId.value = row.id; loadingTables.value = true; tablesDialogVisible.value = true; try { const { data } = await api.get(`/datasource/${row.id}/tables`); tables.value = data.data || [] } catch (error) { ElMessage.error('加载表列表失败'); console.error(error) } finally { loadingTables.value = false } }
async function handleViewColumns(row) { currentTable.value = row.name; loadingColumns.value = true; columnsDialogVisible.value = true; try { const { data } = await api.get(`/datasource/${currentDataSourceId.value}/tables/${encodeURIComponent(row.name)}/columns`); columns.value = data.data || [] } catch (error) { ElMessage.error('加载字段列表失败'); console.error(error) } finally { loadingColumns.value = false } }
function handleDialogClose() { formRef.value?.resetFields() }
onMounted(refreshAll)
</script>

<style scoped>
.data-center { position:relative;min-width:0;min-height:100%;flex:1 0 100%;display:flex;flex-direction:column;overflow:visible;border:1px solid #e4e8ef;border-radius:12px;color:#1f2329;background:#f5f7fa;box-shadow:0 4px 16px rgba(31,35,41,.05); }
.module-menu-floating { position: absolute; z-index: 5; top: 10px; left: 10px; width: 34px; height: 34px; display: grid; place-items: center; padding: 0; border: 1px solid #d9dee7; border-radius: 8px; color: #4e5969; background: #fff; cursor: pointer; }
.data-center-header { min-height: 68px; display: flex; align-items: center; gap: 12px; flex-shrink: 0; padding: 9px 20px; border-bottom: 1px solid #e4e8ef; background: #fff; }.menu-button { width: 34px; height: 34px; display: grid; place-items: center; padding: 0; border: 1px solid #d9dee7; border-radius: 6px; color: #4e5969; background: #fff; cursor: pointer; }.module-mark { width: 38px; height: 38px; display: grid; place-items: center; flex-shrink: 0; border-radius: 8px; color: #fff; background: #2468f2; font-size: 10px; font-weight: 700; letter-spacing: .08em; }
.header-copy { min-width: 0; flex: 1; display: grid; grid-template-columns: max-content 1fr; align-items: baseline; column-gap: 10px; }.header-copy small { grid-column: 1 / -1; margin-bottom: 1px; color: #86909c; font-size: 9px; font-weight: 650; letter-spacing: .1em; }.header-copy strong { font-size: 16px; font-weight: 650; }.header-copy p { overflow: hidden; margin: 0; color: #86909c; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.return-button { min-height: 34px; padding: 0 13px; border: 1px solid #d9dee7; border-radius: 6px; color: #4e5969; background: #fff; font: inherit; font-size: 12px; cursor: pointer; }.return-button:hover { color: #2468f2; border-color: #a9c4fb; background: #f3f7ff; }
.data-center-body { min-height: 0; flex: 1; overflow: visible; padding: clamp(16px, 1.5vw, 24px); }
.summary-grid { display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:9px;margin-bottom:12px; }.summary-card { min-width:0;display:flex;align-items:center;gap:12px;padding:12px 13px;border:1px solid #e4e8ef;border-radius:9px;background:#fff;box-shadow:none;transition:border-color .15s ease; }.summary-card:hover { border-color:#cdd9e8;transform:none;box-shadow:none; }.summary-icon { width:40px;height:40px;display:grid;place-items:center;flex-shrink:0;border-radius:8px; }.summary-icon :deep(svg) { width:19px;height:19px; }.summary-icon.blue { color:#2468f2;background:#edf3ff; }.summary-icon.green { color:#00a870;background:#e8f8f3; }.summary-icon.cyan { color:#00a4c7;background:#e9f8fb; }.summary-icon.amber { color:#d47b00;background:#fff4e2; }.summary-card > div { min-width:0;display:grid;grid-template-columns:1fr max-content;align-items:baseline;column-gap:8px; }.summary-card small { color:#86909c;font-size:10px; }.summary-card strong { grid-row:1 / 3;grid-column:2;color:#2468f2;font-size:20px;line-height:1;font-weight:650; }.summary-card p { overflow:hidden;margin:5px 0 0;color:#4e5969;font-size:11px;text-overflow:ellipsis;white-space:nowrap; }
.asset-layout { display:grid;grid-template-columns:minmax(0,1fr) 310px;gap:12px;align-items:start; }.asset-panel,.insight-panel { min-width:0;border:1px solid #e4e8ef;border-radius:10px;background:#fff;box-shadow:none; }.panel-heading { min-height:56px;display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 14px;border-bottom:1px solid #edf0f5; }.panel-heading > div { min-width:0;display:flex;align-items:center;gap:9px; }.panel-heading h2 { margin:0;color:#1f2329;font-size:15px;font-weight:650; }.panel-heading span { color:#86909c;font-size:11px; }.heading-actions { display:flex;align-items:center;gap:8px; }.asset-toolbar { display:grid;grid-template-columns:minmax(220px,1fr) 138px 138px;gap:9px;padding:12px 14px;border-bottom:1px solid #edf0f5;background:#fafbfc; }
.source-table-wrap { min-width: 0; }.source-table { width: 100%; }.source-name-cell { display: flex; align-items: center; gap: 10px; }.source-name-cell > div, .source-card-head > div { min-width: 0; }.source-name-cell strong, .source-name-cell small { display: block; }.source-name-cell small, .source-card-head small { overflow: hidden; margin-top: 3px; color: #86909c; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.type-avatar { width: 34px; height: 34px; display: grid; place-items: center; flex-shrink: 0; border: 1px solid #d7e3fb; border-radius: 7px; color: #2468f2; background: #f3f7ff; font-size: 10px; font-weight: 700; }.source-table code, .source-card > code { color: #4e5969; font: 11px/1.4 var(--font-family-mono); word-break: break-all; }
.status-label { display: inline-flex; align-items: center; color: #4e5969; font-size: 12px; white-space: nowrap; }.danger-item { color: #e34d59; }.source-card-list { display: none; }
.insight-panel { overflow: hidden; }.insight-heading { align-items: flex-start; flex-direction: column; }.insight-heading > div { width: 100%; justify-content: space-between; }.insight-heading :deep(.el-radio-group) { width: 100%; }.insight-heading :deep(.el-radio-button) { flex: 1; }.insight-heading :deep(.el-radio-button__inner) { width: 100%; }.usage-content { min-height: 244px; padding: 6px 16px 10px; }.usage-list article { padding: 12px 0; border-bottom: 1px solid #f0f2f5; }.usage-list article:last-child { border-bottom: 0; }.usage-row, .usage-meta { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.usage-row > span { min-width: 0; overflow: hidden; color: #4e5969; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }.usage-row em { width: 20px; display: inline-block; color: #86909c; font-style: normal; }.usage-row strong { font-size: 12px; font-weight: 600; white-space: nowrap; }.usage-track { height: 5px; overflow: hidden; margin: 8px 0 6px 20px; border-radius: 4px; background: #f0f2f5; }.usage-track i { height: 100%; display: block; border-radius: inherit; background: #2468f2; }.usage-meta { margin-left: 20px; color: #86909c; font-size: 10px; }
.w-full { width: 100%; }.form-item-tip { width: 100%; margin-top: 4px; color: #86909c; font-size: 11px; }.test-result { display: grid; gap: 16px; }.test-summary { display: flex; gap: 8px; }.test-summary strong { color: #1f2329; }.permissions-section h4 { margin: 0 0 10px; }
@media (max-width: 1600px) { .asset-layout { grid-template-columns: 1fr; }.insight-panel { display: grid; grid-template-columns: minmax(0, 1fr); }.insight-heading { flex-direction: row; align-items: center; }.insight-heading :deep(.el-radio-group) { width: 200px; } }
@media (max-width: 1320px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1080px) { .asset-layout { grid-template-columns: 1fr; }.insight-panel { display: grid; grid-template-columns: 1fr; }.insight-heading { flex-direction: row; align-items: center; }.insight-heading :deep(.el-radio-group) { width: 180px; } }
@media (max-width: 1180px) { .source-table { display: none; }.source-card-list { display: grid; gap: 10px; padding: 12px; }.source-card { padding: 13px; border: 1px solid #e4e8ef; border-radius: 9px; background: #fff; box-shadow:none; }.source-card-head { display: flex; align-items: center; gap: 10px; }.source-card-head > div { flex: 1; }.source-card-head > div strong { display: block; }.source-card > code { display: block; margin: 12px 0; padding: 8px 10px; border-radius: 7px; background: #f7f8fa; }.source-card-meta { display: flex; justify-content: space-between; color: #86909c; font-size: 11px; }.source-card-meta strong { color: #4e5969; }.source-card-actions { display: flex; gap: 2px; margin-top: 8px; border-top: 1px solid #f0f2f5; padding-top: 7px; } }
@media (max-width: 767px) { .data-center { border: 0; border-radius: 0; }.data-center-body { padding: 54px 12px 20px; }.summary-grid { gap: 8px; }.summary-card { gap: 9px; padding: 12px; }.summary-icon { width: 34px; height: 34px; }.summary-icon :deep(svg) { width: 17px; }.summary-card strong { font-size: 20px; }.asset-toolbar { grid-template-columns: 1fr 1fr; padding: 10px 12px; }.asset-toolbar .el-input { grid-column: 1 / -1; }.insight-panel { display: block; }.insight-heading { flex-direction: column; align-items: flex-start; }.insight-heading :deep(.el-radio-group) { width: 100%; } }
@media (max-width: 430px) { .header-copy small { display: none; }.header-copy { display: block; }.return-button { border: 0; color: #2468f2; background: transparent; }.page-intro { gap: 10px; }.page-intro .el-button { padding-inline: 10px; }.summary-grid { grid-template-columns: 1fr 1fr; }.summary-card { align-items: flex-start; flex-direction: column; }.summary-card > div { width: 100%; }.summary-card strong { font-size: 22px; }.source-card-actions { overflow-x: auto; }.source-card-actions :deep(.el-button) { margin-left: 0; } }
</style>
