<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth, clearAuth } from '../auth'

const route = useRoute()
const router = useRouter()
const collapsed = ref(false)
const navItems = [
  { name: 'dashboard', label: '运营概览', icon: '◫', to: '/' },
  { name: 'stations', label: '车站管理', icon: '⌖', to: '/stations' },
  { name: 'trains', label: '车次管理', icon: '⇌', to: '/trains' },
  { name: 'stocks', label: '库存管理', icon: '▦', to: '/stocks' },
]
const pageTitle = computed(
  () => navItems.find((item) => item.name === route.name)?.label || '运营后台',
)

function logout() {
  clearAuth()
  router.replace('/login')
}
</script>

<template>
  <div class="admin-shell" :class="{ collapsed }">
    <aside class="admin-sidebar">
      <div class="admin-brand">
        <span class="brand-mark light">R</span>
        <div>
          <strong>RailFlow</strong>
          <small>运营控制台</small>
        </div>
      </div>
      <button class="collapse-toggle" type="button" @click="collapsed = !collapsed">
        {{ collapsed ? '»' : '«' }}
      </button>
      <nav>
        <RouterLink v-for="item in navItems" :key="item.name" :to="item.to">
          <span class="nav-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot">
        <span class="server-dot"></span>
        <span>本地服务已连接</span>
      </div>
    </aside>

    <div class="admin-workspace">
      <header class="admin-topbar">
        <div>
          <span class="admin-kicker">RAILFLOW ADMIN</span>
          <h1>{{ pageTitle }}</h1>
        </div>
        <div class="admin-user">
          <span class="admin-avatar">{{ auth.admin?.realName?.slice(0, 1) || 'A' }}</span>
          <div>
            <strong>{{ auth.admin?.realName || auth.admin?.username }}</strong>
            <small>{{ auth.admin?.role }}</small>
          </div>
          <button type="button" @click="logout">退出</button>
        </div>
      </header>
      <main class="admin-content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
