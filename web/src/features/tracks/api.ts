import { http } from '../../shared/http'

export interface TrackPoint {
  gpsTime: string
  lng: number
  lat: number
  speedKph: number
  directionDeg: number
  eventRelated: boolean
}

export interface TrackSegment {
  startTime: string
  endTime: string
  points: TrackPoint[]
}

export type TrackGapReason = 'INVALID_FIX' | 'TIME_GAP'

export interface TrackGap {
  startTime: string
  endTime: string
  reason: TrackGapReason
}

export interface TrackView {
  vehicleId: number
  plateNo: string
  fleetId: number
  from: string
  to: string
  rawPointCount: number
  invalidPointCount: number
  displayPointCount: number
  segments: TrackSegment[]
  gaps: TrackGap[]
}

export const GAP_LABELS: Record<TrackGapReason, string> = {
  INVALID_FIX: '定位无效',
  TIME_GAP: '超过 30 秒无有效定位',
}

export async function getTrack(vehicleId: number, from: string, to: string): Promise<TrackView> {
  return (await http.get<TrackView>('/tracks', { params: { vehicleId, from, to } })).data
}
