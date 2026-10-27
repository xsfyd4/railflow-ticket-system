import { reactive } from 'vue'

export const toasts = reactive([])
let nextId = 1

export function toast(message, type = 'success') {
  const id = nextId++
  toasts.push({ id, message, type })
  setTimeout(() => {
    const index = toasts.findIndex((item) => item.id === id)
    if (index >= 0) toasts.splice(index, 1)
  }, 3200)
}
