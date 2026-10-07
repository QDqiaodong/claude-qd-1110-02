<script setup>
// 课后成果记录弹窗：课程排期列表与学员档案抽屉共用。
// 以课次为幂等键，提交按钮在请求返回前禁用，防止重复点击。
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 课次行（必传）：id / courseName / date / startMin / endMin / status / studentId
  schedule: { type: Object, default: null },
  // 可选：学员名（档案抽屉里已有；排期页没有则用学号占位）
  studentName: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'changed'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const saving = ref(false)
const submitting = ref(false)
const record = ref(null) // 后端视图；无记录时为带快照的空壳，status === null
const form = reactive({ workTopic: '', completion: null, teacherComment: '', nextPractice: '' })

const recordStatusType = (s) => ({ 草稿: 'warning', 已提交: 'success', 已归档: 'info' }[s] || 'info')

const isDraft = computed(() => record.value?.status === '草稿')
const isSubmitted = computed(() => record.value?.status === '已提交')
const isArchived = computed(() => record.value?.status === '已归档')
const readonly = computed(() => isArchived.value)

const fmtMin = (n) => {
  if (n == null) return ''
  const h = Math.floor(n / 60); const m = n % 60
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0')
}
function fmtTime(t) {
  if (!t) return '—'
  return String(t).replace('T', ' ').slice(0, 16)
}

function fillForm(r) {
  form.workTopic = r.workTopic || ''
  form.completion = r.completion ?? null
  form.teacherComment = r.teacherComment || ''
  form.nextPractice = r.nextPractice || ''
}

async function load() {
  if (!props.schedule?.id) return
  loading.value = true
  try {
    record.value = await api.getCourseRecord(props.schedule.id)
    fillForm(record.value)
  } catch (e) {
    ElMessage.error(e.message)
  } finally { loading.value = false }
}

watch(() => [props.modelValue, props.schedule?.id], ([open]) => {
  if (open) load()
})

function payload() {
  return {
    workTopic: form.workTopic || null,
    completion: form.completion == null ? null : Number(form.completion),
    teacherComment: form.teacherComment || null,
    nextPractice: form.nextPractice || null
  }
}

function closeAndNotify() { visible.value = false; emit('changed') }

async function saveDraft() {
  saving.value = true
  try {
    const r = await api.saveDraftRecord(props.schedule.id, payload())
    record.value = r
    fillForm(r)
    ElMessage.success('草稿已保存，补齐作业信息后可提交')
    emit('changed')
  } catch (e) { ElMessage.error(e.message) } finally { saving.value = false }
}

async function submitRecord() {
  if (!form.workTopic?.trim() || form.completion == null || !form.teacherComment?.trim()) {
    ElMessage.error('提交前请补齐：作品主题、完成度、教师点评')
    return
  }
  submitting.value = true
  try {
    // 后端在同一事务内幂等收口：重复点击 / 并发提交都返回同一份记录
    const body = await api.submitCourseRecord(props.schedule.id, payload())
    record.value = body.data
    fillForm(body.data)
    ElMessage.success(body.message || '已提交')
    emit('changed')
  } catch (e) { ElMessage.error(e.message) } finally { submitting.value = false }
}

