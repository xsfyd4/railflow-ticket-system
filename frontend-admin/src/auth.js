import { reactive } from 'vue'

const TOKEN_KEY = 'railflow_admin_token'
const ADMIN_KEY = 'railflow_admin_profile'

function readAdmin() {
  try {
    return JSON.parse(localStorage.getItem(ADMIN_KEY) || 'null')
  } catch {
    return null
  }
}

export const auth = reactive({
  token: localStorage.getItem(TOKEN_KEY) || '',
  admin: readAdmin(),
})

export function setAuth(payload) {
  auth.token = payload.token
  auth.admin = payload.admin
  localStorage.setItem(TOKEN_KEY, payload.token)
  localStorage.setItem(ADMIN_KEY, JSON.stringify(payload.admin))
}

export function clearAuth() {
  auth.token = ''
  auth.admin = null
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ADMIN_KEY)
}

export function isLoggedIn() {
  return Boolean(auth.token)
}
