<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import StudentView from './views/StudentView.vue'
import ArtMaterialView from './views/ArtMaterialView.vue'
import CourseScheduleView from './views/CourseScheduleView.vue'
import MaterialUseView from './views/MaterialUseView.vue'

// ------------------------------------------------------------------
// 导航形态：顶部标签页（el-tabs）。四个业务模块各占一个 tab，
// 双数模块（学员）采用「双击行 + 右侧抽屉」范式，其余为表格 + 弹窗。
// ------------------------------------------------------------------

const modules = [
  { path: '/student',        label: '学员档案', sub: '学号 · 级别', comp: StudentView },
  { path: '/art-material',   label: '画材库存', sub: '类别 · 库存', comp: ArtMaterialView },
  { path: '/course-schedule', label: '课程排期', sub: '时段 · 签到', comp: CourseScheduleView },
  { path: '/material-use',   label: '领用记录', sub: '扣减 · 台账', comp: MaterialUseView }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({})

const currentView = computed(() => {
  const m = modules.find((mm) => mm.path === currentPath.value)
  return m ? m.comp : StudentView
})

async function loadCounts() {
  try {
    const [s, m, c, u] = await Promise.all([
      api.listStudents(), api.listArtMaterials(),
      api.listCourseSchedules(), api.listMaterialUses()
    ])
    counts.value = {
      '/student': s.length,
      '/art-material': m.length,
      '/course-schedule': c.length,
      '/material-use': u.length
    }
  } catch (e) { /* 计数失败不影响主流程 */ }
}

function onTabChange(name) {
  router.push(name)
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })

onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">画</span>
        <div class="brand-text">
          <div class="brand-title">画室 · 学员档案与画材库存</div>
          <div class="brand-sub">靛青主题 · 顶部标签页导航 · 双击档案抽屉</div>
        </div>
      </div>
    </header>

    <el-tabs
      v-model="currentPath"
      class="nav-tabs"
      @tab-change="onTabChange"
    >
      <el-tab-pane
        v-for="m in modules"
        :key="m.path"
        :name="m.path"
        :label="`${m.label}（${counts[m.path] ?? 0}）`"
      />
    </el-tabs>

    <main class="content">
      <component :is="currentView" @changed="loadCounts" />
    </main>
  </div>
</template>

<style scoped>
.app-shell { min-height: 100vh; display: flex; flex-direction: column; }

.topbar {
  display: flex; align-items: center; gap: 16px;
  padding: 0 22px; height: 60px;
  background: linear-gradient(100deg, var(--primary-dark-2) 0%, var(--primary) 70%, var(--primary-light-3) 100%);
  color: #fff; box-shadow: 0 2px 12px rgba(92, 107, 192, 0.25);
}
.brand { display: flex; align-items: center; gap: 12px; }
.brand-mark {
  width: 38px; height: 38px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center;
  background: #fff; color: var(--primary-dark-2); font-weight: 700; font-size: 20px;
}
.brand-title { font-size: 17px; font-weight: 600; }
.brand-sub { font-size: 12px; opacity: 0.85; }

.nav-tabs { padding: 0 22px; background: #fff; }
.nav-tabs :deep(.el-tabs__header) { margin: 0; }
.nav-tabs :deep(.el-tabs__nav-wrap)::after { height: 1px; background: var(--line); }

.content { flex: 1; padding: 18px 22px 28px; }
</style>
