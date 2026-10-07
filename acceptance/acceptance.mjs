#!/usr/bin/env node
/**
 * 课后成果记录闭环 · 一键验收脚本
 *
 * 用法：
 *   sh start.sh                 # 先把栈起起来
 *   node acceptance/acceptance.mjs
 *   BASE=http://127.0.0.1:8420 node acceptance/acceptance.mjs   # 自定义地址
 *
 * 覆盖链路：
 *   A 正常：待上课拦截 → 签到 → 只填点评存草稿 → 缺要素提交被拦 → 补齐提交 → 重复/并发提交幂等
 *          → 已提交只读 → 归档 → 归档只读/重复归档幂等
 *   B 草稿不进已完成列表
 *   C 取消课次：取消前后都不能新建记录，且不留半条数据
 *   D 并发提交：N 个同时请求只有一份记录，所有响应拿到同一 id
 *   E 学员状态变化（休学/结业）不影响已上课课次提交，视图带学员当前状态
 *   F 学员删除：无历史数据可删；有排课/记录/领用的删除被友好拦截
 *   G 删除草稿不误伤课次、学员状态、画材库存
 *   H （可选，需 docker）学员档案被物理删除后，提交给出可理解原因
 */

const BASE = process.env.BASE || 'http://127.0.0.1:8420'
const TS = Date.now()
const REPO = new URL('..', import.meta.url).pathname

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

async function mustFail(name, p, msgPart) {
  const r = await p
  const good = r.json && r.json.ok === false && r.json.message && r.json.message.includes(msgPart)
  ok(name, good, `got ${JSON.stringify(r.json)}`)
  return r
}

