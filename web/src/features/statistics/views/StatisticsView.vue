<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import {
  formatRate,
  getFleets,
  getOverview,
  getTrend,
  getTypes,
  type StatisticsFleets,
  type StatisticsOverview,
  type StatisticsTrend,
  type StatisticsTypes,
  type VehicleMetrics,
} from '../api'

const overview = ref<StatisticsOverview | null>(null)
const trend = ref<StatisticsTrend | null>(null)
const types = ref<StatisticsTypes | null>(null)
const fleets = ref<StatisticsFleets | null>(null)
const startDate = ref('')
const endDate = ref('')
const loading = ref(false)
const error = ref('')

const vehicleFields: { key: keyof VehicleMetrics; label: string }[] = [
  { key: 'total', label: '车辆总数' },
  { key: 'online', label: '在线' },
  { key: 'offline', label: '离线' },
  { key: 'notConnected', label: '未接入' },
  { key: 'disabled', label: '停用' },
  { key: 'activeDisconnected', label: '主动断开' },
  { key: 'startupPending', label: '启动待确认' },
  { key: 'unbound', label: '未绑定' },
]

const alarmFields = [
  { key: 'pending', label: 'PENDING 待处理' },
  { key: 'processing', label: 'PROCESSING 处理中' },
  { key: 'handled', label: 'HANDLED 已处理' },
  { key: 'ignored', label: 'IGNORED 已忽略' },
] as const

const totalOnlineAndOffline = computed(() =>
  overview.value ? overview.value.vehicles.online + overview.value.vehicles.offline : 0,
)

function typeLabel(alarmType: string): string {
  return alarmType === 'OVERSPEED' ? '超速' : alarmType
}

async function loadRange() {
  if (!startDate.value || !endDate.value) {
    return
  }
  const query = { startDate: startDate.value, endDate: endDate.value }
  const [nextTrend, nextTypes, nextFleets] = await Promise.all([
    getTrend(query),
    getTypes(query),
    getFleets(query),
  ])
  trend.value = nextTrend
  types.value = nextTypes
  fleets.value = nextFleets
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const loaded = await getOverview()
    overview.value = loaded
    if (!startDate.value) {
      startDate.value = loaded.today
    }
    if (!endDate.value) {
      endDate.value = loaded.today
    }
    await loadRange()
  } catch (e) {
    error.value = errorMessage(e, '统计加载失败')
  } finally {
    loading.value = false
  }
}

