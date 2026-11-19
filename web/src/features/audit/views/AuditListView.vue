<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import {
  getOperationRecords,
  searchAuditLogs,
  type AuditPage,
  type AuditQuery,
  type OperationRecords,
} from '../api'

const categories = [
  'BOOTSTRAP',
  'LOGIN',
  'LOGOUT',
  'ACCOUNT',
  'AUTHORIZATION',
  'FLEET',
  'VEHICLE',
  'TERMINAL',
  'DRIVER',
  'COMMAND',
]

const filters = reactive({
  actor: '',
  category: '',
  action: '',
  result: '',
  from: '',
  to: '',
})

const page = ref(0)
const size = ref(20)
const result = ref<AuditPage>({ items: [], page: 0, size: 20, total: 0, hasMore: false })
const records = ref<OperationRecords | null>(null)
const loading = ref(false)
const error = ref('')

function query(): AuditQuery {
  return {
    actor: filters.actor.trim() || undefined,
    category: filters.category || undefined,
    action: filters.action.trim() || undefined,
    result: filters.result || undefined,
    from: filters.from || undefined,
    to: filters.to || undefined,
    page: page.value,
    size: size.value,
  }
}

async function search(resetPage = true) {
  if (loading.value) {
    return
  }
  if (resetPage) {
    page.value = 0
  }
  loading.value = true
  error.value = ''
  try {
    result.value = await searchAuditLogs(query())
  } catch (e) {
    error.value = errorMessage(e, '审计查询失败')
  } finally {
    loading.value = false
  }
}

async function loadRecords() {
  try {
    records.value = await getOperationRecords()
  } catch (e) {
    error.value = errorMessage(e, '运行记录加载失败')
  }
}

function previousPage() {
  if (page.value === 0) {
    return
  }
  page.value -= 1
  void search(false)
}

function nextPage() {
  if (!result.value.hasMore) {
    return
  }
  page.value += 1
  void search(false)
}

function changeSize() {
  void search()
}

function resetFilters() {
  filters.actor = ''
  filters.category = ''
  filters.action = ''
  filters.result = ''
  filters.from = ''
  filters.to = ''
  void search()
}

function formatTime(value: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 19) : '—'
}

onMounted(() => {
  void search()
  void loadRecords()
})
</script>

