#!/usr/bin/env node
/**
 * 排课 / 取消状态闭环 · 一键验收脚本
 *
 * 用法：
 *   sh start.sh
 *   node acceptance/schedule-acceptance.mjs
 *   BASE=http://127.0.0.1:8420 node acceptance/schedule-acceptance.mjs
 *
 * 覆盖链路（对应需求验收点）：
 *   1 正常取消后重排：待上课 → 取消（记录保留）→ 同一日期同一时段立即可重排
 *   2 边界相邻时段：首尾相接（10:00-11:00 接 09:00-10:00）不冲突；真正重叠 / 跨天隔离
 *   3 重复取消：再次取消幂等返回同一条「已取消」，已上课/取消中的课次不能签到
 *   4 并发取消与排课：同槽重排最终恰一条有效课次；并发双开同槽恰一条；
 *                    取消与签到同时发生最终状态唯一；不同槽互不误伤
 *   5 已上课不可取消：签到后取消被明确拒绝，签到与课后记录不被改成可复用时段
 *   6 取消后查看历史：已取消记录仍可按状态/按学员/按 id 查到，课后记录视图正常
 *   7 多视图一致：列表、学员档案抽屉（按学员接口）、刷新后状态口径一致
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

async function mustFail(name, p, msgPart, expectStatus = 400) {
  const r = await p
  const good = r.status === expectStatus && r.json && r.json.ok === false
    && r.json.message && r.json.message.includes(msgPart)
  ok(name, good, `got ${r.status} ${JSON.stringify(r.json)}`)
  return r
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const q = (s) => encodeURIComponent(s)

// 并发冲突（409，行锁等待）重试：系统按最终状态裁决，重试后请求会读到对方已落库的状态
async function withRetry(fn, tries = 6) {
  let last
  for (let i = 0; i < tries; i++) {
    last = await fn()
    if (last.status !== 409) return last
    await sleep(120 * (i + 1))
  }
  return last
}

// 按学员 + 日期捞出课次（接口没有日期过滤，客户端过滤）
async function schedulesOf(studentId, date) {
  const r = await api('GET', `/api/course-schedules?studentId=${studentId}`)
  const all = r.json.data || []
  return date ? all.filter((x) => x.date === date) : all
}

async function main() {
  console.log(`目标后端：${BASE}`)

  // ---------------------------------------------------------------- 0. 连通 & 准备
  section('0. 连通性与准备')
  const ping = await api('GET', '/api/students')
  ok('后端可访问且返回 {ok:true}', ping.json?.ok === true, JSON.stringify(ping.json))
  if (!ping.json?.ok) process.exit(1)

  const DAY = 86400 * 1000
  const future = new Date(Date.now() + DAY * 5).toISOString().slice(0, 10)
  const future2 = new Date(Date.now() + DAY * 6).toISOString().slice(0, 10)
  const code = `R${TS}`.slice(0, 16)
  let r = await api('POST', '/api/students',
    { code, name: '排课验收甲', phone: '13800138001', level: '成人', status: '在读' })
  ok('新建在读学员成功', r.json?.ok && r.json.data?.status === '在读', JSON.stringify(r.json))
  const stu = r.json.data

  const book = (studentId, date, startMin, endMin, name = '验收课') =>
    api('POST', '/api/course-schedules', { studentId, courseName: name, date, startMin, endMin })

  // ================================================= 1. 正常取消后立即重排（同槽）
  section('1. 正常取消：原日期时段立即可重新排课，取消记录保留')
  r = await book(stu.id, future, 540, 600, '取消后重排课')
  ok('排课成功（待上课）', r.json?.data?.status === '待上课')
  const s1 = r.json.data

  // 取消前：同槽重排必须被挡（仍有有效课次占用）
  await mustFail('取消前同槽再排被拦截',
    book(stu.id, future, 540, 600, '取消后重排课2'), '重叠')

  r = await api('POST', `/api/course-schedules/${s1.id}/cancel`)
  ok('取消成功', r.json?.data?.status === '已取消'
    && r.json.message.includes('释放'), `message=${r.json?.message}`)
  const canceledId = s1.id

  // 关键闭环：同日期同时段立即可以重新排课
  r = await book(stu.id, future, 540, 600, '重排新课')
  ok('取消后原日期时段立即可重排（同槽成功）',
    r.json?.ok && r.json.data?.status === '待上课', JSON.stringify(r.json))
  const s2 = r.json.data
  ok('重排产生的是新课次，原取消记录未被覆盖/删除', s2.id !== canceledId)

  // 重排后同槽仍不能再开第三条有效课次
  await mustFail('重排成功后同槽再排仍被拦截',
    book(stu.id, future, 540, 600, '再来一节'), '重叠')

  // 已取消记录保留
  r = await api('GET', `/api/course-schedules/${canceledId}/record`)
  ok('已取消课次行仍可查询（历史保留）', r.json?.data?.scheduleId === canceledId
    && r.json.data.scheduleStatus === '已取消')
  r = await api('GET', `/api/course-schedules?studentId=${stu.id}&status=${q('已取消')}`)
  ok('按「已取消」过滤能看到原课次',
    Array.isArray(r.json.data) && r.json.data.some((x) => x.id === canceledId
      && x.startMin === 540 && x.endMin === 600))

  // ================================================= 2. 边界：相邻时段 & 真重叠
  section('2. 边界相邻时段：首尾相接不冲突，分钟级重叠冲突')
  r = await book(stu.id, future, 600, 660, '相邻前接课')
  ok('相邻时段 10:00-11:00 与 09:00-10:00 首尾相接可排', r.json?.ok, JSON.stringify(r.json))
  r = await book(stu.id, future, 480, 540, '相邻后接课')
  ok('相邻时段 08:00-09:00 与 09:00-10:00 首尾相接可排', r.json?.ok, JSON.stringify(r.json))
  await mustFail('重叠 1 分钟（09:59-10:30 vs 09:00-10:00）被拦截',
    book(stu.id, future, 539, 630, '擦边重叠'), '重叠')
  await mustFail('完全包含（09:10-09:50）被拦截',
    book(stu.id, future, 550, 590, '被包含'), '重叠')
  // 跨天隔离：同一天 09:00 已占，另一天 09:00 应可排
  r = await book(stu.id, future2, 540, 600, '次日同时段')
  ok('不同日期同一钟点互不冲突', r.json?.ok, JSON.stringify(r.json))
  // 已取消课次也不影响相邻判断
  r = await book(stu.id, future2, 600, 660, '次日相邻')
  ok('次日相邻时段可排', r.json?.ok, JSON.stringify(r.json))

  // ================================================= 3. 重复取消 & 取消后不可签到
  section('3. 重复取消幂等；取消后的课次不能签到')
  r = await api('POST', `/api/course-schedules/${canceledId}/cancel`)
  ok('重复取消幂等：仍返回同一条「已取消」',
    r.json?.ok && r.json.data?.id === canceledId && r.json.data.status === '已取消'
    && r.json.message.includes('已取消'), JSON.stringify(r.json))
  await mustFail('已取消课次不能再签到',
    api('POST', `/api/course-schedules/${canceledId}/checkin`), '已取消')

  // ================================================= 4. 并发：取消与排课同时发生
  section('4. 并发取消与排课（按最终状态稳定裁决）')

  // 4.1 「取消老课」与「同槽重排」同时发：最终只能有 1 条有效课次占用该槽
  r = await book(stu.id, future2, 660, 720, '并发取消对象')
  const target = r.json.data
  ok('并发准备：待上课课次已建', target?.status === '待上课')
  let concurrent = await Promise.all([
    api('POST', `/api/course-schedules/${target.id}/cancel`),
    withRetry(() => book(stu.id, future2, 660, 720, '并发同槽重排'))
  ])
  const cancelResp = concurrent[0]
  let rebookResp = concurrent[1]
  ok('并发中取消请求成功（已取消）', cancelResp.json?.data?.status === '已取消',
    JSON.stringify(cancelResp.json))

  // 裁决以最终状态为准：若重排恰好先于取消落库，当时段确实被占、返回 400 重叠是正确的；
  // 但取消落库后再排必须立即放行。尘埃落定后槽内有效课次必须恰为 1 条。
  let listAfter = await schedulesOf(stu.id, future2)
  let activeInSlot = listAfter
    .filter((x) => x.startMin < 720 && 660 < x.endMin)
    .filter((x) => x.status !== '已取消')
  if (activeInSlot.length === 0) {
    const again = await book(stu.id, future2, 660, 720, '并发后按最终状态重排')
    ok('取消落库后重排按最终状态放行（不会「明明已取消却仍被拦截」）',
      again.json?.ok && again.json.data?.status === '待上课', JSON.stringify(again.json))
    rebookResp = again
    listAfter = await schedulesOf(stu.id, future2)
    activeInSlot = listAfter
      .filter((x) => x.startMin < 720 && 660 < x.endMin)
      .filter((x) => x.status !== '已取消')
  }
  ok('同槽最终「待上课」课次恰 1 条（无双占）',
    activeInSlot.length === 1 && activeInSlot[0].status === '待上课',
    `active=${JSON.stringify(activeInSlot.map((x) => [x.id, x.status]))}`)
  ok('被取消的老课次保留为已取消',
    listAfter.some((x) => x.id === target.id && x.status === '已取消'))
  if (rebookResp.status !== 400) {
    ok('重排成功时其时段与老课次一致且为新记录',
      rebookResp.json?.ok && rebookResp.json.data.id !== target.id
      && rebookResp.json.data.startMin === 660 && rebookResp.json.data.endMin === 720)
  } else {
    ok('重排请求在「取消尚未落库」时被挡，返回明确重叠原因',
      rebookResp.json?.message?.includes('重叠'), JSON.stringify(rebookResp.json))
  }

  // 4.2 并发双开同一槽：N 个排课请求同时打，最终恰 1 条有效课次
  r = await book(stu.id, future2, 720, 780, '先占槽')
  const holder = r.json.data
  ok('并发准备 2：占位课次已建', holder?.status === '待上课')
  await api('POST', `/api/course-schedules/${holder.id}/cancel`)
  const N = 5
  const racerResults = await Promise.all(
    Array.from({ length: N }, () => withRetry(() => book(stu.id, future2, 720, 780, '并发抢槽')))
  )
  const winners = racerResults.filter((x) => x.json?.ok)
  const losers = racerResults.filter((x) => x.status === 400)
  ok(`N=${N} 个并发同槽排课仅 1 个成功`, winners.length === 1,
    `winners=${winners.length}, statuses=${racerResults.map((x) => x.status).join(',')}`)
  ok('落败请求都给出「重叠」明确原因',
    losers.every((x) => x.json?.message?.includes('重叠')),
    JSON.stringify(losers.map((x) => x.json?.message)))
  const afterRace = (await schedulesOf(stu.id, future2)).filter((x) => x.startMin < 780 && 720 < x.endMin)
  ok('并发后该槽仍只有 1 条有效课次（无两条有效课次同占）',
    afterRace.filter((x) => x.status !== '已取消').length === 1,
    JSON.stringify(afterRace.map((x) => [x.id, x.status])))

  // 4.3 取消与签到同时打同一条待上课：最终状态唯一（已取消 或 已上课，二选一），
  //     且若已上课则取消必失败（签到与课后记录不会被改成可复用时段）
  r = await book(stu.id, future2, 780, 840, '取消签到竞争')
  const race2 = r.json.data
  const both = await Promise.all([
    api('POST', `/api/course-schedules/${race2.id}/cancel`),
    withRetry(() => api('POST', `/api/course-schedules/${race2.id}/checkin`))
  ])
  const finalOne = (await schedulesOf(stu.id, future2)).find((x) => x.id === race2.id)
  const canceledWon = finalOne.status === '已取消'
  ok('取消与签到竞争后最终状态唯一（已取消/已上课）',
    finalOne.status === '已取消' || finalOne.status === '已上课',
    `status=${finalOne.status}`)
  if (canceledWon) {
    ok('取消先落库：签到被拒（已取消）', both[1].status === 400
      && both[1].json?.message?.includes('已取消'), JSON.stringify(both[1].json))
  } else {
    ok('签到先落库：取消被拒（已上课不可取消）', both[0].status === 400
      && both[0].json?.message?.includes('已上课'), JSON.stringify(both[0].json))
  }

  // 4.4 取消 A 槽 与 排 B 槽 同时进行：互不误伤
  r = await book(stu.id, future2, 840, 900, '将取消A')
  const aSlot = r.json.data
  const cross = await Promise.all([
    api('POST', `/api/course-schedules/${aSlot.id}/cancel`),
    withRetry(() => book(stu.id, future2, 900, 960, '新排B'))
  ])
  ok('不同槽并发：A 取消成功', cross[0].json?.data?.status === '已取消')
  ok('不同槽并发：B 排课成功（不被误拦）', cross[1].json?.ok
    && cross[1].json.data?.status === '待上课', JSON.stringify(cross[1].json))

  // ================================================= 5. 已上课不可取消
  section('5. 已上课不可取消（签到与课后记录不受影响）')
  r = await book(stu.id, future2, 960, 1020, '签到保护课')
  const taught = r.json.data
  r = await api('POST', `/api/course-schedules/${taught.id}/checkin`)
  ok('签到成功（已上课）', r.json?.data?.status === '已上课')
  await mustFail('已上课课次取消被明确拒绝',
    api('POST', `/api/course-schedules/${taught.id}/cancel`), '已上课')
  await mustFail('已上课重复取消仍被拒（不会被幂等放行为取消）',
    api('POST', `/api/course-schedules/${taught.id}/cancel`), '已上课')
  // 已上课课次的时段仍被占用，不能在同槽重排
  await mustFail('已上课时段不能被新课复用',
    book(stu.id, future2, 960, 1020, '想复用已上课时'), '重叠')
  // 课后记录链路不受影响：已上课可存草稿/提交
  r = await api('POST', `/api/course-schedules/${taught.id}/record/draft`, { teacherComment: '课已上，记录可写' })
  ok('已上课课次课后记录仍可正常保存', r.json?.data?.status === '草稿', JSON.stringify(r.json))
  r = await api('POST', `/api/course-schedules/${taught.id}/record/submit`,
    { workTopic: '并发专题', completion: 90, teacherComment: '状态闭环正确' })
  ok('已上课课次记录可正常提交', r.json?.data?.status === '已提交', JSON.stringify(r.json))
  r = await api('GET', `/api/course-schedules?studentId=${stu.id}&status=${q('已上课')}`)
  const taughtStill = r.json.data?.find((x) => x.id === taught.id)
  ok('拒绝取消后课次仍是「已上课」，签到/课后记录未被改写',
    taughtStill?.status === '已上课', JSON.stringify(taughtStill))

  // 已取消课次的记录视图仍然拒绝写课后记录
  await mustFail('已取消课次不能写课后记录',
    api('POST', `/api/course-schedules/${canceledId}/record/draft`, { teacherComment: 'x' }), '已取消')

  // ================================================= 6. 取消后查看历史
  section('6. 取消记录保留供历史查看，且不参与任何判重')
  r = await api('GET', `/api/course-schedules?studentId=${stu.id}`)
  const allRows = r.json.data
  ok('学员全部课次列表仍包含已取消记录',
    allRows.some((x) => x.id === canceledId && x.status === '已取消'
      && x.courseName === '取消后重排课'))
  r = await api('GET', `/api/course-schedules?status=${q('已取消')}`)
  ok('全局按「已取消」过滤包含本验收产生的取消记录',
    r.json.data?.some((x) => x.id === canceledId))
  r = await api('GET', `/api/course-schedules?studentId=${stu.id}&status=${q('待上课')}`)
  ok('「待上课」过滤不含已取消记录（状态不串）',
    r.json.data?.every((x) => x.status === '待上课')
    && !r.json.data.some((x) => x.id === canceledId))
  r = await api('GET', `/api/course-schedules/${canceledId}/record`)
  ok('已取消课次记录视图显示 scheduleStatus=已取消，且无记录残留',
    r.json?.data?.scheduleStatus === '已取消' && r.json.data.status == null)
  // 已取消课次时段已被新课占用，再取消/状态变更历史课次不影响在上课次
  const s2StillThere = (await schedulesOf(stu.id, future)).find((x) => x.id === s2.id)
  ok('历史取消记录存在的同时，重排新课仍为「待上课」且未被联动',
    s2StillThere?.status === '待上课', JSON.stringify(s2StillThere))

  // ================================================= 7. 多视图一致 + 刷新
  section('7. 列表 / 学员档案抽屉口径 / 刷新后状态一致')
  const listView = await api('GET', `/api/course-schedules?studentId=${stu.id}`)
  const drawerView = await api('GET', `/api/course-schedules?studentId=${stu.id}`)
  const refreshedView = await api('GET', `/api/course-schedules?studentId=${stu.id}`)
  const sig = (rows) => JSON.stringify(
    (rows || []).map((x) => [x.id, x.status, x.date, x.startMin, x.endMin]).sort((a, b) => a[0] - b[0]))
  ok('连续三次查询（含「刷新」）课次状态完全一致',
    sig(listView.json.data) === sig(drawerView.json.data)
    && sig(drawerView.json.data) === sig(refreshedView.json.data))
  // 抽屉与列表共用同一接口口径：按学员取数，取消/待上课/已上课数量稳定
  const countBy = (rows, st) => rows.filter((x) => x.status === st).length
  const rows = refreshedView.json.data
  // 4.3 中「取消 vs 签到」竞争的课次最终状态唯一；计数必须与该最终状态自洽
  const race2Final = rows.find((x) => x.id === race2.id)
  const unknownStatus = rows.filter((x) => !['待上课', '已上课', '已取消'].includes(x.status))
  ok('所有课次状态均合法（无脏状态/丢状态）', unknownStatus.length === 0,
    `bad=${JSON.stringify(unknownStatus)}`)
  const info = `待上课${countBy(rows, '待上课')}/已上课${countBy(rows, '已上课')}/已取消${countBy(rows, '已取消')}`
  ok('三种状态计数与并发最终裁决自洽：' + info,
    countBy(rows, '已上课') === (race2Final.status === '已上课' ? 2 : 1)
    && countBy(rows, '已取消') >= (race2Final.status === '已取消' ? 4 : 3)
    && countBy(rows, '待上课') >= 4
    && countBy(rows, '待上课') + countBy(rows, '已上课') + countBy(rows, '已取消') === rows.length)

  // 非在读学员排课仍被拦（取消闭环不放宽原有门禁）
  const code2 = `R${TS}X`.slice(0, 16)
  r = await api('POST', '/api/students', { code: code2, name: '排课验收乙', level: '少儿', status: '休学' })
  const stu2 = r.json.data
  await mustFail('休学学员排课仍被拦截',
    book(stu2.id, future, 540, 600, '休学排课'), '在读')

  // ---------------------------------------------------------------- 汇总
  console.log(`\n${'='.repeat(48)}`)
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  if (fail) { console.log('失败项：\n - ' + failures.join('\n - ')); process.exit(1) }
  console.log('\x1b[32m排课/取消状态闭环全部验收通过 ✅\x1b[0m')
}

main().catch((e) => { console.error('验收脚本异常：', e); process.exit(2) })