async function applyRange() {
  loading.value = true
  error.value = ''
  try {
    await loadRange()
  } catch (e) {
    error.value = errorMessage(e, '统计区间加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(reload)
</script>

<template>
  <AppShell area="monitoring">
    <section class="page">
      <h1>监控统计</h1>
      <p
        v-if="overview"
        class="hint"
      >
        业务时区 {{ overview.timezone }}；今日 {{ overview.today }}，今日窗口
        {{ overview.startTimeFrom }} 至 {{ overview.startTimeTo }}。
        在线只计启用且绑定在线终端的车辆，离线只计曾接入且满足离线条件的车辆。
      </p>
      <p
        v-if="error"
        class="error"
        data-testid="stats-error"
      >
        {{ error }}
      </p>
      <p
        v-if="loading"
        class="hint"
        data-testid="stats-loading"
      >
        加载中…
      </p>

      <section
        v-if="overview"
        class="card"
        data-testid="stats-overview"
      >
        <h2>今日车辆状态</h2>
        <div class="metrics">
          <div
            v-for="field in vehicleFields"
            :key="field.key"
            class="metric"
            :data-testid="`stats-vehicle-${field.key}`"
          >
            <span class="metric-label">{{ field.label }}</span>
            <strong>{{ overview.vehicles[field.key] }}</strong>
          </div>
        </div>
        <p class="hint">
          在线 {{ overview.vehicles.online }} + 离线 {{ overview.vehicles.offline }} =
          {{ totalOnlineAndOffline }}，车辆总数 {{ overview.vehicles.total }}；
          未接入、停用、主动断开、启动待确认与未绑定分别单列，不强行凑总数。
        </p>
      </section>

      <section
        v-if="overview"
        class="card"
        data-testid="stats-alarms"
      >
        <h2>今日报警与处置</h2>
        <div class="metrics">
          <div
            class="metric"
            data-testid="stats-alarm-total"
          >
            <span class="metric-label">报警总数</span>
            <strong>{{ overview.alarms.total }}</strong>
          </div>
          <div
            v-for="field in alarmFields"
            :key="field.key"
            class="metric"
            :data-testid="`stats-alarm-${field.key}`"
          >
            <span class="metric-label">{{ field.label }}</span>
            <strong>{{ overview.alarms[field.key] }}</strong>
          </div>
        </div>
        <div
          class="rate"
          data-testid="stats-handling-rate"
        >
          <div>
            <span class="metric-label">处理率（同批 HANDLED + IGNORED / 同批创建总数）</span>
            <strong class="rate-value">{{ formatRate(overview.alarms.handlingRate) }}</strong>
          </div>
          <div class="rate-detail">
            <span>{{ overview.alarms.handlingRate.numerator }} / {{ overview.alarms.handlingRate.denominator }}</span>
            <span class="note">{{ overview.alarms.handlingRate.note }}</span>
          </div>
        </div>
        <p class="hint">
          PENDING 与 PROCESSING 分别呈现；零分母显示“—”，不得显示 0% 或 NaN。
        </p>
      </section>

      <section class="card">
        <h2>统计区间</h2>
        <div class="controls">
          <label>
            起始日期
            <input
              v-model="startDate"
              type="date"
              data-testid="stats-range-start"
            >
          </label>
          <label>
            结束日期
            <input
              v-model="endDate"
              type="date"
              data-testid="stats-range-end"
            >
          </label>
          <button
            type="button"
            data-testid="stats-range-apply"
            @click="applyRange"
          >
            查询
          </button>
        </div>
        <p class="hint">
          趋势、类型与车队三视图共用同一 start_time 区间与数据范围，可与明细相互复算；区间不能晚于今日，最多 90 天。
        </p>
      </section>

      <section
        v-if="trend"
        class="card"
        data-testid="stats-trend"
      >
        <h2>日期趋势</h2>
        <p class="hint">
          {{ trend.startDate }} 至 {{ trend.endDate }}，按 start_time 的 Asia/Shanghai 日期分组。
        </p>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>日期</th><th>报警总数</th><th>PENDING</th><th>PROCESSING</th>
                <th>HANDLED</th><th>IGNORED</th><th>处理率</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="day in trend.days"
                :key="day.date"
              >
                <td>{{ day.date }}</td>
                <td>{{ day.alarms.total }}</td>
                <td>{{ day.alarms.pending }}</td>
                <td>{{ day.alarms.processing }}</td>
                <td>{{ day.alarms.handled }}</td>
                <td>{{ day.alarms.ignored }}</td>
                <td>{{ formatRate(day.alarms.handlingRate) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section
        v-if="types"
        class="card"
        data-testid="stats-types"
      >
        <h2>报警类型分布</h2>
        <p
          v-if="types.types.length === 0"
          class="hint"
          data-testid="stats-types-empty"
        >
          区间内暂无报警
        </p>
        <div
          v-else
          class="table-wrap"
        >
          <table>
            <thead>
              <tr>
                <th>类型</th><th>总数</th><th>PENDING</th><th>PROCESSING</th>
                <th>HANDLED</th><th>IGNORED</th><th>处理率</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in types.types"
                :key="item.alarmType"
              >
                <td>{{ typeLabel(item.alarmType) }}</td>
                <td>{{ item.alarms.total }}</td>
                <td>{{ item.alarms.pending }}</td>
                <td>{{ item.alarms.processing }}</td>
                <td>{{ item.alarms.handled }}</td>
                <td>{{ item.alarms.ignored }}</td>
                <td>{{ formatRate(item.alarms.handlingRate) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>合计</td><td>{{ types.total }}</td><td colspan="5" />
              </tr>
            </tfoot>
          </table>
        </div>
      </section>

      <section
        v-if="fleets"
        class="card"
        data-testid="stats-fleets"
      >
        <h2>车队聚合</h2>
        <p class="hint">
          当前车辆与在线状态按当前档案归属；历史报警按 fleet_id_at_event 归属。
        </p>
        <p
          v-if="fleets.fleets.length === 0"
          class="hint"
          data-testid="stats-fleets-empty"
        >
          暂无车队
        </p>
        <div
          v-else
          class="table-wrap"
        >
          <table>
            <thead>
              <tr>
                <th>车队</th><th>车辆总数</th><th>在线</th><th>离线</th><th>未接入</th>
                <th>停用</th><th>主动断开</th><th>启动待确认</th><th>未绑定</th>
                <th>报警总数</th><th>PENDING</th><th>PROCESSING</th><th>处理率</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="fleet in fleets.fleets"
                :key="fleet.fleetId"
                :data-testid="`stats-fleet-${fleet.fleetId}`"
              >
                <td>{{ fleet.fleetName }}（{{ fleet.fleetCode }}）</td>
                <td>{{ fleet.vehicles.total }}</td>
                <td>{{ fleet.vehicles.online }}</td>
                <td>{{ fleet.vehicles.offline }}</td>
                <td>{{ fleet.vehicles.notConnected }}</td>
                <td>{{ fleet.vehicles.disabled }}</td>
                <td>{{ fleet.vehicles.activeDisconnected }}</td>
                <td>{{ fleet.vehicles.startupPending }}</td>
                <td>{{ fleet.vehicles.unbound }}</td>
                <td>{{ fleet.alarms.total }}</td>
                <td>{{ fleet.alarms.pending }}</td>
                <td>{{ fleet.alarms.processing }}</td>
                <td>{{ formatRate(fleet.alarms.handlingRate) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td>合计</td>
                <td>{{ fleets.totalVehicles.total }}</td>
                <td>{{ fleets.totalVehicles.online }}</td>
                <td>{{ fleets.totalVehicles.offline }}</td>
                <td>{{ fleets.totalVehicles.notConnected }}</td>
                <td>{{ fleets.totalVehicles.disabled }}</td>
                <td>{{ fleets.totalVehicles.activeDisconnected }}</td>
                <td>{{ fleets.totalVehicles.startupPending }}</td>
                <td>{{ fleets.totalVehicles.unbound }}</td>
                <td>{{ fleets.totalAlarms.total }}</td>
                <td>{{ fleets.totalAlarms.pending }}</td>
                <td>{{ fleets.totalAlarms.processing }}</td>
                <td>{{ formatRate(fleets.totalAlarms.handlingRate) }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.page {
  padding: 1.5rem;
}
h1 {
  margin: 0 0 0.5rem;
  font-size: 1.25rem;
}
h2 {
  margin: 0 0 0.75rem;
  font-size: 1rem;
}
.hint {
  margin: 0 0 1rem;
  color: #5b6b7c;
  font-size: 0.875rem;
}
.card {
  margin-bottom: 1rem;
  padding: 1rem;
  background: #fff;
  border: 1px solid #e2e8ee;
  border-radius: 8px;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(8.5rem, 1fr));
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
.metric {
  display: grid;
  gap: 0.25rem;
  padding: 0.75rem;
  background: #f7f9fc;
  border: 1px solid #e2e8ee;
  border-radius: 6px;
}
.metric-label {
  color: #5b6b7c;
  font-size: 0.8125rem;
}
.metric strong {
  font-size: 1.4rem;
}
.rate {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.75rem;
  background: #f7f9fc;
  border: 1px solid #e2e8ee;
  border-radius: 6px;
}
.rate-value {
  margin-left: 0.5rem;
  font-size: 1.4rem;
}
.rate-detail {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  color: #33414f;
}
.note {
  padding: 0.15rem 0.45rem;
  color: #8a5a00;
  background: #fff4d6;
  border-radius: 999px;
  font-size: 0.8125rem;
}
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
}
.controls label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.875rem;
  color: #33414f;
}
.controls input {
  padding: 0.45rem 0.6rem;
  border: 1px solid #cfd8e3;
  border-radius: 6px;
}
button {
  padding: 0.5rem 1rem;
  border: 1px solid #2f6fed;
  border-radius: 6px;
  background: #2f6fed;
  color: #fff;
  cursor: pointer;
}
.table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
  white-space: nowrap;
}
th,
td {
  padding: 0.45rem 0.6rem;
  border-bottom: 1px solid #e2e8ee;
  text-align: left;
}
th {
  color: #5b6b7c;
  font-weight: 600;
  background: #f7f9fc;
}
tfoot td {
  font-weight: 600;
  background: #fbfcfe;
}
.error {
  margin: 0 0 1rem;
  color: #b42318;
}
</style>
