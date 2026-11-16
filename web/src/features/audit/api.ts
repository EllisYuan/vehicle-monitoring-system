import { http } from '../../shared/http'

export interface AuditEntry {
  id: number
  actorId: number | null
  actorName: string | null
  category: string
  action: string
  target: string | null
  result: string
  sourceIp: string | null
  detail: string | null
  createdAt: string
}

export interface AuditPage {
  items: AuditEntry[]
  page: number
  size: number
  total: number
  hasMore: boolean
}

export interface AuditQuery {
  actor?: string
  category?: string
  action?: string
  result?: string
  from?: string
  to?: string
  page?: number
  size?: number
}

export interface OutboxDeadEntry {
  eventId: string
  eventType: string
  aggregateId: number
  attempts: number
  retryAt: string | null
  updatedAt: string | null
}

export interface OperationRecords {
  deadOutbox: OutboxDeadEntry[]
  outboxCounts: Record<string, number>
  generatedAt: string
}

/** 审计只读：仅查询登录、关键写操作与运行记录，不提供写接口。 */
export async function searchAuditLogs(query: AuditQuery): Promise<AuditPage> {
  const { data } = await http.get<AuditPage>('/audit/logs', { params: query })
  return data
}

export async function getOperationRecords(): Promise<OperationRecords> {
  const { data } = await http.get<OperationRecords>('/audit/operation-records')
  return data
}
