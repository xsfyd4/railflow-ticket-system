<script setup>
import { useRouter } from 'vue-router'
import { auth, clearAuth } from '../auth'

const router = useRouter()

function logout() {
  clearAuth()
  router.push('/')
}
</script>

<template>
  <header class="site-header">
    <div class="header-inner">
      <RouterLink class="brand" to="/">
        <span class="brand-mark">R</span>
        <span>
          <strong>RailFlow</strong>
          <small>铁路出行</small>
        </span>
      </RouterLink>

      <nav class="main-nav">
        <RouterLink to="/">查票购票</RouterLink>
        <RouterLink v-if="auth.token" to="/orders">我的订单</RouterLink>
        <RouterLink v-if="auth.token" to="/passengers">乘车人</RouterLink>
      </nav>

      <div class="header-actions">
        <template v-if="auth.token">
          <span class="user-chip">{{ auth.user?.nickname || auth.user?.username }}</span>
          <button class="button ghost small" type="button" @click="logout">退出</button>
        </template>
        <template v-else>
          <RouterLink class="button ghost small" to="/login">登录</RouterLink>
          <RouterLink class="button primary small" to="/register">注册</RouterLink>
        </template>
      </div>
    </div>
  </header>
</template>
