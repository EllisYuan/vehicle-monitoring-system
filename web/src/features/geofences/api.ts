import { http } from '../../shared/http'
import type { DraftGeometry, Vertex } from './geometry'

export type GeofenceType = 'CIRCLE' | 'POLYGON'
export type GeofenceStatus = 'ENABLED' | 'DISABLED'

export interface GeofenceVehicle {
  id: number
  plateNo: string
  fleetId: number
  fleetName: string
}

export interface Geofence {
  id: number
  fenceName: string
  fenceType: GeofenceType
  centerLng: number | null
  centerLat: number | null
  radiusM: number | null
  vertices: Vertex[] | null
  enterEnabled: boolean
  leaveEnabled: boolean
  status: GeofenceStatus
  version: number
  vehicles: GeofenceVehicle[]
  updatedAt: string
}

export interface GeofencePayload {
  fenceName: string
  fenceType: GeofenceType
  centerLng?: number
  centerLat?: number
  radiusM?: number
  vertices?: Vertex[]
  enterEnabled: boolean
  leaveEnabled: boolean
  vehicleIds: number[]
}

export function geometryPayload(geometry: DraftGeometry | null): Pick<GeofencePayload,
  'fenceType' | 'centerLng' | 'centerLat' | 'radiusM' | 'vertices'> | null {
  if (!geometry) {
    return null
  }
  return geometry.fenceType === 'CIRCLE'
    ? {
        fenceType: 'CIRCLE',
        centerLng: geometry.centerLng,
        centerLat: geometry.centerLat,
        radiusM: geometry.radiusM,
      }
    : { fenceType: 'POLYGON', vertices: geometry.vertices }
}

export async function listGeofences(fleetId?: number): Promise<Geofence[]> {
  return (await http.get<Geofence[]>('/geofences', { params: fleetId ? { fleetId } : undefined })).data
}

export async function createGeofence(payload: GeofencePayload): Promise<Geofence> {
  return (await http.post<Geofence>('/geofences', payload)).data
}

export async function updateGeofence(id: number, payload: GeofencePayload): Promise<Geofence> {
  return (await http.put<Geofence>(`/geofences/${id}`, payload)).data
}

export async function updateGeofenceStatus(id: number, status: GeofenceStatus): Promise<Geofence> {
  return (await http.put<Geofence>(`/geofences/${id}/status`, { status })).data
}

export async function updateGeofenceVehicles(id: number, vehicleIds: number[]): Promise<Geofence> {
  return (await http.put<Geofence>(`/geofences/${id}/vehicles`, { vehicleIds })).data
}
