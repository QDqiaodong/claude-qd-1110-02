#!/usr/bin/env node
/**
 * 排课 · 取消与重排状态闭环 · 一键验收脚本
 *
 * 用法：
 *   sh start.sh                       # 先把栈起起来
 *   node acceptance/acceptance-schedule.mjs
 *   BASE=http://127.0.0.1:8420 node acceptance/acceptance-schedule.mjs
 *
 * 覆盖链路：
 *   A 正常取消后原时段立即可重排；已取消记录保留但不参与重叠校验
 *   B 边界相邻时段允许、严格重叠（含被已取消课次夹住）拦截
 *   C 重复取消给明确原因、幂等不二次写入
 *   D 并发取消与重排：最终状态稳定（已取消不拦截重排，且不会两条有效课次占同一时段）
 *   E 并发两次排课 / 取消与签到竞争：只允许一个胜出
 *   F 已上课不可取消；签到与课后记录不被取消动作影响
 *   G 取消后历史可查（列表、按学员、按状态、重复刷新状态一致）
 *   H 跨学员时段互不干扰；不存在课次给明确原因
 */

const BASE = process.env.BASE || 'http://127.0.0.1:8420'
const TS = Date.now()

let pass = 0
let fail = 0
const failures = []

function ok(name, cond, extra = '') {
  if (cond) { pass++; console.log(`  \x1b[32m✓\x1b[0m ${name}`) }
  else { fail++; failures.push(name); console.log(`  \x1b[31m✗ ${name}${extra ? ' — ' + extra : ''}\x1b[0m`) }
}
function section(t) { console.log(`\n=== ${t} ===`) }

async function api(method, path, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined
  })
  let json = null
  try { json = await res.json() } catch { /* 非 JSON */ }
  return { status: res.status, json }
}

async function mustFail(name, p, msgPart, httpStatus) {
  const r = await p
  const good = r.json && r.json.ok === false && r.json.message && r.json.message.includes(msgPart)
    && (httpStatus == null || r.status === httpStatus)
  ok(name, good, `got status=${r.status} ${JSON.stringify(r.json)}`)
  return r
}

async function getSchedule(id) {
  const r = await api('GET', '/api/course-schedules')
  const all = r.json.data
  return all.find((x) => x.id === id) || null
}

async function getStudentSchedules(studentId, status) {
  const path = `/api/course-schedules?studentId=${studentId}`
    + (status ? `&status=${encodeURIComponent(status)}` : '')
  const r = await api('GET', path)
  return r.json.data
}

async function createStudent(tag, status = '在读') {
  const code = `S${TS}${tag}`.slice(0, 16)
  const r = await api('POST', '/api/students',
    { code, name: `排课验收${tag}`, phone: '13800138000', level: '成人', status })
  if (!r.json?.ok) throw new Error('建学员失败：' + JSON.stringify(r.json))
  return r.json.data
}

