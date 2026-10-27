<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '../api'
import { toast } from '../ui'

const loading = ref(false)
const saving = ref(false)
const stations = ref([])
const page = reactive({ records: [], total: 0, page: 1, size: 10 })
const query = reactive({
  trainNo: '',
  trainType: '',
  status: null,
  page: 1,
  size: 10,
})
const pages = computed(() => Math.max(1, Math.ceil(page.total / page.size)))
const editorOpen = ref(false)
const editingId = ref(null)
const editingHasStops = ref(false)
const createOpen = ref(false)
const createStep = ref(1)
const createSaving = ref(false)
const createError = ref('')
const fullForm = reactive({
  trainNo: '',
  trainType: 'G',
  status: 1,
  remark: '',
  stops: [],
})
const createPrices = ref([])
const lineOpen = ref(false)
const lineLoading = ref(false)
const lineMode = ref('view')
const lineSaving = ref(false)
const stopDraft = ref([])
const priceDraft = ref([])
const lineData = reactive({ train: null, stops: [], segments: [], prices: [] })
const form = reactive({
  trainNo: '',
  trainType: 'G',
  startStationId: null,
  endStationId: null,
  totalMileage: null,
  status: 1,
  remark: '',
})

onMounted(async () => {
  await Promise.all([loadStations(), load(1)])
})

async function loadStations() {
  const records = []
  let current = 1
  let total = 1
  while (records.length < total) {
    const data = await adminApi.stations({ page: current, size: 100, status: 1 })
    records.push(...(data.records || []))
    total = data.total || 0
    if (!data.records?.length) break
    current += 1
  }
  stations.value = records
}

