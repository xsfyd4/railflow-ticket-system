<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { userApi } from '../api'
import { toast } from '../ui'
import {
  dateTime,
  money,
  newIdempotencyKey,
  orderStatus,
  seatLabel,
} from '../orderStatus'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const acting = ref(false)
const error = ref('')
const order = ref(null)
const selectedItemIds = ref([])

const refundableItems = computed(() =>
  (order.value?.items || []).filter((item) => item.status === 0 || item.status === 1),
)

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    order.value = await userApi.orderDetail(route.params.orderNo)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function toggleRefund(id) {
  const index = selectedItemIds.value.indexOf(id)
  if (index >= 0) selectedItemIds.value.splice(index, 1)
  else selectedItemIds.value.push(id)
}

async function pay() {
  acting.value = true
  try {
    await userApi.payOrder(order.value.orderNo, newIdempotencyKey())
    toast('支付成功')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    acting.value = false
  }
}

async function cancel() {
  if (!window.confirm('确认取消这笔订单？')) return
  acting.value = true
  try {
    await userApi.cancelOrder(order.value.orderNo)
    toast('订单已取消')
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    acting.value = false
  }
}

async function refund() {
  if (!selectedItemIds.value.length) {
    toast('请选择要退的票', 'error')
    return
  }
  if (!window.confirm(`确认退掉选中的 ${selectedItemIds.value.length} 张票？`)) return
  acting.value = true
  try {
    const result = await userApi.refundOrder(order.value.orderNo, selectedItemIds.value)
    toast(`退票成功，退款 ¥${money(result.refundedAmount)}`)
    selectedItemIds.value = []
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    acting.value = false
  }
}

function itemStatus(status) {
  return { 0: '待出票', 1: '已出票', 2: '已退票' }[status] || '未知'
}
</script>

<template>
  <div class="content-page narrow-page">
    <button class="text-back" type="button" @click="router.push('/orders')">← 返回订单列表</button>

    <p v-if="error" class="page-alert">{{ error }}</p>
    <div v-if="loading" class="skeleton-card tall"></div>

    <template v-else-if="order">
      <section class="detail-ticket">
        <div class="detail-ticket-main">
          <div>
            <span class="eyebrow">E-TICKET RECORD</span>
            <div class="detail-route">
              <h1>{{ order.fromStationName }}</h1>
              <span class="route-arrow"><i></i>→</span>
              <h1>{{ order.toStationName }}</h1>
            </div>
            <p>{{ order.travelDate }} · {{ order.trainNo }} · {{ seatLabel(order.seatType) }}</p>
          </div>
          <span class="status-pill large" :class="`status-${orderStatus(order.status).tone}`">
            {{ orderStatus(order.status).label }}
          </span>
        </div>
        <div class="detail-cut-line"></div>
        <div class="detail-stats">
          <div><small>订单号</small><strong>{{ order.orderNo }}</strong></div>
          <div><small>乘车人数</small><strong>{{ order.passengerCount }} 人</strong></div>
          <div><small>订单金额</small><strong class="price-accent">¥{{ money(order.totalAmount) }}</strong></div>
          <div><small>支付截止</small><strong>{{ dateTime(order.expireTime) }}</strong></div>
        </div>
      </section>

      <div class="detail-grid">
        <section class="panel">
          <div class="panel-heading">
            <div>
              <span class="eyebrow">PASSENGERS</span>
              <h2>乘车人明细</h2>
            </div>
            <small v-if="order.status === 1 || order.status === 3">
              可勾选未退票的乘客办理退票
            </small>
          </div>
          <div class="passenger-ticket-list">
            <label
              v-for="item in order.items"
              :key="item.id"
              class="passenger-ticket"
              :class="{ refunded: item.status === 2, selectable: refundableItems.some((x) => x.id === item.id) }"
            >
              <input
                v-if="refundableItems.some((x) => x.id === item.id)"
                type="checkbox"
                :checked="selectedItemIds.includes(item.id)"
                @change="toggleRefund(item.id)"
              />
              <span v-else class="ticket-dot"></span>
              <div class="passenger-ticket-name">
                <strong>{{ item.passengerName }}</strong>
                <small>{{ item.idCardNo }}</small>
              </div>
              <div>
                <small>{{ seatLabel(item.seatType) }}</small>
                <strong>¥{{ money(item.price) }}</strong>
              </div>
              <span class="mini-status">{{ itemStatus(item.status) }}</span>
            </label>
          </div>
        </section>

        <aside class="panel action-panel">
          <span class="eyebrow">ACTIONS</span>
          <h2>订单操作</h2>
          <template v-if="order.status === 0">
            <p>库存已为你保留，请及时完成支付。</p>
            <button class="button primary wide" :disabled="acting" @click="pay">
              {{ acting ? '处理中…' : '模拟支付' }}
            </button>
            <button class="button ghost wide danger" :disabled="acting" @click="cancel">
              取消订单
            </button>
          </template>
          <template v-else-if="order.status === 1 || order.status === 3">
            <p>选择左侧未退票的乘客，可以部分或全部退票。</p>
            <button
              class="button primary wide"
              :disabled="acting || !selectedItemIds.length"
              @click="refund"
            >
              {{ acting ? '处理中…' : `退掉选中 ${selectedItemIds.length} 张` }}
            </button>
          </template>
          <template v-else>
            <p>该订单当前没有可执行操作。</p>
            <RouterLink class="button ghost wide" to="/">重新查票</RouterLink>
          </template>
          <div class="order-totals">
            <span><small>已退票</small><strong>{{ order.refundedCount || 0 }} 张</strong></span>
            <span><small>已退款</small><strong>¥{{ money(order.refundedAmount) }}</strong></span>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>
