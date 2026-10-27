<script setup>
import { RouterView } from 'vue-router'
import AppHeader from './components/AppHeader.vue'
import ToastStack from './components/ToastStack.vue'
</script>

<template>
  <div class="app-shell">
    <AppHeader />
    <main class="app-main">
      <RouterView />
    </main>
    <footer class="app-footer">
      <span>RailFlow · 铁路购票学习项目</span>
      <span>数据来自本地演示数据库，不代表真实铁路信息</span>
    </footer>
    <ToastStack />
  </div>
</template>
