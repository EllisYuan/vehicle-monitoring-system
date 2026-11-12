<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { listFleets, type Fleet } from '../../fleets/api'
import { useSessionStore } from '../../identity/store/session'
import {
  bindingHistory, bindTerminal, createVehicle, fleetHistory, listTerminals, listVehicles,
  registerTerminal, transferVehicle, updateTerminal, updateVehicle,
  type Binding, type FleetHistory, type Terminal, type Vehicle,
} from '../api'

const session = useSessionStore()
const route = useRoute()
const area = computed(() => route.meta.area === 'admin' ? 'admin' : 'monitoring')
const canManage = computed(() => session.isAdmin)
const vehicles = ref<Vehicle[]>([])
const terminals = ref<Terminal[]>([])
const fleets = ref<Fleet[]>([])
const enabledFleets = computed(() => fleets.value.filter(f => f.status === 'ENABLED'))
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const filter = ref<number | ''>('')
const vehicleForm = reactive({ plateNo: '', fleetId: '' as number | '' })
const terminalNo = ref('')
const editor = ref<number | null>(null)
const edit = reactive({ plateNo: '', fleetId: '' as number | '', terminalId: '' as number | '' })
const historyTarget = ref<Vehicle | null>(null)
const bindings = ref<Binding[]>([])
const fleetIntervals = ref<FleetHistory[]>([])

async function load() {
  loading.value = true
  const [v, t, f] = await Promise.all([listVehicles(filter.value || undefined), listTerminals(), listFleets()])
  vehicles.value = v
  terminals.value = t
  fleets.value = f
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
    error.value = errorMessage(e, '档案加载失败')
    vehicles.value = []
    terminals.value = []
  } finally { loading.value = false }
}

function openEditor(vehicle: Vehicle) {
  editor.value = vehicle.id
  edit.plateNo = vehicle.plateNo
  edit.fleetId = vehicle.fleetId
  edit.terminalId = vehicle.terminalId ?? ''
  historyTarget.value = null
}

// 完整终端绑定历史含转队前其他车队的终端，车队长不可读；车队归属历史按授权范围过滤后可见
const canReadBindings = computed(() => session.role !== 'FLEET_LEADER')

async function showHistory(vehicle: Vehicle) {
  historyTarget.value = null
  await perform(async () => {
    const [b, f] = await Promise.all([
      canReadBindings.value ? bindingHistory(vehicle.id) : Promise.resolve([]),
      fleetHistory(vehicle.id),
    ])
    bindings.value = b
    fleetIntervals.value = f
    historyTarget.value = vehicle
  }, '')
}

function availableTerminals(vehicle: Vehicle) {
  return terminals.value.filter(t => t.status === 'ENABLED' && (t.vehicleId === null || t.vehicleId === vehicle.id))
}

function time(value: string) { return value.replace('T', ' ') }
onMounted(refresh)
</script>

