<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import { listFleets, type Fleet } from '../../fleets/api'
import { ROLE_LABELS, useSessionStore, type RoleCode } from '../../identity/store/session'
import {
  assignAccountFleets,
  createAccount,
  listAccounts,
  resetAccountPassword,
  updateAccountStatus,
  type Account,
} from '../api'

const accounts = ref<Account[]>([])
const fleets = ref<Fleet[]>([])
const error = ref('')
const notice = ref('')
const creating = ref(false)
const session = useSessionStore()

const form = reactive({
  username: '',
  password: '',
  roleCode: 'MONITOR' as RoleCode,
  fleetIds: [] as number[],
})

const resetTarget = ref<number | null>(null)
const resetPassword = ref('')
const fleetEditor = ref<number | null>(null)
const fleetSelection = ref<number[]>([])

const roleOptions: RoleCode[] = ['ADMIN', 'MONITOR', 'FLEET_LEADER']
const needsFleets = computed(() => form.roleCode === 'FLEET_LEADER')

const fleetNameById = computed(() => new Map(fleets.value.map((fleet) => [fleet.id, fleet.fleetName])))

async function load() {
  error.value = ''
  try {
    const [accountList, fleetList] = await Promise.all([listAccounts(), listFleets()])
    accounts.value = accountList
    fleets.value = fleetList
  } catch (e) {
    error.value = errorMessage(e, '账号数据加载失败')
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
    await createAccount({
      username: form.username.trim(),
      password: form.password,
      roleCode: form.roleCode,
      fleetIds: form.roleCode === 'FLEET_LEADER' ? form.fleetIds : [],
    })
    form.username = ''
    form.password = ''
    form.fleetIds = []
    notice.value = '账号已创建'
    await load()
  } catch (e) {
    error.value = errorMessage(e, '账号创建失败')
  } finally {
    creating.value = false
  }
}

