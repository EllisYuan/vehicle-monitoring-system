<script setup lang="ts">
import { computed, ref } from 'vue'
import { useSessionStore } from '../../identity/store/session'
import AppShell from '../../../app/layouts/AppShell.vue'
import VehicleMap from '../components/VehicleMap.vue'
import { COMMUNICATION_LABELS, formatTime, isStale, type CommunicationStatus } from '../api'
import { useMonitorStream } from '../useMonitorStream'

const session = useSessionStore()
const { vehicles, loading, failure, state, statusLabel, lastSyncAt, now, retry } = useMonitorStream()
const search = ref('')
const fleetId = ref('')
const communication = ref('')
const selectedId = ref<number | null>(null)
const fleets = computed(() => [...new Map(vehicles.value.map(vehicle => [vehicle.fleetId,
  { id: vehicle.fleetId, name: vehicle.fleetName }])).values()].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')))
const filtered = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return vehicles.value.filter(vehicle => (!fleetId.value || vehicle.fleetId === Number(fleetId.value))
    && (!communication.value || vehicle.communicationStatus === communication.value)
    && (!query || `${vehicle.plateNo} ${vehicle.terminalNo ?? ''}`.toLocaleLowerCase().includes(query)))
    .sort((a, b) => a.plateNo.localeCompare(b.plateNo, 'zh-CN'))
})
const selected = computed(() => filtered.value.find(vehicle => vehicle.vehicleId === selectedId.value) ?? null)
const positionCount = computed(() => filtered.value.filter(vehicle => vehicle.position?.fixValid).length)
function select(vehicleId: number) { selectedId.value = vehicleId }
</script>