async function load(target = 1) {
  loading.value = true
  try {
    const data = await adminApi.trains({
      trainNo: query.trainNo || undefined,
      trainType: query.trainType || undefined,
      status: query.status ?? undefined,
      page: target,
      size: query.size,
    })
    page.records = data.records || []
    page.total = data.total || 0
    page.page = data.page || target
    page.size = data.size || query.size
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}

function create() {
  Object.assign(fullForm, {
    trainNo: '',
    trainType: 'G',
    status: 1,
    remark: '',
    stops: [
      {
        stationId: stations.value[0]?.id || null,
        arrivalTime: null,
        departureTime: '08:00',
        dayOffset: 0,
        mileage: 0,
      },
      {
        stationId: stations.value[1]?.id || null,
        arrivalTime: '10:00',
        departureTime: null,
        dayOffset: 0,
        mileage: 100,
      },
    ],
  })
  createPrices.value = []
  createStep.value = 1
  createError.value = ''
  createOpen.value = true
}

async function edit(row) {
  try {
    const stops = await adminApi.stops(row.id)
    editingHasStops.value = stops.length > 0
    editingId.value = row.id
    Object.assign(form, {
      trainNo: row.trainNo,
      trainType: row.trainType,
      startStationId: row.startStationId,
      endStationId: row.endStationId,
      totalMileage: row.totalMileage,
      status: row.status,
      remark: row.remark || '',
    })
    editorOpen.value = true
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function save() {
  if (form.startStationId === form.endStationId) {
    toast('始发站和终到站不能相同', 'error')
    return
  }
  saving.value = true
  try {
    await adminApi.updateTrain(editingId.value, form)
    toast('车次已更新')
    editorOpen.value = false
    await load(page.page)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

function addStop() {
  if (fullForm.stops.length >= 30) return
  const last = fullForm.stops[fullForm.stops.length - 1]
  fullForm.stops.splice(fullForm.stops.length - 1, 0, {
    stationId: null,
    arrivalTime: '09:00',
    departureTime: '09:05',
    dayOffset: last?.dayOffset || 0,
    mileage: Number(last?.mileage || 0) + 100,
  })
}

function removeStop(index) {
  if (fullForm.stops.length <= 2) return
  fullForm.stops.splice(index, 1)
}

function validateCreateStep() {
  createError.value = ''
  if (createStep.value === 1) {
    if (!fullForm.trainNo.trim()) {
      createError.value = '请输入车次号'
      return false
    }
  }
  if (createStep.value === 2) {
    if (fullForm.stops.some((stop) => !stop.stationId)) {
      createError.value = '每一个停站都必须选择车站'
      return false
    }
    const ids = fullForm.stops.map((stop) => stop.stationId)
    if (new Set(ids).size !== ids.length) {
      createError.value = '同一个车站不能重复停靠'
      return false
    }
    if (!fullForm.stops[0].departureTime) {
      createError.value = '始发站必须填写发车时间'
      return false
    }
    if (!fullForm.stops.at(-1).arrivalTime) {
      createError.value = '终到站必须填写到达时间'
      return false
    }
    for (let i = 1; i < fullForm.stops.length; i++) {
      if (Number(fullForm.stops[i].mileage) <= Number(fullForm.stops[i - 1].mileage)) {
        createError.value = '累计里程必须逐站增加'
        return false
      }
    }
  }
  return true
}

function nextCreateStep() {
  if (!validateCreateStep()) return
  if (createStep.value === 2) preparePrices()
  createStep.value += 1
}

function preparePrices() {
  const segmentCount = fullForm.stops.length - 1
  const existing = new Map(
    createPrices.value.map((item) => [`${item.segmentIndex}-${item.seatType}`, item]),
  )
  const next = []
  for (let segment = 1; segment <= segmentCount; segment++) {
    for (const seatType of [1, 2, 3]) {
      const key = `${segment}-${seatType}`
      next.push(existing.get(key) || {
        segmentIndex: segment,
        seatType,
        price: '',
      })
    }
  }
  createPrices.value = next
}

function quickFillPrices() {
  const rates = {
    G: { 1: 1.45, 2: 0.75, 3: 0.46 },
    D: { 1: 0.95, 2: 0.5, 3: 0.32 },
    K: { 1: 0.55, 2: 0.28, 3: 0.15 },
    T: { 1: 0.65, 2: 0.32, 3: 0.18 },
    Z: { 1: 0.7, 2: 0.35, 3: 0.19 },
  }
  for (const item of createPrices.value) {
    const from = fullForm.stops[item.segmentIndex - 1].mileage
    const to = fullForm.stops[item.segmentIndex].mileage
    const mileage = Number(to) - Number(from)
    item.price = Math.max(0.01, mileage * rates[fullForm.trainType][item.seatType]).toFixed(2)
  }
}

async function submitFullTrain() {
  createError.value = ''
  if (createPrices.value.some((item) => !item.price || Number(item.price) <= 0)) {
    createError.value = '每一段、每一种席别都必须填写大于 0 的票价'
    return
  }
  createSaving.value = true
  try {
    await adminApi.createTrainFull({
      trainNo: fullForm.trainNo.trim(),
      trainType: fullForm.trainType,
      status: fullForm.status,
      remark: fullForm.remark,
      stops: fullForm.stops.map((stop) => ({
        stationId: Number(stop.stationId),
        arrivalTime: stop.arrivalTime || null,
        departureTime: stop.departureTime || null,
        dayOffset: Number(stop.dayOffset || 0),
        mileage: Number(stop.mileage),
      })),
      prices: createPrices.value.map((item) => ({
        segmentIndex: item.segmentIndex,
        seatType: item.seatType,
        price: Number(item.price),
      })),
    })
    toast('完整车次已创建，可以生成库存并售票')
    createOpen.value = false
    await load(1)
  } catch (e) {
    createError.value = e.message
  } finally {
    createSaving.value = false
  }
}

async function clearStocks() {
  if (!lineData.train) return
  if (!window.confirm('清空该车次全部库存后才能重新配置停站。确认继续？')) return
  try {
    await adminApi.clearTrainStocks(lineData.train.id)
    toast('库存已清空，现在可以重新配置停站')
    await openLine(lineData.train)
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function remove(row) {
  if (!window.confirm(`确认删除车次 ${row.trainNo}？`)) return
  try {
    await adminApi.deleteTrain(row.id)
    toast('车次已删除')
    await load(page.page)
  } catch (e) {
    toast(e.message, 'error')
  }
}

async function openLine(row) {
  lineOpen.value = true
  lineLoading.value = true
  lineMode.value = 'view'
  lineData.train = row
  try {
    const [stops, segments, prices] = await Promise.all([
      adminApi.stops(row.id),
      adminApi.segments(row.id),
      adminApi.prices(row.id),
    ])
    lineData.stops = stops || []
    lineData.segments = segments || []
    lineData.prices = prices || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    lineLoading.value = false
  }
}

function editStops() {
  stopDraft.value = lineData.stops.map((stop) => ({
    stationId: stop.stationId,
    arrivalTime: stop.arrivalTime ? String(stop.arrivalTime).slice(0, 5) : null,
    departureTime: stop.departureTime ? String(stop.departureTime).slice(0, 5) : null,
    dayOffset: stop.dayOffset || 0,
    mileage: stop.mileage,
  }))
  lineMode.value = 'stops'
}

function addDraftStop() {
  const last = stopDraft.value.at(-1)
  stopDraft.value.splice(Math.max(1, stopDraft.value.length - 1), 0, {
    stationId: null,
    arrivalTime: '09:00',
    departureTime: '09:05',
    dayOffset: last?.dayOffset || 0,
    mileage: Number(last?.mileage || 0) + 100,
  })
}

function removeDraftStop(index) {
  if (stopDraft.value.length > 2) stopDraft.value.splice(index, 1)
}

async function saveStops() {
  lineSaving.value = true
  try {
    await adminApi.saveStops(lineData.train.id, stopDraft.value.map((stop) => ({
      stationId: Number(stop.stationId),
      arrivalTime: stop.arrivalTime || null,
      departureTime: stop.departureTime || null,
      dayOffset: Number(stop.dayOffset || 0),
      mileage: Number(stop.mileage),
    })))
    toast('停站和分段已重建')
    await openLine(lineData.train)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    lineSaving.value = false
  }
}

function editPrices() {
  priceDraft.value = lineData.prices.map((price) => ({
    segmentIndex: price.segmentIndex,
    seatType: price.seatType,
    price: price.price,
  }))
  lineMode.value = 'prices'
}

async function savePrices() {
  lineSaving.value = true
  try {
    await adminApi.savePrices(lineData.train.id, priceDraft.value.map((price) => ({
      segmentIndex: price.segmentIndex,
      seatType: price.seatType,
      price: Number(price.price),
    })))
    toast('票价已更新，历史订单价格快照不受影响')
    await openLine(lineData.train)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    lineSaving.value = false
  }
}

function priceFor(segmentIndex, seatType) {
  return lineData.prices.find(
    (item) => item.segmentIndex === segmentIndex && item.seatType === seatType,
  )?.price
}

function time(value) {
  return value ? String(value).slice(0, 5) : '—'
}
</script>

<template>
  <div class="admin-page-block">
    <div class="admin-page-heading">
      <div>
        <span class="eyebrow">TRAIN CATALOG</span>
        <h2>车次管理</h2>
        <p>维护车次号、运行区间、车型和启停状态。</p>
      </div>
      <button class="button primary" @click="create">＋ 新增车次</button>
    </div>

    <form class="admin-filter-bar" @submit.prevent="load(1)">
      <label><span>车次号</span><input v-model.trim="query.trainNo" placeholder="如 G100" /></label>
      <label>
        <span>车型</span>
        <select v-model="query.trainType">
          <option value="">全部车型</option>
          <option v-for="type in ['G', 'D', 'K', 'T', 'Z']" :key="type" :value="type">{{ type }}</option>
        </select>
      </label>
      <label>
        <span>状态</span>
        <select v-model="query.status">
          <option :value="null">全部状态</option>
          <option :value="1">运行中</option>
          <option :value="0">停运</option>
        </select>
      </label>
      <button class="button dark">筛选</button>
    </form>

    <div class="admin-table-card">
      <div class="table-card-head">
        <span>共 {{ page.total }} 个车次</span>
        <small>第 {{ page.page }} / {{ pages }} 页</small>
      </div>
      <div class="table-scroll">
        <table class="admin-table train-table">
          <thead>
            <tr>
              <th>车次</th>
              <th>运行区间</th>
              <th>里程</th>
              <th>状态</th>
              <th>备注</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading"><td colspan="6" class="table-loading">正在加载车次数据…</td></tr>
            <tr v-for="row in page.records" :key="row.id">
              <td>
                <span class="train-list-identity">
                  <i>{{ row.trainType }}</i>
                  <strong>{{ row.trainNo }}</strong>
                </span>
              </td>
              <td>
                <span class="route-pair">
                  <strong>{{ row.startStationName }}</strong>
                  <em>→</em>
                  <strong>{{ row.endStationName }}</strong>
                </span>
              </td>
              <td class="mono">{{ row.totalMileage ?? '—' }} km</td>
              <td><span :class="['tiny-status', row.status === 1 ? 'online' : 'offline']">{{ row.status === 1 ? '运行中' : '停运' }}</span></td>
              <td class="muted dotted">{{ row.remark || '—' }}</td>
              <td>
                <div class="table-actions">
                  <button @click="openLine(row)">线路</button>
                  <button @click="edit(row)">编辑</button>
                  <button class="danger-text" @click="remove(row)">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="table-pager">
        <button :disabled="page.page <= 1" @click="load(page.page - 1)">上一页</button>
        <span>{{ page.page }} / {{ pages }}</span>
        <button :disabled="page.page >= pages" @click="load(page.page + 1)">下一页</button>
      </div>
    </div>

    <Transition name="fade">
      <div v-if="createOpen" class="modal-backdrop" @click.self="createOpen = false">
        <div class="train-wizard">
          <button class="modal-close" @click="createOpen = false">×</button>
          <div class="wizard-head">
            <div>
              <span class="eyebrow">COMPLETE TRAIN CREATION</span>
              <h2>新增完整可售车次</h2>
              <p>基础信息、停站、分段和票价一次提交，失败时全部回滚。</p>
            </div>
            <div class="wizard-steps">
              <span v-for="step in 3" :key="step" :class="{ active: createStep >= step }">{{ step }}</span>
            </div>
          </div>

          <div v-if="createStep === 1" class="wizard-body">
            <div class="wizard-title"><strong>01 / 基础信息</strong><small>定义车次编号与运营状态</small></div>
            <div class="field-row">
              <label class="field"><span>车次号 *</span><input v-model.trim="fullForm.trainNo" placeholder="如 G2001" /></label>
              <label class="field">
                <span>车型 *</span>
                <select v-model="fullForm.trainType"><option v-for="type in ['G','D','K','T','Z']" :key="type" :value="type">{{ type }}</option></select>
              </label>
            </div>
            <div class="field-row">
              <label class="field">
                <span>状态</span>
                <select v-model.number="fullForm.status"><option :value="1">运行中</option><option :value="0">停运</option></select>
              </label>
              <label class="field"><span>备注</span><input v-model.trim="fullForm.remark" placeholder="选填" /></label>
            </div>
          </div>

          <div v-if="createStep === 2" class="wizard-body">
            <div class="wizard-title"><strong>02 / 停站与时刻</strong><small>数组顺序就是列车运行顺序</small></div>
            <div class="wizard-stop-list">
              <div v-for="(stop, index) in fullForm.stops" :key="index" class="wizard-stop-row">
                <span class="wizard-stop-index">{{ index + 1 }}</span>
                <select v-model.number="stop.stationId">
                  <option :value="null">选择车站</option>
                  <option v-for="station in stations" :key="station.id" :value="station.id">{{ station.stationName }}</option>
                </select>
                <input v-model="stop.arrivalTime" type="time" title="到达时间" :disabled="index === 0" />
                <input v-model="stop.departureTime" type="time" title="发车时间" :disabled="index === fullForm.stops.length - 1" />
                <input v-model.number="stop.dayOffset" type="number" min="0" title="跨天" placeholder="+日" />
                <input v-model.number="stop.mileage" type="number" min="0" step="0.1" title="累计里程" placeholder="里程" />
                <button type="button" :disabled="fullForm.stops.length <= 2" @click="removeStop(index)">×</button>
              </div>
            </div>
            <button class="wizard-add-stop" type="button" @click="addStop">＋ 插入中间停站</button>
          </div>

          <div v-if="createStep === 3" class="wizard-body">
            <div class="wizard-title">
              <div><strong>03 / 分段票价</strong><small>共 {{ fullForm.stops.length - 1 }} 段，每段三种席别</small></div>
              <button class="button ghost small" type="button" @click="quickFillPrices">按里程快速计算</button>
            </div>
            <div class="wizard-price-grid">
              <div v-for="item in createPrices" :key="`${item.segmentIndex}-${item.seatType}`" class="wizard-price-item">
                <span>第 {{ item.segmentIndex }} 段 · {{ {1:'商务座',2:'一等座',3:'二等座'}[item.seatType] }}</span>
                <input v-model.number="item.price" type="number" min="0.01" step="0.01" placeholder="票价" />
              </div>
            </div>
          </div>

          <p v-if="createError" class="form-error">{{ createError }}</p>
          <div class="wizard-actions">
            <button class="button ghost" type="button" @click="createOpen = false">取消</button>
            <button v-if="createStep > 1" class="button ghost" @click="createStep -= 1">上一步</button>
            <button v-if="createStep < 3" class="button primary" @click="nextCreateStep">下一步</button>
            <button v-else class="button primary" :disabled="createSaving" @click="submitFullTrain">
              {{ createSaving ? '事务提交中…' : '创建完整车次' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="fade">
      <div v-if="editorOpen" class="modal-backdrop" @click.self="editorOpen = false">
        <div class="small-modal admin-modal">
          <button class="modal-close" @click="editorOpen = false">×</button>
          <span class="eyebrow">EDIT TRAIN</span>
          <h2>编辑车次</h2>
          <p v-if="editingHasStops" class="form-hint">车次已有停站，始发站、终到站和总里程由停站配置决定，不能在这里直接修改。</p>
          <form class="form-stack" @submit.prevent="save">
            <div class="field-row">
              <label class="field"><span>车次号 *</span><input v-model.trim="form.trainNo" placeholder="如 G1001" /></label>
              <label class="field">
                <span>车型 *</span>
                <select v-model="form.trainType"><option v-for="type in ['G', 'D', 'K', 'T', 'Z']" :key="type" :value="type">{{ type }}</option></select>
              </label>
            </div>
            <div class="field-row">
              <label class="field">
                <span>始发站 *</span>
                <select v-model.number="form.startStationId" :disabled="editingHasStops">
                  <option v-for="station in stations" :key="station.id" :value="station.id">{{ station.stationName }}</option>
                </select>
              </label>
              <label class="field">
                <span>终到站 *</span>
                <select v-model.number="form.endStationId" :disabled="editingHasStops">
                  <option v-for="station in stations" :key="station.id" :value="station.id">{{ station.stationName }}</option>
                </select>
              </label>
            </div>
            <div class="field-row">
              <label class="field"><span>全程里程</span><input v-model.number="form.totalMileage" type="number" step="0.1" :disabled="editingHasStops" /></label>
              <label class="field">
                <span>状态</span>
                <select v-model.number="form.status"><option :value="1">运行中</option><option :value="0">停运</option></select>
              </label>
            </div>
            <label class="field"><span>备注</span><textarea v-model.trim="form.remark" rows="3"></textarea></label>
            <div class="modal-actions">
              <button class="button ghost" type="button" @click="editorOpen = false">取消</button>
              <button class="button primary" :disabled="saving">{{ saving ? '保存中…' : '保存车次' }}</button>
            </div>
          </form>
        </div>
      </div>
    </Transition>

    <Transition name="fade">
      <div v-if="lineOpen" class="modal-backdrop" @click.self="lineOpen = false">
        <div class="line-modal">
          <button class="modal-close" @click="lineOpen = false">×</button>
          <span class="eyebrow">ROUTE INSPECTOR</span>
          <h2>{{ lineData.train?.trainNo }} · 线路详情</h2>
          <div v-if="lineLoading" class="inline-loading">正在读取停站、分段和票价…</div>
          <template v-else>
            <div class="line-policy-note">
              <span>停站决定分段编号；有订单禁止改线，有库存需先清空库存。</span>
              <button type="button" class="danger-text" @click="clearStocks">清空该车次库存</button>
            </div>

            <div v-if="lineMode === 'view'" class="line-mode-actions">
              <button class="button ghost small" @click="editStops">编辑停站</button>
              <button class="button ghost small" @click="editPrices">编辑票价</button>
            </div>

            <template v-if="lineMode === 'view'">
              <div class="stop-timeline">
                <div v-for="stop in lineData.stops" :key="stop.id" class="stop-node">
                  <span class="stop-index">{{ stop.stopOrder }}</span>
                  <div><strong>{{ stop.stationName }}</strong><small>{{ time(stop.arrivalTime) }} 到 · {{ time(stop.departureTime) }} 发</small></div>
                  <em>{{ stop.mileage }} km</em>
                </div>
              </div>
              <div v-if="lineData.segments.length" class="segment-price-grid">
                <div v-for="segment in lineData.segments" :key="segment.id">
                  <strong>第 {{ segment.segmentIndex }} 段</strong>
                  <small>{{ segment.fromStationName }} → {{ segment.toStationName }}</small>
                  <span>二等 ¥{{ priceFor(segment.segmentIndex, 3) ?? '—' }}</span>
                </div>
              </div>
              <p v-else class="form-hint">该车次还没有配置分段和票价。</p>
            </template>

            <template v-if="lineMode === 'stops'">
              <div class="wizard-stop-list">
                <div v-for="(stop, index) in stopDraft" :key="index" class="wizard-stop-row">
                  <span class="wizard-stop-index">{{ index + 1 }}</span>
                  <select v-model.number="stop.stationId">
                    <option :value="null">选择车站</option>
                    <option v-for="station in stations" :key="station.id" :value="station.id">{{ station.stationName }}</option>
                  </select>
                  <input v-model="stop.arrivalTime" type="time" title="到达时间" :disabled="index === 0" />
                  <input v-model="stop.departureTime" type="time" title="发车时间" :disabled="index === stopDraft.length - 1" />
                  <input v-model.number="stop.dayOffset" type="number" min="0" title="跨天" />
                  <input v-model.number="stop.mileage" type="number" min="0" step="0.1" title="累计里程" />
                  <button type="button" :disabled="stopDraft.length <= 2" @click="removeDraftStop(index)">×</button>
                </div>
              </div>
              <button class="wizard-add-stop" type="button" @click="addDraftStop">＋ 插入中间停站</button>
              <div class="wizard-actions">
                <button class="button ghost" @click="lineMode = 'view'">取消</button>
                <button class="button primary" :disabled="lineSaving" @click="saveStops">
                  {{ lineSaving ? '保存中…' : '保存停站并重建分段' }}
                </button>
              </div>
            </template>

            <template v-if="lineMode === 'prices'">
              <div class="wizard-price-grid">
                <div v-for="price in priceDraft" :key="`${price.segmentIndex}-${price.seatType}`" class="wizard-price-item">
                  <span>第 {{ price.segmentIndex }} 段 · {{ {1:'商务座',2:'一等座',3:'二等座'}[price.seatType] }}</span>
                  <input v-model.number="price.price" type="number" min="0.01" step="0.01" />
                </div>
              </div>
              <div class="wizard-actions">
                <button class="button ghost" @click="lineMode = 'view'">取消</button>
                <button class="button primary" :disabled="lineSaving" @click="savePrices">
                  {{ lineSaving ? '保存中…' : '保存全部票价' }}
                </button>
              </div>
            </template>
          </template>
        </div>
      </div>
    </Transition>
  </div>
</template>
