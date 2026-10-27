import axios from 'axios'
import { auth, clearAuth } from './auth'

const http = axios.create({
  baseURL: '/api',
  timeout: 20000,
})

http.interceptors.request.use((config) => {
  if (auth.token) config.headers.Authorization = `Bearer ${auth.token}`
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
    if (code === 401) clearAuth()
    const wrapped = new Error(
      error.response?.data?.msg || error.message || '网络连接失败',
    )
    wrapped.code = code
    throw wrapped
  },
)

export const adminApi = {
  login: (data) => http.post('/admin/auth/login', data),
  me: () => http.get('/admin/auth/me'),
  stations: (params) => http.get('/admin/stations', { params }),
  station: (id) => http.get(`/admin/stations/${id}`),
  createStation: (data) => http.post('/admin/stations', data),
  updateStation: (id, data) => http.put(`/admin/stations/${id}`, data),
  deleteStation: (id) => http.delete(`/admin/stations/${id}`),
  trains: (params) => http.get('/admin/trains', { params }),
  train: (id) => http.get(`/admin/trains/${id}`),
  createTrain: (data) => http.post('/admin/trains', data),
  createTrainFull: (data) => http.post('/admin/trains/full', data),
  updateTrain: (id, data) => http.put(`/admin/trains/${id}`, data),
  deleteTrain: (id) => http.delete(`/admin/trains/${id}`),
  clearTrainStocks: (id) => http.delete(`/admin/trains/${id}/stocks`),
  stops: (trainId) => http.get(`/admin/trains/${trainId}/stops`),
  saveStops: (trainId, stops) =>
    http.put(`/admin/trains/${trainId}/stops`, { stops }),
  segments: (trainId) => http.get(`/admin/trains/${trainId}/segments`),
  prices: (trainId) => http.get(`/admin/trains/${trainId}/prices`),
  savePrices: (trainId, prices) =>
    http.put(`/admin/trains/${trainId}/prices`, { prices }),
  stocks: (params) => http.get('/admin/stocks', { params }),
  generateStocks: (data) => http.post('/admin/stocks/generate', data),
  adjustStock: (id, data) => http.put(`/admin/stocks/${id}`, data),
}
