<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import CourseRecordDialog from '../components/CourseRecordDialog.vue'

const emit = defineEmits(['changed'])

const loading = ref(false)
const schedules = ref([])
const students = ref([])
const studentMap = ref({})
const studentId = ref(null)
const status = ref('')
// 「已完成课程」= 课次已上课 且 课后记录已提交/已归档（未提交的草稿不算结课）
const completedOnly = ref(false)

// 课后记录映射：scheduleId -> 记录视图
const recordMap = ref({})
const recordLoading = ref(false)

const STATUS = ['待上课', '已上课', '已取消']

const fmtMin = (n) => {
  if (n == null) return ''
  const h = Math.floor(n / 60); const m = n % 60
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0')
}
const statusType = (s) => ({ 待上课: 'warning', 已上课: 'success', 已取消: 'info' }[s] || '')
const recordStatusType = (s) => ({ 草稿: 'warning', 已提交: 'success', 已归档: 'info' }[s] || 'info')
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

const shownSchedules = computed(() => {
  if (!completedOnly.value) return schedules.value
  return schedules.value.filter((c) => {
    const rs = recordMap.value[c.id]?.status
    return c.status === '已上课' && (rs === '已提交' || rs === '已归档')
  })
})

async function loadStudents() {
  students.value = await api.listStudents()
  studentMap.value = Object.fromEntries(students.value.map((s) => [s.id, s.name]))
}
async function loadRecords() {
  recordLoading.value = true
  try {
    const records = await api.listCourseRecords(studentId.value || null)
    recordMap.value = Object.fromEntries(records.map((r) => [r.scheduleId, r]))
  } catch (e) { /* 记录加载失败不阻塞课次列表 */ } finally { recordLoading.value = false }
}
async function load() {
  loading.value = true
  try {
    schedules.value = await api.listCourseSchedules(studentId.value || null, status.value || null)
    await loadRecords()
  } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}
function resetFilters() {
  studentId.value = null; status.value = ''; completedOnly.value = false
  load()
}

async function cancel(row) {
  try {
    const body = await api.cancelCourseSchedule(row.id)
    ElMessage.success(body.message || '已取消')
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}
async function checkin(row) {
  try { await api.checkinCourseSchedule(row.id); ElMessage.success('已签到上课'); await load(); emit('changed') } catch (e) { ElMessage.error(e.message) }
}

// ---------------- 课后记录入口 ----------------
const recordVisible = ref(false)
const recordSchedule = ref(null)

function openRecord(row) {
  if (row.status !== '已上课') {
    ElMessage.warning(row.status === '待上课'
      ? '该课次还是「待上课」，请先签到上课后再填写课后记录'
      : '该课次已取消，不能填写课后记录')
    return
  }
  recordSchedule.value = row
  recordVisible.value = true
}
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="filters">
        <el-select v-model="studentId" placeholder="学员" clearable filterable style="width:160px" @change="load">
          <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.code + '）'" :value="s.id" />
        </el-select>
        <el-select v-model="status" placeholder="课次状态" clearable style="width:120px" @change="load">
          <el-option v-for="s in STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-checkbox v-model="completedOnly" @change="loadRecords">仅看已完成（记录已提交）</el-checkbox>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <span class="hint">排课请到「学员档案」双击学员 → 右侧抽屉 → 排课</span>
    </div>

    <el-table :data="shownSchedules" v-loading="loading || recordLoading" border stripe>
      <el-table-column label="学员" width="120">
        <template #default="{ row }">{{ studentMap[row.studentId] || row.studentId }}</template>
      </el-table-column>
      <el-table-column prop="courseName" label="课程" />
      <el-table-column prop="date" label="日期" width="120" />
      <el-table-column label="时段" width="130">
        <template #default="{ row }">{{ fmtMin(row.startMin) }}–{{ fmtMin(row.endMin) }}</template>
      </el-table-column>
      <el-table-column prop="status" label="课次状态" width="100">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="课后记录" width="150">
        <template #default="{ row }">
          <el-tag v-if="recordMap[row.id]?.status" :type="recordStatusType(recordMap[row.id].status)" size="small">
            {{ recordMap[row.id].status }}
          </el-tag>
          <span v-else class="muted">{{ row.status === '已上课' ? '未填写' : '—' }}</span>
          <div v-if="recordMap[row.id]?.updatedAt" class="time">{{ fmtTime(recordMap[row.id].updatedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="230">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRecord(row)">课后记录</el-button>
          <el-button v-if="row.status === '待上课'" link type="success" @click="checkin(row)">签到上课</el-button>
          <el-button v-if="row.status === '待上课'" link type="danger" @click="cancel(row)">取消</el-button>
        </template>
      </el-table-column>
    </el-table>

    <CourseRecordDialog v-model="recordVisible" :schedule="recordSchedule"
      :student-name="recordSchedule ? (studentMap[recordSchedule.studentId] || '') : ''"
      @changed="load" />
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.filters { display: flex; gap: 8px; align-items: center; }
.hint { color: var(--muted); font-size: 13px; }
.muted { color: var(--muted); font-size: 12px; }
.time { color: var(--muted); font-size: 11px; margin-top: 2px; }
</style>