<template>
  <AppShell area="admin">
    <section class="page">
      <h1>系统审计</h1>
      <p class="hint">
        登录成功/失败与关键写操作审计在此只读查询，默认最近 7 天、最长 31 天；详情中的 password、鉴权码、token 与 WS ticket 已脱敏。
      </p>

      <form
        class="filter-form"
        data-testid="audit-filter-form"
        @submit.prevent="search()"
      >
        <label>
          操作者
          <input
            v-model="filters.actor"
            data-testid="audit-actor"
            maxlength="64"
            placeholder="用户名"
          >
        </label>
        <label>
          分类
          <select
            v-model="filters.category"
            data-testid="audit-category"
          >
            <option value="">全部</option>
            <option
              v-for="category in categories"
              :key="category"
              :value="category"
            >
              {{ category }}
            </option>
          </select>
        </label>
        <label>
          动作
          <input
            v-model="filters.action"
            data-testid="audit-action"
            maxlength="64"
            placeholder="如 LOGIN、CREATE"
          >
        </label>
        <label>
          结果
          <select
            v-model="filters.result"
            data-testid="audit-result"
          >
            <option value="">全部</option>
            <option value="SUCCESS">SUCCESS</option>
            <option value="FAILURE">FAILURE</option>
          </select>
        </label>
        <label>
          开始时间
          <input
            v-model="filters.from"
            data-testid="audit-from"
            type="datetime-local"
          >
        </label>
        <label>
          结束时间
          <input
            v-model="filters.to"
            data-testid="audit-to"
            type="datetime-local"
          >
        </label>
        <div class="filter-actions">
          <button
            type="submit"
            data-testid="audit-search"
            :disabled="loading"
          >
            {{ loading ? '查询中…' : '查询' }}
          </button>
          <button
            type="button"
            class="secondary"
            data-testid="audit-reset"
            @click="resetFilters"
          >
            重置
          </button>
        </div>
      </form>

      <p
        v-if="error"
        class="error"
        role="alert"
        data-testid="audit-error"
      >
        {{ error }}
      </p>

      <div class="summary">
        <span data-testid="audit-total">共 {{ result.total }} 条</span>
        <span>第 {{ result.page + 1 }} 页</span>
        <label class="size">
          每页
          <select
            v-model.number="size"
            data-testid="audit-size"
            @change="changeSize"
          >
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
          </select>
        </label>
      </div>

      <table
        class="table"
        data-testid="audit-table"
      >
        <thead>
          <tr>
            <th>时间</th>
            <th>操作者</th>
            <th>分类</th>
            <th>动作</th>
            <th>对象</th>
            <th>结果</th>
            <th>来源 IP</th>
            <th>详情</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="entry in result.items"
            :key="entry.id"
            :data-testid="`audit-row-${entry.id}`"
          >
            <td>{{ formatTime(entry.createdAt) }}</td>
            <td>{{ entry.actorName ?? '—' }}</td>
            <td>{{ entry.category }}</td>
            <td>{{ entry.action }}</td>
            <td>{{ entry.target ?? '—' }}</td>
            <td :class="entry.result === 'SUCCESS' ? 'success' : 'failure'">
              {{ entry.result }}
            </td>
            <td>{{ entry.sourceIp ?? '—' }}</td>
            <td class="detail">
              {{ entry.detail ?? '—' }}
            </td>
          </tr>
          <tr v-if="result.items.length === 0">
            <td
              colspan="8"
              class="empty"
            >
              当前筛选条件下没有审计记录
            </td>
          </tr>
        </tbody>
      </table>

      <div class="pager">
        <button
          type="button"
          class="secondary"
          data-testid="audit-prev"
          :disabled="result.page === 0 || loading"
          @click="previousPage"
        >
          上一页
        </button>
        <button
          type="button"
          class="secondary"
          data-testid="audit-next"
          :disabled="!result.hasMore || loading"
          @click="nextPage"
        >
          下一页
        </button>
      </div>

      <h2>运行记录</h2>
      <p class="hint">
        运行记录直接来自当前已实现的源事实：notification_outbox 各状态计数与 DEAD 告警，以及 track_partition_alert 中的轨迹分区维护失败告警。
      </p>
      <p
        v-if="records"
        data-testid="audit-outbox-counts"
      >
        <span
          v-for="(count, status) in records.outboxCounts"
          :key="status"
          class="count"
        >{{ status }} {{ count }}</span>
        <span class="count">生成于 {{ formatTime(records.generatedAt) }}</span>
      </p>
      <table
        class="table"
        data-testid="audit-dead-table"
      >
        <thead>
          <tr>
            <th>事件</th>
            <th>类型</th>
            <th>聚合</th>
            <th>尝试次数</th>
            <th>最近更新</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="entry in records?.deadOutbox ?? []"
            :key="entry.eventId"
            :data-testid="`audit-dead-${entry.eventId}`"
          >
            <td>{{ entry.eventId }}</td>
            <td>{{ entry.eventType }}</td>
            <td>{{ entry.aggregateId }}</td>
            <td>{{ entry.attempts }}</td>
            <td>{{ formatTime(entry.updatedAt) }}</td>
          </tr>
          <tr v-if="(records?.deadOutbox.length ?? 0) === 0">
            <td
              colspan="5"
              class="empty"
            >
              当前没有 DEAD 状态的 outbox 事件
            </td>
          </tr>
        </tbody>
      </table>

      <h3>分区维护告警</h3>
      <table
        class="table"
        data-testid="audit-partition-alert-table"
      >
        <thead>
          <tr>
            <th>时间</th>
            <th>类型</th>
            <th>消息</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(alert, index) in records?.partitionAlerts ?? []"
            :key="`${alert.alertType}-${alert.createdAt}-${index}`"
            :data-testid="`audit-partition-alert-${index}`"
          >
            <td>{{ formatTime(alert.createdAt) }}</td>
            <td>{{ alert.alertType }}</td>
            <td class="detail">
              {{ alert.message }}
            </td>
          </tr>
          <tr v-if="(records?.partitionAlerts.length ?? 0) === 0">
            <td
              colspan="3"
              class="empty"
            >
              当前没有轨迹分区维护告警
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

h2 {
  margin: 1.75rem 0 0.5rem;
  font-size: 1.05rem;
}

h3 {
  margin: 1.5rem 0 0.5rem;
  font-size: 0.95rem;
}

.hint {
  margin: 0 0 1rem;
  color: #5b6b7c;
  font-size: 0.875rem;
}

.filter-form {
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

.filter-form > label {
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

.filter-actions {
  display: flex;
  gap: 0.5rem;
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
  opacity: 0.6;
  cursor: default;
}

button.secondary {
  background: #fff;
  color: #2f6fb3;
  border-color: #c7d0d9;
}

.error {
  margin: 0 0 0.75rem;
  color: #b42318;
}

.summary {
  display: flex;
  align-items: center;
  gap: 1rem;
  margin-bottom: 0.6rem;
  font-size: 0.85rem;
  color: #44576a;
}

.size {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
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

.detail {
  max-width: 22rem;
  word-break: break-all;
  color: #44576a;
}

.success {
  color: #1f7a3f;
}

.failure {
  color: #b42318;
}

.empty {
  color: #7a8896;
  text-align: center;
}

.pager {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.75rem;
}

.count {
  display: inline-block;
  margin-right: 0.9rem;
}
</style>
