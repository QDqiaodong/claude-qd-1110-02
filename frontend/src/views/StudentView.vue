<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import CourseRecordDialog from '../components/CourseRecordDialog.vue'

const emit = defineEmits(['changed'])

const loading = ref(false)
const students = ref([])

const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ id: null, code: '', name: '', phone: '', level: '少儿', status: '在读' })

const drawerVisible = ref(false)
const currentStudent = ref(null)
const schedules = ref([])
const scheduleLoading = ref(false)
// scheduleId -> 课后记录视图（档案中展示当前状态与最后更新时间）
const recordMap = ref({})

const schDialog = ref(false)
const schForm = reactive({ courseName: '', date: '', startTime: '09:00', endTime: '10:00' })

// 课后记录弹窗
const recordVisible = ref(false)
const recordSchedule = ref(null)

const LEVELS = ['少儿', '成人', '艺考']
const STATUS = ['在读', '休学', '结业']

const toMin = (s) => { if (!s) return null; const [h, m] = s.split(':').map(Number); return h * 60 + m }
const fmtMin = (n) => {
  if (n == null) return ''
  const h = Math.floor(n / 60); const m = n % 60
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0')
}
const statusType = (s) => ({ '在读': 'success', '休学': 'warning', '结业': 'info' }[s] || '')
const scheduleStatusType = (s) => ({ '待上课': 'warning', '已上课': 'success', '已取消': 'info' }[s] || '')
const recordStatusType = (s) => ({ '草稿': 'warning', '已提交': 'success', '已归档': 'info' }[s] || 'info')
function fmtTime(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '' }

async function load() {
  loading.value = true
  try { students.value = await api.listStudents() } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}

function addStudent() {
  editing.value = false
  Object.assign(form, { id: null, code: '', name: '', phone: '', level: '少儿', status: '在读' })
  dialogVisible.value = true
}

function editStudent(row) {
  editing.value = true
  Object.assign(form, { id: row.id, code: row.code, name: row.name, phone: row.phone || '', level: row.level, status: row.status })
  dialogVisible.value = true
}

