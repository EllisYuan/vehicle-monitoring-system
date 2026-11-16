<script setup lang="ts">
/* global setInterval, clearInterval */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import AppShell from '../../../app/layouts/AppShell.vue'
import { errorCode, errorMessage } from '../../../shared/http'
import { listTerminals, type Terminal } from '../../archives/api'
import { useSessionStore } from '../../identity/store/session'
import {
  COMMAND_STATUS_LABELS, COMMAND_TYPE_LABELS, FAILURE_LABELS, commandSummary,
  listCommands, retryCommand, submitText, submitTracking,
  type CommandRecord,
} from '../api'

const session = useSessionStore()
const terminals = ref<Terminal[]>([])
const commands = ref<CommandRecord[]>([])
const filter = ref<number | ''>('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const latest = ref<CommandRecord | null>(null)
const textForm = reactive({ terminalId: '' as number | '', text: '', textType: 1 })
const trackingForm = reactive({ terminalId: '' as number | '', intervalSeconds: 10, validSeconds: 300 })

const canDispatch = computed(() => session.role === 'ADMIN' || session.role === 'MONITOR')
const selectableTerminals = computed(() => terminals.value.filter(t => t.status === 'ENABLED'))
let timer: ReturnType<typeof setInterval> | null = null

function terminalLabel(terminal: Terminal) {
  const state = terminal.communicationStatus === 'ONLINE' ? '在线'
    : terminal.communicationStatus === 'OFFLINE' ? '离线' : '未接入'
  return `${terminal.terminalNo}${terminal.plateNo ? ` · ${terminal.plateNo}` : ''} · ${state}`
}

function time(value: string | null) { return value ? value.replace('T', ' ') : '—' }

function failureLabel(reason: string | null) {
  if (!reason) return '—'
  return FAILURE_LABELS[reason] ?? reason
}

async function load() {
  const [t, c] = await Promise.all([listTerminals(), listCommands(filter.value || undefined)])
  terminals.value = t
  commands.value = c
  // “最近一次提交”跟随轮询刷新通信结果，提交结果与通信结果始终分开呈现
  const current = latest.value
  if (current) {
    latest.value = c.find(item => item.id === current.id) ?? current
  }
}

async function refresh() {
  loading.value = true
  try {
    await load()
  } catch (e) {
    error.value = errorMessage(e, '指令记录加载失败')
  } finally {
    loading.value = false
  }
}

async function perform(action: () => Promise<CommandRecord>, message: string) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    latest.value = await action()
    notice.value = message
    await load()
  } catch (e) {
    const code = errorCode(e)
    error.value = code === 'COMMAND_TERMINAL_OFFLINE'
      ? '终端当前离线，指令未下发也不排队；终端接入后可重新提交。'
      : errorMessage(e, '指令下发失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

function submitTextCommand() {
  if (!textForm.terminalId) return
  return perform(
    () => submitText(Number(textForm.terminalId), textForm.text.trim(), textForm.textType),
    '文本指令已提交，等待通信结果',
  )
}

function submitTrackingCommand(intervalSeconds: number, validSeconds: number) {
  if (!trackingForm.terminalId) return
  return perform(
    () => submitTracking(Number(trackingForm.terminalId), intervalSeconds, validSeconds),
    '跟踪指令已提交，等待通信结果',
  )
}

async function retry(command: CommandRecord) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    latest.value = await retryCommand(command.id)
    notice.value = `已生成重试指令 #${latest.value.id}（原指令 #${command.id}）`
    await load()
  } catch (e) {
    error.value = errorMessage(e, '重试失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  await refresh()
  // 通信结果由终端应答决定；轻量轮询让提交结果与通信状态分开展示并保持更新
  timer = setInterval(() => { void refresh() }, 2000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <AppShell area="monitoring">
    <section class="commands">
      <header class="page-heading">
        <div>
          <p class="eyebrow">
            文本 · 临时跟踪
          </p>
          <h1>指令记录</h1>
        </div>
        <span class="access-note">{{ canDispatch ? '可下发指令' : '不可下发指令' }}</span>
      </header>
      <p class="hint">
        提交成功只代表平台已受理；下方“通信结果”由终端应答、超时或注销独立决定。超时不自动重发，需要时手工重试会生成新的指令记录。
      </p>
      <p
        v-if="error"
        role="alert"
        class="error"
        data-testid="commands-error"
      >
        {{ error }}
      </p>
      <p
        v-if="notice"
        role="status"
        class="notice"
        data-testid="commands-notice"
      >
        {{ notice }}
      </p>
      <div
        v-if="canDispatch"
        class="dispatch"
      >
        <form
          data-testid="commands-text-form"
          @submit.prevent="submitTextCommand"
        >
          <h2>下发文本</h2>
          <label>终端<select
            v-model="textForm.terminalId"
            required
            data-testid="commands-text-terminal"
          ><option
            value=""
            disabled
          >选择终端</option><option
            v-for="terminal in selectableTerminals"
            :key="terminal.id"
            :value="terminal.id"
          >{{ terminalLabel(terminal) }}</option></select></label>
          <label>类型<select
            v-model="textForm.textType"
            data-testid="commands-text-type"
          ><option :value="1">通知</option><option :value="2">服务</option></select></label>
          <label class="wide">文本内容<textarea
            v-model="textForm.text"
            data-testid="commands-text-input"
            required
            maxlength="100"
            rows="3"
            placeholder="最多 100 个汉字（GBK 200 字节）"
          /></label>
          <button
            :disabled="busy || loading || !textForm.terminalId || !textForm.text.trim()"
            data-testid="commands-text-submit"
          >
            提交文本指令
          </button>
        </form>
        <form
          data-testid="commands-tracking-form"
          @submit.prevent="submitTrackingCommand(trackingForm.intervalSeconds, trackingForm.validSeconds)"
        >
          <h2>临时跟踪</h2>
          <label>终端<select
            v-model="trackingForm.terminalId"
            required
            data-testid="commands-tracking-terminal"
          ><option
            value=""
            disabled
          >选择终端</option><option
            v-for="terminal in selectableTerminals"
            :key="terminal.id"
            :value="terminal.id"
          >{{ terminalLabel(terminal) }}</option></select></label>
          <label>上报间隔（秒）<input
            v-model.number="trackingForm.intervalSeconds"
            data-testid="commands-tracking-interval"
            type="number"
            min="1"
            max="60"
            required
          ></label>
          <label>有效期（秒）<input
            v-model.number="trackingForm.validSeconds"
            data-testid="commands-tracking-valid"
            type="number"
            min="0"
            max="3600"
            required
          ></label>
          <button
            :disabled="busy || loading || !trackingForm.terminalId"
            data-testid="commands-tracking-submit"
          >
            提交跟踪指令
          </button>
        </form>
      </div>
      <p
        v-else
        class="hint"
        data-testid="commands-no-permission"
      >
        当前角色只能查看指令记录，不能下发指令。
      </p>
      <section
        v-if="latest"
        class="latest"
        data-testid="commands-latest"
      >
        <h2>最近一次提交</h2>
        <dl>
          <div><dt>指令</dt><dd>#{{ latest.id }} {{ COMMAND_TYPE_LABELS[latest.commandType] }}</dd></div>
          <div>
            <dt>提交结果</dt><dd data-testid="commands-latest-submission">
              {{ latest.submission.status === 'SUBMITTED' ? '已提交' : latest.submission.status }} · {{ latest.submission.operatorName }} · {{ time(latest.submission.submittedAt) }}
            </dd>
          </div>
          <div>
            <dt>通信结果</dt><dd data-testid="commands-latest-communication">
              {{ COMMAND_STATUS_LABELS[latest.communication.status] }}<small v-if="latest.communication.timeoutAt">超时 {{ time(latest.communication.timeoutAt) }}</small>
            </dd>
          </div>
          <div>
            <dt>流水号 / epoch</dt><dd class="number">
              {{ latest.serialNo }} / {{ latest.connectionEpoch }}
            </dd>
          </div>
        </dl>
      </section>
      <div class="list-heading">
        <h2>指令列表</h2>
        <label>按终端筛选<select
          v-model="filter"
          data-testid="commands-filter"
          :disabled="busy"
          @change="refresh"
        ><option value="">全部可见终端</option><option
          v-for="terminal in terminals"
          :key="terminal.id"
          :value="terminal.id"
        >{{ terminal.terminalNo }}</option></select></label>
        <button
          type="button"
          :disabled="busy || loading"
          @click="refresh"
        >
          刷新
        </button>
      </div>
      <p
        v-if="loading && !commands.length"
        role="status"
      >
        正在加载指令记录…
      </p>
      <div class="table-wrap">
        <table data-testid="commands-table">
          <thead>
            <tr>
              <th>指令</th><th>终端 / 车辆</th><th>内容</th><th>提交结果</th><th>通信结果</th><th>时间</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="command in commands"
              :key="command.id"
              :data-testid="`command-row-${command.id}`"
            >
              <td>#{{ command.id }}<small>{{ COMMAND_TYPE_LABELS[command.commandType] }}</small><small class="number">serial {{ command.serialNo }} · epoch {{ command.connectionEpoch }}</small></td>
              <td class="number">
                {{ command.terminalId }}<small v-if="command.vehicleId">车辆 {{ command.vehicleId }}</small><small v-if="command.retryOfId">重试自 #{{ command.retryOfId }}</small>
              </td>
              <td>{{ commandSummary(command) }}<small v-if="command.commandType === 'TEXT' && command.textBytes">{{ command.textBytes }} 字节</small></td>
              <td data-testid="command-submission-status">
                已提交<small>{{ command.submission.operatorName }}</small><small>{{ time(command.submission.submittedAt) }}</small>
              </td>
              <td :data-testid="`command-communication-${command.id}`">
                {{ COMMAND_STATUS_LABELS[command.communication.status] }}<small v-if="command.communication.failureReason">{{ failureLabel(command.communication.failureReason) }}</small><small v-if="command.communication.replyResult !== null">应答结果 {{ command.communication.replyResult }}</small><small
                  v-if="command.communication.replyMessageId !== null"
                  class="number"
                >应答 0x{{ command.communication.replyMessageId.toString(16).padStart(4, '0') }}</small>
              </td>
              <td>发送 {{ time(command.communication.sendTime) }}<small>应答 {{ time(command.communication.replyTime) }}</small><small v-if="command.communication.timeoutAt">超时 {{ time(command.communication.timeoutAt) }}</small></td>
              <td>
                <button
                  v-if="command.communication.status === 'FAILED' || command.communication.status === 'TIMEOUT'"
                  type="button"
                  :disabled="busy"
                  :data-testid="`command-retry-${command.id}`"
                  @click="retry(command)"
                >
                  重试
                </button>
              </td>
            </tr>
            <tr v-if="!loading && !commands.length">
              <td
                colspan="7"
                class="empty"
              >
                暂无可见指令记录
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </AppShell>
</template>

<style scoped>
.commands { --ink: #1f2d3d; --line: #dbe3eb; --accent: #2f6fb3; padding: 1.5rem; color: var(--ink); }
.page-heading, .list-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 1rem; }
.page-heading { justify-content: space-between; margin-bottom: .75rem; }
.eyebrow { margin: 0 0 .3rem; color: #5b6b7c; font-size: .75rem; letter-spacing: .12em; }
h1 { margin: 0; font-size: 1.35rem; } h2 { font-size: 1rem; margin: 0 0 .8rem; }
.access-note { font-size: .8rem; border-left: 3px solid var(--accent); padding-left: .6rem; }
.hint { color: #5b6b7c; font-size: .85rem; line-height: 1.6; }
.dispatch { display: grid; grid-template-columns: 1.2fr 1fr; gap: 1rem; margin: 1.25rem 0; }
.dispatch > form, .latest { padding: 1.1rem; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
form { display: flex; align-items: flex-end; flex-wrap: wrap; gap: .75rem; } form h2 { flex-basis: 100%; margin-bottom: .1rem; }
label { display: grid; gap: .3rem; font-size: .85rem; } label.wide { flex-basis: 100%; }
input, select, textarea { min-height: 2.2rem; max-width: 100%; padding: .4rem .6rem; border: 1px solid #c7d0d9; border-radius: 5px; background: #fff; color: var(--ink); font: inherit; }
textarea { resize: vertical; }
button { padding: .45rem .75rem; border: 1px solid var(--accent); border-radius: 5px; background: var(--accent); color: #fff; cursor: pointer; }
button:disabled { opacity: .55; cursor: default; } button:focus-visible, input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 2px solid #2075bb; outline-offset: 3px; }
.list-heading { margin: 1.5rem 0 .75rem; } .list-heading h2 { margin: 0; margin-right: auto; }
.latest { margin: 1rem 0; } dl { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: .75rem 1.5rem; margin: 0; }
dt { font-size: .75rem; color: #5b6b7c; } dd { margin: .2rem 0 0; font-size: .9rem; }
.table-wrap { overflow-x: auto; } table { width: 100%; border-collapse: collapse; background: #fff; border: 1px solid var(--line); }
th, td { text-align: left; padding: .7rem .8rem; border-bottom: 1px solid #eef2f6; font-size: .88rem; vertical-align: top; } th { background: #f8fafc; font-weight: 600; }
td small { display: block; color: #5b6b7c; margin-top: .2rem; font-size: .75rem; }
.number { font-family: Consolas, monospace; font-variant-numeric: tabular-nums; }
td button { background: white; color: var(--accent); border-color: #c7d0d9; }
.empty { text-align: center; color: #6b7a88; padding: 1.5rem; }
.error { color: #b42318; padding: .7rem; background: #fff0ed; border-left: 3px solid #b42318; }
.notice { color: #1f7a3f; } @media (max-width: 900px) { .dispatch { grid-template-columns: 1fr; } .commands { padding: 1rem; } }
</style>
