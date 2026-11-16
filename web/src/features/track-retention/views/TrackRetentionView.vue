<script setup lang="ts">
import { onMounted, ref } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorMessage } from '../../../shared/http'
import {
  confirmTrackRetention,
  getTrackRetentionOverview,
  previewTrackRetention,
  type TrackRetentionConfirm,
  type TrackRetentionOverview,
  type TrackRetentionPreview,
} from '../api'

const overview = ref<TrackRetentionOverview | null>(null)
const proposedDays = ref(30)
const preview = ref<TrackRetentionPreview | null>(null)
const confirmed = ref<TrackRetentionConfirm | null>(null)
const loading = ref(false)
const error = ref('')
const notice = ref('')

async function reload() {
  loading.value = true
  error.value = ''
  try {
    overview.value = await getTrackRetentionOverview()
    proposedDays.value = overview.value.retentionDays
  } catch (e) {
    error.value = errorMessage(e, '轨迹保留信息加载失败')
  } finally {
    loading.value = false
  }
}

async function previewChange() {
  error.value = ''
  notice.value = ''
  try {
    preview.value = await previewTrackRetention(proposedDays.value)
    confirmed.value = null
  } catch (e) {
    error.value = errorMessage(e, '预览失败')
  }
}

async function confirmChange() {
  if (!preview.value) {
    return
  }
  error.value = ''
  notice.value = ''
  try {
    confirmed.value = await confirmTrackRetention(preview.value.token)
    notice.value = '保留期已生效，过期分区已按预览范围清理'
    preview.value = null
    await reload()
  } catch (e) {
    error.value = errorMessage(e, '确认失败')
  }
}

onMounted(reload)
</script>

<template>
  <AppShell area="admin">
    <section class="page">
      <h1>轨迹保留与分区维护</h1>
      <p class="hint">
        默认保留上海时区的今天及此前 29 天；启动与每日巡检会补齐保留范围分区、预建未来 7 天分区并清理过期分区。
        缩短保留期必须先生成预览并由管理员确认，确认前不会删除任何分区。
      </p>

      <p v-if="error" class="error" data-testid="retention-error">{{ error }}</p>
      <p v-if="notice" class="notice" data-testid="retention-notice">{{ notice }}</p>

      <section class="card">
        <h2>当前保留配置</h2>
        <p v-if="loading" class="hint">加载中…</p>
        <dl v-else-if="overview" class="grid">
          <dt>保留天数</dt><dd data-testid="retention-days">{{ overview.retentionDays }}</dd>
          <dt>配置版本</dt><dd>{{ overview.configVersion }}</dd>
          <dt>业务今天</dt><dd>{{ overview.today }}</dd>
          <dt>预建至</dt><dd>{{ overview.prebuildThrough }}</dd>
        </dl>
      </section>

      <section class="card">
        <h2>缩短保留期（预览后确认）</h2>
        <div class="controls">
          <label>
            目标保留天数
            <input
              v-model.number="proposedDays"
              type="number"
              min="1"
              max="365"
              data-testid="proposed-retention-days"
            >
          </label>
          <button type="button" data-testid="preview-retention" @click="previewChange">生成预览</button>
        </div>
        <div v-if="preview" class="preview" data-testid="retention-preview">
          <p>
            将从 <strong>{{ preview.currentDays }}</strong> 天缩短为
            <strong>{{ preview.proposedDays }}</strong> 天；早于
            <strong>{{ preview.cutoffDate }}</strong> 的分区将被删除。
          </p>
          <p>预计影响行数：{{ preview.estimatedRows }}</p>
          <p>待删除分区：{{ preview.partitions.join(', ') || '无' }}</p>
          <button type="button" class="danger" data-testid="confirm-retention" @click="confirmChange">确认生效</button>
        </div>
        <p v-if="confirmed" class="notice" data-testid="retention-confirmed">
          已从 {{ confirmed.previousDays }} 天缩短为 {{ confirmed.retentionDays }} 天；已删除分区：{{ confirmed.droppedPartitions.join(', ') || '无' }}
        </p>
      </section>

      <section class="card">
        <h2>维护失败告警</h2>
        <p v-if="!overview || overview.alerts.length === 0" class="hint" data-testid="retention-alerts-empty">
          暂无告警
        </p>
        <ul v-else data-testid="retention-alerts">
          <li v-for="alert in overview.alerts" :key="alert">{{ alert }}</li>
        </ul>
      </section>

      <section class="card">
        <h2>最近维护记录</h2>
        <p v-if="!overview || overview.maintenanceRuns.length === 0" class="hint" data-testid="retention-runs-empty">
          暂无记录
        </p>
        <ul v-else data-testid="retention-runs">
          <li v-for="run in overview.maintenanceRuns" :key="run">{{ run }}</li>
        </ul>
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
}
.controls label {
  display: grid;
  gap: 0.25rem;
  font-size: 0.875rem;
  color: #33414f;
}
.controls input {
  width: 8rem;
  padding: 0.45rem 0.6rem;
  border: 1px solid #cfd8e3;
  border-radius: 6px;
}
.preview {
  margin-top: 1rem;
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
ul {
  margin: 0;
  padding-left: 1.25rem;
}
</style>
