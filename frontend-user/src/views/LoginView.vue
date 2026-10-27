<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { userApi } from '../api'
import { setAuth } from '../auth'
import { toast } from '../ui'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const error = ref('')
const form = reactive({
  username: 'zhangsan',
  password: '123456',
})

async function submit() {
  error.value = ''
  loading.value = true
  try {
    setAuth(await userApi.login(form))
    toast('登录成功，欢迎回来')
    router.replace(String(route.query.redirect || '/'))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="auth-page">
    <div class="auth-visual">
      <div class="eyebrow">RAILFLOW / 2026</div>
      <h1>下一段旅程，<br />从一张车票开始。</h1>
      <p>查询全国铁路演示车次，按分段库存实时确认余票和票价。</p>
      <div class="route-art">
        <span class="route-dot"></span>
        <span class="route-line"></span>
        <span class="route-dot active"></span>
        <div class="route-labels">
          <small>出发地</small>
          <small>目的地</small>
        </div>
      </div>
      <div class="stat-strip">
        <div><strong>110</strong><span>车站</span></div>
        <div><strong>100</strong><span>车次</span></div>
        <div><strong>30</strong><span>天预售</span></div>
      </div>
    </div>

    <div class="auth-card">
      <div class="auth-card-head">
        <span class="mini-mark">R</span>
        <div>
          <h2>登录账户</h2>
          <p>继续管理你的车票和行程</p>
        </div>
      </div>

      <form class="form-stack" @submit.prevent="submit">
        <label class="field">
          <span>用户名</span>
          <input v-model.trim="form.username" autocomplete="username" placeholder="请输入用户名" />
        </label>
        <label class="field">
          <span>密码</span>
          <input
            v-model="form.password"
            type="password"
            autocomplete="current-password"
            placeholder="请输入密码"
          />
        </label>

        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="button primary wide" :disabled="loading">
          {{ loading ? '正在登录…' : '登录' }}
        </button>
      </form>

      <div class="demo-account">
        <span>演示账号</span>
        <code>zhangsan / 123456</code>
      </div>

      <p class="auth-switch">
        还没有账户？
        <RouterLink to="/register">立即注册</RouterLink>
      </p>
    </div>
  </section>
</template>
