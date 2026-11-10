import { http } from '../../shared/http'
import type { EnabledStatus } from '../../shared/status'

export interface Driver {
  id: number
  driverNo: string
  name: string
  phone: string
  fleetId: number
  fleetName: string
  status: EnabledStatus
  vehicleId: number | null
  plateNo: string | null
}

export interface CurrentAssociation {
  driverId: number | null
  driverNo: string | null
  driverName: string | null
  phone: string | null
  vehicleId: number
  plateNo: string | null
  effectiveFrom: string | null
}

export interface Association {
  id: number
  driverId: number
  driverNo: string
  vehicleId: number
  plateNo: string
  effectiveFrom: string
  effectiveTo: string | null
}

export async function listDrivers(fleetId?: number): Promise<Driver[]> {
  return (await http.get<Driver[]>('/drivers', { params: fleetId ? { fleetId } : undefined })).data
}

export async function createDriver(
  driverNo: string,
  name: string,
  phone: string,
  fleetId: number,
): Promise<Driver> {
  return (await http.post<Driver>('/drivers', { driverNo, name, phone, fleetId })).data
}

export async function updateDriver(
  id: number,
  patch: { name?: string; phone?: string; status?: EnabledStatus },
): Promise<Driver> {
  return (await http.put<Driver>(`/drivers/${id}`, patch)).data
}

export async function bindDriver(vehicleId: number, driverId: number | null): Promise<CurrentAssociation> {
  return (await http.put<CurrentAssociation>(`/vehicles/${vehicleId}/driver`, { driverId })).data
}

export async function currentDriver(vehicleId: number): Promise<CurrentAssociation> {
  return (await http.get<CurrentAssociation>(`/vehicles/${vehicleId}/driver`)).data
}

export async function driverHistory(vehicleId: number): Promise<Association[]> {
  return (await http.get<Association[]>(`/vehicles/${vehicleId}/driver-bindings`)).data
}