async function saveStudent() {
  try {
    if (editing.value) await api.updateStudent(form.id, { ...form })
    else await api.createStudent({ ...form })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function removeStudent(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除学员「${row.name}」？该学员若有排课、课后记录或领用台账，将无法删除。`,
      '删除学员', { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
    )
  } catch { return }
  try {
    await api.deleteStudent(row.id)
    ElMessage.success('学员已删除')
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function openDrawer(row) {
  // 以服务端最新数据为准：学员状态（在读/休学/结业）与课次列表、记录一并重拉，
  // 保证列表、抽屉、刷新后看到的状态始终一致
  currentStudent.value = row
  drawerVisible.value = true
  await Promise.all([refreshCurrentStudent(row.id), loadSchedules(row.id)])
}

async function refreshCurrentStudent(id) {
  try {
    const fresh = await api.listStudents()
    students.value = fresh
    const latest = fresh.find((s) => s.id === id)
    if (latest) currentStudent.value = latest
  } catch (e) { /* 学员信息刷新失败不阻断抽屉 */ }
}

async function loadSchedules(studentId) {
  scheduleLoading.value = true
  try {
    const [list, records] = await Promise.all([
      api.listCourseSchedules(studentId),
      api.listCourseRecords(studentId).catch(() => [])
    ])
    schedules.value = list
    recordMap.value = Object.fromEntries(records.map((r) => [r.scheduleId, r]))
  } catch (e) { ElMessage.error(e.message) } finally { scheduleLoading.value = false }
}

function addSchedule() {
  Object.assign(schForm, { courseName: '', date: '', startTime: '09:00', endTime: '10:00' })
  schDialog.value = true
}

async function saveSchedule() {
  if (!currentStudent.value) return
  try {
    await api.createCourseSchedule({
      studentId: currentStudent.value.id,
      courseName: schForm.courseName,
      date: schForm.date,
      startMin: toMin(schForm.startTime),
      endMin: toMin(schForm.endTime)
    })
    ElMessage.success('排课成功')
    schDialog.value = false
    await loadSchedules(currentStudent.value.id); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function cancelSchedule(row) {
  try {
    const body = await api.cancelCourseSchedule(row.id)
    ElMessage.success(body.message || '已取消')
    await loadSchedules(currentStudent.value.id); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}
async function checkinSchedule(row) {
  try { await api.checkinCourseSchedule(row.id); ElMessage.success('已签到上课'); await loadSchedules(currentStudent.value.id); emit('changed') } catch (e) { ElMessage.error(e.message) }
}

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

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="hint">双击任意学员行，右侧抽屉展开其档案与排课</span>
      <el-button type="primary" @click="addStudent">新增学员</el-button>
    </div>

    <el-table :data="students" v-loading="loading" border stripe @row-dblclick="openDrawer" row-class-name="row-clickable">
      <el-table-column prop="code" label="学号" width="120" />
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column prop="phone" label="手机" width="140" />
      <el-table-column prop="level" label="级别" width="90">
        <template #default="{ row }"><el-tag>{{ row.level }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="editStudent(row)">编辑</el-button>
          <el-button link type="danger" @click="removeStudent(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑学员' : '新增学员'" width="420px">
      <el-form label-width="72px">
        <el-form-item label="学号"><el-input v-model="form.code" placeholder="如 S2026007" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" placeholder="1[3-9] 开头 11 位" /></el-form-item>
        <el-form-item label="级别">
          <el-select v-model="form.level"><el-option v-for="l in LEVELS" :key="l" :label="l" :value="l" /></el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status"><el-option v-for="s in STATUS" :key="s" :label="s" :value="s" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveStudent">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" size="420px" :title="currentStudent ? currentStudent.name + ' · 档案' : '学员档案'">
      <template v-if="currentStudent">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="学号">{{ currentStudent.code }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ currentStudent.name }}</el-descriptions-item>
          <el-descriptions-item label="手机">{{ currentStudent.phone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="级别">{{ currentStudent.level }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(currentStudent.status)">{{ currentStudent.status }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="drawer-head">
          <span>课程排期（{{ schedules.length }}）</span>
          <el-button type="primary" size="small" @click="addSchedule">排课</el-button>
        </div>

        <el-table :data="schedules" v-loading="scheduleLoading" border size="small">
          <el-table-column prop="courseName" label="课程" />
          <el-table-column prop="date" label="日期" width="92" />
          <el-table-column label="时段" width="100">
            <template #default="{ row }">{{ fmtMin(row.startMin) }}–{{ fmtMin(row.endMin) }}</template>
          </el-table-column>
          <el-table-column label="课次" width="76">
            <template #default="{ row }"><el-tag size="small" :type="scheduleStatusType(row.status)">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="课后记录" width="120">
            <template #default="{ row }">
              <el-tag v-if="recordMap[row.id]?.status" size="small" :type="recordStatusType(recordMap[row.id].status)">
                {{ recordMap[row.id].status }}
              </el-tag>
              <span v-else class="muted">{{ row.status === '已上课' ? '未填写' : '—' }}</span>
              <div v-if="recordMap[row.id]?.updatedAt" class="time">{{ fmtTime(recordMap[row.id].updatedAt) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openRecord(row)">课后记录</el-button>
              <el-button v-if="row.status === '待上课'" link type="success" size="small" @click="checkinSchedule(row)">上课</el-button>
              <el-button v-if="row.status === '待上课'" link type="danger" size="small" @click="cancelSchedule(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="currentStudent.status !== '在读'" class="tip">该学员当前非「在读」，无法继续排课。</p>
        <p class="tip">提示：课后记录只有「已上课」课次可填写；草稿不算结课，须提交后才计入已完成课程。</p>
      </template>

      <el-dialog v-model="schDialog" title="排课" width="360px" append-to-body>
        <el-form label-width="72px">
          <el-form-item label="课程"><el-input v-model="schForm.courseName" /></el-form-item>
          <el-form-item label="日期"><el-date-picker v-model="schForm.date" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="开始"><el-time-picker v-model="schForm.startTime" format="HH:mm" value-format="HH:mm" /></el-form-item>
          <el-form-item label="结束"><el-time-picker v-model="schForm.endTime" format="HH:mm" value-format="HH:mm" /></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="schDialog = false">取消</el-button>
          <el-button type="primary" @click="saveSchedule">排课</el-button>
        </template>
      </el-dialog>

      <CourseRecordDialog v-model="recordVisible" :schedule="recordSchedule"
        :student-name="currentStudent ? currentStudent.name : ''"
        @changed="currentStudent && loadSchedules(currentStudent.id)" />
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.row-clickable { cursor: pointer; }
.drawer-head { display: flex; align-items: center; justify-content: space-between; margin: 18px 0 10px; font-weight: 600; }
.tip { color: var(--muted); font-size: 12px; margin-top: 10px; }
.muted { color: var(--muted); font-size: 12px; }
.time { color: var(--muted); font-size: 11px; margin-top: 2px; }
</style>
