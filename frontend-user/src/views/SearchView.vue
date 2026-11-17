<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { userApi } from '../api'
import { auth } from '../auth'
import { toast } from '../ui'
import StationPicker from '../components/StationPicker.vue'
import {
  clearPendingOrderIdempotencyKey,
  clearPendingAsyncOrder,
  loadPendingAsyncOrder,
  pendingOrderIdempotencyKey,
  savePendingAsyncOrder,
} from '../orderStatus'

const router = useRouter()
const ASYNC_POLL_INTERVAL_MS = 400
const ASYNC_POLL_TIMEOUT_MS = 30000
const seatTypes = [
  { value: 1, label: '商务座', className: 'business' },
  { value: 2, label: '一等座', className: 'first' },
  { value: 3, label: '二等座', className: 'second' },
]

function isoDate(offset = 0) {
  const value = new Date()
  value.setDate(value.getDate() + offset)
  return value.toISOString().slice(0, 10)
}

const fromStation = ref({
  id: 1,
  stationName: '北京南',
  city: '北京',
})
const toStation = ref({
  id: 27,
  stationName: '上海虹桥',
  city: '上海',
})
const travelDate = ref(isoDate(1))
const seatType = ref(null)
const searching = ref(false)
const searched = ref(false)
const result = reactive({ records: [], total: 0, page: 1, size: 10 })
const error = ref('')
const asyncNotice = ref('')

const booking = reactive({
  open: false,
  ticket: null,
  seat: null,
  passengers: [],
  selectedIds: [],
  loadingPassengers: false,
  submitting: false,
  idempotencyKey: '',
  asyncRequestId: '',
  asyncPayload: null,
  asyncStatusText: '',
  error: '',
})

const selectedPassengers = computed(() =>
  booking.passengers.filter((item) => booking.selectedIds.includes(item.id)),
)

onMounted(() => {
  if (auth.token) {
    search()
    resumePendingAsyncOrder()
  }
})

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function updateAsyncStatus(data) {
  if (data?.requestId) booking.asyncRequestId = data.requestId
  if (data?.statusText) booking.asyncStatusText = data.statusText
}

async function pollAsyncOrder(requestId) {
  const deadline = Date.now() + ASYNC_POLL_TIMEOUT_MS
  let latest = null
  while (Date.now() < deadline) {
    latest = await userApi.asyncOrderStatus(requestId)
    updateAsyncStatus(latest)
    if (latest.status === 3 || latest.status === 4) return latest
    await sleep(ASYNC_POLL_INTERVAL_MS)
  }
  return latest
}

async function handleAsyncResult(latest, payload, { resumed = false } = {}) {
  if (latest?.status === 3 && latest.orderNo) {
    clearPendingOrderIdempotencyKey(payload)
    clearPendingAsyncOrder(latest.requestId)
    booking.asyncRequestId = ''
    booking.asyncPayload = null
    booking.asyncStatusText = ''
    asyncNotice.value = ''
    if (!resumed) booking.open = false
    toast(`订单 ${latest.orderNo.slice(-8)} 创建成功`)
    await router.push(`/orders/${latest.orderNo}`)
    return true
  }
  if (latest?.status === 4) {
    const reason = latest.failReason || latest.statusText || '订单创建失败'
    clearPendingOrderIdempotencyKey(payload)
    clearPendingAsyncOrder(latest.requestId)
    booking.asyncRequestId = ''
    booking.asyncPayload = null
    booking.asyncStatusText = ''
    booking.idempotencyKey = ''
    asyncNotice.value = ''
    if (resumed) {
      toast(reason, 'error')
    } else {
      booking.error = reason
    }
    return true
  }
  return false
}

async function continueAsyncOrder() {
  if (!booking.asyncRequestId || !booking.asyncPayload) return
  booking.error = ''
  booking.submitting = true
  try {
    const latest = await pollAsyncOrder(booking.asyncRequestId)
    if (await handleAsyncResult(latest, booking.asyncPayload)) return
    booking.error =
      '订单仍在排队处理中，已保存请求状态，可刷新页面继续查询。'
  } catch (e) {
    if (e.code === 401) {
      router.push({ name: 'login', query: { redirect: '/' } })
      return
    }
    booking.error = e.message
  } finally {
    booking.submitting = false
  }
}

