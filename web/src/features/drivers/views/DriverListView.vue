<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { listFleets, type Fleet } from '../../fleets/api'
import { listVehicles, type Vehicle } from '../../archives/api'
import { useSessionStore } from '../../identity/store/session'
import {
  bindDriver, createDriver, driverHistory, listDrivers, updateDriver,
  type Association, type Driver,
} from '../api'

const session = useSessionStore()
const route = useRoute()
const area = computed(() => route.meta.area === 'admin' ? 'admin' : 'monitoring')
const canManage = computed(() => session.isAdmin)
const drivers = ref<Driver[]>([])
const fleets = ref<Fleet[]>([])
const vehicles = ref<Vehicle[]>([])
const enabledFleets = computed(() => fleets.value.filter(f => f.status === 'ENABLED'))
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const filter = ref<number | ''>('')
const form = reactive({ driverNo: '', name: '', phone: '', fleetId: '' as number | '' })
const editor = ref<number | null>(null)
const edit = reactive({ driverNo: '', name: '', phone: '', vehicleId: '' as number | '' })
const historyTarget = ref<Driver | null>(null)
const associations = ref<Association[]>([])

async function load() {
  loading.value = true
  const [d, f, v] = await Promise.all([listDrivers(filter.value || undefined), listFleets(), listVehicles()])
  drivers.value = d
  fleets.value = f
  vehicles.value = v
  loading.value = false
}

async function perform(action: () => Promise<unknown>, message: string) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = message
    await load()
  } catch (e) {
    historyTarget.value = null
    error.value = errorMessage(e, '操作失败，请稍后重试')
  } finally {
    busy.value = false
    loading.value = false
  }
}

async function refresh() {
  error.value = ''
  try { await load() } catch (e) {
    error.value = errorMessage(e, '司机档案加载失败')
    drivers.value = []
  } finally { loading.value = false }
}

function openEditor(driver: Driver) {
  editor.value = driver.id
  edit.driverNo = driver.driverNo
  edit.name = driver.name
  edit.phone = driver.phone
  edit.vehicleId = driver.vehicleId ?? ''
  historyTarget.value = null
}

// 司机同一时刻至多关联一辆车；换车由服务端在一次请求内结束旧区间，页面不做两步补偿
async function associate(driver: Driver, selected: number | '') {
  const target = selected === '' ? null : Number(selected)
  if (driver.vehicleId === target) return
  await perform(async () => {
    if (target) await bindDriver(target, driver.id)
    else if (driver.vehicleId) await bindDriver(driver.vehicleId, null)
  }, target ? '司机关联已更新，历史区间已保留' : '已解除司机关联')
}

async function showHistory(driver: Driver) {
  historyTarget.value = null
  await perform(async () => {
    associations.value = await driverHistory(driver.id)
    historyTarget.value = driver
  }, '')
}

function time(value: string) { return value.replace('T', ' ') }
onMounted(refresh)
</script>

