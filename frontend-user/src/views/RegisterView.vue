<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { userApi } from '../api'
import { toast } from '../ui'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  phone: '',
})

async function submit() {
  error.value = ''
  if (form.password !== form.confirmPassword) {
    error.value = '两次输入的密码不一致'
    return
  }
  loading.value = true
  try {
    await userApi.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname,
      phone: form.phone,
    })
    toast('注册成功，请登录')
    router.replace('/login')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="auth-page register-page">
    <div class="auth-visual">
      <div class="eyebrow">JOIN RAILFLOW</div>
      <h1>建立账户，<br />让每次出发更从容。</h1>
      <p>注册后可以保存乘车人、管理订单并完成模拟支付与退票。</p>
      <div class="feature-lines">
        <span><i>01</i> 分段余票实时查询</span>
        <span><i>02</i> 多乘车人一次下单</span>
        <span><i>03</i> 订单与退票完整记录</span>
      </div>
    </div>

    <div class="auth-card">
      <div class="auth-card-head">
        <span class="mini-mark accent">＋</span>
        <div>
          <h2>创建账户</h2>
          <p>几步即可开始购票</p>
        </div>
      </div>

      <form class="form-stack compact" @submit.prevent="submit">
        <div class="field-row">
          <label class="field">
            <span>用户名 *</span>
            <input v-model.trim="form.username" placeholder="4～20 位字母数字" />
          </label>
          <label class="field">
            <span>昵称</span>
            <input v-model.trim="form.nickname" placeholder="怎么称呼你" />
          </label>
        </div>
        <div class="field-row">
          <label class="field">
            <span>密码 *</span>
            <input v-model="form.password" type="password" placeholder="至少 6 位" />
          </label>
          <label class="field">
            <span>确认密码 *</span>
            <input v-model="form.confirmPassword" type="password" placeholder="再次输入" />
          </label>
        </div>
        <label class="field">
          <span>手机号码</span>
          <input v-model.trim="form.phone" placeholder="选填，11 位手机号" />
        </label>

        <p v-if="error" class="form-error">{{ error }}</p>
        <button class="button primary wide" :disabled="loading">
          {{ loading ? '正在创建…' : '注册账户' }}
        </button>
      </form>

      <p class="auth-switch">
        已有账户？
        <RouterLink to="/login">返回登录</RouterLink>
      </p>
    </div>
  </section>
</template>
