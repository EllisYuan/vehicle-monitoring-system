import { http } from '../../shared/http'
import type { EnabledStatus } from '../../shared/status'

export interface Vehicle {
  id: number
  plateNo: string
  fleetId: number
  fleetName: string
  status: EnabledStatus
  terminalId: number | null
  terminalNo: string | null
}

export type TerminalCommunicationStatus = 'ONLINE' | 'OFFLINE' | 'NOT_CONNECTED'

export interface Terminal {
  id: number
  terminalNo: string
  status: EnabledStatus
  vehicleId: number | null
  plateNo: string | null
  fleetId: number | null
  lastSeenAt: string | null
  everAuthenticated: boolean
  communicationStatus: TerminalCommunicationStatus
}

export interface Binding {
  id: number
  terminalId: number
  terminalNo: string
  vehicleId: number
  effectiveFrom: string
  effectiveTo: string | null
}

export interface FleetHistory {
  id: number
  vehicleId: number
  fleetId: number
  fleetName: string
  effectiveFrom: string
  effectiveTo: string | null
}

export async function listVehicles(fleetId?: number): Promise<Vehicle[]> {
  return (await http.get<Vehicle[]>('/vehicles', { params: fleetId ? { fleetId } : undefined })).data
}

export async function createVehicle(plateNo: string, fleetId: number): Promise<Vehicle> {
  return (await http.post<Vehicle>('/vehicles', { plateNo, fleetId })).data
}

export async function updateVehicle(id: number, patch: { plateNo?: string; status?: EnabledStatus }): Promise<Vehicle> {
  return (await http.put<Vehicle>(`/vehicles/${id}`, patch)).data
}

export async function transferVehicle(id: number, fleetId: number): Promise<Vehicle> {
  return (await http.put<Vehicle>(`/vehicles/${id}/fleet`, { fleetId })).data
}

export async function bindTerminal(id: number, terminalId: number | null): Promise<Vehicle> {
  return (await http.put<Vehicle>(`/vehicles/${id}/terminal`, { terminalId })).data
}

export async function listTerminals(): Promise<Terminal[]> {
  return (await http.get<Terminal[]>('/terminals')).data
}

export async function registerTerminal(terminalNo: string): Promise<Terminal> {
  return (await http.post<Terminal>('/terminals', { terminalNo })).data
}

export async function updateTerminal(id: number, status: EnabledStatus): Promise<Terminal> {
  return (await http.put<Terminal>(`/terminals/${id}`, { status })).data
}

export async function bindingHistory(id: number): Promise<Binding[]> {
  return (await http.get<Binding[]>(`/vehicles/${id}/terminal-bindings`)).data
}

export async function fleetHistory(id: number): Promise<FleetHistory[]> {
  return (await http.get<FleetHistory[]>(`/vehicles/${id}/fleet-history`)).data
}
