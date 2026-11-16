<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { listFleets, type Fleet } from '../../fleets/api'
import { useSessionStore } from '../../identity/store/session'
import {
  getAlarm, handleAlarm, listAlarms,
  type AlarmDetail, type AlarmRow, type HandleStatus, type HandleTarget,
} from '../api'

const session = useSessionStore()
const canHandle = computed(() => session.canDisposeOrCommand)
const alarms = ref<AlarmRow[]>([])
const fleets = ref<Fleet[]>([])
const fleetFilter = ref<number | ''>('')
const detail = ref<AlarmDetail | null>(null)
const remark = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')

const conditionLabels: Record<string, string> = {
  ACTIVE: '活跃', RECOVERED: '已恢复', EVENT: '事件',
}
const handleLabels: Record<HandleStatus, string> = {
  PENDING: '待处理', PROCESSING: '处理中', HANDLED: '已处理', IGNORED: '已忽略',
}
function conditionLabel(value: string) { return conditionLabels[value] ?? value }
function handleLabel(value: string) { return handleLabels[value as HandleStatus] ?? value }

async function refresh() {
  loading.value = true
  error.value = ''
  try {
    const [rows, visibleFleets] = await Promise.all([
      listAlarms(fleetFilter.value === '' ? undefined : fleetFilter.value),
      listFleets(),
    ])
    alarms.value = rows
    fleets.value = visibleFleets
    if (detail.value && !rows.some(row => row.id === detail.value?.id)) {
      detail.value = null
    }
  } catch (e) {
    alarms.value = []
    detail.value = null
    error.value = errorMessage(e, '报警列表加载失败')
  } finally {
    loading.value = false
  }
}

async function openDetail(row: AlarmRow) {
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    detail.value = await getAlarm(row.id)
    remark.value = ''
  } catch (e) {
    detail.value = null
    error.value = errorMessage(e, '报警详情加载失败')
  } finally {
    busy.value = false
  }
}

async function submit(target: HandleTarget) {
  if (!detail.value || busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await handleAlarm(detail.value.id, target, remark.value.trim() || undefined)
    notice.value = target === 'PROCESSING' ? '已开始处理，记录已留痕'
      : target === 'HANDLED' ? '报警已处理，记录已留痕'
        : '报警已标记为忽略，记录已留痕'
    const current = detail.value.id
    await refresh()
    detail.value = await getAlarm(current)
    remark.value = ''
  } catch (e) {
    error.value = errorMessage(e, '报警处置失败，请刷新后按当前状态重试')
  } finally {
    busy.value = false
  }
}

function time(value: string | null) {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}
onMounted(refresh)
</script>

