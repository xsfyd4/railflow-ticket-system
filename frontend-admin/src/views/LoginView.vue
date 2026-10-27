<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { adminApi } from '../api'
import { setAuth } from '../auth'
import { toast } from '../ui'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const error = ref('')
const form = reactive({
  username: 'admin',
  password: '123456',
})

async function submit() {
  loading.value = true
  error.value = ''
  try {
    setAuth(await adminApi.login(form))
    toast('管理员登录成功')
    router.replace(String(route.query.redirect || '/'))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="admin-login-page">
    <div class="login-grid-lines"></div>
    <section class="admin-login-brand">
      <div class="admin-login-logo">R</div>
      <span class="eyebrow">RAILFLOW CONTROL CENTER</span>
      <h1>让每一段运力，<br /><em>清晰可见。</em></h1>
      <p>车站、车次、分段库存与订单数据，在一个工作台内完成管理。</p>
      <div class="login-system-cards">
        <article><strong>110</strong><span>车站基础数据</span></article>
        <article><strong>100</strong><span>国内演示车次</span></article>
        <article><strong>30</strong><span>天库存窗口</span></article>
      </div>
    </section>

    <section class="admin-login-panel">
      <div class="admin-login-form-wrap">
        <span class="eyebrow">SECURE ACCESS</span>
        <h2>管理端登录</h2>
        <p>使用管理员账户进入运营控制台。</p>
        <form class="form-stack" @submit.prevent="submit">
          <label class="field">
            <span>管理员账号</span>
            <input v-model.trim="form.username" autocomplete="username" />
          </label>
          <label class="field">
            <span>密码</span>
            <input v-model="form.password" type="password" autocomplete="current-password" />
          </label>
          <p v-if="error" class="form-error">{{ error }}</p>
          <button class="button primary wide" :disabled="loading">
            {{ loading ? '正在验证…' : '进入控制台' }}
          </button>
        </form>
        <div class="admin-demo">演示账号 <code>admin / 123456</code></div>
      </div>
    </section>
  </div>
</template>
