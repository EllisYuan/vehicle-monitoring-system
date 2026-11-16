import { computed, onMounted, onUnmounted, ref } from 'vue'
import { errorMessage, isUnauthorized } from '../../shared/http'
import { getSnapshot, getWsTicket, type MonitorVehicle } from './api'

type StreamState = 'connecting' | 'connected' | 'disconnected' | 'reconnecting' | 'stopped'
interface PositionFrame {
  type: 'vehicle_position'
  aggregate_id: number
  version: number
  server_time: string
  payload: MonitorVehicle
}

const retryDelays = [1000, 2000, 4000, 8000, 15_000]
const STALE_AFTER_MS = 60_000
const REVOKED_MESSAGE = '实时订阅已失效：账号状态、密码或车队授权已变化，请重新登录后重试。'

export function useMonitorStream() {
  const vehicles = ref<MonitorVehicle[]>([])
  const loading = ref(true)
  const failure = ref('')
  const state = ref<StreamState>('connecting')
  const retryCount = ref(0)
  const lastSyncAt = ref<string | null>(null)
  const now = ref(Date.now())
  let serverOffset = 0
  let disposed = false
  // 1008 撤权后禁止任何在途快照回填，直到用户手动重试建立新的订阅。
  let revoked = false
  let generation = 0
  let socket: WebSocket | null = null
  let controller: AbortController | null = null
  let snapshotController: AbortController | null = null
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  let handshakeTimer: ReturnType<typeof setTimeout> | undefined
  let clockTimer: ReturnType<typeof setInterval> | undefined
  let syncTimer: ReturnType<typeof setInterval> | undefined
  let openedAt = 0

  const statusLabel = computed(() => ({
    connecting: '正在建立实时连接',
    connected: '实时连接已建立',
    disconnected: '实时连接已断开',
    reconnecting: `正在重连 ${retryCount.value}/${retryDelays.length}`,
    stopped: '实时连接已停止 · 请手动重试',
  })[state.value])

  // 快照或推送超过 60 秒未更新时提示数据陈旧；车辆行另有逐车定位陈旧标记。
  const stale = computed(() => {
    if (!lastSyncAt.value) return false
    const synced = Date.parse(lastSyncAt.value)
    return Number.isFinite(synced) && now.value - synced > STALE_AFTER_MS
  })

  function updateClock(value: string) {
    const time = Date.parse(value)
    if (Number.isFinite(time)) serverOffset = time - Date.now()
    now.value = Date.now() + serverOffset
  }

  async function syncSnapshot(current: number) {
    snapshotController?.abort()
    const request = new AbortController()
    snapshotController = request
    try {
      const snapshot = await getSnapshot(request.signal)
      // 撤权后仍在途的快照必须丢弃，不能恢复已清空的车辆列表或清除失效提示。
      if (disposed || revoked || current !== generation || request.signal.aborted) return
      const previous = new Map(vehicles.value.map(vehicle => [vehicle.vehicleId, vehicle]))
      // 列表归属以新快照为准；请求期间到达的较新 WS 位置不会被旧快照覆盖。
      vehicles.value = snapshot.vehicles.map(vehicle => {
        const old = previous.get(vehicle.vehicleId)
        return old?.position && old.position.version > (vehicle.position?.version ?? -1)
          && old.terminalId === vehicle.terminalId
          ? { ...vehicle, position: old.position } : vehicle
      })
      updateClock(snapshot.serverTime)
      lastSyncAt.value = snapshot.serverTime
      failure.value = ''
    } catch (error) {
      if (!disposed && !revoked && !request.signal.aborted && current === generation
        && !isUnauthorized(error)) {
        failure.value = errorMessage(error, '车辆快照加载失败，请检查连接后重试。')
      }
    } finally {
      if (!disposed && !revoked && current === generation) loading.value = false
    }
  }

  function closeSocket() {
    clearTimeout(handshakeTimer)
    if (!socket) return
    const closing = socket
    socket = null
    closing.onopen = null
    closing.onmessage = null
    closing.onerror = null
    closing.onclose = null
    closing.close()
  }

  function scheduleReconnect(current: number) {
    if (disposed || current !== generation) return
    clearTimeout(handshakeTimer)
    if (openedAt && Date.now() - openedAt >= 30_000) retryCount.value = 0
    openedAt = 0
    if (retryCount.value >= retryDelays.length) {
      state.value = 'stopped'
      loading.value = false
      return
    }
    const delay = retryDelays[retryCount.value]!
    retryCount.value += 1
    // 连接断开与等待重连分开呈现，页面能明确显示“断线”而不是一直“重连中”。
    state.value = 'disconnected'
    clearTimeout(retryTimer)
    retryTimer = setTimeout(() => {
      if (disposed || current !== generation) return
      state.value = 'reconnecting'
      void connect()
    }, delay)
  }

  async function connect() {
    const current = ++generation
    revoked = false
    controller?.abort()
    snapshotController?.abort()
    closeSocket()
    const request = new AbortController()
    controller = request
    // REST 独立于 WS 可用性；握手后再次校正，以补足查询与订阅间隙。
    void syncSnapshot(current)
    try {
      const ticket = await getWsTicket(request.signal)
      if (disposed || request.signal.aborted || current !== generation) return
      const url = new URL('/ws/monitor', window.location.href)
      url.protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      url.searchParams.set('ticket', ticket)
      const connection = new WebSocket(url)
      socket = connection
      // 握手不能永久挂起；每次重连使用新的一次性 ticket。
      handshakeTimer = setTimeout(() => {
        if (disposed || current !== generation || socket !== connection) return
        closeSocket()
        scheduleReconnect(current)
      }, 10_000)
      connection.onopen = () => {
        if (disposed || current !== generation || socket !== connection) return
        clearTimeout(handshakeTimer)
        openedAt = Date.now()
        state.value = 'connected'
        // 先订阅成功，再取快照，填补查询与握手之间的变化。
        void syncSnapshot(current)
      }
      connection.onmessage = event => {
        if (disposed || current !== generation || socket !== connection) return
        let frame: PositionFrame
        try { frame = JSON.parse(String(event.data)) as PositionFrame } catch { return }
        if (frame.type !== 'vehicle_position' || !frame.payload?.position
          || frame.aggregate_id !== frame.payload.vehicleId
          || !Number.isSafeInteger(frame.version) || frame.version < 1
          || frame.payload.position.version !== frame.version) return
        const index = vehicles.value.findIndex(vehicle => vehicle.vehicleId === frame.aggregate_id)
        const previous = vehicles.value[index]
        // 旧版本帧不得覆盖已显示的新版本；断线补发与重复推送由这里幂等消化。
        if (previous?.position && previous.position.version >= frame.version) return
        if (index < 0) vehicles.value.push(frame.payload)
        else vehicles.value[index] = frame.payload
        updateClock(frame.server_time)
      }
      connection.onerror = () => {
        if (disposed || current !== generation || socket !== connection) return
        closeSocket()
        scheduleReconnect(current)
      }
      connection.onclose = event => {
        if (disposed || current !== generation || socket !== connection) return
        socket = null
        clearTimeout(handshakeTimer)
        openedAt = 0
        if (event.code === 1008) {
          // 授权失效（停用、改密、新登录替换、撤权或到期）：停止旧订阅，不再自动重连。
          revoked = true
          snapshotController?.abort()
          snapshotController = null
          vehicles.value = []
          lastSyncAt.value = null
          loading.value = false
          state.value = 'stopped'
          failure.value = REVOKED_MESSAGE
          return
        }
        // 1011 等异常关闭：重建订阅，重连成功后重新获取快照。
        scheduleReconnect(current)
      }
    } catch (error) {
      if (disposed || request.signal.aborted || current !== generation) return
      if (isUnauthorized(error)) return
      failure.value = errorMessage(error, '无法建立实时连接；恢复连接后将重新获取快照。')
      scheduleReconnect(current)
    }
  }

  function retry() {
    clearTimeout(retryTimer)
    retryCount.value = 0
    state.value = 'connecting'
    loading.value = vehicles.value.length === 0
    void connect()
  }

  onMounted(() => {
    void connect()
    clockTimer = setInterval(() => { now.value = Date.now() + serverOffset }, 1000)
    // 通信状态可由 heartbeat/断线改变，定时读取同权限快照校正。
    syncTimer = setInterval(() => {
      if (state.value === 'connected') void syncSnapshot(generation)
    }, 30_000)
  })
  onUnmounted(() => {
    disposed = true
    generation += 1
    controller?.abort()
    snapshotController?.abort()
    clearTimeout(retryTimer)
    clearTimeout(handshakeTimer)
    clearInterval(clockTimer)
    clearInterval(syncTimer)
    closeSocket()
  })

  return { vehicles, loading, failure, state, statusLabel, lastSyncAt, now, stale, retry }
}