// 简单延迟，便于 updatedAt 出现可观测差异
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  console.log(`目标后端：${BASE}`)

  // ---------------------------------------------------------------- 0. 连通 & 基线快照
  section('0. 连通性与无关业务基线')
  const ping = await api('GET', '/api/students')
  ok('后端可访问且返回 {ok:true}', ping.json?.ok === true, JSON.stringify(ping.json))
  if (!ping.json?.ok) process.exit(1)

  const mats0 = (await api('GET', '/api/art-materials')).json.data
  const stock0 = Object.fromEntries(mats0.map((m) => [m.id, m.stock]))
  const future = new Date(Date.now() + 86400 * 3).toISOString().slice(0, 10)

  // ---------------------------------------------------------------- 1. 建学员 + 排课
  section('1. 准备：新建在读学员并排课')
  const code = `T${TS}`.slice(0, 16)
  let r = await api('POST', '/api/students', { code, name: '验收甲', phone: '13800138000', level: '成人', status: '在读' })
  ok('新建学员成功', r.json?.ok === true && r.json.data?.status === '在读')
  const student = r.json.data

  r = await api('POST', '/api/course-schedules', {
    studentId: student.id, courseName: '验收油画课', date: future, startMin: 540, endMin: 600
  })
  ok('排课成功（待上课）', r.json?.data?.status === '待上课')
  const sch1 = r.json.data

  // ---------------------------------------------------------------- 2. 课次状态门禁
  section('2. 只有「已上课」才能新建/提交记录')
  await mustFail('待上课课次不能存草稿',
    api('POST', `/api/course-schedules/${sch1.id}/record/draft`, { teacherComment: '先写一句点评' }), '待上课')
  await mustFail('待上课课次不能直接提交',
    api('POST', `/api/course-schedules/${sch1.id}/record/submit`, { workTopic: 'x', completion: 80, teacherComment: 'y' }), '待上课')
  r = await api('GET', `/api/course-schedules/${sch1.id}/record`)
  ok('被拦截后没有留下半条记录（status 为空）', r.json?.data && r.json.data.status == null)

  // ---------------------------------------------------------------- 3. 取消课次链路
  section('3. 已取消课次明确拒绝且不留数据')
  r = await api('POST', '/api/course-schedules', {
    studentId: student.id, courseName: '验收将取消课', date: future, startMin: 660, endMin: 720
  })
  const schCancel = r.json.data
  await mustFail('取消前（待上课）不能写记录',
    api('POST', `/api/course-schedules/${schCancel.id}/record/draft`, { teacherComment: 'c' }), '待上课')
  r = await api('POST', `/api/course-schedules/${schCancel.id}/cancel`)
  ok('课次取消成功', r.json?.data?.status === '已取消')
  await mustFail('已取消课次不能存草稿',
    api('POST', `/api/course-schedules/${schCancel.id}/record/draft`, { teacherComment: 'c' }), '已取消')
  await mustFail('已取消课次不能提交',
    api('POST', `/api/course-schedules/${schCancel.id}/record/submit`,
      { workTopic: 'x', completion: 70, teacherComment: 'y' }), '已取消')
  r = await api('GET', `/api/course-schedules/${schCancel.id}/record`)
  ok('已取消课次下不存在任何记录', r.json?.data && r.json.data.status == null
    && r.json.data.scheduleStatus === '已取消')

  // ---------------------------------------------------------------- 4. 签到 → 草稿 → 缺要素提交
  section('4. 签到后：草稿可只填点评，提交必须补齐三要素')
  r = await api('POST', `/api/course-schedules/${sch1.id}/checkin`)
  ok('签到成功（已上课）', r.json?.data?.status === '已上课')

  await sleep(1050)
  r = await api('POST', `/api/course-schedules/${sch1.id}/record/draft`, { teacherComment: '色调还行' })
  ok('只填教师点评即可保存草稿', r.json?.data?.status === '草稿' && r.json.data.workTopic == null
    && r.json.data.teacherComment === '色调还行')
  const draftId = r.json.data.id
  const draftUpdated = r.json.data.updatedAt

  await sleep(1050)
  await mustFail('缺作品主题/完成度时提交被拦截',
    api('POST', `/api/course-schedules/${sch1.id}/record/submit`, { teacherComment: '色调还行' }), '补齐')
  r = await api('GET', `/api/course-schedules/${sch1.id}/record`)
  ok('提交失败后仍是草稿、未产生新记录', r.json?.data?.id === draftId && r.json.data.status === '草稿')

  // ---------------------------------------------------------------- 5. 草稿不进已完成列表
  section('5. 未提交草稿不算结课')
  r = await api('GET', `/api/course-records?studentId=${student.id}&status=已提交`)
  ok('已提交列表不含草稿课次', Array.isArray(r.json.data)
    && !r.json.data.some((x) => x.scheduleId === sch1.id))
  r = await api('GET', `/api/course-records?studentId=${student.id}&status=草稿`)
  ok('草稿列表能看到该草稿', r.json.data?.some((x) => x.scheduleId === sch1.id))

  // ---------------------------------------------------------------- 6. 正常提交 + 并发/重复提交幂等
  section('6. 提交：重复点击与并发同时提交得到同一结果')
  const fullPayload = { workTopic: '静物：苹果', completion: 88, teacherComment: '暗部再透气些', nextPractice: '单色明暗稿两张' }

  // 先把草稿内容补齐
  r = await api('POST', `/api/course-schedules/${sch1.id}/record/draft`, fullPayload)
  ok('草稿补齐后可继续编辑（id 不变）', r.json?.data?.id === draftId && r.json.data.workTopic === '静物：苹果')
  ok('编辑草稿刷新了最后更新时间', r.json.data.updatedAt !== draftUpdated)

  const N = 5
  const concurrent = await Promise.all(
    Array.from({ length: N }, () => api('POST', `/api/course-schedules/${sch1.id}/record/submit`, fullPayload))
  )
  const ids = new Set(concurrent.map((x) => x.json?.data?.id))
  const statuses = new Set(concurrent.map((x) => x.json?.data?.status))
  ok(`N=${N} 个并发提交全部成功返回`, concurrent.every((x) => x.json?.ok))
  ok('并发提交只产生一份记录（id 唯一）', ids.size === 1 && ids.has(draftId), `ids=${[...ids].join(',')}`)
  ok('并发结果状态一致：已提交', statuses.size === 1 && statuses.has('已提交'))
  const changedCount = concurrent.filter((x) => x.json.message.includes('已提交，课次结课')).length
  ok('并发中仅 1 个请求真正推进状态，其余幂等命中', changedCount === 1,
    `changedCount=${changedCount}; messages=${concurrent.map((x) => x.json.message).join(' | ')}`)

  r = await api('POST', `/api/course-schedules/${sch1.id}/record/submit`, fullPayload)
  ok('事后再点一次提交仍返回同一份记录', r.json?.data?.id === draftId
    && r.json.message.includes('无需重复提交'))

  r = await api('GET', `/api/course-records?scheduleId=${sch1.id}`)
  ok('按课次查询只有一份记录', r.json.data?.length === 1 && r.json.data[0].id === draftId)
  r = await api('GET', `/api/course-records?studentId=${student.id}&status=已提交`)
  ok('已完成（已提交）列表现在包含该课次', r.json.data?.some((x) => x.scheduleId === sch1.id))

  // ---------------------------------------------------------------- 7. 已提交只读
  section('7. 已提交不可改，归档后只读')
  await mustFail('已提交记录不能再保存草稿式修改',
    api('POST', `/api/course-schedules/${sch1.id}/record/draft`, { teacherComment: '想改' }), '已提交')
  await mustFail('已提交记录不能删除',
    api('DELETE', `/api/course-schedules/${sch1.id}/record`), '草稿')

  r = await api('POST', `/api/course-schedules/${sch1.id}/record/archive`)
  ok('归档成功', r.json?.data?.status === '已归档' && !!r.json.data.archivedAt)
  r = await api('POST', `/api/course-schedules/${sch1.id}/record/archive`)
  ok('重复归档幂等（同一记录 + 说明）', r.json?.data?.status === '已归档'
    && r.json.message.includes('已归档'))
  r = await api('POST', `/api/course-schedules/${sch1.id}/record/submit`, fullPayload)
  ok('归档后重复提交仍返回同一只读记录（幂等）', r.json?.data?.id === draftId
    && r.json.data.status === '已归档' && r.json.message.includes('已归档'))
  await mustFail('归档后编辑被拒（只读）',
    api('POST', `/api/course-schedules/${sch1.id}/record/draft`, { workTopic: '改' }), '只读')
  await mustFail('归档后不能删除',
    api('DELETE', `/api/course-schedules/${sch1.id}/record`), '草稿')

  // 视图带课次/学员快照与最后更新时间
  r = await api('GET', `/api/course-schedules/${sch1.id}/record`)
  const v = r.json.data
  ok('记录视图带当前状态与最后更新时间', v.status === '已归档' && !!v.updatedAt && !!v.submittedAt)
  ok('记录视图带课次与学员快照', v.courseName === '验收油画课' && v.studentName === '验收甲'
    && v.scheduleStatus === '已上课' && v.workTopic === '静物：苹果')

  // ---------------------------------------------------------------- 8. 学员状态变化
  section('8. 学员状态变化（休学/结业）不影响提交，但视图反映当前状态')
  r = await api('POST', '/api/course-schedules', {
    studentId: student.id, courseName: '验收状态变化课', date: future, startMin: 780, endMin: 840
  })
  const sch2 = r.json.data
  await api('POST', `/api/course-schedules/${sch2.id}/checkin`)
  r = await api('PUT', `/api/students/${student.id}`, { status: '休学' })
  ok('学员状态改为休学', r.json?.data?.status === '休学')
  r = await api('POST', `/api/course-schedules/${sch2.id}/record/submit`,
    { workTopic: '休学后仍可结课', completion: 90, teacherComment: '状态变化不影响已上课课次' })
  ok('学员休学后，已上课课次仍可提交记录', r.json?.data?.status === '已提交')
  ok('视图反映学员当前状态（休学）', r.json.data.studentStatus === '休学')

  // ---------------------------------------------------------------- 9. 删除草稿的副作用隔离
  section('9. 删除草稿不误伤课次/学员/库存')
  const code3 = `D${TS}`.slice(0, 16)
  r = await api('POST', '/api/students', { code: code3, name: '验收丙', level: '少儿', status: '在读' })
  const s3 = r.json.data
  r = await api('POST', '/api/course-schedules', {
    studentId: s3.id, courseName: '验收草稿删除课', date: future, startMin: 540, endMin: 600
  })
  // 新学员同日 09:00 与其他人不冲突（按学员判重），但 s3 只有这一条
  const sch3 = r.json.data
  await api('POST', `/api/course-schedules/${sch3.id}/checkin`)
  await api('POST', `/api/course-schedules/${sch3.id}/record/draft`, { teacherComment: '待删草稿' })
  r = await api('DELETE', `/api/course-schedules/${sch3.id}/record`)
  ok('删除草稿成功', r.json?.ok)
  r = await api('GET', `/api/course-schedules/${sch3.id}/record`)
  ok('删除后该课次无记录', r.json?.data?.status == null)
  r = await api('GET', `/api/course-schedules?studentId=${s3.id}`)
  ok('课次本身仍是「已上课」，未被误改', r.json.data?.[0]?.status === '已上课')
  r = await api('GET', '/api/students')
  ok('学员状态仍是「在读」，未被误改', r.json.data?.find((x) => x.id === s3.id)?.status === '在读')

  // ---------------------------------------------------------------- 10. 学员删除门禁
  section('10. 学员删除：无历史可删，有历史给明确原因')
  r = await api('DELETE', `/api/students/${s3.id}`)
  ok('有排课的学员删除被拦截并给出可读原因', r.json?.ok === false && /历史业务数据/.test(r.json.message))
  // 真正零历史数据的学员
  const code4 = `E${TS}`.slice(0, 16)
  r = await api('POST', '/api/students', { code: code4, name: '验收丁', level: '少儿', status: '在读' })
  const s4 = r.json.data
  r = await api('DELETE', `/api/students/${s4.id}`)
  ok('零历史数据学员可删除', r.json?.ok === true)
  r = await api('DELETE', `/api/students/${s4.id}`)
  ok('删除不存在学员给出原因', r.json?.ok === false && /不存在/.test(r.json.message))
  r = await api('DELETE', `/api/students/${student.id}`)
  ok('有排课/记录的学员删除被拦截并给出可读原因', r.json?.ok === false
    && /历史业务数据/.test(r.json.message) && /排课/.test(r.json.message))
  r = await api('GET', '/api/students')
  ok('被拦截后学员仍在', !!r.json.data?.find((x) => x.id === student.id))

  // ---------------------------------------------------------------- 11. 库存全程未被课后记录触碰
  section('11. 画材库存与课后记录完全隔离')
  const mats1 = (await api('GET', '/api/art-materials')).json.data
  const stock1 = Object.fromEntries(mats1.map((m) => [m.id, m.stock]))
  const changedMats = Object.keys(stock0).filter((id) => stock0[id] !== stock1[id])
  ok('整个验收过程画材库存零变化', changedMats.length === 0,
    `changed=${changedMats.map((id) => `${id}:${stock0[id]}→${stock1[id]}`).join(',')}`)

  // ---------------------------------------------------------------- 12.（可选）学员档案物理消失
  section('12. 可选：学员档案被物理删除时提交给出可理解原因（需 docker 直连 MySQL）')
  const { execSync } = await import('node:child_process')
  let dockerOk = false
  try { execSync('docker compose ps -q mysql', { cwd: REPO, stdio: 'pipe' }); dockerOk = true } catch { dockerOk = false }
  if (!dockerOk) {
    console.log('  - 跳过（当前环境无 docker，无法直连 MySQL 制造档案缺失；该防线由提交时的存在性校验保证）')
  } else {
    // s3 的课次 sch3 已无记录、学员还在：制造“档案消失”后尝试提交
    const sql = (s) => execSync(
      `docker compose exec -T mysql mysql -uroot -proot art_studio -e "${s.replace(/"/g, '\\"')}"`,
      { cwd: REPO, stdio: 'pipe' }
    ).toString()
    try {
      sql(`SET FOREIGN_KEY_CHECKS=0; DELETE FROM student WHERE id=${s3.id};`)
      const rr = await api('POST', `/api/course-schedules/${sch3.id}/record/submit`,
        { workTopic: '孤儿', completion: 50, teacherComment: '学员已没了' })
      ok('学员档案缺失时提交被拒且原因可读', rr.json?.ok === false && /学员档案不存在/.test(rr.json.message),
        JSON.stringify(rr.json))
      const rg = await api('GET', `/api/course-schedules/${sch3.id}/record`)
      ok('失败后未留半条记录', rg.json?.data?.status == null)
    } finally {
      // 还原引用完整性：清掉孤儿课次，恢复 FK 检查
      sql(`SET FOREIGN_KEY_CHECKS=0; DELETE FROM course_schedule WHERE id=${sch3.id}; SET FOREIGN_KEY_CHECKS=1;`)
    }
  }

  // ---------------------------------------------------------------- 汇总
  console.log(`\n${'='.repeat(48)}`)
  console.log(`通过 ${pass} 项，失败 ${fail} 项`)
  if (fail) { console.log('失败项：\n - ' + failures.join('\n - ')); process.exit(1) }
  console.log('\x1b[32m全部验收通过 ✅\x1b[0m')
}

main().catch((e) => { console.error('验收脚本异常：', e); process.exit(2) })
