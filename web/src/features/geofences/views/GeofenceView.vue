<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { listVehicles, type Vehicle } from '../../archives/api'
import { listFleets, type Fleet } from '../../fleets/api'
import {
  createGeofence,
  geometryPayload,
  listGeofences,
  updateGeofence,
  updateGeofenceStatus,
  updateGeofenceVehicles,
  type Geofence,
} from '../api'
import { validateGeometry, type DraftGeometry } from '../geometry'
import GeofenceMap from '../components/GeofenceMap.vue'

const fences = ref<Geofence[]>([])
const vehicles = ref<Vehicle[]>([])
const fleets = ref<Fleet[]>([])
const fleetFilter = ref<number | ''>('')
const editingId = ref<number | null>(null)
const fenceName = ref('')
const enterEnabled = ref(true)
const leaveEnabled = ref(true)
const selectedVehicleIds = ref<number[]>([])
const geometry = ref<DraftGeometry | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const editing = computed(() => editingId.value !== null)
const availableVehicles = computed(() => vehicles.value)

watch(fleetFilter, () => {
  void reload()
})

function displayTime(value: string | null | undefined): string {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

function fenceGeometry(fence: Geofence): DraftGeometry | null {
  if (fence.fenceType === 'CIRCLE') {
    if (fence.centerLng === null || fence.centerLat === null || fence.radiusM === null) return null
    return {
      fenceType: 'CIRCLE',
      centerLng: fence.centerLng,
      centerLat: fence.centerLat,
      radiusM: fence.radiusM,
    }
  }
  return fence.vertices && fence.vertices.length >= 3
    ? { fenceType: 'POLYGON', vertices: fence.vertices.map(vertex => ({ lng: vertex.lng, lat: vertex.lat })) }
    : null
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    fences.value = await listGeofences(fleetFilter.value === '' ? undefined : fleetFilter.value)
  } catch (e) {
    error.value = errorMessage(e, '围栏列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadOptions() {
  try {
    const [vehicleList, fleetList] = await Promise.all([listVehicles(), listFleets()])
    vehicles.value = vehicleList
    fleets.value = fleetList
  } catch (e) {
    error.value = errorMessage(e, '车辆或车队列表加载失败')
  }
}

function resetForm() {
  editingId.value = null
  fenceName.value = ''
  enterEnabled.value = true
  leaveEnabled.value = true
  selectedVehicleIds.value = []
  geometry.value = null
}

function startCreate() {
  resetForm()
  error.value = ''
  notice.value = ''
}

function startEdit(fence: Geofence) {
  editingId.value = fence.id
  fenceName.value = fence.fenceName
  enterEnabled.value = fence.enterEnabled
  leaveEnabled.value = fence.leaveEnabled
  selectedVehicleIds.value = fence.vehicles.map(vehicle => vehicle.id)
  geometry.value = fenceGeometry(fence)
  error.value = ''
  notice.value = ''
}

async function save() {
  error.value = ''
  notice.value = ''
  const name = fenceName.value.trim()
  if (!name) {
    error.value = '请填写围栏名称'
    return
  }
  if (!geometry.value) {
    error.value = '请先在绘制区画好圆形或多边形围栏'
    return
  }
  const geometryError = validateGeometry(geometry.value)
  if (geometryError) {
    error.value = geometryError
    return
  }
  const payload = geometryPayload(geometry.value)
  if (!payload) {
    error.value = '围栏几何无效'
    return
  }
  const body = {
    fenceName: name,
    ...payload,
    enterEnabled: enterEnabled.value,
    leaveEnabled: leaveEnabled.value,
    vehicleIds: [...selectedVehicleIds.value],
  }
  saving.value = true
  try {
    const saved = editingId.value === null
      ? await createGeofence(body)
      : await updateGeofence(editingId.value, body)
    notice.value = (editingId.value === null ? '围栏已创建：' : '围栏已更新：')
      + saved.fenceName + '，版本 v' + saved.version
    resetForm()
    await reload()
  } catch (e) {
    error.value = errorMessage(e, editingId.value === null ? '创建围栏失败' : '保存围栏失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(fence: Geofence) {
  error.value = ''
  notice.value = ''
  try {
    const saved = await updateGeofenceStatus(fence.id, fence.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
    notice.value = '围栏已' + (saved.status === 'ENABLED' ? '启用' : '停用') + '：' + saved.fenceName
      + '，版本 v' + saved.version
    await reload()
  } catch (e) {
    error.value = errorMessage(e, '更新围栏状态失败')
  }
}

async function bindVehicles(fence: Geofence) {
  error.value = ''
  notice.value = ''
  try {
    const saved = await updateGeofenceVehicles(fence.id, [...selectedVehicleIds.value])
    notice.value = '围栏绑定已保存：' + saved.fenceName + '，绑定 ' + saved.vehicles.length
      + ' 辆车，版本 v' + saved.version
    await reload()
  } catch (e) {
    error.value = errorMessage(e, '更新围栏绑定失败')
  }
}

function bindEditing() {
  const fence = fences.value.find(item => item.id === editingId.value)
  if (fence) void bindVehicles(fence)
}

function toggleVehicle(id: number) {
  selectedVehicleIds.value = selectedVehicleIds.value.includes(id)
    ? selectedVehicleIds.value.filter(value => value !== id)
    : [...selectedVehicleIds.value, id]
}

onMounted(async () => {
  await Promise.all([reload(), loadOptions()])
})
</script>

<template>
  <AppShell area="monitoring">
    <section
      class="page"
      data-testid="geofence-page"
    >
      <h1>电子围栏</h1>
      <p class="hint">
        绘制圆形或多边形围栏并绑定车辆。首次有效实时定位只建立内外基线；边界算内，连续 3 个有效实时点确认
        新的内外状态后生成一次进入或离开事件，相邻有效点超过 30 秒重置候选。补传历史点不改变当前围栏状态。
      </p>

      <p
        v-if="error"
        class="error"
        role="alert"
        data-testid="geofence-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        class="notice"
        data-testid="geofence-notice"
      >
        {{ notice }}
      </p>

      <div class="layout">
        <section
          class="card form-card"
          data-testid="geofence-form"
        >
          <h2>{{ editing ? '编辑围栏' : '新建围栏' }}</h2>
          <div class="field">
            <label for="fence-name">围栏名称</label>
            <input
              id="fence-name"
              v-model="fenceName"
              type="text"
              maxlength="64"
              placeholder="例如 一号仓库装卸区"
              data-testid="fence-name"
            >
          </div>
          <div class="flags">
            <label>
              <input
                v-model="enterEnabled"
                type="checkbox"
                data-testid="enter-enabled"
              >
              生成进入事件
            </label>
            <label>
              <input
                v-model="leaveEnabled"
                type="checkbox"
                data-testid="leave-enabled"
              >
              生成离开事件
            </label>
          </div>
          <div class="field">
            <span class="field-label">绑定车辆（可多选，未绑定不参与判定）</span>
            <div
              class="vehicle-list"
              data-testid="geofence-vehicle-options"
            >
              <label
                v-for="vehicle in availableVehicles"
                :key="vehicle.id"
                class="vehicle-option"
              >
                <input
                  type="checkbox"
                  :checked="selectedVehicleIds.includes(vehicle.id)"
                  :data-testid="'vehicle-option-' + vehicle.id"
                  @change="toggleVehicle(vehicle.id)"
                >
                <span>{{ vehicle.plateNo }}</span>
                <small>{{ vehicle.fleetName }}</small>
              </label>
              <p
                v-if="availableVehicles.length === 0"
                class="hint"
              >
                暂无可绑定车辆
              </p>
            </div>
          </div>
          <div class="actions">
            <button
              type="button"
              class="primary"
              :disabled="saving"
              data-testid="save-geofence"
              @click="save"
            >
              {{ saving ? '保存中…' : (editing ? '保存编辑' : '创建围栏') }}
            </button>
            <button
              v-if="editing"
              type="button"
              data-testid="bind-geofence"
              @click="bindEditing"
            >
              仅保存车辆绑定
            </button>
            <button
              v-if="editing"
              type="button"
              data-testid="cancel-edit"
              @click="startCreate"
            >
              取消编辑
            </button>
            <button
              v-else
              type="button"
              data-testid="reset-form"
              @click="startCreate"
            >
              重置
            </button>
          </div>
        </section>

        <section class="card map-card">
          <GeofenceMap v-model:geometry="geometry" />
        </section>
      </div>

      <section class="card">
        <div class="list-header">
          <h2>围栏列表</h2>
          <label class="filter">
            按车队过滤
            <select
              v-model.number="fleetFilter"
              data-testid="geofence-fleet-filter"
            >
              <option value="">全部车队</option>
              <option
                v-for="fleet in fleets"
                :key="fleet.id"
                :value="fleet.id"
              >
                {{ fleet.fleetName }}
              </option>
            </select>
          </label>
        </div>
        <p
          v-if="loading"
          class="hint"
        >
          加载中…
        </p>
        <p
          v-else-if="fences.length === 0"
          class="hint"
          data-testid="geofence-list-empty"
        >
          暂无围栏，先在左侧绘制并创建。
        </p>
        <ul
          v-else
          class="fence-list"
          data-testid="geofence-list"
        >
          <li
            v-for="fence in fences"
            :key="fence.id"
            class="fence-row"
            :data-testid="'geofence-row-' + fence.id"
          >
            <div class="fence-main">
              <strong>{{ fence.fenceName }}</strong>
              <span class="tag">{{ fence.fenceType === 'CIRCLE' ? '圆形' : '多边形' }}</span>
              <span class="tag">{{ fence.enterEnabled ? '进入事件' : '不记录进入' }}</span>
              <span class="tag">{{ fence.leaveEnabled ? '离开事件' : '不记录离开' }}</span>
              <span
                class="version"
                :data-testid="'geofence-version-' + fence.id"
              >v{{ fence.version }}</span>
              <span
                class="status"
                :class="{ disabled: fence.status === 'DISABLED' }"
                :data-testid="'geofence-status-' + fence.id"
              >
                {{ fence.status === 'ENABLED' ? '启用中' : '已停用' }}
              </span>
            </div>
            <div class="fence-meta">
              <span v-if="fence.fenceType === 'CIRCLE'">
                半径 {{ fence.radiusM }} 米 · 圆心 {{ fence.centerLng }}, {{ fence.centerLat }}
              </span>
              <span v-else>顶点 {{ fence.vertices?.length ?? 0 }} 个</span>
              <span>绑定车辆：</span>
              <span
                v-if="fence.vehicles.length === 0"
                class="muted"
              >未绑定</span>
              <span
                v-for="vehicle in fence.vehicles"
                :key="vehicle.id"
                class="vehicle-chip"
              >{{ vehicle.plateNo }}（{{ vehicle.fleetName }}）</span>
              <span class="muted">更新于 {{ displayTime(fence.updatedAt) }}</span>
            </div>
            <div class="row-actions">
              <button
                type="button"
                :data-testid="'edit-geofence-' + fence.id"
                @click="startEdit(fence)"
              >
                编辑/绑定
              </button>
              <button
                type="button"
                :data-testid="'toggle-geofence-' + fence.id"
                @click="toggleStatus(fence)"
              >
                {{ fence.status === 'ENABLED' ? '停用' : '启用' }}
              </button>
            </div>
          </li>
        </ul>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.page { padding: 1.5rem; }
h1 { margin: 0 0 0.5rem; font-size: 1.25rem; }
h2 { margin: 0 0 0.75rem; font-size: 1rem; }
.hint { margin: 0 0 1rem; color: #5b6b7c; font-size: 0.875rem; }
.layout { display: grid; grid-template-columns: minmax(320px, 2fr) 3fr; gap: 1rem; align-items: stretch; }
.card { margin-bottom: 1rem; padding: 1rem; background: #fff; border: 1px solid #e2e8ee; border-radius: 8px; }
.map-card { display: flex; flex-direction: column; }
.map-card :deep(.geofence-map) { flex: 1; }
.field { display: grid; gap: 0.3rem; margin-bottom: 0.85rem; }
.field label, .field-label { font-size: 0.875rem; color: #33414f; }
.field input[type='text'] { padding: 0.5rem 0.6rem; border: 1px solid #cfd8e3; border-radius: 6px; }
.flags { display: flex; flex-wrap: wrap; gap: 1rem; margin-bottom: 0.85rem; font-size: 0.875rem; }
.vehicle-list { max-height: 10rem; overflow-y: auto; padding: 0.5rem; border: 1px solid #e2e8ee; border-radius: 6px; }
.vehicle-option { display: flex; align-items: center; gap: 0.5rem; padding: 0.15rem 0; font-size: 0.875rem; }
.vehicle-option small { color: #6b7c8d; }
.actions { display: flex; flex-wrap: wrap; gap: 0.5rem; }
button { padding: 0.5rem 0.9rem; border: 1px solid #cfd8e3; border-radius: 6px; background: #fff; color: #24445f; cursor: pointer; font: inherit; font-size: 0.875rem; }
button:hover { background: #eff5fa; }
button.primary { border-color: #2f6fed; background: #2f6fed; color: #fff; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.list-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 0.75rem; }
.filter { display: flex; align-items: center; gap: 0.5rem; font-size: 0.875rem; color: #33414f; }
.filter select { padding: 0.35rem 0.5rem; border: 1px solid #cfd8e3; border-radius: 6px; }
.fence-list { margin: 0; padding: 0; list-style: none; }
.fence-row { padding: 0.75rem 0; border-top: 1px solid #eef2f6; }
.fence-main { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; }
.tag { padding: 0.05rem 0.4rem; border-radius: 4px; background: #eef3f9; color: #35526e; font-size: 0.75rem; }
.version { color: #5b6b7c; font-size: 0.8rem; }
.status { color: #1f6f43; font-size: 0.8rem; font-weight: 600; }
.status.disabled { color: #8a5a12; }
.fence-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem; margin-top: 0.35rem; color: #4b5b6b; font-size: 0.8rem; }
.muted { color: #7c8b99; }
.vehicle-chip { padding: 0.05rem 0.4rem; border: 1px solid #d7e2ec; border-radius: 4px; background: #f7fafc; }
.row-actions { display: flex; gap: 0.5rem; margin-top: 0.5rem; }
.row-actions button { padding: 0.3rem 0.6rem; font-size: 0.8rem; }
.error { margin: 0 0 1rem; color: #b42318; }
.notice { margin: 0 0 1rem; color: #1f6f43; }
@media (max-width: 980px) { .layout { grid-template-columns: 1fr; } }
</style>