<template>
  <AppShell :area="area">
    <section class="archives">
      <header class="page-heading">
        <div>
          <p class="eyebrow">
            车辆 · 终端 · 归属
          </p><h1>车辆与终端档案</h1>
        </div>
        <span class="access-note">{{ canManage ? '档案维护' : '只读档案' }}</span>
      </header>
      <p class="hint">
        终端预登记后绑定车辆。换绑与转队保留历史；停用仅改变档案状态，不删除既有记录。
      </p>
      <p
        v-if="session.role === 'FLEET_LEADER'"
        class="hint"
      >
        当前仅显示授权车队车辆及其已绑定终端。
      </p>
      <p
        v-if="error"
        role="alert"
        class="error"
        data-testid="archive-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
        class="notice"
        data-testid="archive-notice"
      >
        {{ notice }}
      </p>
      <div
        v-if="canManage"
        class="registration"
      >
        <form
          data-testid="vehicle-create-form"
          @submit.prevent="perform(async () => { if (!vehicleForm.fleetId) return; await createVehicle(vehicleForm.plateNo.trim(), vehicleForm.fleetId); vehicleForm.plateNo = '' }, '车辆已登记')"
        >
          <h2>登记车辆</h2>
          <label>车牌<input
            v-model="vehicleForm.plateNo"
            data-testid="vehicle-create-plate"
            required
            maxlength="20"
          ></label>
          <label>所属车队<select
            v-model="vehicleForm.fleetId"
            data-testid="vehicle-create-fleet"
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
            data-testid="vehicle-create-submit"
          >
            新增车辆
          </button>
          <p
            v-if="!enabledFleets.length"
            class="hint"
          >
            请先在车队管理中创建或启用车队。
          </p>
        </form>
        <form
          data-testid="terminal-create-form"
          @submit.prevent="perform(async () => { await registerTerminal(terminalNo.trim()); terminalNo = '' }, '终端已预登记')"
        >
          <h2>终端预登记</h2>
          <label>终端编号<input
            v-model="terminalNo"
            data-testid="terminal-create-number"
            inputmode="numeric"
            pattern="[0-9]{20}"
            required
            minlength="20"
            maxlength="20"
            placeholder="20 位数字"
          ></label>
          <button
            :disabled="busy || loading"
            data-testid="terminal-create-submit"
          >
            预登记终端
          </button>
        </form>
      </div>
      <div class="list-heading">
        <h2>车辆档案</h2><label>按车队筛选<select
          v-model="filter"
          data-testid="archive-fleet-filter"
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
        正在加载档案…
      </p>
      <div class="table-wrap">
        <table data-testid="vehicle-table">
          <thead>
            <tr>
              <th>车牌</th><th>所属车队</th><th>状态</th><th>当前终端</th><th>
                操作
              </th>
            </tr>
          </thead>
          <tbody>
            <template
              v-for="vehicle in vehicles"
              :key="vehicle.id"
            >
              <tr :data-testid="`vehicle-row-${vehicle.plateNo}`">
                <td class="plate">
                  {{ vehicle.plateNo }}
                </td><td>{{ vehicle.fleetName }}</td><td>{{ vehicle.status === 'ENABLED' ? '启用' : '停用' }}</td><td class="number">
                  {{ vehicle.terminalNo ?? '未绑定' }}
                </td><td
                  class="actions"
                >
                  <button
                    v-if="canManage"
                    type="button"
                    :disabled="busy"
                    :data-testid="`vehicle-edit-${vehicle.id}`"
                    @click="openEditor(vehicle)"
                  >
                    维护
                  </button>
                  <button
                    v-if="canManage"
                    type="button"
                    :disabled="busy"
                    :data-testid="`vehicle-toggle-${vehicle.id}`"
                    @click="perform(() => updateVehicle(vehicle.id, { status: vehicle.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' }), '车辆状态已更新')"
                  >
                    {{ vehicle.status === 'ENABLED' ? '停用' : '启用' }}
                  </button>
                  <button
                    type="button"
                    :disabled="busy"
                    :data-testid="`vehicle-history-${vehicle.id}`"
                    @click="showHistory(vehicle)"
                  >
                    查看历史
                  </button>
                </td>
              </tr>
              <tr v-if="canManage && editor === vehicle.id">
                <td colspan="5">
                  <div
                    class="editor"
                    data-testid="vehicle-editor"
                  >
                    <form @submit.prevent="perform(() => updateVehicle(vehicle.id, { plateNo: edit.plateNo.trim() }), '车牌已更新')">
                      <label>车牌<input
                        v-model="edit.plateNo"
                        required
                        maxlength="20"
                        data-testid="vehicle-edit-plate"
                      ></label><button :disabled="busy">
                        保存车牌
                      </button>
                    </form>
                    <form @submit.prevent="perform(() => transferVehicle(vehicle.id, Number(edit.fleetId)), '转队已生效，历史归属已保留')">
                      <label>转入车队<select
                        v-model="edit.fleetId"
                        required
                        data-testid="vehicle-transfer-fleet"
                      ><option
                        v-for="fleet in enabledFleets"
                        :key="fleet.id"
                        :value="fleet.id"
                      >{{ fleet.fleetName }}</option></select></label><button
                        :disabled="busy || !edit.fleetId || edit.fleetId === vehicle.fleetId"
                        data-testid="vehicle-transfer-submit"
                      >
                        确认转队
                      </button>
                    </form>
                    <form @submit.prevent="perform(() => bindTerminal(vehicle.id, edit.terminalId || null), '终端绑定已更新，历史区间已保留')">
                      <label>绑定终端<select
                        v-model="edit.terminalId"
                        data-testid="vehicle-bind-terminal"
                      ><option value="">不绑定（解绑）</option><option
                        v-for="terminal in availableTerminals(vehicle)"
                        :key="terminal.id"
                        :value="terminal.id"
                      >{{ terminal.terminalNo }}</option></select></label><button
                        :disabled="busy"
                        data-testid="vehicle-bind-submit"
                      >
                        保存绑定
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
            <tr v-if="!loading && !vehicles.length">
              <td
                :colspan="5"
                class="empty"
              >
                暂无可见车辆
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <section
        v-if="historyTarget"
        class="history"
        data-testid="archive-history"
      >
        <div class="list-heading">
          <h2>{{ historyTarget.plateNo }} · 历史记录</h2><button
            type="button"
            @click="historyTarget = null"
          >
            关闭
          </button>
        </div>
        <p class="hint">
          生效时间为 Asia/Shanghai；区间包含开始时刻，不包含结束时刻。
        </p>
        <template v-if="canReadBindings">
          <h3>终端绑定</h3><ul>
            <li
              v-for="item in bindings"
              :key="item.id"
            >
              <span class="number">{{ item.terminalNo }}</span> {{ time(item.effectiveFrom) }} — {{ item.effectiveTo ? time(item.effectiveTo) : '至今' }}
            </li>
          </ul><p v-if="!bindings.length">
            尚无绑定记录
          </p>
        </template>
        <h3>车队归属</h3><ul>
          <li
            v-for="item in fleetIntervals"
            :key="item.id"
          >
            {{ item.fleetName }} {{ time(item.effectiveFrom) }} — {{ item.effectiveTo ? time(item.effectiveTo) : '至今' }}
          </li>
        </ul>
      </section>
      <h2 class="terminal-heading">
        终端档案
      </h2>
      <div class="table-wrap">
        <table data-testid="terminal-table">
          <thead>
            <tr>
              <th>终端编号</th><th>档案状态</th><th>通信状态</th><th>最后通信</th><th>当前车辆</th><th v-if="canManage">
                操作
              </th>
            </tr>
          </thead><tbody>
            <tr
              v-for="terminal in terminals"
              :key="terminal.id"
              :data-testid="`terminal-row-${terminal.terminalNo}`"
            >
              <td class="number">
                {{ terminal.terminalNo }}
              </td><td>{{ terminal.status === 'ENABLED' ? '启用' : '停用' }}</td><td>{{ terminal.communicationStatus === 'ONLINE' ? '在线' : terminal.communicationStatus === 'OFFLINE' ? '离线' : '未接入' }}</td><td>{{ terminal.lastSeenAt ? time(terminal.lastSeenAt) : '—' }}<small v-if="terminal.everAuthenticated">曾鉴权</small></td><td>{{ terminal.plateNo ?? '未绑定' }}</td><td v-if="canManage">
                <button
                  type="button"
                  :disabled="busy"
                  :data-testid="`terminal-toggle-${terminal.id}`"
                  @click="perform(() => updateTerminal(terminal.id, terminal.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'), '终端状态已更新')"
                >
                  {{ terminal.status === 'ENABLED' ? '停用' : '启用' }}
                </button>
              </td>
            </tr>
            <tr v-if="!loading && !terminals.length">
              <td
                :colspan="canManage ? 6 : 5"
                class="empty"
              >
                暂无可见终端
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.archives { --ink: #1f2d3d; --line: #dbe3eb; --accent: #2f6fb3; padding: 1.5rem; color: var(--ink); }
.page-heading, .list-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 1rem; }
.page-heading { justify-content: space-between; margin-bottom: .75rem; }
.eyebrow { margin: 0 0 .3rem; color: #5b6b7c; font-size: .75rem; letter-spacing: .12em; }
h1 { margin: 0; font-size: 1.35rem; } h2 { font-size: 1rem; margin: 0 0 .8rem; } h3 { font-size: .9rem; }
.access-note { font-size: .8rem; border-left: 3px solid var(--accent); padding-left: .6rem; }
.hint { color: #5b6b7c; font-size: .85rem; line-height: 1.6; }
.registration { display: grid; grid-template-columns: 1.2fr 1fr; gap: 1rem; margin: 1.25rem 0; }
.registration > form, .history { padding: 1.1rem; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
form { display: flex; align-items: flex-end; flex-wrap: wrap; gap: .75rem; } form h2 { flex-basis: 100%; margin-bottom: .1rem; }
label { display: grid; gap: .3rem; font-size: .85rem; }
input, select { min-height: 2.2rem; max-width: 100%; padding: .4rem .6rem; border: 1px solid #c7d0d9; border-radius: 5px; background: #fff; color: var(--ink); }
button { padding: .45rem .75rem; border: 1px solid var(--accent); border-radius: 5px; background: var(--accent); color: #fff; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; } button:focus-visible, input:focus-visible, select:focus-visible { outline: 2px solid #2075bb; outline-offset: 3px; }
.list-heading { margin: 1.5rem 0 .75rem; } .list-heading h2 { margin: 0; margin-right: auto; }
.table-wrap { overflow-x: auto; } table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid var(--line); }
th, td { text-align: left; padding: .7rem .8rem; border-bottom: 1px solid #eef2f6; font-size: .88rem; } th { background: #f8fafc; font-weight: 600; }
.plate { font-weight: 600; } .number { font-family: Consolas, monospace; font-variant-numeric: tabular-nums; }
.actions { display: flex; flex-wrap: wrap; gap: .4rem; } .actions button, td button { background: white; color: var(--accent); border-color: #c7d0d9; }
.editor { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 1.2rem; padding: .5rem 0; } .history { margin-top: 1rem; }
.history .list-heading { margin-top: 0; } li { margin: .5rem 0; line-height: 1.7; font-size: .85rem; }
.terminal-heading { margin-top: 1.5rem; } .empty { text-align: center; color: #6b7a88; padding: 1.5rem; }
.error { color: #b42318; padding: .7rem; background: #fff0ed; border-left: 3px solid #b42318; }
.notice { color: #1f7a3f; } @media (max-width: 900px) { .registration { grid-template-columns: 1fr; } .archives { padding: 1rem; } }
</style>
