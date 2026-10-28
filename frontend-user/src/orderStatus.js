export const ORDER_STATUS = {
  0: { label: '待支付', tone: 'warning' },
  1: { label: '已支付', tone: 'success' },
  2: { label: '已取消', tone: 'muted' },
  3: { label: '部分退票', tone: 'partial' },
  4: { label: '全部退票', tone: 'muted' },
  5: { label: '已完成', tone: 'success' },
}

export const SEAT_LABELS = {
  1: '商务座',
  2: '一等座',
  3: '二等座',
}

export function orderStatus(status) {
  return ORDER_STATUS[status] || { label: '未知状态', tone: 'muted' }
}

export function seatLabel(type) {
  return SEAT_LABELS[type] || '其他席别'
}

export function money(value) {
  return Number(value || 0).toFixed(2)
}

export function dateTime(value) {
  if (!value) return '-'
  return String(value).replace('T', ' ').slice(0, 16)
}

export function newIdempotencyKey() {
  return crypto.randomUUID()
}

const ORDER_PREFIX = 'ticket:pending:order:'
const PAY_PREFIX = 'ticket:pending:pay:'
const KEY_TTL_MS = 24 * 60 * 60 * 1000

function orderFingerprint(payload) {
  return JSON.stringify({
    trainId: payload.trainId,
    travelDate: payload.travelDate,
    fromStationId: payload.fromStationId,
    toStationId: payload.toStationId,
    seatType: payload.seatType,
    passengerIds: [...payload.passengerIds].sort((a, b) => a - b),
  })
}

function persistentKey(storageKey) {
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey))
    if (saved?.idempotencyKey && Date.now() - saved.createdAt < KEY_TTL_MS) {
      return saved.idempotencyKey
    }
  } catch {
    // 损坏的本地数据直接覆盖。
  }
  const idempotencyKey = newIdempotencyKey()
  sessionStorage.setItem(
    storageKey,
    JSON.stringify({ idempotencyKey, createdAt: Date.now() }),
  )
  return idempotencyKey
}

export function pendingOrderIdempotencyKey(payload) {
  return persistentKey(
    `${ORDER_PREFIX}${userScope()}:${orderFingerprint(payload)}`,
  )
}

export function clearPendingOrderIdempotencyKey(payload) {
  sessionStorage.removeItem(
    `${ORDER_PREFIX}${userScope()}:${orderFingerprint(payload)}`,
  )
}

export function paymentIdempotencyKey(orderNo) {
  return persistentKey(`${PAY_PREFIX}${userScope()}:${orderNo}`)
}

export function clearPaymentIdempotencyKey(orderNo) {
  sessionStorage.removeItem(`${PAY_PREFIX}${userScope()}:${orderNo}`)
}
import { auth } from './auth'


function userScope() {
  return auth.user?.id || 'anonymous'
}
