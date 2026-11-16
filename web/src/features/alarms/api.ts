import { http } from '../../shared/http'

export type ConditionState = 'ACTIVE' | 'RECOVERED' | 'EVENT'
export type HandleStatus = 'PENDING' | 'PROCESSING' | 'HANDLED' | 'IGNORED'
export type HandleTarget = 'PROCESSING' | 'HANDLED' | 'IGNORED'

export interface AlarmRow {
  id: number
  vehicleId: number
  plateNo: string
  fleetId: number
  fleetName: string
  source: string
  alarmType: string
  conditionState: ConditionState
  handleStatus: HandleStatus
  driverId: number | null
  ruleVersion: number | null
  startTime: string
  lastTime: string
  recoveredAt: string | null
}

export interface AlarmDriver {
  id: number
  name: string
  phone: string
}

export interface AlarmPosition {
  lng: number
  lat: number
  speedKph: number | null
  gpsTime: string
}

export interface AlarmHandleLog {
  id: number
  changeType: 'CONDITION' | 'HANDLE'
  fromStatus: string
  toStatus: string
  operatorId: number | null
  operatorName: string | null
  remark: string | null
  createdAt: string
}

export interface AlarmDetail extends AlarmRow {
  driver: AlarmDriver | null
  ruleId: number | null
  ruleSnapshot: Record<string, string | number | null> | null
  position: AlarmPosition | null
  handleHistory: AlarmHandleLog[]
  conditionHistory: AlarmHandleLog[]
  createdAt: string
  updatedAt: string
}

export interface HandleResult {
  alarmId: number
  conditionState: ConditionState
  handleStatus: HandleStatus
  logId: number
  handledAt: string
}

export async function listAlarms(fleetId?: number): Promise<AlarmRow[]> {
  const { data } = await http.get<AlarmRow[]>('/alarms', { params: fleetId ? { fleetId } : {} })
  return data
}

export async function getAlarm(alarmId: number): Promise<AlarmDetail> {
  const { data } = await http.get<AlarmDetail>(`/alarms/${alarmId}`)
  return data
}

export async function handleAlarm(alarmId: number, targetStatus: HandleTarget,
  remark?: string): Promise<HandleResult> {
  const { data } = await http.post<HandleResult>(`/alarms/${alarmId}/handle`, { targetStatus, remark })
  return data
}
