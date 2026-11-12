import { computed, onMounted, onUnmounted, ref } from 'vue'
import { errorMessage, isUnauthorized } from '../../shared/http'
import { getSnapshot, getWsTicket, type MonitorVehicle } from './api'

type StreamState = 'connecting' | 'connected' | 'reconnecting' | 'stopped'
interface PositionFrame {
  type: 'vehicle_position'
  aggregate_id: number
  version: number
  server_time: string
  payload: MonitorVehicle
}

const retryDelays = [1000, 2000, 4000, 8000, 15_000]

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
    reconnecting: `实时连接中断 · 正在重连 ${retryCount.value}/${retryDelays.length}`,
    stopped: '实时连接已停止 · 请手动重试',
  })[state.value])

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
      if (disposed || current !== generation || request.signal.aborted) return
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
      if (!disposed && !request.signal.aborted && current === generation && !isUnauthorized(error)) {
        failure.value = errorMessage(error, '车辆快照加载失败，请检查连接后重试。')
      }
    } finally {
      if (!disposed && current === generation) loading.value = false
    }
  }

  function closeSocket() {
    clearTimeout(handshakeTimer)
    if (!socket) return
    socket.onopen = null
    socket.onmessage = null
    socket.onerror = null
    socket.onclose = null
    socket.close()
    socket = null
  }

  function reconnect(current: number) {
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
    state.value = 'reconnecting'
    retryTimer = setTimeout(() => { void connect() }, delay)
  }

  async function connect() {
    const current = ++generation
    controller?.abort()
    snapshotController?.abort()
    closeSocket()
    const request = new AbortController()
    controller = request
    // REST 独立于 WS 可用性；握手后再次校正，以补足初次查询与订阅间隙。
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
        closeSocket()
        reconnect(current)
      }, 10_000)
      connection.onopen = () => {
        if (disposed || current !== generation) return
        clearTimeout(handshakeTimer)
        openedAt = Date.now()
        state.value = 'connected'
        // 先订阅成功，再取快照，填补查询与握手之间的变化。
        void syncSnapshot(current)
      }
      connection.onmessage = event => {
        if (disposed || current !== generation) return
        let frame: PositionFrame
        try { frame = JSON.parse(String(event.data)) as PositionFrame } catch { return }
        if (frame.type !== 'vehicle_position' || !frame.payload?.position
          || frame.aggregate_id !== frame.payload.vehicleId
          || !Number.isSafeInteger(frame.version) || frame.version < 1
          || frame.payload.position.version !== frame.version) return
        const index = vehicles.value.findIndex(vehicle => vehicle.vehicleId === frame.aggregate_id)
        const previous = vehicles.value[index]
        if (previous?.position && previous.position.version >= frame.version) return
        if (index < 0) vehicles.value.push(frame.payload)
        else vehicles.value[index] = frame.payload
        updateClock(frame.server_time)
      }
      connection.onerror = () => connection.close()
      connection.onclose = () => reconnect(current)
    } catch (error) {
      if (disposed || request.signal.aborted || current !== generation) return
      if (isUnauthorized(error)) return
      failure.value = errorMessage(error, '无法建立实时连接；恢复连接后将重新获取快照。')
      reconnect(current)
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

  return { vehicles, loading, failure, state, statusLabel, lastSyncAt, now, retry }
}
