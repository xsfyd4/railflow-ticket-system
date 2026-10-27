import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from './auth'
import AdminLayout from './components/AdminLayout.vue'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import StationsView from './views/StationsView.vue'
import TrainsView from './views/TrainsView.vue'
import StocksView from './views/StocksView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginView },
    {
      path: '/',
      component: AdminLayout,
      meta: { auth: true },
      children: [
        { path: '', name: 'dashboard', component: DashboardView },
        { path: 'stations', name: 'stations', component: StationsView },
        { path: 'trains', name: 'trains', component: TrainsView },
        { path: 'stocks', name: 'stocks', component: StocksView },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.meta.auth && !isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && isLoggedIn()) {
    return { name: 'dashboard' }
  }
})

export default router