<template>
  <AppShell :area="area">
    <section class="drivers">
      <header class="page-heading">
        <div>
          <p class="eyebrow">
            司机 · 档案 · 关联
          </p><h1>司机档案</h1>
        </div>
        <span class="access-note">{{ canManage ? '档案维护' : '只读档案' }}</span>
      </header>
      <p class="hint">
        关联司机由业务人员维护，不构成对实际驾驶员身份的认证。更换关联保留生效区间历史，停用司机不删除既有记录。
      </p>
      <p
        v-if="session.role === 'FLEET_LEADER'"
        class="hint"
        data-testid="driver-mask-note"
      >
        当前仅显示授权车队司机，电话号码中间四位已掩码。
      </p>
      <p
        v-if="error"
        role="alert"
        class="error"
        data-testid="driver-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
        class="notice"
        data-testid="driver-notice"
      >
        {{ notice }}
      </p>
      <form
        v-if="canManage"
        class="registration"
        data-testid="driver-create-form"
        @submit.prevent="perform(async () => { if (!form.fleetId) return; await createDriver(form.driverNo.trim(), form.name.trim(), form.phone, form.fleetId); form.driverNo = ''; form.name = ''; form.phone = '' }, '司机已登记')"
      >
        <h2>登记司机</h2>
        <label>司机编号<input
          v-model="form.driverNo"
          data-testid="driver-create-no"
          required
          maxlength="32"
        ></label>
        <label>姓名<input
          v-model="form.name"
          data-testid="driver-create-name"
          required
          maxlength="50"
        ></label>
        <label>手机号<input
          v-model="form.phone"
          data-testid="driver-create-phone"
          inputmode="numeric"
          pattern="[0-9]{11}"
          required
          minlength="11"
          maxlength="11"
        ></label>
        <label>所属车队<select
          v-model="form.fleetId"
          data-testid="driver-create-fleet"
          required
        ><option
          value=""
          disabled
        >选择启用车队</option><option
          v-for="fleet in enabledFleets"
          :key="fleet.id"
          :value="fleet.id"
        >{{ fleet.fleetName }}</option></select></label>
        <button
          :disabled="busy || loading"
          data-testid="driver-create-submit"
        >
          新增司机
        </button>
        <p
          v-if="!enabledFleets.length"
          class="hint"
        >
          请先在车队管理中创建或启用车队。
        </p>
      </form>
      <div class="list-heading">
        <h2>司机列表</h2><label>按车队筛选<select
          v-model="filter"
          data-testid="driver-fleet-filter"
          :disabled="busy"
          @change="refresh"
        ><option value="">全部可见车队</option><option
          v-for="fleet in fleets"
          :key="fleet.id"
          :value="fleet.id"
        >{{ fleet.fleetName }}</option></select></label><button
          type="button"
          :disabled="busy || loading"
          @click="refresh"
        >
          刷新
        </button>
      </div>
      <p
        v-if="loading"
        role="status"
      >
        正在加载司机档案…
      </p>
      <div class="table-wrap">
        <table data-testid="driver-table">
          <thead>
            <tr>
              <th>司机编号</th><th>姓名</th><th>电话</th><th>所属车队</th><th>状态</th><th>当前车辆</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template
              v-for="driver in drivers"
              :key="driver.id"
            >
              <tr :data-testid="`driver-row-${driver.driverNo}`">
                <td class="number">
                  {{ driver.driverNo }}
                </td><td>{{ driver.name }}</td><td class="number">
                  {{ driver.phone }}
                </td><td>{{ driver.fleetName }}</td><td>{{ driver.status === 'ENABLED' ? '启用' : '停用' }}</td><td>{{ driver.plateNo ?? '未关联' }}</td><td class="actions">
                  <button
                    v-if="canManage"
                    type="button"
                    :disabled="busy"
                    :data-testid="`driver-edit-${driver.id}`"
                    @click="openEditor(driver)"
                  >
                    维护
                  </button>
                  <button
                    v-if="canManage"
                    type="button"
                    :disabled="busy"
                    :data-testid="`driver-toggle-${driver.id}`"
                    @click="perform(() => updateDriver(driver.id, { status: driver.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' }), '司机状态已更新')"
                  >
                    {{ driver.status === 'ENABLED' ? '停用' : '启用' }}
                  </button>
                  <button
                    type="button"
                    :disabled="busy"
                    :data-testid="`driver-history-${driver.id}`"
                    @click="showHistory(driver)"
                  >
                    查看历史
                  </button>
                </td>
              </tr>
              <tr v-if="canManage && editor === driver.id">
                <td colspan="7">
                  <div
                    class="editor"
                    data-testid="driver-editor"
                  >
                    <form @submit.prevent="perform(() => updateDriver(driver.id, { driverNo: edit.driverNo.trim(), name: edit.name.trim(), phone: edit.phone }), '司机档案已更新')">
                      <label>司机编号<input
                        v-model="edit.driverNo"
                        required
                        maxlength="32"
                        data-testid="driver-edit-no"
                      ></label>
                      <label>姓名<input
                        v-model="edit.name"
                        required
                        maxlength="50"
                        data-testid="driver-edit-name"
                      ></label>
                      <label>手机号<input
                        v-model="edit.phone"
                        inputmode="numeric"
                        pattern="[0-9]{11}"
                        required
                        minlength="11"
                        maxlength="11"
                        data-testid="driver-edit-phone"
                      ></label><button
                        :disabled="busy"
                        data-testid="driver-edit-save"
                      >
                        保存档案
                      </button>
                    </form>
                    <form @submit.prevent="associate(driver, edit.vehicleId)">
                      <label>关联车辆<select
                        v-model="edit.vehicleId"
                        data-testid="driver-associate-vehicle"
                      ><option value="">不关联（解除）</option><option
                        v-for="vehicle in vehicles"
                        :key="vehicle.id"
                        :value="vehicle.id"
                      >{{ vehicle.plateNo }}</option></select></label><button
                        :disabled="busy"
                        data-testid="driver-associate-submit"
                      >
                        保存关联
                      </button>
                    </form>
                    <button
                      type="button"
                      :disabled="busy"
                      @click="editor = null"
                    >
                      收起
                    </button>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!loading && !drivers.length">
              <td
                colspan="7"
                class="empty"
              >
                暂无可见司机
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <section
        v-if="historyTarget"
        class="history"
        data-testid="driver-history"
      >
        <div class="list-heading">
          <h2>{{ historyTarget.name }} · 关联历史</h2><button
            type="button"
            @click="historyTarget = null"
          >
            关闭
          </button>
        </div>
        <p class="hint">
          生效时间为 Asia/Shanghai；区间包含开始时刻，不包含结束时刻。
        </p>
        <template v-if="associations.length">
          <ul>
            <li
              v-for="item in associations"
              :key="item.id"
            >
              <span class="number">{{ item.plateNo }}</span> {{ time(item.effectiveFrom) }} — {{ item.effectiveTo ? time(item.effectiveTo) : '至今' }}
            </li>
          </ul>
        </template>
        <p v-else>
          尚无关联记录
        </p>
      </section>
    </section>
  </AppShell>