async function book(studentId, date, s, e, courseName = '验收课') {
  return api('POST', '/api/course-schedules',
    { studentId, courseName, date, startMin: s, endMin: e })
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

// 按本地时区取「N 天后」的 YYYY-MM-DD（容器 TZ=Asia/Shanghai，不能用 toISOString 的 UTC 日期）
function localDateAfter(days) {
  const d = new Date(Date.now() + days * 86400 * 1000)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function main() {
  console.log(`目标后端：${BASE}`)

  // ---------------------------------------------------------------- 0. 连通
  section('0. 连通性')
  const ping = await api('GET', '/api/students')
  ok('后端可访问且返回 {ok:true}', ping.json?.ok === true, JSON.stringify(ping.json))
  if (!ping.json?.ok) process.exit(1)

  const future = localDateAfter(5)
  const otherDate = localDateAfter(6)

  const student = await createStudent('A')
  const studentB = await createStudent('B')

  // ---------------------------------------------------------------- A. 正常取消后重排
  section('A. 取消后原时段立即释放，可重新排课')
  let r = await book(student.id, future, 540, 600, '将取消的课')
  ok('排课成功（待上课）', r.json?.data?.status === '待上课')
  const schA = r.json.data

  r = await api('POST', `/api/course-schedules/${schA.id}/cancel`)
  ok('待上课课次取消成功', r.json?.data?.status === '已取消')

  // 取消前原时段不能重排（同 id 尚为待上课的对照已在上面建立；这里直接验证取消后立即可排）
  r = await book(student.id, future, 540, 600, '重排的课')
  ok('取消后原日期时段可立即重新排课', r.json?.ok === true && r.json.data?.status === '待上课',
    JSON.stringify(r.json))
  const schARebook = r.json.data
  ok('重排生成的是新课次（旧记录保留，未被复用/覆盖）', schARebook.id !== schA.id)

  r = await api('GET', `/api/course-schedules/${schA.id}/record`)
  ok('旧课次仍可按 id 追溯（记录视图带已取消快照）',
    r.json?.data?.scheduleId === schA.id && r.json.data.scheduleStatus === '已取消')

  // 再次在该时段排课应被重排后的待上课课次拦截（证明释放的是「已取消」而非所有历史）
  await mustFail('已重排后该时段再次排课被有效课次拦截',
    book(student.id, future, 540, 600, '想再插一节'), '重叠')

  // ---------------------------------------------------------------- B. 边界相邻时段
  section('B. 边界相邻时段允许，真重叠拦截（含被已取消课次夹住）')
  // 重排课占 09:00-10:00；10:00-11:00 相邻应允许
  r = await book(student.id, future, 600, 660, '相邻课')
  ok('紧邻结束时刻（10:00 起）可排', r.json?.ok === true, JSON.stringify(r.json))
  // 08:00-09:00 相邻应允许
  r = await book(student.id, future, 480, 540, '早间相邻')
  ok('紧邻开始时刻（09:00 止）可排', r.json?.ok === true, JSON.stringify(r.json))
  // 真重叠：09:30-09:45
  await mustFail('分钟级真重叠（09:30-09:45）被拦截',
    book(student.id, future, 570, 585, '重叠课'), '重叠')
  // 被已取消课次「夹住」的时段：取消 10:00-11:00，再排 09:30-10:30 仍应只对有效课判重
  const adjacent = (await getStudentSchedules(student.id)).find((x) => x.startMin === 600 && x.endMin === 660)
  r = await api('POST', `/api/course-schedules/${adjacent.id}/cancel`)
  ok('相邻课取消成功', r.json?.data?.status === '已取消')
  await mustFail('跨已取消课次、与有效课重叠（09:30-10:30）仍被拦截',
    book(student.id, future, 570, 630, '跨取消课'), '重叠')
  // 完全落在已取消课次的时段内、且不碰任何有效课：10:00-11:00 可排
  r = await book(student.id, future, 600, 660, '填回取消时段')
  ok('已取消课次占用过的时段可被新课次完整复用', r.json?.ok === true, JSON.stringify(r.json))

  // ---------------------------------------------------------------- C. 重复取消
  section('C. 重复取消 / 终态操作给出明确原因')
  r = await api('POST', `/api/course-schedules/${schA.id}/cancel`)
  ok('重复取消被拒绝', r.json?.ok === false, JSON.stringify(r.json))
  ok('重复取消原因明确（已是已取消 / 无需重复）',
    r.json?.message?.includes('已取消') && r.json.message.includes('重复'), r.json?.message)
  const again = await getSchedule(schA.id)
  ok('重复取消未改动记录，状态仍为已取消', again && again.status === '已取消')
  await mustFail('已取消课次不能签到',
    api('POST', `/api/course-schedules/${schA.id}/checkin`), '已取消')

  // ---------------------------------------------------------------- D. 并发取消与重排
  section('D. 取消与重排同时发生：按最终状态稳定判定')

  async function raceCancelAndBook(tag) {
    const base = (await book(student.id, otherDate, 540 + tag, 600 + tag, `竞态课${tag}`)).json.data
    // 同一会话日下只允许该课存在；并发发起「取消旧课」与「在同一时段排新课」
    const [cancelRes, bookRes] = await Promise.all([
      api('POST', `/api/course-schedules/${base.id}/cancel`),
      book(student.id, otherDate, 540 + tag, 600 + tag, `竞态新课${tag}`)
    ])
    return { base, cancelRes, bookRes, tag }
  }

  // 连续跑 3 轮，每轮换新时段。
  // 服务端用学员行锁把两请求按到达顺序串行：若取消先到则重排直接成功；
  // 若重排先到（此时旧课还是待上课），会被重叠正确拒绝——客户端按提示刷新
  // 看到「已取消」后重试重排。两种到达顺序下最终态都必须一致：旧课已取消、
  // 新槽位恰好一条有效课次，且不允许出现"最终已取消却永远排不进去"。
  for (let i = 0; i < 3; i++) {
    const tag = 700 + i * 20
    const { base, cancelRes, bookRes } = await raceCancelAndBook(tag)
    let rebook = bookRes
    if (!rebook.json?.ok && (rebook.status === 409 || (rebook.status === 400
        && rebook.json.message.includes('重叠')))) {
      await sleep(30)
      // 模拟教务收到提示后刷新：确认旧课已取消，再在原时段重排
      const refreshed = await getSchedule(base.id)
      ok(`第${i + 1}轮：刷新后旧课次确为已取消（并发按最终状态判定）`,
        refreshed && refreshed.status === '已取消', `refreshed=${refreshed?.status}`)
      rebook = await book(student.id, otherDate, 540 + tag, 600 + tag, `竞态新课补${i}`)
    }
    const finalOld = await getSchedule(base.id)
    const list = await getStudentSchedules(student.id)
    const sameSlotActive = list.filter((x) => x.date === otherDate
      && x.startMin === 540 + tag && x.endMin === 600 + tag
      && x.status !== '已取消')
    ok(`第${i + 1}轮：旧课次最终为已取消`, cancelRes.json?.ok === true && finalOld.status === '已取消',
      `cancel=${JSON.stringify(cancelRes.json)} final=${finalOld?.status}`)
    ok(`第${i + 1}轮：重排最终成功（已取消不被误拦）`, rebook.json?.ok === true
      && rebook.json.data?.status === '待上课', `book=${JSON.stringify(rebook.json)}`)
    ok(`第${i + 1}轮：同一时段只有一条有效课次`, sameSlotActive.length === 1,
      `active=${sameSlotActive.map((x) => x.id + ':' + x.status).join(',')}`)
  }

  // 两个完全相同的排课请求并发：至多一条成功
  section('E. 并发两次排课同一时段：只允许一条有效课次')
  const d2 = localDateAfter(7)
  const pair = await Promise.all([
    book(student.id, d2, 720, 780, '并发双排'),
    book(student.id, d2, 720, 780, '并发双排')
  ])
  const successCount = pair.filter((x) => x.json?.ok).length
  ok('并发双排恰有一条成功', successCount === 1, pair.map((x) => JSON.stringify(x.json)).join(' | '))
  const loser = pair.find((x) => !x.json?.ok)
  ok('落败请求被明确拒绝（重叠提示，非 5xx、非脏写）',
    loser && loser.status === 400 && loser.json.message.includes('重叠'),
    loser ? `${loser.status} ${loser.json.message}` : '两条都成功了')
  const activeD2 = (await getStudentSchedules(student.id))
    .filter((x) => x.date === d2 && x.startMin === 720 && x.endMin === 780 && x.status !== '已取消')
  ok('落库后该时段有效课次唯一', activeD2.length === 1,
    `active=${activeD2.map((x) => x.id).join(',')}`)

  // 取消与签到竞争同一课次：终态只能二选一，且终态不可逆
  section('E2. 取消与签到同时竞争：终态唯一且不可逆')
  const raceSch = (await book(student.id, d2, 780, 840, '取消签到竞争')).json.data
  const [cRes, kRes] = await Promise.all([
    api('POST', `/api/course-schedules/${raceSch.id}/cancel`),
    api('POST', `/api/course-schedules/${raceSch.id}/checkin`)
  ])
  const finals = await getSchedule(raceSch.id)
  const winners = [cRes, kRes].filter((x) => x.json?.ok).length
  ok('竞争中恰有一个操作成功', winners === 1,
    `cancel=${JSON.stringify(cRes.json)} | checkin=${JSON.stringify(kRes.json)}`)
  ok(['已取消', '已上课'].includes(finals.status), `final=${finals.status}`)
  if (finals.status === '已上课') {
    await mustFail('已上课终态不可再取消',
      api('POST', `/api/course-schedules/${raceSch.id}/cancel`), '已上课')
  } else {
    await mustFail('已取消终态不可再签到',
      api('POST', `/api/course-schedules/${raceSch.id}/checkin`), '已取消')
    // 取消胜出 → 时段应可重排（D 场景在「竞争」语境下再验证一次）
    const rr = await book(student.id, d2, 780, 840, '取消胜出后重排')
    ok('取消胜出后原时段可重排', rr.json?.ok === true, JSON.stringify(rr.json))
  }
  const finals2 = await getSchedule(raceSch.id)
  ok('终态稳定（再次查询未翻转）', finals2.status === finals.status)

  // ---------------------------------------------------------------- F. 已上课不可取消，课后记录不受影响
  section('F. 已上课不可取消，签到与课后记录不被复用/误删')
  const schF = (await book(student.id, d2, 840, 900, '已上课保护')).json.data
  r = await api('POST', `/api/course-schedules/${schF.id}/checkin`)
  ok('签到成功（已上课）', r.json?.data?.status === '已上课')
  r = await api('POST', `/api/course-schedules/${schF.id}/record/draft`,
    { workTopic: '课后作品', completion: 92, teacherComment: '表现稳定' })
  ok('已上课课次可建立课后记录', r.json?.data?.status === '草稿', JSON.stringify(r.json))
  const recordId = r.json.data.id
  r = await api('POST', `/api/course-schedules/${schF.id}/cancel`)
  ok('已上课课次取消被拒绝', r.json?.ok === false && r.status === 400, JSON.stringify(r.json))
  ok('拒绝原因明确（已签到上课 / 不能取消）', /已签到上课|已上课/.test(r.json?.message))
  const fStill = await getSchedule(schF.id)
  ok('被拦截后课次仍是已上课（签到未被改成可复用时段）', fStill.status === '已上课')
  r = await api('GET', `/api/course-schedules/${schF.id}/record`)
  ok('课后记录原样保留，未受取消尝试影响',
    r.json?.data?.id === recordId && r.json.data.status === '草稿'
    && r.json.data.scheduleStatus === '已上课', JSON.stringify(r.json))
  // 已上课课次仍占时段：同时间段重排被拦
  await mustFail('已上课课次的时段不可被新排课占用',
    book(student.id, d2, 870, 930, '挤占已上课'), '重叠')

  // ---------------------------------------------------------------- G. 历史查看 & 多视图一致
  section('G. 已取消记录保留供历史查看，列表/抽屉/刷新状态一致')
  const allList = await getStudentSchedules(student.id)
  const histA = allList.find((x) => x.id === schA.id)
  ok('学员全部课次列表含已取消历史', histA && histA.status === '已取消')
  const cancelledOnly = await getStudentSchedules(student.id, '已取消')
  ok('按「已取消」筛选能看到历史记录', cancelledOnly.some((x) => x.id === schA.id))
  const activeOnly = await getStudentSchedules(student.id, '待上课')
  ok('按「待上课」筛选不含已取消', !activeOnly.some((x) => x.id === schA.id))
  // 全局列表（排课页，不带 studentId）
  const globalList = (await api('GET', '/api/course-schedules')).json.data
  ok('全局列表同样保留已取消历史', globalList.some((x) => x.id === schA.id && x.status === '已取消'))
  // 连续 3 次刷新，所有相关课次状态一致
  let consistent = true
  for (let i = 0; i < 3; i++) {
    const [one, two] = await Promise.all([getSchedule(schA.id), getSchedule(schF.id)])
    if (!one || one.status !== '已取消' || !two || two.status !== '已上课') consistent = false
    await sleep(50)
  }
  ok('重复刷新状态稳定一致（已取消 / 已上课不漂移）', consistent)

  // ---------------------------------------------------------------- H. 跨学员隔离 & 不存在
  section('H. 跨学员时段互不干扰；不存在课次给明确原因')
  r = await book(studentB.id, future, 540, 600, '另一学员同时段')
  ok('不同学员可占用同一日期时段', r.json?.ok === true, JSON.stringify(r.json))
  const bSch = r.json.data
  const aOwnList = await getStudentSchedules(student.id)
  ok('另一学员的课次不会混进本学员档案抽屉', !aOwnList.some((x) => x.id === bSch.id))
  const bOwnList = await getStudentSchedules(studentB.id)
  ok('本学员档案能看到自己的新课次', bOwnList.some((x) => x.id === bSch.id && x.status === '待上课'))

  await mustFail('取消不存在的课次给出明确原因',
    api('POST', '/api/course-schedules/99999999/cancel'), '不存在')
  await mustFail('签到不存在的课次给出明确原因',
    api('POST', '/api/course-schedules/99999999/checkin'), '不存在')

  // 非在读学员排课门禁（状态闭环的入口前提）
  const resting = await createStudent('R', '休学')
  await mustFail('休学学员不能排课',
    book(resting.id, future, 540, 600), '在读')

  // ---------------------------------------------------------------- 汇总
  console.log(`\n${'='.repeat(48)}`)
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  if (fail) { console.log('失败项：\n - ' + failures.join('\n - ')); process.exit(1) }
  console.log('\x1b[32m全部验收通过 ✅\x1b[0m')
}

main().catch((e) => { console.error('验收脚本异常：', e); process.exit(2) })