<template>
  <AppShell area="monitoring">
    <section class="alarms">
      <header class="page-heading">
        <div>
          <p class="eyebrow">
            报警 · 条件状态 · 处置闭环
          </p><h1>报警管理</h1>
        </div>
        <span class="access-note">{{ canHandle ? '可处置' : '只读' }}</span>
      </header>
      <p class="hint">
        条件状态随异常出现与恢复变化，处置状态由人工推进；两者独立，终态处置不因恢复或回落而重开。新异常周期生成新报警事件。
      </p>
      <p
        v-if="!canHandle"
        class="hint"
        data-testid="alarm-readonly-note"
      >
        车队长仅可查看授权车队报警，电话号码中间四位已掩码，不能执行处置。
      </p>
      <p
        v-if="error"
        role="alert"
        class="error"
        data-testid="alarm-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
        class="notice"
        data-testid="alarm-notice"
      >
        {{ notice }}
      </p>
      <div class="list-heading">
        <h2>报警列表</h2>
        <label>按车队筛选<select
          v-model="fleetFilter"
          data-testid="alarm-fleet-filter"
          :disabled="busy"
          @change="refresh"
        ><option value="">
          全部可见车队
        </option><option
          v-for="fleet in fleets"
          :key="fleet.id"
          :value="fleet.id"
        >{{ fleet.fleetName }}</option></select></label>
        <button
          type="button"
          :disabled="busy || loading"
          data-testid="alarm-refresh"
          @click="refresh"
        >
          刷新
        </button>
      </div>
      <p
        v-if="loading"
        role="status"
        data-testid="alarm-loading"
      >
        正在加载报警列表…
      </p>
      <div class="table-wrap">
        <table data-testid="alarm-table">
          <thead>
            <tr>
              <th>报警时间</th><th>车牌</th><th>车队</th><th>类型</th><th>条件状态</th><th>处置状态</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="alarm in alarms"
              :key="alarm.id"
              :data-testid="`alarm-row-${alarm.id}`"
            >
              <td class="number">
                {{ time(alarm.startTime) }}
              </td>
              <td>{{ alarm.plateNo }}</td>
              <td>{{ alarm.fleetName }}</td>
              <td>{{ alarm.alarmType }}</td>
              <td :data-testid="`alarm-condition-${alarm.id}`">
                {{ conditionLabel(alarm.conditionState) }}
              </td>
              <td :data-testid="`alarm-handle-state-${alarm.id}`">
                {{ handleLabel(alarm.handleStatus) }}
              </td>
              <td class="actions">
                <button
                  type="button"
                  :disabled="busy"
                  :data-testid="`alarm-open-${alarm.id}`"
                  @click="openDetail(alarm)"
                >
                  详情
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p
          v-if="!loading && !alarms.length && !error"
          class="hint"
          data-testid="alarm-empty"
        >
          暂无可见报警记录。
        </p>
      </div>
      <section
        v-if="detail"
        class="alarm-detail"
        data-testid="alarm-detail"
      >
        <header class="list-heading">
          <h2>报警详情 #{{ detail.id }}</h2>
          <button
            type="button"
            data-testid="alarm-detail-close"
            @click="detail = null"
          >
            关闭
          </button>
        </header>
        <dl>
          <dt>车牌 / 车队</dt><dd>{{ detail.plateNo }} / {{ detail.fleetName }}</dd>
          <dt>报警时间</dt><dd class="number">{{ time(detail.startTime) }}</dd>
          <dt>最近发生</dt><dd class="number">{{ time(detail.lastTime) }}</dd>
          <dt>恢复时间</dt><dd class="number">{{ time(detail.recoveredAt) }}</dd>
          <dt>条件状态</dt><dd data-testid="alarm-detail-condition">
            {{ conditionLabel(detail.conditionState) }}
          </dd>
          <dt>处置状态</dt><dd data-testid="alarm-detail-handle">
            {{ handleLabel(detail.handleStatus) }}
          </dd>
          <dt>报警位置</dt><dd data-testid="alarm-detail-position">
            <template v-if="detail.position">
              {{ detail.position.lng.toFixed(6) }}, {{ detail.position.lat.toFixed(6) }}
              · {{ detail.position.speedKph ?? '—' }} km/h · {{ time(detail.position.gpsTime) }}
            </template>
            <template v-else>
              无可用位置
            </template>
          </dd>
          <dt>报警时司机</dt><dd data-testid="alarm-detail-driver">
            <template v-if="detail.driver">
              {{ detail.driver.name }} · <span data-testid="alarm-detail-phone">{{ detail.driver.phone }}</span>
            </template>
            <template v-else>
              未关联司机
            </template>
          </dd>
          <dt>规则快照</dt><dd data-testid="alarm-detail-rule">
            {{ detail.ruleSnapshot ? `阈值 ${detail.ruleSnapshot.thresholdKph ?? '—'} km/h · 版本 ${detail.ruleSnapshot.ruleVersion ?? '—'}` : '无规则快照' }}
          </dd>
        </dl>
        <h3>处置历史</h3>
        <p
          v-if="!detail.handleHistory.length"
          class="hint"
          data-testid="alarm-handle-history-empty"
        >
          尚无人工处置记录。
        </p>
        <ul
          v-else
          class="history"
          data-testid="alarm-handle-history"
        >
          <li
            v-for="log in detail.handleHistory"
            :key="log.id"
            :data-testid="`alarm-handle-log-${log.id}`"
          >
            {{ time(log.createdAt) }} · {{ log.operatorName ?? '系统' }} ·
            {{ handleLabel(log.fromStatus) }} →
            {{ handleLabel(log.toStatus) }}
            <template v-if="log.remark">
              · {{ log.remark }}
            </template>
          </li>
        </ul>
        <h3>条件变化</h3>
        <p
          v-if="!detail.conditionHistory.length"
          class="hint"
          data-testid="alarm-condition-history-empty"
        >
          暂无恢复等条件变化。
        </p>
        <ul
          v-else
          class="history"
          data-testid="alarm-condition-history"
        >
          <li
            v-for="log in detail.conditionHistory"
            :key="log.id"
            :data-testid="`alarm-condition-log-${log.id}`"
          >
            {{ time(log.createdAt) }} · {{ conditionLabel(log.fromStatus) }} →
            {{ conditionLabel(log.toStatus) }}
          </li>
        </ul>
        <form
          v-if="canHandle"
          class="handle-form"
          data-testid="alarm-handle-form"
          @submit.prevent="submit(detail.handleStatus === 'PENDING' ? 'PROCESSING' : 'HANDLED')"
        >
          <h3>处置</h3>
          <label>处置备注<input
            v-model="remark"
            data-testid="alarm-handle-remark"
            maxlength="500"
            placeholder="可选，记录联系司机等处理说明"
          ></label>
          <div class="actions">
            <button
              v-if="detail.handleStatus === 'PENDING'"
              type="submit"
              :disabled="busy"
              data-testid="alarm-handle-start"
            >
              开始处理
            </button>
            <button
              v-if="detail.handleStatus === 'PROCESSING'"
              type="button"
              :disabled="busy"
              data-testid="alarm-handle-finish"
              @click="submit('HANDLED')"
            >
              标记已处理
            </button>
            <button
              v-if="detail.handleStatus === 'PROCESSING'"
              type="button"
              :disabled="busy"
              data-testid="alarm-handle-ignore"
              @click="submit('IGNORED')"
            >
              标记忽略
            </button>
            <span
              v-if="detail.handleStatus === 'HANDLED' || detail.handleStatus === 'IGNORED'"
              class="hint"
              data-testid="alarm-terminal-note"
            >
              处置已终结；同一异常若再次发生将生成新的报警事件。
            </span>
          </div>
        </form>
        <p
          v-else
          class="hint"
          data-testid="alarm-readonly-detail-note"
        >
          仅管理员与监控员可处置报警。
        </p>
      </section>
    </section>
  </AppShell>
</template>
