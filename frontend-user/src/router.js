import { createRouter, createWebHistory } from 'vue-router'
import { isLoggedIn } from './auth'
import SearchView from './views/SearchView.vue'
import LoginView from './views/LoginView.vue'
import RegisterView from './views/RegisterView.vue'
import OrdersView from './views/OrdersView.vue'
import OrderDetailView from './views/OrderDetailView.vue'
import PassengersView from './views/PassengersView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'search', component: SearchView },
    { path: '/login', name: 'login', component: LoginView, meta: { guest: true } },
    { path: '/register', name: 'register', component: RegisterView, meta: { guest: true } },
    { path: '/orders', name: 'orders', component: OrdersView, meta: { auth: true } },
    {
      path: '/orders/:orderNo',
      name: 'order-detail',
      component: OrderDetailView,
      meta: { auth: true },
    },
    {
      path: '/passengers',
      name: 'passengers',
      component: PassengersView,
      meta: { auth: true },
    },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (to.meta.auth && !isLoggedIn()) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.meta.guest && isLoggedIn()) {
    return { name: 'search' }
  }
})

export default router
