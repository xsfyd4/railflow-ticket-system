<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { userApi } from '../api'
import { toast } from '../ui'
import {
  dateTime,
  money,
  newIdempotencyKey,
  orderStatus,
  seatLabel,
} from '../orderStatus'

const router = useRouter()
const loading = ref(false)
const acting = ref('')
const error = ref('')
const filters = [
  { value: null, label: '全部' },
  { value: 0, label: '待支付' },
  { value: 1, label: '已支付' },
  { value: 2, label: '已取消' },
  { value: 3, label: '部分退票' },
  { value: 4, label: '全部退票' },
  { value: 5, label: '已完成' },
]
const query = reactive({ status: null, page: 1, size: 8 })
const page = reactive({ records: [], total: 0, page: 1, size: 8 })
const pages = computed(() => Math.max(1, Math.ceil(page.total / page.size)))

onMounted(load)

async function load(target = 1) {
  loading.value = true
  error.value = ''
  try {
    const data = await userApi.orders({
      status: query.status ?? undefined,
      page: target,
      size: query.size,
    })
    page.records = data.records || []
    page.total = data.total || 0
    page.page = data.page || target
    page.size = data.size || query.size
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function chooseStatus(value) {
  query.status = value
  load(1)
}

async function pay(order) {
  acting.value = `pay-${order.orderNo}`
  try {
    await userApi.payOrder(order.orderNo, newIdempotencyKey())
    toast('支付成功')
    await load(page.page)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    acting.value = ''
  }
}

async function cancel(order) {
  if (!window.confirm(`确认取消订单 ${order.orderNo}？`)) return
  acting.value = `cancel-${order.orderNo}`
  try {
    await userApi.cancelOrder(order.orderNo)
    toast('订单已取消')
    await load(page.page)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    acting.value = ''
  }
}
</script>

<template>
  <div class="content-page">
    <div class="page-heading">
      <div>
        <span class="eyebrow">MY JOURNEYS</span>
        <h1>我的订单</h1>
        <p>共 {{ page.total }} 笔订单，集中管理支付、退票与行程记录。</p>
      </div>
      <RouterLink class="button primary" to="/">继续购票</RouterLink>
    </div>

    <div class="order-filters">
      <button
        v-for="item in filters"
        :key="String(item.value)"
        type="button"
        :class="{ active: query.status === item.value }"
        @click="chooseStatus(item.value)"
      >
        {{ item.label }}
      </button>
    </div>

    <p v-if="error" class="page-alert">{{ error }}</p>

    <div v-if="loading" class="skeleton-list">
      <div v-for="index in 3" :key="index" class="skeleton-card compact"></div>
    </div>

    <div v-else-if="page.records.length" class="order-list">
      <article v-for="order in page.records" :key="order.orderNo" class="order-card">
        <div class="order-top">
          <div>
            <span class="order-no">订单 {{ order.orderNo }}</span>
            <span class="status-pill" :class="`status-${orderStatus(order.status).tone}`">
              {{ orderStatus(order.status).label }}
            </span>
          </div>
          <small>创建于 {{ dateTime(order.createdAt) }}</small>
        </div>

        <div class="order-body">
          <div class="order-route">
            <strong>{{ order.fromStationName }}</strong>
            <span class="mini-route"><i></i>→<i></i></span>
            <strong>{{ order.toStationName }}</strong>
          </div>
          <div class="order-meta">
            <span>{{ order.travelDate }}</span>
            <span>{{ order.trainNo }}</span>
            <span>{{ seatLabel(order.seatType) }}</span>
            <span>{{ order.passengerCount }} 人</span>
          </div>
          <div class="order-amount">
            <small>订单金额</small>
            <strong>¥{{ money(order.totalAmount) }}</strong>
          </div>
        </div>

        <div class="order-actions">
          <span v-if="order.status === 0" class="expire-tip">
            请在 {{ dateTime(order.expireTime) }} 前支付
          </span>
          <span v-else-if="order.refundedCount" class="expire-tip">
            已退 {{ order.refundedCount }} 张 · ¥{{ money(order.refundedAmount) }}
          </span>
          <span v-else></span>
          <div>
            <button class="button ghost small" @click="router.push(`/orders/${order.orderNo}`)">
              查看详情
            </button>
            <button
              v-if="order.status === 0"
              class="button ghost small danger"
              :disabled="acting === `cancel-${order.orderNo}`"
              @click="cancel(order)"
            >
              取消
            </button>
            <button
              v-if="order.status === 0"
              class="button primary small"
              :disabled="acting === `pay-${order.orderNo}`"
              @click="pay(order)"
            >
              {{ acting === `pay-${order.orderNo}` ? '支付中…' : '模拟支付' }}
            </button>
          </div>
        </div>
      </article>
    </div>

    <div v-else class="empty-state">
      <span class="empty-icon">票</span>
      <h3>这个分类下还没有订单</h3>
      <p>查询一趟列车，开始你的下一段行程。</p>
      <RouterLink class="button primary" to="/">去查票</RouterLink>
    </div>

    <div v-if="page.total > page.size" class="pager">
      <button class="button ghost" :disabled="page.page <= 1" @click="load(page.page - 1)">
        上一页
      </button>
      <span>第 {{ page.page }} 页 / 共 {{ pages }} 页</span>
      <button class="button ghost" :disabled="page.page >= pages" @click="load(page.page + 1)">
        下一页
      </button>
    </div>
  </div>
</template>
