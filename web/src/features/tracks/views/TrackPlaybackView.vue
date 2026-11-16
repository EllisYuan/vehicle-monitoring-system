<script setup lang="ts">
/* global AbortController, setInterval, clearInterval */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorCode, errorMessage } from '../../../shared/http'
import { formatTime, getSnapshot, type MonitorVehicle } from '../../monitoring/api'
import { GAP_LABELS, getTrack, type TrackView } from '../api'
import TrackMap from '../components/TrackMap.vue'

const route = useRoute()
const vehicles = ref<MonitorVehicle[]>([])
const vehicleId = ref<number | null>(null)
const fromInput = ref('')
const toInput = ref('')
const track = ref<TrackView | null>(null)
const loading = ref(false)
const listError = ref('')
const error = ref('')
const tooDense = ref(false)

const speeds = [1, 2, 4, 8]
const speed = ref(1)
const playing = ref(false)
const currentMs = ref(0)
let timer: ReturnType<typeof setInterval> | undefined

const startMs = computed(() => {
  const segments = track.value?.segments
  return segments?.length ? Date.parse(segments[0].startTime) : 0
})
const endMs = computed(() => {
  const segments = track.value?.segments
  return segments?.length ? Date.parse(segments[segments.length - 1].endTime) : 0
})
const duration = computed(() => Math.max(0, endMs.value - startMs.value))
const seekValue = computed(() => Math.max(0, Math.round(currentMs.value - startMs.value)))
const state = computed(() => (playing.value ? 'playing' : track.value ? (currentMs.value > startMs.value ? 'paused' : 'ready') : 'idle'))

const marker = computed(() => {
  if (!track.value) return null
  const time = currentMs.value
  for (const segment of track.value.segments) {
    const start = Date.parse(segment.startTime)
    const end = Date.parse(segment.endTime)
    if (time < start || time > end) continue
    const points = segment.points
    if (!points.length) return null
    if (points.length === 1) return { lng: points[0].lng, lat: points[0].lat }
    for (let index = 0; index < points.length - 1; index += 1) {
      const from = Date.parse(points[index].gpsTime)
      const to = Date.parse(points[index + 1].gpsTime)
      if (time >= from && time <= to) {
        // 只在同一分段内插值，断点区间不生成位置。
        const ratio = to === from ? 0 : (time - from) / (to - from)
        return {
          lng: points[index].lng + (points[index + 1].lng - points[index].lng) * ratio,
          lat: points[index].lat + (points[index + 1].lat - points[index].lat) * ratio,
        }
      }
    }
    return { lng: points[0].lng, lat: points[0].lat }
  }
  return null
})
const inGap = computed(() => Boolean(track.value?.segments.length) && marker.value === null)
const clockText = computed(() => (track.value?.segments.length ? formatTime(new Date(currentMs.value).toISOString()) : '—'))

function shanghaiInput(date: Date): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date).replace(' ', 'T')
}

async function loadVehicles() {
  listError.value = ''
  try {
    const snapshot = await getSnapshot(new AbortController().signal)
    vehicles.value = snapshot.vehicles
    const requested = Number(route.query.vehicleId)
    if (Number.isFinite(requested) && vehicles.value.some(vehicle => vehicle.vehicleId === requested)) {
      vehicleId.value = requested
    }
  } catch (e) {
    listError.value = errorMessage(e, '车辆列表加载失败')
  }
}

async function query() {
  if (vehicleId.value === null) {
    error.value = '请选择车辆'
    return
  }
  if (!fromInput.value || !toInput.value) {
    error.value = '请选择开始与结束时间'
    return
  }
  loading.value = true
  error.value = ''
  tooDense.value = false
  playing.value = false
  try {
    track.value = await getTrack(vehicleId.value, fromInput.value, toInput.value)
    currentMs.value = startMs.value
  } catch (e) {
    track.value = null
    tooDense.value = errorCode(e) === 'TRACK_TOO_DENSE'
    error.value = errorMessage(e, '轨迹查询失败')
  } finally {
    loading.value = false
  }
}

function play() {
  if (!track.value || duration.value <= 0) return
  if (currentMs.value >= endMs.value) currentMs.value = startMs.value
  playing.value = true
}

function pause() {
  playing.value = false
}

function seek(value: number) {
  if (!track.value) return
  playing.value = false
  currentMs.value = Math.min(endMs.value, startMs.value + Math.max(0, value))
}

function setSpeed(value: number) {
  speed.value = value
}

