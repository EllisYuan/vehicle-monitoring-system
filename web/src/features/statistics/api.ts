import { http } from '../../shared/http'

export interface VehicleMetrics {
  total: number
  online: number
  offline: number
  notConnected: number
  disabled: number
  activeDisconnected: number
  startupPending: number
  unbound: number
}

export interface HandlingRate {
  numerator: number
  denominator: number
  percent: number | null
  note: string
}

export interface AlarmMetrics {
  total: number
  pending: number
  processing: number
  handled: number
  ignored: number
  handlingRate: HandlingRate
}

export interface StatisticsOverview {
  timezone: string
  today: string
  startTimeFrom: string
  startTimeTo: string
  vehicles: VehicleMetrics
  alarms: AlarmMetrics
}

export interface TrendPoint {
  date: string
  alarms: AlarmMetrics
}

export interface StatisticsTrend {
  timezone: string
  startDate: string
  endDate: string
  days: TrendPoint[]
}

export interface TypeStat {
  alarmType: string
  alarms: AlarmMetrics
}

export interface StatisticsTypes {
  timezone: string
  startDate: string
  endDate: string
  total: number
  types: TypeStat[]
}

export interface FleetStat {
  fleetId: number
  fleetCode: string
  fleetName: string
  status: string
  vehicles: VehicleMetrics
  alarms: AlarmMetrics
}

export interface StatisticsFleets {
  timezone: string
  startDate: string
  endDate: string
  totalVehicles: VehicleMetrics
  totalAlarms: AlarmMetrics
  fleets: FleetStat[]
}

export interface RangeQuery {
  startDate: string
  endDate: string
}

export async function getOverview(fleetId?: number): Promise<StatisticsOverview> {
  const { data } = await http.get<StatisticsOverview>('/statistics/overview', {
    params: fleetId ? { fleetId } : {},
  })
  return data
}

export async function getTrend(query: RangeQuery, fleetId?: number): Promise<StatisticsTrend> {
  const { data } = await http.get<StatisticsTrend>('/statistics/trend', {
    params: { ...query, ...(fleetId ? { fleetId } : {}) },
  })
  return data
}

export async function getTypes(query: RangeQuery, fleetId?: number): Promise<StatisticsTypes> {
  const { data } = await http.get<StatisticsTypes>('/statistics/types', {
    params: { ...query, ...(fleetId ? { fleetId } : {}) },
  })
  return data
}

export async function getFleets(query: RangeQuery, fleetId?: number): Promise<StatisticsFleets> {
  const { data } = await http.get<StatisticsFleets>('/statistics/fleets', {
    params: { ...query, ...(fleetId ? { fleetId } : {}) },
  })
  return data
}

export function formatRate(rate: HandlingRate): string {
  return rate.percent === null ? '—' : `${rate.percent.toFixed(1)}%`
}