async function toggleStatus(account: Account) {
  error.value = ''
  notice.value = ''
  try {
    await updateAccountStatus(account.id, account.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')
    await load()
  } catch (e) {
    error.value = errorMessage(e, '账号状态更新失败')
  }
}

function openReset(account: Account) {
  resetTarget.value = account.id
  resetPassword.value = ''
}

async function submitReset(account: Account) {
  error.value = ''
  notice.value = ''
  try {
    await resetAccountPassword(account.id, resetPassword.value)
    resetTarget.value = null
    resetPassword.value = ''
    notice.value = `已重置 ${account.username} 的密码，该账号已有会话已失效`
  } catch (e) {
    error.value = errorMessage(e, '密码重置失败')
  }
}

function openFleetEditor(account: Account) {
  fleetEditor.value = account.id
  fleetSelection.value = [...account.fleetIds]
}

async function saveFleets(account: Account) {
  error.value = ''
  notice.value = ''
  try {
    await assignAccountFleets(account.id, fleetSelection.value)
    fleetEditor.value = null
    notice.value = `已更新 ${account.username} 的车队授权，该账号已有会话已失效`
    await load()
  } catch (e) {
    error.value = errorMessage(e, '车队授权更新失败')
  }
}

onMounted(load)
</script>

<template>
  <AppShell area="admin">
    <section class="page">
      <h1>账号管理</h1>
      <p class="hint">
        每个账号固定一个角色；车队长可授权一个或多个车队。停用、重置密码和调整授权会使已有会话立即失效。
      </p>

      <form
        class="create-form"
        data-testid="account-create-form"
        @submit.prevent="submit"
      >
        <label>
          用户名
          <input
            v-model="form.username"
            data-testid="account-create-username"
            required
            minlength="4"
            maxlength="64"
            pattern="[A-Za-z0-9_.-]+"
          >
        </label>
        <label>
          初始密码
          <input
            v-model="form.password"
            data-testid="account-create-password"
            type="password"
            required
            minlength="8"
            maxlength="200"
            autocomplete="new-password"
          >
        </label>
        <label>
          角色
          <select
            v-model="form.roleCode"
            data-testid="account-create-role"
          >
            <option
              v-for="role in roleOptions"
              :key="role"
              :value="role"
            >
              {{ ROLE_LABELS[role] }}
            </option>
          </select>
        </label>
        <fieldset
          v-if="needsFleets"
          class="fleet-picker"
          data-testid="account-create-fleets"
        >
          <legend>授权车队（可多选）</legend>
          <label
            v-for="fleet in fleets"
            :key="fleet.id"
            class="checkbox"
          >
            <input
              v-model="form.fleetIds"
              type="checkbox"
              :value="fleet.id"
            >
            {{ fleet.fleetName }}
          </label>
          <span v-if="fleets.length === 0">暂无车队，请先在车队管理中创建</span>
        </fieldset>
        <button
          type="submit"
          data-testid="account-create-submit"
          :disabled="creating"
        >
          {{ creating ? '创建中…' : '新增账号' }}
        </button>
      </form>

      <p
        v-if="error"
        class="error"
        role="alert"
        data-testid="account-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        class="notice"
        data-testid="account-notice"
      >
        {{ notice }}
      </p>

      <table
        class="table"
        data-testid="account-table"
      >
        <thead>
          <tr>
            <th>用户名</th>
            <th>角色</th>
            <th>状态</th>
            <th>授权车队</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="account in accounts"
            :key="account.id"
            :data-testid="`account-row-${account.username}`"
          >
            <td>{{ account.username }}</td>
            <td>{{ ROLE_LABELS[account.roleCode] }}</td>
            <td>{{ account.status === 'ENABLED' ? '启用' : '停用' }}</td>
            <td>
              <span v-if="account.roleCode !== 'FLEET_LEADER'">全企业</span>
              <span v-else>
                {{ account.fleetIds.map((id) => fleetNameById.get(id) ?? `#${id}`).join('、') || '未授权' }}
              </span>
              <div
                v-if="account.roleCode === 'FLEET_LEADER'"
                class="inline-editor"
              >
                <template v-if="fleetEditor === account.id">
                  <label
                    v-for="fleet in fleets"
                    :key="fleet.id"
                    class="checkbox"
                  >
                    <input
                      v-model="fleetSelection"
                      type="checkbox"
                      :value="fleet.id"
                    >
                    {{ fleet.fleetName }}
                  </label>
                  <button
                    type="button"
                    class="link-button"
                    @click="saveFleets(account)"
                  >
                    保存
                  </button>
                  <button
                    type="button"
                    class="link-button"
                    @click="fleetEditor = null"
                  >
                    取消
                  </button>
                </template>
                <button
                  v-else
                  type="button"
                  class="link-button"
                  :data-testid="`account-fleets-${account.username}`"
                  @click="openFleetEditor(account)"
                >
                  调整授权
                </button>
              </div>
            </td>
            <td class="actions">
              <button
                v-if="account.id !== session.user?.id"
                type="button"
                class="link-button"
                :data-testid="`account-toggle-${account.username}`"
                @click="toggleStatus(account)"
              >
                {{ account.status === 'ENABLED' ? '停用' : '启用' }}
              </button>
              <button
                type="button"
                class="link-button"
                :data-testid="`account-reset-${account.username}`"
                @click="openReset(account)"
              >
                重置密码
              </button>
              <template v-if="resetTarget === account.id">
                <input
                  v-model="resetPassword"
                  type="password"
                  minlength="8"
                  maxlength="200"
                  placeholder="新密码（至少 8 位）"
                  :data-testid="`account-reset-input-${account.username}`"
                >
                <button
                  type="button"
                  class="link-button"
                  :data-testid="`account-reset-submit-${account.username}`"
                  @click="submitReset(account)"
                >
                  确认重置
                </button>
              </template>
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

.create-form > label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: #2c3e50;
}

input,
select {
  padding: 0.45rem 0.6rem;
  border: 1px solid #c7d0d9;
  border-radius: 6px;
  font-size: 0.9rem;
}

.fleet-picker {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.6rem;
  margin: 0;
  padding: 0.35rem 0.6rem;
  border: 1px dashed #c7d0d9;
  border-radius: 6px;
  font-size: 0.85rem;
}

.checkbox {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  color: #2c3e50;
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
  margin-right: 0.35rem;
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
  vertical-align: top;
}

th {
  background: #f8fafc;
  font-weight: 600;
}

.inline-editor {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin-top: 0.35rem;
  font-size: 0.82rem;
}

.actions {
  white-space: nowrap;
}
</style>
