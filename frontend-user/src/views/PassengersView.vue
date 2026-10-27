<script setup>
import { onMounted, reactive, ref } from 'vue'
import { userApi } from '../api'
import { toast } from '../ui'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const passengers = ref([])
const editorOpen = ref(false)
const editingId = ref(null)
const form = reactive({
  name: '',
  idCardNo: '',
  passengerType: 1,
  phone: '',
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    passengers.value = await userApi.passengers()
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', idCardNo: '', passengerType: 1, phone: '' })
  editorOpen.value = true
}

function openEdit(passenger) {
  editingId.value = passenger.id
  Object.assign(form, {
    name: passenger.name,
    idCardNo: '',
    passengerType: passenger.passengerType,
    phone: passenger.phone || '',
  })
  editorOpen.value = true
}

async function save() {
  if (!form.name.trim() || !form.idCardNo.trim()) {
    toast('姓名和证件号不能为空', 'error')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.updatePassenger(editingId.value, form)
      toast('乘车人已更新')
    } else {
      await userApi.createPassenger(form)
      toast('乘车人已添加')
    }
    editorOpen.value = false
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(passenger) {
  if (!window.confirm(`确认删除乘车人 ${passenger.name}？`)) return
  try {
    await userApi.deletePassenger(passenger.id)
    toast('乘车人已删除')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  }
}

function typeLabel(type) {
  return { 1: '成人票', 2: '学生票', 3: '儿童票' }[type] || '乘车人'
}
</script>

<template>
  <div class="content-page">
    <div class="page-heading">
      <div>
        <span class="eyebrow">TRAVEL COMPANIONS</span>
        <h1>乘车人管理</h1>
        <p>保存常用乘车人，下单时无需重复填写证件信息。</p>
      </div>
      <button class="button primary" type="button" @click="openCreate">＋ 添加乘车人</button>
    </div>

    <p v-if="error" class="page-alert">{{ error }}</p>
    <div v-if="loading" class="skeleton-list">
      <div v-for="index in 3" :key="index" class="skeleton-card compact"></div>
    </div>

    <section v-else-if="passengers.length" class="passenger-card-grid">
      <article v-for="passenger in passengers" :key="passenger.id" class="passenger-profile">
        <div class="passenger-avatar">{{ passenger.name.slice(0, 1) }}</div>
        <div class="passenger-profile-main">
          <div>
            <h3>{{ passenger.name }}</h3>
            <span class="type-chip">{{ typeLabel(passenger.passengerType) }}</span>
          </div>
          <p>{{ passenger.idCardNo }}</p>
          <small>{{ passenger.phone || '未填写手机号' }}</small>
        </div>
        <div class="passenger-profile-actions">
          <button type="button" @click="openEdit(passenger)">编辑</button>
          <button type="button" class="danger-text" @click="remove(passenger)">删除</button>
        </div>
      </article>
    </section>

    <div v-else class="empty-state">
      <span class="empty-icon">人</span>
      <h3>还没有常用乘车人</h3>
      <p>添加后，购买车票时可以直接勾选。</p>
      <button class="button primary" type="button" @click="openCreate">添加第一位乘车人</button>
    </div>

    <Transition name="fade">
      <div v-if="editorOpen" class="modal-backdrop" @click.self="editorOpen = false">
        <div class="small-modal">
          <button class="modal-close" type="button" @click="editorOpen = false">×</button>
          <span class="eyebrow">{{ editingId ? 'EDIT PASSENGER' : 'NEW PASSENGER' }}</span>
          <h2>{{ editingId ? '编辑乘车人' : '添加乘车人' }}</h2>
          <p v-if="editingId" class="form-hint">
            出于脱敏策略，修改时需要重新输入完整证件号。
          </p>
          <form class="form-stack" @submit.prevent="save">
            <label class="field">
              <span>姓名 *</span>
              <input v-model.trim="form.name" placeholder="与身份证件一致" />
            </label>
            <label class="field">
              <span>身份证号 *</span>
              <input v-model.trim="form.idCardNo" placeholder="15 或 18 位身份证号" />
            </label>
            <div class="field-row">
              <label class="field">
                <span>票种</span>
                <select v-model.number="form.passengerType">
                  <option :value="1">成人票</option>
                  <option :value="2">学生票</option>
                  <option :value="3">儿童票</option>
                </select>
              </label>
              <label class="field">
                <span>手机号</span>
                <input v-model.trim="form.phone" placeholder="选填" />
              </label>
            </div>
            <div class="modal-actions">
              <button class="button ghost" type="button" @click="editorOpen = false">取消</button>
              <button class="button primary" :disabled="saving">
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Transition>
  </div>
</template>
