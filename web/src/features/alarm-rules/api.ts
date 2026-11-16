import { http } from '../../shared/http'

export interface AlarmRule {
  id: number
  scopeType: 'GLOBAL' | 'FLEET'
  fleetId: number | null
  fleetName: string | null
  alarmType: string
  thresholdKph: number
  enabled: boolean
  version: number
  updatedAt: string
}

export interface AlarmRuleOverview {
  global: AlarmRule | null
  fleetOverrides: AlarmRule[]
}

export async function getAlarmRuleOverview(): Promise<AlarmRuleOverview> {
  const { data } = await http.get<AlarmRuleOverview>('/admin/alarm-rules')
  return data
}

export async function updateGlobalThreshold(thresholdKph: number): Promise<AlarmRule> {
  const { data } = await http.put<AlarmRule>('/admin/alarm-rules/global', { thresholdKph })
  return data
}

export async function upsertFleetOverride(fleetId: number, thresholdKph: number): Promise<AlarmRule> {
  const { data } = await http.put<AlarmRule>(`/admin/alarm-rules/fleets/${fleetId}`, { thresholdKph })
  return data
}

export async function deleteFleetOverride(fleetId: number): Promise<void> {
  await http.delete(`/admin/alarm-rules/fleets/${fleetId}`)
}
