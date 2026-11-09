<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { useSessionStore } from '../../identity/store/session'
import { createFleet, listFleets, updateFleet, type Fleet } from '../api'

const route = useRoute()
const session = useSessionStore()
const area = computed(() => ((route.meta.area as 'monitoring' | 'admin' | undefined) ?? 'admin'))
const fleets = ref<Fleet[]>([])
const loading = ref(false)
const error = ref('')
const notice = ref('')
const creating = ref(false)
const form = reactive({ fleetCode: '', fleetName: '' })

const canManage = session.isAdmin

async function load() {
  loading.value = true
  error.value = ''
  try {
    fleets.value = await listFleets()
  } catch (e) {
    error.value = errorMessage(e, '车队列表加载失败')
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (creating.value) {
    return
  }
  creating.value = true
  error.value = ''
  notice.value = ''
  try {
    await createFleet(form.fleetCode.trim(), form.fleetName.trim())
    form.fleetCode = ''
    form.fleetName = ''
    notice.value = '车队已创建'
    await load()
  } catch (e) {
    error.value = errorMessage(e, '车队创建失败')
  } finally {
    creating.value = false
  }
}

async function toggle(fleet: Fleet) {
  error.value = ''
  notice.value = ''
  try {
    await updateFleet(fleet.id, { status: fleet.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' })
    await load()
  } catch (e) {
    error.value = errorMessage(e, '车队状态更新失败')
  }
}

onMounted(load)
</script>

<template>
  <AppShell :area="area">
    <section class="page">
      <h1>车队管理</h1>
      <p class="hint">
        管理员可创建、停用车队；监控员全企业只读；车队长仅见授权车队。
      </p>

      <form
        v-if="canManage"
        class="create-form"
        data-testid="fleet-create-form"
        @submit.prevent="submit"
      >
        <label>
          车队编号
          <input
            v-model="form.fleetCode"
            data-testid="fleet-create-code"
            required
            maxlength="64"
            placeholder="如 FL-BJ-01"
          >
        </label>
        <label>
          车队名称
          <input
            v-model="form.fleetName"
            data-testid="fleet-create-name"
            required
            maxlength="128"
            placeholder="如 北京一队"
          >
        </label>
        <button
          type="submit"
          data-testid="fleet-create-submit"
          :disabled="creating"
        >
          {{ creating ? '创建中…' : '新增车队' }}
        </button>
      </form>

      <p
        v-if="error"
        class="error"
        role="alert"
        data-testid="fleet-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        class="notice"
        data-testid="fleet-notice"
      >
        {{ notice }}
      </p>

      <table
        class="table"
        data-testid="fleet-table"
      >
        <thead>
          <tr>
            <th>编号</th>
            <th>名称</th>
            <th>状态</th>
            <th v-if="canManage">
              操作
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="fleet in fleets"
            :key="fleet.id"
            :data-testid="`fleet-row-${fleet.fleetCode}`"
          >
            <td>{{ fleet.fleetCode }}</td>
            <td>{{ fleet.fleetName }}</td>
            <td>{{ fleet.status === 'ENABLED' ? '启用' : '停用' }}</td>
            <td v-if="canManage">
              <button
                type="button"
                class="link-button"
                :data-testid="`fleet-toggle-${fleet.fleetCode}`"
                @click="toggle(fleet)"
              >
                {{ fleet.status === 'ENABLED' ? '停用' : '启用' }}
              </button>
            </td>
          </tr>
          <tr v-if="!loading && fleets.length === 0">
            <td
              :colspan="canManage ? 4 : 3"
              class="empty"
            >
              暂无可见车队
            </td>
          </tr>
        </tbody>
      </table>
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

.hint {
  margin: 0 0 1rem;
  color: #5b6b7c;
  font-size: 0.875rem;
}

.create-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 0.75rem;
  margin-bottom: 1rem;
  padding: 1rem;
  background: #fff;
  border: 1px solid #e2e8ee;
  border-radius: 8px;
}

.create-form label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: #2c3e50;
}

input {
  padding: 0.45rem 0.6rem;
  border: 1px solid #c7d0d9;
  border-radius: 6px;
  font-size: 0.9rem;
}

button {
  padding: 0.5rem 0.9rem;
  border: 1px solid #2f6fb3;
  border-radius: 6px;
  background: #2f6fb3;
  color: #fff;
  cursor: pointer;
}

button:disabled {
  opacity: 0.7;
  cursor: progress;
}

.link-button {
  padding: 0.2rem 0.55rem;
  background: #fff;
  color: #2f6fb3;
  border-color: #c7d0d9;
}

.error {
  margin: 0 0 0.75rem;
  color: #b42318;
}

.notice {
  margin: 0 0 0.75rem;
  color: #1f7a3f;
}

.table {
  width: 100%;
  border-collapse: collapse;
  background: #fff;
  border: 1px solid #e2e8ee;
}

th,
td {
  padding: 0.55rem 0.75rem;
  border-bottom: 1px solid #eef2f6;
  text-align: left;
  font-size: 0.9rem;
}

th {
  background: #f8fafc;
  font-weight: 600;
}

.empty {
  color: #8496a8;
  text-align: center;
}
</style>
