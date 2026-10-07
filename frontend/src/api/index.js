import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

// 返回完整 body（含 message），用于需要区分「本次成功」与「重复请求幂等命中」的操作
async function callFull(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 学员 ----------------
  listStudents: (status, level) => call(http.get('/students', params(
    status ? { status, ...(level ? { level } : {}) }
            : (level ? { level } : {})))),
  createStudent: (d) => call(http.post('/students', d)),
  updateStudent: (id, d) => call(http.put(`/students/${id}`, d)),
  deleteStudent: (id) => call(http.delete(`/students/${id}`)),

  // ---------------- 画材 ----------------
  listArtMaterials: (category, status) => call(http.get('/art-materials', params(
    category ? { category, ...(status ? { status } : {}) }
              : (status ? { status } : {})))),
  createArtMaterial: (d) => call(http.post('/art-materials', d)),
  updateArtMaterial: (id, d) => call(http.put(`/art-materials/${id}`, d)),

  // ---------------- 课程排期 ----------------
  listCourseSchedules: (studentId, status) => call(http.get('/course-schedules', params(
    studentId ? { studentId, ...(status ? { status } : {}) }
              : (status ? { status } : {})))),
  createCourseSchedule: (d) => call(http.post('/course-schedules', d)),
  cancelCourseSchedule: (id) => callFull(http.post(`/course-schedules/${id}/cancel`)),
  checkinCourseSchedule: (id) => call(http.post(`/course-schedules/${id}/checkin`)),

  // ---------------- 课后成果记录（按课次幂等） ----------------
  listCourseRecords: (studentId, status, scheduleId) => call(http.get('/course-records', params({
    ...(studentId ? { studentId } : {}),
    ...(status ? { status } : {}),
    ...(scheduleId ? { scheduleId } : {})
  }))),
  getCourseRecord: (scheduleId) => call(http.get(`/course-schedules/${scheduleId}/record`)),
  saveDraftRecord: (scheduleId, d) => call(http.post(`/course-schedules/${scheduleId}/record/draft`, d || {})),
  submitCourseRecord: (scheduleId, d) => callFull(http.post(`/course-schedules/${scheduleId}/record/submit`, d || {})),
  archiveCourseRecord: (scheduleId) => callFull(http.post(`/course-schedules/${scheduleId}/record/archive`)),
  deleteDraftRecord: (scheduleId) => call(http.delete(`/course-schedules/${scheduleId}/record`)),

  // ---------------- 领用记录 ----------------
  listMaterialUses: () => call(http.get('/material-uses')),
  createMaterialUse: (d) => call(http.post('/material-uses', d))
}
