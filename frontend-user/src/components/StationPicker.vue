<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { userApi } from '../api'

const props = defineProps({
  modelValue: { type: Object, default: null },
  placeholder: { type: String, default: '输入城市或车站' },
})
const emit = defineEmits(['update:modelValue'])

const root = ref(null)
const query = ref(props.modelValue?.stationName || '')
const options = ref([])
const open = ref(false)
const loading = ref(false)
let timer = null
let requestSeq = 0

watch(
  () => props.modelValue,
  (value) => {
    if (value && value.stationName !== query.value) {
      query.value = value.stationName
    }
  },
)

async function load(keyword = '') {
  const seq = ++requestSeq
  loading.value = true
  try {
    const data = await userApi.stations(keyword)
    if (seq === requestSeq) options.value = data || []
  } catch {
    if (seq === requestSeq) options.value = []
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function onInput() {
  open.value = true
  emit('update:modelValue', null)
  clearTimeout(timer)
  timer = setTimeout(() => load(query.value.trim()), 220)
}

function choose(station) {
  query.value = station.stationName
  emit('update:modelValue', station)
  open.value = false
}

function onFocus() {
  open.value = true
  if (!options.value.length) load(query.value.trim())
}

function onOutside(event) {
  if (root.value && !root.value.contains(event.target)) open.value = false
}

onMounted(() => {
  document.addEventListener('click', onOutside)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onOutside)
  clearTimeout(timer)
})
</script>

<template>
  <div ref="root" class="station-picker">
    <input
      v-model="query"
      :placeholder="placeholder"
      autocomplete="off"
      @input="onInput"
      @focus="onFocus"
    />
    <span v-if="loading" class="picker-spinner"></span>
    <Transition name="pop">
      <div v-if="open && options.length" class="station-options">
        <button
          v-for="station in options"
          :key="station.id"
          type="button"
          @click="choose(station)"
        >
          <span>
            <strong>{{ station.stationName }}</strong>
            <small>{{ station.city }} · {{ station.province }}</small>
          </span>
          <code>{{ station.stationCode }}</code>
        </button>
      </div>
    </Transition>
  </div>
</template>