onMounted(() => {
  const now = new Date()
  toInput.value = shanghaiInput(now)
  fromInput.value = shanghaiInput(new Date(now.getTime() - 60 * 60 * 1000))
  loadVehicles()
  timer = setInterval(() => {
    if (!playing.value || !track.value) return
    currentMs.value = Math.min(endMs.value, currentMs.value + 200 * speed.value)
    if (currentMs.value >= endMs.value) playing.value = false
  }, 200)
})

onBeforeUnmount(() => {
  clearInterval(timer)
})
</script>

<template>
  <AppShell area="monitoring">
    <section class="page" aria-labelledby="track-title">
      <header class="page-header">
        <div>
          <span class="eyebrow">TRACK PLAYBACK</span>
          <h1 id="track-title">轨迹回放</h1>
        </div>
        <p class="scope-note">
          单次最多 24 小时；展示点按分段与 Douglas-Peucker 抽稀，断点处不连线、不插值。
        </p>
      </header>

      <p v-if="listError" class="error" role="alert" data-testid="track-list-error">{{ listError }}</p>

      <form class="filters" @submit.prevent="query">
        <label>
          <span>车辆</span>
          <select v-model.number="vehicleId" data-testid="track-vehicle">
            <option :value="null" disabled>请选择车辆</option>
            <option v-for="vehicle in vehicles" :key="vehicle.vehicleId" :value="vehicle.vehicleId">
              {{ vehicle.plateNo }} · {{ vehicle.fleetName }}
            </option>
          </select>
        </label>
        <label>
          <span>开始时间（上海时间）</span>
          <input v-model="fromInput" type="datetime-local" data-testid="track-from">
        </label>
        <label>
          <span>结束时间（上海时间）</span>
          <input v-model="toInput" type="datetime-local" data-testid="track-to">
        </label>
        <button type="submit" :disabled="loading" data-testid="track-query">
          {{ loading ? '查询中…' : '查询轨迹' }}
        </button>
      </form>

      <p v-if="error" class="error" role="alert" data-testid="track-error">{{ error }}</p>
      <p v-if="tooDense" class="hint" data-testid="track-too-dense">
        必要点已经超过 2000 个，请缩小时间范围后重新查询；系统不会静默截断。
      </p>

      <template v-if="track">
        <section class="summary" data-testid="track-summary">
          <div><span>车辆</span><strong>{{ track.plateNo }}</strong></div>
          <div><span>原始点</span><strong data-testid="track-raw-count">{{ track.rawPointCount }}</strong></div>
          <div><span>无效点</span><strong>{{ track.invalidPointCount }}</strong></div>
          <div><span>展示点</span><strong data-testid="track-display-count">{{ track.displayPointCount }}</strong></div>
          <div><span>分段</span><strong data-testid="track-segment-count">{{ track.segments.length }}</strong></div>
          <div><span>断点</span><strong data-testid="track-gap-count">{{ track.gaps.length }}</strong></div>
          <div class="range"><span>时间范围</span><strong>{{ formatTime(track.from) }} — {{ formatTime(track.to) }}</strong></div>
        </section>

        <div
          class="playback"
          data-testid="track-playback"
          :data-state="state"
          :data-speed="speed"
          :data-clock-ms="Math.round(currentMs)"
          :data-in-gap="inGap"
        >
          <div class="workspace">
            <aside class="side">
              <section class="card">
                <h2>回放控制</h2>
                <p class="clock" data-testid="track-clock">{{ clockText }}</p>
                <p v-if="inGap" class="gap-indicator" data-testid="track-in-gap">断点缺失位置，不跨段插值</p>
                <div class="controls">
                  <button type="button" data-testid="track-play" @click="play">播放</button>
                  <button type="button" data-testid="track-pause" @click="pause">暂停</button>
                </div>
                <label class="seek">
                  <span>时间轴</span>
                  <input
                    type="range"
                    data-testid="track-seek"
                    min="0"
                    :max="duration"
                    step="1000"
                    :value="seekValue"
                    @input="seek(Number(($event.target as HTMLInputElement).value))"
                  >
                </label>
                <div class="speeds" role="group" aria-label="回放倍速">
                  <button
                    v-for="value in speeds"
                    :key="value"
                    type="button"
                    :data-testid="`track-speed-${value}`"
                    :aria-pressed="speed === value"
                    :class="{ active: speed === value }"
                    @click="setSpeed(value)"
                  >
                    {{ value }}×
                  </button>
                </div>
              </section>

              <section class="card">
                <h2>断点</h2>
                <p v-if="!track.gaps.length" class="hint" data-testid="track-gaps-empty">无断点</p>
                <ul v-else class="gaps" data-testid="track-gaps">
                  <li v-for="(gap, index) in track.gaps" :key="`${gap.startTime}-${gap.reason}`" :data-testid="`track-gap-${index}`">
                    <strong>{{ GAP_LABELS[gap.reason] ?? gap.reason }}</strong>
                    <span>{{ formatTime(gap.startTime) }} — {{ formatTime(gap.endTime) }}</span>
                  </li>
                </ul>
              </section>

              <section class="card">
                <h2>分段</h2>
                <p v-if="!track.segments.length" class="hint" data-testid="track-empty">该范围内没有有效轨迹点</p>
                <ol v-else class="segments">
                  <li v-for="(segment, index) in track.segments" :key="`${segment.startTime}-${index}`">
                    第 {{ index + 1 }} 段 · {{ segment.points.length }} 点 ·
                    {{ formatTime(segment.startTime) }} — {{ formatTime(segment.endTime) }}
                  </li>
                </ol>
              </section>
            </aside>

            <div class="map-panel">
              <TrackMap :track="track" :marker="marker" />
            </div>
          </div>
        </div>
      </template>
    </section>
  </AppShell>