async function archiveRecord() {
  try {
    await ElMessageBox.confirm('归档后记录将转为只读，确认归档？', '归档确认', {
      confirmButtonText: '归档', cancelButtonText: '再看看', type: 'warning'
    })
  } catch { return }
  try {
    const body = await api.archiveCourseRecord(props.schedule.id)
    record.value = body.data
    fillForm(body.data)
    ElMessage.success(body.message || '已归档')
    emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

async function removeDraft() {
  try {
    await ElMessageBox.confirm('删除后该课次将没有任何课后记录（课次本身与库存、学员状态不受影响）。确认删除草稿？',
      '删除草稿', { confirmButtonText: '删除', cancelButtonText: '保留', type: 'warning' })
  } catch { return }
  try {
    await api.deleteDraftRecord(props.schedule.id)
    ElMessage.success('草稿已删除')
    closeAndNotify()
  } catch (e) { ElMessage.error(e.message) }
}
</script>

<template>
  <el-dialog v-model="visible" title="课后成果记录" width="620px" :close-on-click-modal="false">
    <div v-loading="loading">
      <!-- 课次 / 学员上下文 + 当前状态 -->
      <div class="ctx" v-if="schedule">
        <div class="ctx-line">
          <span class="ctx-title">{{ schedule.courseName }}</span>
          <el-tag size="small" :type="schedule.status === '已上课' ? 'success' : 'info'">
            课次 · {{ schedule.status }}
          </el-tag>
          <el-tag v-if="record && record.status" size="small" :type="recordStatusType(record.status)">
            记录 · {{ record.status }}
          </el-tag>
          <el-tag v-else size="small" type="info">记录 · 未填写</el-tag>
        </div>
        <div class="ctx-sub">
          <span>{{ schedule.date }} {{ fmtMin(schedule.startMin) }}–{{ fmtMin(schedule.endMin) }}</span>
          <span>学员：{{ studentName || record?.studentName || schedule.studentId }}</span>
          <span v-if="record?.studentStatus">学员当前状态：{{ record.studentStatus }}</span>
        </div>
        <div class="ctx-sub" v-if="record && record.status">
          <span>创建：{{ fmtTime(record.createdAt) }}</span>
          <span>最后更新：{{ fmtTime(record.updatedAt) }}</span>
          <span v-if="record.submittedAt">提交：{{ fmtTime(record.submittedAt) }}</span>
          <span v-if="record.archivedAt">归档：{{ fmtTime(record.archivedAt) }}</span>
        </div>
      </div>

      <el-alert v-if="isArchived" type="info" :closable="false" show-icon
        title="该记录已归档，内容只读，不能再修改。" class="bar" />
      <el-alert v-else-if="isSubmitted" type="success" :closable="false" show-icon
        title="记录已提交，课次结课资料齐全；教务归档后转为只读。" class="bar" />
      <el-alert v-else type="warning" :closable="false" show-icon
        title="草稿可只先记教师点评；提交时必须补齐作品主题、完成度、教师点评。" class="bar" />

      <el-form label-width="92px" class="rec-form">
        <el-form-item label="作品主题">
          <el-input v-model="form.workTopic" :disabled="readonly || isSubmitted" maxlength="128" show-word-limit
            placeholder="如：静物水彩 · 苹果与陶罐" />
        </el-form-item>
        <el-form-item label="完成度">
          <el-input-number v-model="form.completion" :min="0" :max="100" :step="5"
            :disabled="readonly || isSubmitted" />
          <span class="unit">%</span>
        </el-form-item>
        <el-form-item label="教师点评">
          <el-input v-model="form.teacherComment" type="textarea" :rows="3"
            :disabled="readonly || isSubmitted" maxlength="1000" show-word-limit
            placeholder="可以先快速记下点评，稍后补齐作业信息再提交" />
        </el-form-item>
        <el-form-item label="下次练习">
          <el-input v-model="form.nextPractice" type="textarea" :rows="2"
            :disabled="readonly || isSubmitted" maxlength="1000" show-word-limit
            placeholder="选填，给学员的课后练习建议" />
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <template v-if="isDraft">
        <el-button type="danger" plain :loading="saving" @click="removeDraft">删除草稿</el-button>
        <el-button :loading="saving" @click="saveDraft">保存草稿</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRecord">提交</el-button>
      </template>
      <template v-else-if="isSubmitted">
        <el-button @click="visible = false">关闭</el-button>
        <el-button type="primary" @click="archiveRecord">归档</el-button>
      </template>
      <template v-else-if="isArchived">
        <el-button type="primary" @click="visible = false">知道了</el-button>
      </template>
      <template v-else>
        <!-- 尚无记录 -->
        <el-button @click="visible = false">取消</el-button>
        <el-button :loading="saving" @click="saveDraft">先存点评（草稿）</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRecord">补齐并提交</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<style scoped>
.ctx { background: var(--primary-wash); border-radius: 8px; padding: 10px 12px; margin-bottom: 12px; }
.ctx-line { display: flex; align-items: center; gap: 8px; }
.ctx-title { font-weight: 600; margin-right: 4px; }
.ctx-sub { display: flex; flex-wrap: wrap; gap: 14px; color: var(--muted); font-size: 12px; margin-top: 6px; }
.bar { margin-bottom: 12px; }
.rec-form { margin-top: 4px; }
.unit { margin-left: 8px; color: var(--muted); }
</style>
