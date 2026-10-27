<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { adminApi } from '../api'
import { toast } from '../ui'

const loading = ref(false)
const saving = ref(false)
const page = reactive({ records: [], total: 0, page: 1, size: 10 })
const query = reactive({ keyword: '', city: '', status: null, page: 1, size: 10 })
const editorOpen = ref(false)
const editingId = ref(null)
const form = reactive({
  stationCode: '',
  stationName: '',
  city: '',
  province: '',
  pinyin: '',
  pinyinAbbr: '',
  status: 1,
})
const pages = computed(() => Math.max(1, Math.ceil(page.total / page.size)))

onMounted(() => load(1))

async function load(target = 1) {
  loading.value = true
  try {
    const data = await adminApi.stations({
      keyword: query.keyword || undefined,
      city: query.city || undefined,
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
  editingId.value = null
  Object.assign(form, {
    stationCode: '',
    stationName: '',
    city: '',
    province: '',
    pinyin: '',
    pinyinAbbr: '',
    status: 1,
  })
  editorOpen.value = true
}

function edit(row) {
  editingId.value = row.id
  Object.assign(form, row)
  editorOpen.value = true
}

async function save() {
  saving.value = true
  try {
    if (editingId.value) {
      await adminApi.updateStation(editingId.value, form)
      toast('车站信息已更新')
    } else {
      await adminApi.createStation(form)
      toast('车站已创建')
    }
    editorOpen.value = false
    await load(editingId.value ? page.page : 1)
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  if (!window.confirm(`确认删除车站 ${row.stationName}？`)) return
  try {
    await adminApi.deleteStation(row.id)
    toast('车站已删除')
    await load(page.page)
  } catch (e) {
    toast(e.message, 'error')
  }
}
</script>

<template>
  <div class="admin-page-block">
    <div class="admin-page-heading">
      <div>
        <span class="eyebrow">STATION DIRECTORY</span>
        <h2>车站基础数据</h2>
        <p>维护车站三字码、城市、拼音与启停状态。</p>
      </div>
      <button class="button primary" @click="create">＋ 新增车站</button>
    </div>

    <form class="admin-filter-bar" @submit.prevent="load(1)">
      <label>
        <span>关键词</span>
        <input v-model.trim="query.keyword" placeholder="站名 / 拼音 / 首字母" />
      </label>
      <label>
        <span>城市</span>
        <input v-model.trim="query.city" placeholder="精确城市" />
      </label>
      <label>
        <span>状态</span>
        <select v-model="query.status">
          <option :value="null">全部状态</option>
          <option :value="1">启用</option>
          <option :value="0">停用</option>
        </select>
      </label>
      <button class="button dark">筛选</button>
    </form>

    <div class="admin-table-card">
      <div class="table-card-head">
        <span>共 {{ page.total }} 个车站</span>
        <small>第 {{ page.page }} / {{ pages }} 页</small>
      </div>
      <div class="table-scroll">
        <table class="admin-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>车站</th>
              <th>三字码</th>
              <th>城市 / 省份</th>
              <th>拼音</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading"><td colspan="7" class="table-loading">正在加载车站数据…</td></tr>
            <tr v-for="row in page.records" :key="row.id">
              <td class="mono muted">{{ row.id }}</td>
              <td><strong>{{ row.stationName }}</strong></td>
              <td><code class="code-chip">{{ row.stationCode }}</code></td>
              <td><span>{{ row.city }}</span><small class="block-muted">{{ row.province }}</small></td>
              <td><span>{{ row.pinyin }}</span><small class="block-muted">{{ row.pinyinAbbr }}</small></td>
              <td><span :class="['tiny-status', row.status === 1 ? 'online' : 'offline']">{{ row.status === 1 ? '启用' : '停用' }}</span></td>
              <td>
                <div class="table-actions">
                  <button @click="edit(row)">编辑</button>
                  <button class="danger-text" @click="remove(row)">删除</button>
                </div>
              </td>
            </tr>
            <tr v-if="!loading && !page.records.length"><td colspan="7" class="table-empty">暂无车站</td></tr>
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
      <div v-if="editorOpen" class="modal-backdrop" @click.self="editorOpen = false">
        <div class="small-modal admin-modal">
          <button class="modal-close" @click="editorOpen = false">×</button>
          <span class="eyebrow">{{ editingId ? 'EDIT STATION' : 'NEW STATION' }}</span>
          <h2>{{ editingId ? '编辑车站' : '新增车站' }}</h2>
          <form class="form-stack" @submit.prevent="save">
            <div class="field-row">
              <label class="field"><span>车站名称 *</span><input v-model.trim="form.stationName" placeholder="如 北京南" /></label>
              <label class="field"><span>三字码 *</span><input v-model.trim="form.stationCode" placeholder="如 BJN" /></label>
            </div>
            <div class="field-row">
              <label class="field"><span>城市 *</span><input v-model.trim="form.city" /></label>
              <label class="field"><span>省份</span><input v-model.trim="form.province" /></label>
            </div>
            <div class="field-row">
              <label class="field"><span>全拼</span><input v-model.trim="form.pinyin" /></label>
              <label class="field"><span>拼音首字母</span><input v-model.trim="form.pinyinAbbr" /></label>
            </div>
            <label class="field">
              <span>状态</span>
              <select v-model.number="form.status"><option :value="1">启用</option><option :value="0">停用</option></select>
            </label>
            <div class="modal-actions">
              <button class="button ghost" type="button" @click="editorOpen = false">取消</button>
              <button class="button primary" :disabled="saving">{{ saving ? '保存中…' : '保存车站' }}</button>
            </div>
          </form>
        </div>
      </div>
    </Transition>
  </div>
</template>