async function resumePendingAsyncOrder() {
  const pending = loadPendingAsyncOrder()
  if (!pending) return
  asyncNotice.value = '检测到正在创建的订单，正在恢复处理状态…'
  try {
    const latest = await pollAsyncOrder(pending.requestId)
    if (await handleAsyncResult(
      latest,
      pending.payload,
      { resumed: true },
    )) return
    asyncNotice.value =
      '订单仍在排队处理中；继续停留或稍后刷新页面即可恢复查询。'
  } catch (e) {
    if (e.code === 401) {
      router.push({ name: 'login', query: { redirect: '/' } })
      return
    }
    asyncNotice.value = `订单状态恢复失败：${e.message}`
  }
}

async function search(page = 1) {
  error.value = ''
  if (!auth.token) {
    router.push({ name: 'login', query: { redirect: '/' } })
    return
  }
  if (!fromStation.value || !toStation.value) {
    error.value = '请选择出发站和到达站'
    return
  }
  if (fromStation.value.id === toStation.value.id) {
    error.value = '出发站和到达站不能相同'
    return
  }
  searching.value = true
  try {
    const data = await userApi.searchTickets({
      fromStationId: fromStation.value.id,
      toStationId: toStation.value.id,
      travelDate: travelDate.value,
      seatType: seatType.value || undefined,
      page,
      size: result.size,
    })
    result.records = data.records || []
    result.total = data.total || 0
    result.page = data.page || page
    searched.value = true
  } catch (e) {
    if (e.code === 401) {
      router.push({ name: 'login', query: { redirect: '/' } })
      return
    }
    error.value = e.message
    result.records = []
  } finally {
    searching.value = false
  }
}

function swapStations() {
  const value = fromStation.value
  fromStation.value = toStation.value
  toStation.value = value
}

function chooseDate(offset) {
  travelDate.value = isoDate(offset)
  search(1)
}

function formatTime(time) {
  return time ? String(time).slice(0, 5) : '--:--'
}

function formatDuration(minutes) {
  if (minutes == null) return '-'
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return `${h}时${String(m).padStart(2, '0')}分`
}

function seatMeta(type) {
  return seatTypes.find((item) => item.value === type) || {
    label: '其他席别',
    className: 'second',
  }
}

function openBooking(ticket, seat) {
  if (!auth.token) {
    toast('请先登录再购买车票', 'error')
    router.push({ name: 'login', query: { redirect: '/' } })
    return
  }
  booking.ticket = ticket
  booking.seat = seat
  booking.open = true
  booking.selectedIds = []
  booking.error = ''
  booking.idempotencyKey = ''
  booking.asyncRequestId = ''
  booking.asyncPayload = null
  booking.asyncStatusText = ''
  loadPassengers()
}

async function loadPassengers() {
  booking.loadingPassengers = true
  try {
    booking.passengers = await userApi.passengers()
  } catch (e) {
    booking.error = e.message
  } finally {
    booking.loadingPassengers = false
  }
}

function togglePassenger(id) {
  const index = booking.selectedIds.indexOf(id)
  if (index >= 0) booking.selectedIds.splice(index, 1)
  else if (booking.selectedIds.length < 5) booking.selectedIds.push(id)
}

function closeBooking() {
  if (!booking.submitting) booking.open = false
}

async function submitOrder() {
  booking.error = ''
  asyncNotice.value = ''
  if (!booking.selectedIds.length) {
    booking.error = '请至少选择一位乘车人'
    return
  }
  if (booking.asyncRequestId) {
    await continueAsyncOrder()
    return
  }
  booking.submitting = true
  let orderPayload = null
  let requestId = ''
  try {
    orderPayload = {
      trainId: booking.ticket.trainId,
      travelDate: travelDate.value,
      fromStationId: fromStation.value.id,
      toStationId: toStation.value.id,
      seatType: booking.seat.seatType,
      passengerIds: [...booking.selectedIds],
    }
    booking.idempotencyKey = pendingOrderIdempotencyKey(orderPayload)
    requestId = booking.idempotencyKey
    booking.asyncPayload = orderPayload
    savePendingAsyncOrder(requestId, orderPayload)
    const submitted = await userApi.submitAsyncOrder(
      orderPayload,
      requestId,
    )
    updateAsyncStatus(submitted)
    const latest = await pollAsyncOrder(requestId)
    if (await handleAsyncResult(latest, orderPayload)) return
    booking.error =
      '订单仍在排队处理中，已保存请求状态，可点击按钮继续查询。'
  } catch (e) {
    if (e.code === 401) {
      router.push({ name: 'login', query: { redirect: '/' } })
      return
    }
    if (requestId) {
      try {
        const latest = await userApi.asyncOrderStatus(requestId)
        updateAsyncStatus(latest)
        if (await handleAsyncResult(latest, orderPayload)) return
        booking.error =
          '下单请求已提交，但状态同步暂时失败，可点击按钮继续查询。'
      } catch {
        booking.error =
          '网络状态不确定，请求已保存在当前会话，可刷新页面继续恢复。'
      }
    } else {
      booking.error = e.message
    }
  } finally {
    booking.submitting = false
  }
}

