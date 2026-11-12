import { http } from '../../shared/http'
import type { EnabledStatus } from '../../shared/status'

export type CommunicationStatus = 'UNBOUND' | 'NOT_CONNECTED' | 'ONLINE' | 'OFFLINE' | 'DISABLED'

export interface MonitorPosition {
  version: number
  terminalId: number
  gpsTime: string
  receivedAt: string
  lng: number
  lat: number
  speedKph: number
  directionDeg: number
  accOn: boolean
  fixValid: boolean
}

export interface MonitorVehicle {
  vehicleId: number
  plateNo: string
  fleetId: number
  fleetName: string
  status: EnabledStatus
  terminalId: number | null
  terminalNo: string | null
  lastSeenAt: string | null
  communicationStatus: CommunicationStatus
  position: MonitorPosition | null
}

export interface MonitorSnapshot {
  serverTime: string
  vehicles: MonitorVehicle[]
}

export async function getSnapshot(signal: AbortSignal): Promise<MonitorSnapshot> {
  return (await http.get<MonitorSnapshot>('/monitor/snapshot', { signal })).data
}

export async function getWsTicket(signal: AbortSignal): Promise<string> {
  const { data } = await http.post<{ ticket: string; expiresAt: string }>('/auth/ws-ticket', undefined, { signal })
  return data.ticket
}

export const COMMUNICATION_LABELS: Record<CommunicationStatus, string> = {
  UNBOUND: '未绑定终端',
  NOT_CONNECTED: '未接入',
  ONLINE: '在线',
  OFFLINE: '离线',
  DISABLED: '已停用',
}

const timeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

export function formatTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isFinite(date.getTime()) ? timeFormatter.format(date) : '—'
}

// 当前实时点容差为 60 秒；在线只说明通信存在，并不表示位置仍新鲜。
export function isStale(position: MonitorPosition | null, now: number): boolean {
  return position !== null && now - Date.parse(position.gpsTime) > 60_000
}
