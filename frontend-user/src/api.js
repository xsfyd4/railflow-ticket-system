import axios from 'axios'
import { auth, clearAuth } from './auth'

const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body?.code !== 200) {
      const error = new Error(body?.msg || '请求失败')
      error.code = body?.code
      throw error
    }
    return body.data
  },
  (error) => {
    const code = error.response?.data?.code
    const message = error.response?.data?.msg || error.message || '网络连接失败'
    if (code === 401) {
      clearAuth()
    }
    const wrapped = new Error(message)
    wrapped.code = code
    throw wrapped
  },
)

export const userApi = {
  login: (data) => http.post('/user/auth/login', data),
  register: (data) => http.post('/user/auth/register', data),
  me: () => http.get('/user/auth/me'),
  stations: (keyword = '') =>
    http.get('/public/stations', { params: { keyword } }),
  searchTickets: (params) => http.get('/user/tickets/search', { params }),
  trainStops: (trainId) => http.get(`/public/trains/${trainId}/stops`),
  passengers: () => http.get('/user/passengers'),
  createPassenger: (data) => http.post('/user/passengers', data),
  updatePassenger: (id, data) => http.put(`/user/passengers/${id}`, data),
  deletePassenger: (id) => http.delete(`/user/passengers/${id}`),
  createOrder: (data, idempotencyKey) =>
    http.post('/user/orders', data, {
      headers: { 'Idempotency-Key': idempotencyKey },
    }),
  orders: (params) => http.get('/user/orders', { params }),
  orderDetail: (orderNo) => http.get(`/user/orders/${orderNo}`),
  payOrder: (orderNo, idempotencyKey) =>
    http.post(
      `/user/orders/${orderNo}/pay`,
      { channel: 'MOCK' },
      { headers: { 'Idempotency-Key': idempotencyKey } },
    ),
  cancelOrder: (orderNo) => http.post(`/user/orders/${orderNo}/cancel`),
  refundOrder: (orderNo, orderItemIds) =>
    http.post(`/user/orders/${orderNo}/refund`, { orderItemIds }),
}
