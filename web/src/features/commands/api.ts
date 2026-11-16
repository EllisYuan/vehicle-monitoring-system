import { http } from '../../shared/http'

export type CommandType = 'TEXT' | 'TRACKING'
export type CommandStatus = 'SUBMITTED' | 'SENT' | 'ACK_SUCCESS' | 'FAILED' | 'TIMEOUT'

export interface CommandSubmission {
  status: 'SUBMITTED'
  operatorId: number
  operatorName: string
  submittedAt: string
}

export interface CommandCommunication {
  status: CommandStatus
  sendTime: string | null
  replyTime: string | null
  replyResult: number | null
  replyMessageId: number | null
  failureReason: string | null
}

export interface CommandRecord {
  id: number
  commandType: CommandType
  messageId: number
  terminalId: number
  vehicleId: number | null
  serialNo: number
  connectionEpoch: number
  retryOfId: number | null
  textContent: string | null
  textType: number | null
  textBytes: number | null
  intervalSeconds: number | null
  validSeconds: number | null
  submission: CommandSubmission
  communication: CommandCommunication
}

export async function submitText(terminalId: number, text: string, textType: number): Promise<CommandRecord> {
  return (await http.post<CommandRecord>('/commands/text', { terminalId, text, textType })).data
}

export async function submitTracking(
  terminalId: number,
  intervalSeconds: number,
  validSeconds: number,
): Promise<CommandRecord> {
  return (await http.post<CommandRecord>('/commands/tracking', { terminalId, intervalSeconds, validSeconds })).data
}

export async function retryCommand(id: number): Promise<CommandRecord> {
  return (await http.post<CommandRecord>(`/commands/${id}/retry`)).data
}

export async function listCommands(terminalId?: number): Promise<CommandRecord[]> {
  return (await http.get<CommandRecord[]>('/commands', {
    params: terminalId ? { terminalId } : undefined,
  })).data
}

export async function getCommand(id: number): Promise<CommandRecord> {
  return (await http.get<CommandRecord>(`/commands/${id}`)).data
}

export const COMMAND_TYPE_LABELS: Record<CommandType, string> = {
  TEXT: '文本通知',
  TRACKING: '临时跟踪',
}

export const COMMAND_STATUS_LABELS: Record<CommandStatus, string> = {
  SUBMITTED: '已提交',
  SENT: '已下发',
  ACK_SUCCESS: '终端应答成功',
  FAILED: '失败',
  TIMEOUT: '超时未应答',
}

export const FAILURE_LABELS: Record<string, string> = {
  TERMINAL_REJECTED: '终端拒绝',
  LOGOUT: '终端注销',
  NOT_CONNECTED: '终端未连接',
  STALE_EPOCH: '连接已更换',
  WRITE_FAILED: '下行写入失败',
}

export function commandSummary(command: CommandRecord): string {
  if (command.commandType === 'TEXT') {
    return command.textContent ?? ''
  }
  if (command.intervalSeconds === 0) {
    return '停止临时跟踪'
  }
  return `间隔 ${command.intervalSeconds} 秒 · 有效期 ${command.validSeconds} 秒`
}
