import { http } from '../../shared/http'
import type { EnabledStatus } from '../../shared/status'

export interface Fleet {
  id: number
  fleetCode: string
  fleetName: string
  status: EnabledStatus
}

export async function listFleets(fleetId?: number): Promise<Fleet[]> {
  const { data } = await http.get<Fleet[]>('/fleets', { params: fleetId ? { fleetId } : undefined })
  return data
}

export async function createFleet(fleetCode: string, fleetName: string): Promise<Fleet> {
  const { data } = await http.post<Fleet>('/fleets', { fleetCode, fleetName })
  return data
}

export async function updateFleet(id: number, patch: { fleetName?: string; status?: EnabledStatus }): Promise<Fleet> {
  const { data } = await http.put<Fleet>(`/fleets/${id}`, patch)
  return data
}
