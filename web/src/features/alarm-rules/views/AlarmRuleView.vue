<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { listFleets, type Fleet } from '../../fleets/api'
import {
  deleteFleetOverride,
  getAlarmRuleOverview,
  updateGlobalThreshold,
  upsertFleetOverride,
  type AlarmRule,
  type AlarmRuleOverview,
} from '../api'

const overview = ref<AlarmRuleOverview | null>(null)
const fleets = ref<Fleet[]>([])
const globalThreshold = ref(80)
const selectedFleetId = ref<number | ''>('')
const overrideThreshold = ref(80)
const loading = ref(false)
const error = ref('')
const notice = ref('')

watch(selectedFleetId, (id) => {
  if (id === '') {
    return
  }
  const existing = overview.value?.fleetOverrides.find((rule) => rule.fleetId === id)
  overrideThreshold.value = existing ? existing.thresholdKph : (overview.value?.global?.thresholdKph ?? 80)
})

function displayTime(value: string): string {
  return value.replace('T', ' ')
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await getAlarmRuleOverview()
    globalThreshold.value = overview.value.global?.thresholdKph ?? 80
  } catch (e) {
    error.value = errorMessage(e, '报警规则加载失败')
  } finally {
    loading.value = false
  }
}

async function loadFleets() {
  try {
    fleets.value = await listFleets()
  } catch (e) {
    error.value = errorMessage(e, '车队列表加载失败')
  }
}

async function saveGlobal() {
  error.value = ''
  notice.value = ''
  try {
    const saved = await updateGlobalThreshold(globalThreshold.value)
    notice.value = '全局默认阈值已保存，版本更新为 v' + saved.version
    await reload()
  } catch (e) {
    error.value = errorMessage(e, '保存全局默认失败')
  }
}

async function saveOverride() {
  if (selectedFleetId.value === '') {
    error.value = '请选择要覆盖的车队'
    return
  }
  error.value = ''
  notice.value = ''
  try {
    const saved = await upsertFleetOverride(selectedFleetId.value, overrideThreshold.value)
    notice.value = '车队覆盖已保存：' + (saved.fleetName ?? '') + ' v' + saved.version
    await reload()
  } catch (e) {
    error.value = errorMessage(e, '保存车队覆盖失败')
  }
}

async function removeOverride(rule: AlarmRule) {
  if (rule.fleetId === null) {
    return
  }
  error.value = ''
  notice.value = ''
  try {
    await deleteFleetOverride(rule.fleetId)
    notice.value = '车队覆盖已删除：' + (rule.fleetName ?? '') + '，该车队恢复使用全局默认'
    await reload()
  } catch (e) {
    error.value = errorMessage(e, '删除车队覆盖失败')
  }
}

onMounted(async () => {
  await Promise.all([reload(), loadFleets()])
})
</script>

<template>
  <AppShell area="admin">
    <section class="page">
      <h1>报警规则</h1>
      <p class="hint">
        超速报警采用全局默认与车队覆盖两级规则，车队覆盖优先；每次修改递增版本，已成立的报警保留成立时的规则快照。
        连续 3 个有效实时点超过阈值成立 ACTIVE，连续 3 个有效点不超过阈值后恢复。
      </p>

      <p v-if="error" class="error" data-testid="alarm-rule-error">{{ error }}</p>
      <p v-if="notice" class="notice" data-testid="alarm-rule-notice">{{ notice }}</p>

      <section class="card">
        <h2>全局默认</h2>
        <p v-if="loading" class="hint">加载中…</p>
        <dl v-else-if="overview?.global" class="grid">
          <dt>当前阈值</dt>
          <dd data-testid="global-threshold">{{ overview.global.thresholdKph }} km/h</dd>
          <dt>规则版本</dt>
          <dd data-testid="global-version">{{ overview.global.version }}</dd>
          <dt>更新时间</dt>
          <dd>{{ displayTime(overview.global.updatedAt) }}</dd>
        </dl>
        <p v-else class="hint">尚无全局规则，保存后创建。</p>
        <div class="controls">
          <label>
            阈值（km/h）
            <input
              v-model.number="globalThreshold"
              type="number"
              min="1"
              max="300"
              data-testid="global-threshold-input"
            >
          </label>
          <button type="button" data-testid="save-global" @click="saveGlobal">保存全局默认</button>
        </div>
      </section>

      <section class="card">
        <h2>车队覆盖</h2>
        <p
          v-if="!loading && (overview?.fleetOverrides.length ?? 0) === 0"
          class="hint"
          data-testid="fleet-overrides-empty"
        >
          暂无车队覆盖，全部车队使用全局默认。
        </p>
        <ul v-else data-testid="fleet-overrides">
          <li
            v-for="rule in overview?.fleetOverrides ?? []"
            :key="rule.id"
            :data-testid="'fleet-override-' + rule.fleetId"
          >
            <span class="fleet-name">{{ rule.fleetName }}</span>
            <span>{{ rule.thresholdKph }} km/h</span>
            <span class="version">v{{ rule.version }}</span>
            <span class="time">{{ displayTime(rule.updatedAt) }}</span>
            <button
              type="button"
              class="danger"
              :data-testid="'delete-override-' + rule.fleetId"
              @click="removeOverride(rule)"
            >
              删除覆盖
            </button>
          </li>
        </ul>
        <div class="controls">
          <label>
            车队
            <select v-model.number="selectedFleetId" data-testid="override-fleet-select">
              <option value="">选择车队</option>
              <option v-for="fleet in fleets" :key="fleet.id" :value="fleet.id">
                {{ fleet.fleetName }}
              </option>
            </select>
          </label>
          <label>
            阈值（km/h）
            <input
              v-model.number="overrideThreshold"
              type="number"
              min="1"
              max="300"
              data-testid="override-threshold-input"
            >
          </label>
          <button type="button" data-testid="save-override" @click="saveOverride">保存车队覆盖</button>
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
.grid {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 0.4rem 1rem;
  margin: 0;
}
.grid dt {
  color: #5b6b7c;
}
.grid dd {
  margin: 0;
  font-weight: 600;
}
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
  margin-top: 1rem;
}
.controls label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.875rem;
  color: #33414f;
}
.controls input,
.controls select {
  width: 10rem;
  padding: 0.45rem 0.6rem;
  border: 1px solid #cfd8e3;
  border-radius: 6px;
}
ul {
  margin: 0;
  padding-left: 1.25rem;
}
li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
  padding: 0.35rem 0;
}
.fleet-name {
  font-weight: 600;
}
.version {
  color: #5b6b7c;
}
.time {
  color: #5b6b7c;
  font-size: 0.875rem;
}
button {
  padding: 0.5rem 1rem;
  border: 1px solid #2f6fed;
  border-radius: 6px;
  background: #2f6fed;
  color: #fff;
  cursor: pointer;
}
button.danger {
  background: #c0362c;
  border-color: #c0362c;
}
.error {
  margin: 0 0 1rem;
  color: #b42318;
}
.notice {
  margin: 0.75rem 0 0;
  color: #1f6f43;
}
</style>
