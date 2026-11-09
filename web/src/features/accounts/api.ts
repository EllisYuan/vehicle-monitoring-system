import { http } from '../../shared/http'
import type { AccountStatus } from '../fleets/api'
import type { RoleCode } from '../identity/store/session'

export interface Account {
  id: number
  username: string
  roleCode: RoleCode
  status: AccountStatus
  fleetIds: number[]
}

export async function listAccounts(): Promise<Account[]> {
  const { data } = await http.get<Account[]>('/users')
  return data
}

export async function createAccount(payload: {
  username: string
  password: string
  roleCode: RoleCode
  fleetIds: number[]
}): Promise<Account> {
  const { data } = await http.post<Account>('/users', payload)
  return data
}

export async function updateAccountStatus(id: number, status: AccountStatus): Promise<Account> {
  const { data } = await http.put<Account>(`/users/${id}/status`, { status })
  return data
}

export async function resetAccountPassword(id: number, newPassword: string): Promise<void> {
  await http.post(`/users/${id}/password-reset`, { newPassword })
}

export async function assignAccountFleets(id: number, fleetIds: number[]): Promise<Account> {
  const { data } = await http.put<Account>(`/users/${id}/fleets`, { fleetIds })
  return data
}
