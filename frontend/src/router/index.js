import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import StudentView from '../views/StudentView.vue'
import ArtMaterialView from '../views/ArtMaterialView.vue'
import CourseScheduleView from '../views/CourseScheduleView.vue'
import MaterialUseView from '../views/MaterialUseView.vue'

const routes = [
  { path: '/', redirect: '/student' },
  { path: '/student', name: 'student', component: StudentView },
  { path: '/art-material', name: 'art-material', component: ArtMaterialView },
  { path: '/course-schedule', name: 'course-schedule', component: CourseScheduleView },
  { path: '/material-use', name: 'material-use', component: MaterialUseView },
  { path: '/:pathMatch(.*)*', redirect: '/student' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
