<script setup>
import { onMounted, ref } from 'vue'
import { adminApi } from '../api'
import { toast } from '../ui'

const loading = ref(true)
const stationTotal = ref(0)
const trainTotal = ref(0)
const activeTrainTotal = ref(0)
const recentTrains = ref([])

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [stations, trains, activeTrains] = await Promise.all([
      adminApi.stations({ page: 1, size: 1 }),
      adminApi.trains({ page: 1, size: 6 }),
      adminApi.trains({ page: 1, size: 1, status: 1 }),
    ])
    stationTotal.value = stations.total
    trainTotal.value = trains.total
    activeTrainTotal.value = activeTrains.total
    recentTrains.value = trains.records || []
  } catch (e) {
    toast(e.message, 'error')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="dashboard-page">
    <section class="dashboard-hero">
      <div>
        <span class="eyebrow">SYSTEM OVERVIEW</span>
        <h2>今天，也要让每张票<br />走到正确的车厢。</h2>
        <p>运营后台已连接本地 MySQL、Redis 与 RabbitMQ 服务。</p>
      </div>
      <div class="dashboard-stamp">
        <small>LOCAL TIME</small>
        <strong>{{ new Date().toLocaleDateString('zh-CN') }}</strong>
        <span>运营正常</span>
      </div>
    </section>

    <section class="metric-grid">
      <article class="metric-card">
        <span class="metric-icon">⌖</span>
        <div><small>车站总数</small><strong>{{ loading ? '—' : stationTotal }}</strong></div>
        <RouterLink to="/stations">管理车站 →</RouterLink>
      </article>
      <article class="metric-card accent">
        <span class="metric-icon">⇌</span>
        <div><small>车次总数</small><strong>{{ loading ? '—' : trainTotal }}</strong></div>
        <RouterLink to="/trains">管理车次 →</RouterLink>
      </article>
      <article class="metric-card dark">
        <span class="metric-icon">▦</span>
        <div><small>启用车次</small><strong>{{ loading ? '—' : activeTrainTotal }}</strong></div>
        <RouterLink to="/stocks">查看库存 →</RouterLink>
      </article>
    </section>

    <section class="dashboard-columns">
      <article class="panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">RECENT TRAINS</span>
            <h2>车次数据</h2>
          </div>
          <RouterLink to="/trains">全部车次</RouterLink>
        </div>
        <div v-if="!loading" class="dashboard-train-list">
          <div v-for="train in recentTrains" :key="train.id">
            <span class="train-type-dot">{{ train.trainType }}</span>
            <div>
              <strong>{{ train.trainNo }}</strong>
              <small>{{ train.startStationName }} → {{ train.endStationName }}</small>
            </div>
            <span :class="['tiny-status', train.status === 1 ? 'online' : 'offline']">
              {{ train.status === 1 ? '运行中' : '停运' }}
            </span>
          </div>
        </div>
        <div v-else class="inline-loading">正在汇总系统数据…</div>
      </article>

      <article class="panel quick-panel">
        <span class="eyebrow">QUICK ACTIONS</span>
        <h2>常用操作</h2>
        <RouterLink to="/stations">
          <span>＋</span><div><strong>维护车站</strong><small>新增、编辑、停用车站</small></div>
        </RouterLink>
        <RouterLink to="/trains">
          <span>⇌</span><div><strong>维护车次</strong><small>管理运行区间和状态</small></div>
        </RouterLink>
        <RouterLink to="/stocks">
          <span>▦</span><div><strong>生成库存</strong><small>按日期批量生成余票</small></div>
        </RouterLink>
      </article>
    </section>
  </div>
</template>