</template>

<style scoped>
.page { max-width: 1680px; margin: 0 auto; padding: 20px 24px 28px; color: #243c50; font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 13px; }
.page-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; margin-bottom: 16px; }
.eyebrow { color: #64788a; font-size: 9px; font-weight: 700; letter-spacing: 1.6px; }
h1 { margin: 4px 0 0; font-size: 24px; font-weight: 650; letter-spacing: 1px; }
.scope-note { margin: 0; max-width: 620px; color: #5b6b7c; font-size: 11px; line-height: 1.7; }
.filters { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 12px; padding: 14px 16px; background: #fff; border: 1px solid #d8e1e8; }
.filters label { display: grid; gap: 6px; font-size: 11px; color: #64788a; }
select, input { box-sizing: border-box; min-height: 34px; padding: 6px 10px; border: 1px solid #cbd7e0; border-radius: 3px; color: #243c50; background: #fff; font: inherit; font-size: 12px; }
button { border: 1px solid #24669c; border-radius: 3px; background: #24669c; color: #fff; padding: 7px 12px; cursor: pointer; font: inherit; font-size: 12px; }
button:hover { background: #1c5480; }
button:disabled { opacity: 0.6; cursor: default; }
button:focus-visible, select:focus-visible, input:focus-visible { outline: 2px solid #24669c; outline-offset: 2px; }
.error { margin: 12px 0 0; padding: 10px 13px; border: 1px solid #e5b9b1; background: #fff4f1; color: #863c32; }
.hint { color: #64788a; font-size: 12px; line-height: 1.7; }
.summary { display: flex; flex-wrap: wrap; gap: 10px 26px; margin: 14px 0; padding: 12px 16px; background: #fff; border: 1px solid #d8e1e8; }
.summary div { display: grid; gap: 3px; }
.summary span { color: #64788a; font-size: 10px; }
.summary strong { font-size: 15px; font-weight: 600; font-variant-numeric: tabular-nums; }
.summary .range { margin-left: auto; }
.workspace { display: grid; grid-template-columns: 320px minmax(0, 1fr); border: 1px solid #d8e1e8; background: #fff; min-height: 540px; }
.side { display: flex; flex-direction: column; gap: 0; border-right: 1px solid #d8e1e8; }
.card { padding: 14px 16px; border-bottom: 1px solid #e8edf1; }
.card:last-child { border-bottom: 0; }
h2 { margin: 0 0 10px; font-size: 13px; }
.clock { margin: 0 0 8px; font-size: 20px; font-weight: 600; font-variant-numeric: tabular-nums; }
.gap-indicator { margin: 0 0 8px; padding: 6px 8px; background: #fff5df; color: #805b21; font-size: 11px; }
.controls { display: flex; gap: 8px; margin-bottom: 10px; }
.seek { display: grid; gap: 5px; font-size: 11px; color: #64788a; }
.seek input { width: 100%; padding: 0; }
.speeds { display: flex; gap: 6px; margin-top: 10px; }
.speeds button { background: #fff; color: #24669c; padding: 5px 10px; }
.speeds button.active { background: #24669c; color: #fff; }
.gaps, .segments { margin: 0; padding-left: 18px; display: grid; gap: 7px; font-size: 11px; line-height: 1.6; color: #43596d; }
.gaps li { display: grid; }
.gaps strong { color: #805b21; }
.map-panel { min-width: 0; min-height: 0; }
@media (max-width: 900px) { .page { padding: 14px; } .page-header { flex-wrap: wrap; } .workspace { display: flex; flex-direction: column; } .side { order: 2; border-right: 0; border-top: 1px solid #d8e1e8; } .map-panel { height: 360px; } .summary .range { margin-left: 0; } }
</style>

