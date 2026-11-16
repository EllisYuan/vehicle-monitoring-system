import { http } from '../../shared/http'

export interface TrackRetentionOverview {
  retentionDays: number
  configVersion: number
  today: string
  prebuildThrough: string
  maintenanceRuns: string[]
  alerts: string[]
}

export interface TrackRetentionPreview {
  token: string
  currentDays: number
  proposedDays: number
  cutoffDate: string
  partitions: string[]
  estimatedRows: number
  expiresAt: string
}

export interface TrackRetentionConfirm {
  previousDays: number
  retentionDays: number
  cutoffDate: string
  droppedPartitions: string[]
  droppedRowsEstimate: number
  success: boolean
  message: string | null
}

export async function getTrackRetentionOverview(): Promise<TrackRetentionOverview> {
  const { data } = await http.get<TrackRetentionOverview>('/admin/track-retention')
  return data
}

export async function previewTrackRetention(retentionDays: number): Promise<TrackRetentionPreview> {
  const { data } = await http.post<TrackRetentionPreview>('/admin/track-retention/preview', {
    retentionDays,
  })
  return data
}

export async function confirmTrackRetention(token: string): Promise<TrackRetentionConfirm> {
  const { data } = await http.post<TrackRetentionConfirm>('/admin/track-retention/confirm', { token })
  return data
}