</template>

<style scoped>
.drivers { --ink: #1f2d3d; --line: #dbe3eb; --accent: #2f6fb3; padding: 1.5rem; color: var(--ink); }
.page-heading, .list-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 1rem; }
.page-heading { justify-content: space-between; margin-bottom: .75rem; }
.eyebrow { margin: 0 0 .3rem; color: #5b6b7c; font-size: .75rem; letter-spacing: .12em; }
h1 { margin: 0; font-size: 1.35rem; } h2 { font-size: 1rem; margin: 0 0 .8rem; } h3 { font-size: .9rem; }
.access-note { font-size: .8rem; border-left: 3px solid var(--accent); padding-left: .6rem; }
.hint { color: #5b6b7c; font-size: .85rem; line-height: 1.6; }
.registration { display: flex; align-items: flex-end; flex-wrap: wrap; gap: .75rem; padding: 1.1rem; margin: 1.25rem 0; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
form { display: flex; align-items: flex-end; flex-wrap: wrap; gap: .75rem; }
label { display: grid; gap: .3rem; font-size: .85rem; }
input, select { min-height: 2.2rem; max-width: 100%; padding: .4rem .6rem; border: 1px solid #c7d0d9; border-radius: 5px; background: #fff; color: var(--ink); }
button { padding: .45rem .75rem; border: 1px solid var(--accent); border-radius: 5px; background: var(--accent); color: #fff; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; } button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid #2075bb; outline-offset: 3px; }
.list-heading { margin: 1.5rem 0 .75rem; } .list-heading h2 { margin: 0; margin-right: auto; }
.table-wrap { overflow-x: auto; } table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid var(--line); }
th, td { text-align: left; padding: .7rem .8rem; border-bottom: 1px solid #eef2f6; font-size: .88rem; } th { background: #f8fafc; font-weight: 600; }
.number { font-family: Consolas, monospace; font-variant-numeric: tabular-nums; }
.actions { display: flex; flex-wrap: wrap; gap: .4rem; } .actions button, td button { background: white; color: var(--accent); border-color: #c7d0d9; }
.editor { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 1.2rem; padding: .5rem 0; } .history { margin-top: 1rem; padding: 1.1rem; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.history .list-heading { margin-top: 0; } li { margin: .5rem 0; line-height: 1.7; font-size: .85rem; }
.empty { text-align: center; color: #6b7a88; padding: 1.5rem; }
.error { color: #b42318; padding: .7rem; background: #fff0ed; border-left: 3px solid #b42318; }
.notice { color: #1f7a3f; } @media (max-width: 900px) { .registration, .editor { flex-direction: column; align-items: stretch; } .drivers { padding: 1rem; } }
</style>