function passengerTypeLabel(type) {
  return { 1: '成人', 2: '学生', 3: '儿童' }[type] || '乘车人'
}
</script>

<template>
  <div class="search-page">
    <section class="hero">
      <div class="hero-copy">
        <span class="eyebrow">TRAIN TICKET SEARCH</span>
        <h1>把目的地，<br /><em>变成下一站。</em></h1>
        <p>1000 个国内演示车次，分段库存实时计算。选择出发与到达，我们为你找到余票。</p>
      </div>
      <div class="hero-note">
        <span class="live-dot"></span>
        <span>{{ auth.token ? '本地演示数据 · Redis 缓存生效' : '登录后可查询实时余票并下单' }}</span>
      </div>
    </section>

    <section class="search-panel">
      <div class="station-field">
        <span class="field-index">01</span>
        <label>出发站</label>
        <StationPicker v-model="fromStation" placeholder="选择出发站" />
        <small>{{ fromStation?.city || '输入城市或车站' }}</small>
      </div>

      <button class="swap-button" type="button" title="交换出发与到达" @click="swapStations">
        ⇄
      </button>

      <div class="station-field">
        <span class="field-index">02</span>
        <label>到达站</label>
        <StationPicker v-model="toStation" placeholder="选择到达站" />
        <small>{{ toStation?.city || '输入城市或车站' }}</small>
      </div>

      <div class="date-field">
        <span class="field-index">03</span>
        <label>出发日期</label>
        <input v-model="travelDate" type="date" :min="isoDate(0)" />
        <div class="date-shortcuts">
          <button type="button" @click="chooseDate(0)">今天</button>
          <button type="button" @click="chooseDate(1)">明天</button>
          <button type="button" @click="chooseDate(2)">后天</button>
        </div>
      </div>

      <div class="seat-field">
        <span class="field-index">04</span>
        <label>席别</label>
        <select v-model="seatType">
          <option :value="null">全部席别</option>
          <option v-for="seat in seatTypes" :key="seat.value" :value="seat.value">
            {{ seat.label }}
          </option>
        </select>
        <small>不选则显示全部</small>
      </div>

      <button class="button primary search-button" :disabled="searching" @click="search(1)">
        <span v-if="!searching">查询车票</span>
        <span v-else class="button-loading"><i></i>查询中</span>
      </button>
    </section>

    <p v-if="error" class="page-alert">{{ error }}</p>
    <p v-if="asyncNotice" class="page-alert">{{ asyncNotice }}</p>

    <section v-if="searched" class="results-section">
      <div class="results-head">
        <div>
          <span class="eyebrow">AVAILABLE JOURNEYS</span>
          <h2>{{ fromStation?.stationName }} → {{ toStation?.stationName }}</h2>
        </div>
        <div class="result-meta">
          <strong>{{ result.total }}</strong>
          <span>个车次</span>
          <small>{{ travelDate }}</small>
        </div>
      </div>

      <div v-if="searching" class="skeleton-list">
        <div v-for="index in 3" :key="index" class="skeleton-card"></div>
      </div>

      <div v-else-if="result.records.length" class="ticket-list">
        <article v-for="ticket in result.records" :key="ticket.trainId" class="ticket-card">
          <div class="ticket-main">
            <div class="train-identity">
              <span class="train-badge">{{ ticket.trainType }}</span>
              <div>
                <strong>{{ ticket.trainNo }}</strong>
                <small>{{ ticket.fromStationName }} 出发</small>
              </div>
            </div>

            <div class="journey-time">
              <div>
                <strong>{{ formatTime(ticket.departTime) }}</strong>
                <span>{{ ticket.fromStationName }}</span>
              </div>
              <div class="duration-line">
                <small>{{ formatDuration(ticket.durationMinutes) }}</small>
                <span><i></i><b>→</b></span>
                <small>{{ ticket.mileage }} km</small>
              </div>
              <div class="align-right">
                <strong>{{ formatTime(ticket.arriveTime) }}</strong>
                <span>{{ ticket.toStationName }}</span>
              </div>
            </div>
          </div>

          <div class="seat-list">
            <div
              v-for="seat in ticket.seats"
              :key="seat.seatType"
              class="seat-item"
              :class="seatMeta(seat.seatType).className"
            >
              <div>
                <span>{{ seatMeta(seat.seatType).label }}</span>
                <strong>¥{{ Number(seat.price).toFixed(2) }}</strong>
              </div>
              <div class="seat-action">
                <small :class="{ sold: seat.remainCount <= 0 }">
                  {{ seat.remainCount > 0 ? `余 ${seat.remainCount} 张` : '已售完' }}
                </small>
                <button
                  class="button tiny"
                  type="button"
                  :disabled="seat.remainCount <= 0"
                  @click="openBooking(ticket, seat)"
                >
                  预订
                </button>
              </div>
            </div>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">
        <span class="empty-icon">⌁</span>
        <h3>没有找到符合条件的车次</h3>
        <p>可以尝试更换日期、席别或相邻车站。</p>
      </div>

      <div v-if="result.total > result.size" class="pager">
        <button
          class="button ghost"
          :disabled="result.page <= 1"
          @click="search(result.page - 1)"
        >
          上一页
        </button>
        <span>第 {{ result.page }} 页 / 共 {{ Math.ceil(result.total / result.size) }} 页</span>
        <button
          class="button ghost"
          :disabled="result.page >= Math.ceil(result.total / result.size)"
          @click="search(result.page + 1)"
        >
          下一页
        </button>
      </div>
    </section>

    <Transition name="fade">
      <div v-if="booking.open" class="modal-backdrop" @click.self="closeBooking">
        <div class="booking-modal">
          <button class="modal-close" type="button" @click="closeBooking">×</button>
          <div class="modal-head">
            <span class="eyebrow">CONFIRM JOURNEY</span>
            <h2>确认乘车人</h2>
            <p>
              {{ booking.ticket?.trainNo }} ·
              {{ booking.ticket?.fromStationName }} → {{ booking.ticket?.toStationName }}
            </p>
          </div>

          <div class="booking-summary">
            <div>
              <small>出发日期</small>
              <strong>{{ travelDate }}</strong>
            </div>
            <div>
              <small>席别</small>
              <strong>{{ seatMeta(booking.seat?.seatType).label }}</strong>
            </div>
            <div>
              <small>单价</small>
              <strong>¥{{ Number(booking.seat?.price || 0).toFixed(2) }}</strong>
            </div>
            <div>
              <small>预计合计</small>
              <strong class="price-accent">
                ¥{{ (Number(booking.seat?.price || 0) * selectedPassengers.length).toFixed(2) }}
              </strong>
            </div>
          </div>

          <div class="passenger-picker">
            <div class="subsection-title">
              <span>选择乘车人</span>
              <RouterLink to="/passengers" @click="booking.open = false">管理乘车人</RouterLink>
            </div>
            <div v-if="booking.loadingPassengers" class="inline-loading">正在加载乘车人…</div>
            <div v-else-if="booking.passengers.length" class="passenger-options">
              <button
                v-for="passenger in booking.passengers"
                :key="passenger.id"
                type="button"
                class="passenger-option"
                :class="{ selected: booking.selectedIds.includes(passenger.id) }"
                @click="togglePassenger(passenger.id)"
              >
                <span class="check-box">{{ booking.selectedIds.includes(passenger.id) ? '✓' : '' }}</span>
                <span>
                  <strong>{{ passenger.name }}</strong>
                  <small>{{ passenger.idCardNo }}</small>
                </span>
                <em>{{ passengerTypeLabel(passenger.passengerType) }}</em>
              </button>
            </div>
            <div v-else class="empty-inline">
              还没有乘车人，请先
              <RouterLink to="/passengers" @click="booking.open = false">添加乘车人</RouterLink>
            </div>
          </div>

          <p v-if="booking.error" class="form-error">{{ booking.error }}</p>
          <p
            v-if="booking.submitting || booking.asyncRequestId"
            class="queue-status"
          >
            <i></i>
            <span>{{ booking.asyncStatusText || '订单已进入处理队列…' }}</span>
          </p>
          <div class="modal-actions">
            <button class="button ghost" type="button" @click="closeBooking">取消</button>
            <button class="button primary" :disabled="booking.submitting" @click="submitOrder">
              {{
                booking.submitting
                  ? (booking.asyncStatusText || '订单排队中…')
                  : booking.asyncRequestId
                    ? '继续查询订单状态'
                    : '创建待支付订单'
              }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>