<template>
  <AppShell area="monitoring">
    <section
      class="monitor-console"
      aria-labelledby="monitor-title"
    >
      <header class="monitor-header">
        <div class="heading-group">
          <span class="eyebrow">LIVE OPERATIONS</span><h1 id="monitor-title">
            实时监控
          </h1>
        </div>
        <div class="sync-group">
          <span
            class="stream-status"
            :class="{ connected: state === 'connected' }"
            data-testid="monitor-stream-status"
            role="status"
          >
            <span aria-hidden="true">{{ state === 'connected' ? '●' : '○' }}</span>{{ statusLabel }}
          </span>
          <span class="sync-time">快照 {{ formatTime(lastSyncAt) }} · 上海时间</span>
          <button
            type="button"
            class="quiet-button"
            @click="retry"
          >
            {{ state === 'stopped' ? '重新连接' : '刷新' }}
          </button>
        </div>
      </header>
      <p
        v-if="session.role === 'FLEET_LEADER'"
        class="scope-note"
        data-testid="leader-scope-note"
      >
        当前账号为车队长：仅能查看被授权车队的车辆、轨迹、报警和统计，且不可处置报警或下发指令。
      </p>
      <div
        v-if="failure"
        class="request-error"
        role="alert"
      >
        <span>{{ failure }}</span><button
          type="button"
          class="quiet-button"
          @click="retry"
        >
          重试
        </button>
      </div>
      <div
        class="filter-row"
        role="search"
        aria-label="监控车辆筛选"
      >
        <label class="search-field"><span>车辆 / 终端</span><input
          v-model="search"
          aria-label="搜索车辆或终端"
          placeholder="搜索车牌、终端编号"
          type="search"
        ></label>
        <label><span>所属车队</span><select
          v-model="fleetId"
          aria-label="筛选车队"
        ><option value="">全部可见车队</option><option
          v-for="fleet in fleets"
          :key="fleet.id"
          :value="String(fleet.id)"
        >{{ fleet.name }}</option></select></label>
        <label><span>通信状态</span><select
          v-model="communication"
          aria-label="筛选通信状态"
        ><option value="">全部状态</option><option
          v-for="(label, value) in COMMUNICATION_LABELS"
          :key="value"
          :value="value"
        >{{ label }}</option></select></label>
        <p class="filter-result">
          <strong>{{ filtered.length }}</strong> 辆可见 <span>/</span> {{ positionCount }} 辆有有效位置
        </p>
      </div>
      <div
        class="monitor-workspace"
        :aria-busy="loading"
      >
        <aside
          class="vehicle-panel"
          aria-label="车辆列表"
        >
          <div class="panel-heading">
            <h2>车辆列表</h2><span>通信 / 最后有效定位</span>
          </div>
          <div
            v-if="loading && !vehicles.length"
            class="list-state"
            role="status"
          >
            正在加载车辆快照…
          </div>
          <div
            v-else-if="!filtered.length"
            class="list-state"
            data-testid="monitor-empty"
          >
            <strong>{{ vehicles.length ? '没有匹配车辆' : '暂无可见车辆' }}</strong>
            <p>{{ vehicles.length ? '调整搜索内容、车队或通信状态。' : '请确认车辆档案及当前账号的车队授权。' }}</p>
          </div>
          <div class="vehicle-list">
            <button
              v-for="vehicle in filtered"
              :key="vehicle.vehicleId"
              type="button"
              class="vehicle-row"
              :class="{ selected: selected?.vehicleId === vehicle.vehicleId }"
              :aria-pressed="selected?.vehicleId === vehicle.vehicleId"
              :data-testid="`monitor-vehicle-${vehicle.vehicleId}`"
              @click="select(vehicle.vehicleId)"
            >
              <span class="vehicle-row-heading"><strong>{{ vehicle.plateNo }}</strong><span
                class="communication-label"
                :class="{ online: vehicle.communicationStatus === 'ONLINE' }"
              ><span aria-hidden="true">{{ vehicle.communicationStatus === 'ONLINE' ? '●' : '○' }}</span> {{ COMMUNICATION_LABELS[vehicle.communicationStatus as CommunicationStatus] }}</span></span>
              <span class="fleet-name">{{ vehicle.fleetName }}</span>
              <span class="vehicle-position"><span v-if="vehicle.position">{{ vehicle.position.speedKph.toFixed(1) }} km/h <span class="direction">{{ vehicle.position.directionDeg }}°</span></span><span v-else>暂无有效定位</span><span
                v-if="isStale(vehicle.position, now)"
                class="stale-label"
              >◷ 定位陈旧</span></span>
              <span class="vehicle-times">通信 {{ formatTime(vehicle.lastSeenAt) }}<br>定位 {{ formatTime(vehicle.position?.gpsTime) }}</span>
            </button>
          </div>
          <div class="list-footnote">
            通信在线不代表定位新鲜；有效定位超过 60 秒标为陈旧。
          </div>
        </aside>
        <div class="map-panel">
          <VehicleMap
            :vehicles="filtered"
            :selected-id="selected?.vehicleId ?? null"
            @select="select"
          />
          <section
            v-if="selected"
            class="vehicle-detail"
            aria-label="选中车辆详情"
            data-testid="monitor-selected-vehicle"
          >
            <div class="detail-heading">
              <div><span class="eyebrow">SELECTED VEHICLE</span><h2>{{ selected.plateNo }}</h2><p>{{ selected.fleetName }} · 终端 {{ selected.terminalNo ?? '未绑定' }}</p></div><button
                class="close-detail"
                type="button"
                aria-label="关闭车辆详情"
                @click="selectedId = null"
              >
                ×
              </button>
            </div>
            <div
              v-if="selected.position"
              class="position-readout"
            >
              <strong>{{ selected.position.speedKph.toFixed(1) }}<small>km/h</small></strong><span>方向 {{ selected.position.directionDeg }}°</span><span>{{ selected.position.accOn ? 'ACC 开' : 'ACC 关' }}</span><span>{{ selected.position.fixValid ? '定位有效' : '定位无效' }}</span>
            </div>
            <p
              v-else
              class="no-position"
            >
              暂无有效定位，等待终端上报。
            </p>
            <p
              v-if="isStale(selected.position, now)"
              class="detail-stale"
            >
              ◷ 定位陈旧：当前显示最后一次有效位置。
            </p>
            <dl>
              <div><dt>通信状态</dt><dd>{{ COMMUNICATION_LABELS[selected.communicationStatus] }}</dd></div><div><dt>最后通信</dt><dd>{{ formatTime(selected.lastSeenAt) }}</dd></div><div><dt>最后有效定位</dt><dd>{{ formatTime(selected.position?.gpsTime) }}</dd></div><div><dt>平台接收</dt><dd>{{ formatTime(selected.position?.receivedAt) }}</dd></div><div v-if="selected.position">
                <dt>WGS84</dt><dd>{{ selected.position.lng.toFixed(6) }}, {{ selected.position.lat.toFixed(6) }}</dd>
              </div>
            </dl>
          </section>
          <div
            v-else
            class="selection-hint"
          >
            从列表或地图选择车辆，查看位置与通信详情
          </div>
        </div>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.monitor-console { --ink: #243c50; --muted: #64788a; --line: #d8e1e8; --accent: #24669c; margin: 0 auto; max-width: 1920px; padding: 22px 24px 24px; color: var(--ink); font-family: "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", sans-serif; font-size: 13px; }
.monitor-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.heading-group { display: flex; flex-direction: column; gap: 4px; }
.eyebrow { font-size: 9px; font-weight: 700; letter-spacing: 1.6px; color: var(--muted); }
h1 { margin: 0; font-size: 24px; line-height: 1.4; letter-spacing: 1px; font-weight: 650; }
.sync-group { display: flex; align-items: center; gap: 14px; }
.stream-status { display: flex; align-items: center; gap: 6px; color: #7b5b22; font-size: 12px; }
.stream-status.connected { color: #32634c; }
.sync-time { color: var(--muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.quiet-button { border: 1px solid #c5d2de; border-radius: 3px; background: #fff; padding: 6px 12px; color: var(--ink); cursor: pointer; font: inherit; font-size: 12px; }
.quiet-button:hover { background: #edf4fa; }
.scope-note { margin: 0 0 12px; padding: 10px 12px; border-left: 3px solid #be9140; background: #fff8e9; color: #775c2a; font-size: 12px; }
.request-error { margin-bottom: 12px; padding: 10px 14px; display: flex; align-items: center; justify-content: space-between; border: 1px solid #e5b9b1; background: #fff4f1; color: #863c32; }
.filter-row { display: flex; align-items: flex-end; gap: 16px; padding: 14px 16px; background: #fff; border: 1px solid var(--line); border-bottom: 0; }
.filter-row label { display: grid; gap: 6px; min-width: 160px; }
.filter-row label > span { color: var(--muted); font-size: 11px; }
.search-field { flex: 0 1 270px; }
input, select { box-sizing: border-box; min-height: 34px; padding: 6px 10px; border: 1px solid #cbd7e0; border-radius: 3px; color: var(--ink); background: #fff; font: inherit; font-size: 12px; width: 100%; }
input::placeholder { color: #8292a0; }
button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.filter-result { margin: 0 0 8px auto; color: var(--muted); font-size: 11px; white-space: nowrap; }
.filter-result strong { font-size: 15px; color: var(--ink); font-weight: 600; }
.filter-result span { margin: 0 8px; color: #b1bec9; }
.monitor-workspace { display: grid; grid-template-columns: 300px minmax(0, 1fr); min-height: 580px; height: calc(100vh - 235px); border: 1px solid var(--line); background: #fff; }
.vehicle-panel { display: flex; flex-direction: column; min-height: 0; border-right: 1px solid var(--line); }
.panel-heading { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 14px 16px; border-bottom: 1px solid var(--line); }
h2 { margin: 0; font-size: 14px; }
.panel-heading > span { font-size: 10px; color: var(--muted); }
.vehicle-list { overflow-y: auto; flex: 1; min-height: 0; }
.vehicle-row { position: relative; display: flex; flex-direction: column; gap: 7px; width: 100%; padding: 15px 16px; text-align: left; border: 0; border-bottom: 1px solid #e8edf1; background: white; color: var(--ink); cursor: pointer; font: inherit; }
.vehicle-row:hover { background: #f5f8fb; }
.vehicle-row.selected { background: #edf4fa; box-shadow: inset 3px 0 var(--accent); }
.vehicle-row-heading { display: flex; justify-content: space-between; gap: 8px; }
.vehicle-row-heading strong { font-size: 14px; overflow-wrap: anywhere; }
.communication-label { font-size: 10px; color: #67798b; white-space: nowrap; }
.communication-label.online { color: #32634c; }
.fleet-name { color: var(--muted); font-size: 11px; }
.vehicle-position { display: flex; justify-content: space-between; gap: 6px; font-size: 12px; font-variant-numeric: tabular-nums; }
.direction { color: var(--muted); margin-left: 8px; }
.stale-label { color: #825b20; font-size: 10px; }
.vehicle-times { font-size: 10px; line-height: 1.7; color: var(--muted); font-variant-numeric: tabular-nums; }
.list-footnote { padding: 11px 16px; border-top: 1px solid var(--line); font-size: 10px; line-height: 1.7; color: var(--muted); background: #f9fafb; }
.list-state { padding: 32px 18px; color: var(--muted); font-size: 12px; line-height: 1.8; }
.list-state strong { color: var(--ink); }
.list-state p { margin: 5px 0; }
.map-panel { position: relative; min-width: 0; min-height: 0; }
.vehicle-detail { position: absolute; right: 16px; bottom: 40px; width: min(340px, calc(100% - 32px)); box-sizing: border-box; padding: 17px 18px; border: 1px solid #cbd7e0; background: #fffffff5; box-shadow: 0 6px 24px #243c501c; }
.detail-heading { display: flex; justify-content: space-between; gap: 12px; }
.detail-heading h2 { margin: 5px 0; font-size: 18px; overflow-wrap: anywhere; }
.detail-heading p { color: var(--muted); font-size: 10px; overflow-wrap: anywhere; margin: 6px 0 14px; }
.close-detail { background: transparent; color: var(--muted); border: 0; font-size: 24px; cursor: pointer; padding: 0 4px; align-self: flex-start; }
.position-readout { display: flex; align-items: baseline; flex-wrap: wrap; gap: 12px; padding-bottom: 13px; border-bottom: 1px solid var(--line); font-size: 11px; }
.position-readout strong { font-size: 25px; font-weight: 600; }
.position-readout small { font-size: 10px; font-weight: 400; color: var(--muted); margin-left: 5px; }
.no-position, .detail-stale { font-size: 11px; line-height: 1.7; }
.detail-stale { padding: 7px 9px; background: #fff5df; color: #805b21; }
dl { margin: 13px 0 0; display: grid; gap: 7px; font-size: 11px; }
dl > div { display: flex; justify-content: space-between; gap: 12px; }
dt { color: var(--muted); } dd { margin: 0; font-variant-numeric: tabular-nums; }
.selection-hint { position: absolute; bottom: 42px; left: 16px; padding: 7px 11px; background: #fffffff2; border: 1px solid #d8e1e8; color: var(--muted); font-size: 11px; pointer-events: none; }
@media (max-width: 1100px) { .monitor-console { padding: 18px 16px; } .monitor-header { flex-wrap: wrap; } .sync-group { flex-wrap: wrap; gap: 10px; } .filter-row { flex-wrap: wrap; gap: 12px; } .filter-result { margin: 0 0 8px; } .monitor-workspace { grid-template-columns: 265px minmax(0, 1fr); height: 650px; } }
@media (max-width: 700px) { .monitor-workspace { display: flex; flex-direction: column; height: auto; } .vehicle-panel { order: 2; max-height: 450px; border-right: 0; border-top: 1px solid var(--line); } .map-panel { min-height: 380px; height: 380px; } .filter-row label { flex: 1 1 130px; min-width: 0; } .filter-row .search-field { flex-basis: 100%; } .filter-result { flex-basis: 100%; margin: 3px 0 0; } .vehicle-detail { bottom: 32px; padding: 12px; } .selection-hint { right: 16px; bottom: 44px; font-size: 10px; } .sync-time { display: none; } }
</style>
