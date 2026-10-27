<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '../api'
import { toast } from '../ui'

function isoDate(offset = 0) {
  const date = new Date()
  date.setDate(date.getDate() + offset)
  return date.toISOString().slice(0, 10)
}

const trains = ref([])
const trainId = ref(1)
const travelDate = ref(isoDate(1))
const stocks = ref([])
const loading = ref(false)
const generateOpen = ref(false)
const generateSaving = ref(false)
const generateForm = reactive({ startDate: isoDate(1), days: 15 })
const adjustOpen = ref(false)
const adjustSaving = ref(false)
const adjusting = ref(null)
const remainingCount = ref(0)

const seatGroups = computed(() => {
  const groups = { 1: '商务座', 2: '一等座', 3: '二等座' }
  return Object.entries(groups).map(([type, label]) => ({
    type: Number(type),
    label,
    rows: stocks.value.filter((item) => item.seatType === Number(type)),
  }))
})
const totalRemaining = computed(() =>
  stocks.value.reduce((sum, item) => sum + Number(item.remainingCount || 0), 0),
)
const selectedTrain = computed(() => trains.value.find((item) => item.id === trainId.value))

onMounted(async () => {
  await loadTrains()
  await load()
})

async function loadTrains() {
  const records = []
  let current = 1
  let total = 1
  while (records.length < total) {
    const data = await adminApi.trains({ page: current, size: 100, status: 1 })
    records.push(...(data.records || []))
    total = data.total || 0
    if (!data.records?.length) break
    current += 1
  }
  trains.value = records
  if (!records.some((item) => item.id === trainId.value)) {
    trainId.value = records[0]?.id || null
  }
}

async function load() {
  if (!trainId.value || !travelDate.value) return
  loading.value = true
  try {
    stocks.value = await adminApi.stocks({
      trainId: trainId.value,
      travelDate: travelDate.value,
    })
  } catch (e) {
    stocks.value = []
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

function openGenerate() {
  generateForm.startDate = isoDate(1)
  generateForm.days = 15
  generateOpen.value = true
}

async function generate() {
  generateSaving.value = true
  try {
    const count = await adminApi.generateStocks(generateForm)
    toast(`库存生成完成，新增 ${count} 条`)
    generateOpen.value = false
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    generateSaving.value = false
  }
}

function openAdjust(row) {
  adjusting.value = row
  remainingCount.value = row.remainingCount
  adjustOpen.value = true
}

async function saveAdjust() {
  adjustSaving.value = true
  try {
    await adminApi.adjustStock(adjusting.value.id, {
      remainingCount: Number(remainingCount.value),
    })
    toast('库存已调整')
    adjustOpen.value = false
    await load()
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    adjustSaving.value = false
  }
}

function seatLabel(type) {
  return { 1: '商务座', 2: '一等座', 3: '二等座' }[type] || '席别'
}
</script>

<template>
  <div class="admin-page-block">
    <div class="admin-page-heading">
      <div>
        <span class="eyebrow">INVENTORY CONTROL</span>
        <h2>分段库存</h2>
        <p>按日期和车次查看每段、每席别余票，并支持批量生成和手工调整。</p>
      </div>
      <button class="button primary" @click="openGenerate">＋ 生成库存</button>
    </div>

    <div class="stock-toolbar">
      <label>
        <span>车次</span>
        <select v-model.number="trainId" @change="load">
          <option v-for="train in trains" :key="train.id" :value="train.id">
            {{ train.trainNo }} · {{ train.startStationName }} → {{ train.endStationName }}
          </option>
        </select>
      </label>
      <label>
        <span>乘车日期</span>
        <input v-model="travelDate" type="date" @change="load" />
      </label>
      <button class="button dark" @click="load">刷新库存</button>
    </div>

    <section class="stock-overview">
      <div>
        <small>当前车次</small>
        <strong>{{ selectedTrain?.trainNo || '—' }}</strong>
      </div>
      <div>
        <small>库存日期</small>
        <strong>{{ travelDate }}</strong>
      </div>
      <div>
        <small>库存记录</small>
        <strong>{{ stocks.length }} 条</strong>
      </div>
      <div class="highlight">
        <small>当前余票合计</small>
        <strong>{{ totalRemaining }}</strong>
      </div>
    </section>

    <div v-if="loading" class="skeleton-list">
      <div v-for="index in 3" :key="index" class="skeleton-card compact"></div>
    </div>
    <div v-else-if="stocks.length" class="seat-stock-grid">
      <article v-for="group in seatGroups" :key="group.type" class="seat-stock-column">
        <div class="seat-stock-head">
          <span>{{ group.label }}</span>
          <strong>{{ group.rows.length }} 段</strong>
        </div>
        <div v-for="row in group.rows" :key="row.id" class="stock-row">
          <div>
            <strong>第 {{ row.segmentIndex }} 段</strong>
            <small>{{ row.fromStationName }} → {{ row.toStationName }}</small>
          </div>
          <div class="stock-numbers">
            <span><b>{{ row.remainingCount }}</b> 余票</span>
            <small>总 {{ row.totalCount }} / 售 {{ row.soldCount }}</small>
          </div>
          <button type="button" @click="openAdjust(row)">调整</button>
        </div>
      </article>
    </div>
    <div v-else class="empty-admin-state">
      <span>▦</span>
      <h3>该日期还没有库存</h3>
      <p>可以点击“生成库存”为未来日期批量创建分段余票。</p>
    </div>

    <Transition name="fade">
      <div v-if="generateOpen" class="modal-backdrop" @click.self="generateOpen = false">
        <div class="small-modal admin-modal">
          <button class="modal-close" @click="generateOpen = false">×</button>
          <span class="eyebrow">BATCH GENERATION</span>
          <h2>批量生成库存</h2>
          <p class="form-hint">对每个启用分段和席别生成一条库存，已存在的数据不会覆盖。</p>
          <form class="form-stack" @submit.prevent="generate">
            <label class="field"><span>开始日期</span><input v-model="generateForm.startDate" type="date" :min="isoDate(0)" /></label>
            <label class="field"><span>生成天数</span><input v-model.number="generateForm.days" type="number" min="1" max="60" /></label>
            <div class="modal-actions">
              <button class="button ghost" type="button" @click="generateOpen = false">取消</button>
              <button class="button primary" :disabled="generateSaving">{{ generateSaving ? '生成中…' : '开始生成' }}</button>
            </div>
          </form>
        </div>
      </div>
    </Transition>

    <Transition name="fade">
      <div v-if="adjustOpen" class="modal-backdrop" @click.self="adjustOpen = false">
        <div class="small-modal admin-modal">
          <button class="modal-close" @click="adjustOpen = false">×</button>
          <span class="eyebrow">STOCK ADJUSTMENT</span>
          <h2>调整余票</h2>
          <p class="form-hint">
            {{ adjusting?.fromStationName }} → {{ adjusting?.toStationName }} ·
            {{ seatLabel(adjusting?.seatType) }}
          </p>
          <form class="form-stack" @submit.prevent="saveAdjust">
            <label class="field">
              <span>剩余票数</span>
              <input v-model.number="remainingCount" type="number" min="0" :max="adjusting?.totalCount" />
              <small>总票数 {{ adjusting?.totalCount }} 张</small>
            </label>
            <div class="modal-actions">
              <button class="button ghost" type="button" @click="adjustOpen = false">取消</button>
              <button class="button primary" :disabled="adjustSaving">{{ adjustSaving ? '保存中…' : '保存调整' }}</button>
            </div>
          </form>
        </div>
      </div>
    </Transition>
  </div>
</template>
