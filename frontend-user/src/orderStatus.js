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
