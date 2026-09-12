import { apiBase, apiRequest, ApiError } from '../../api/client'
import type { RoleContext } from '../../domain'

export type DirectoryOnboardingStatus = 'WAITING_PROFILE' | 'PENDING_APPROVAL' | 'CONFLICT' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED'
export type DirectoryPositionOption = { id: string; name: string }
export type DirectoryDepartmentOption = { id: string; name: string; positions: DirectoryPositionOption[] }
export type DirectoryHotelOption = { id: string; name: string; departments: DirectoryDepartmentOption[] }
export type DirectoryOnboardingContext = {
  candidateId: string
  status: DirectoryOnboardingStatus
  displayName: string
  loginName?: string
  requiresAccountRegistration: boolean
  hotels: DirectoryHotelOption[]
  rowVersion: number
}
export type DirectoryOnboardingSubmitResponse = {
  candidateId: string
  status: DirectoryOnboardingStatus
  rowVersion: number
  message: string
}
export type DirectoryCandidate = {
  id: string
  maskedFingerprint: string
  displayName: string
  requestedLoginName?: string
  requestedHotelName?: string
  requestedDepartmentName?: string
  requestedPositionName?: string
  status: DirectoryOnboardingStatus
  failureCode?: string
  invitationExpiresAt?: string
  expiringSoon: boolean
  retryable: boolean
  canRegenerate: boolean
  suggestedAction?: string
  updatedAt: string
  rowVersion: number
}
export type DirectoryApprovalResponse = {
  candidateId: string
  accountId?: string
  status: DirectoryOnboardingStatus
  bindingStatus?: string
  rowVersion: number
  message: string
}
export type DirectoryInvitationActionResponse = {
  candidateId: string
  status: DirectoryOnboardingStatus
  invitationExpiresAt: string
  rowVersion: number
  message: string
}
export type DirectoryEventRow = {
  id: string
  changeType: string
  status: 'DEAD_LETTER'
  lastErrorCode?: string
  errorMessage: string
  receivedAt: string
  rowVersion: number
  suggestedAction: string
}
export type DirectoryEventRetryResponse = {
  receiptId: string
  status: string
  rowVersion: number
  message: string
}

async function publicPost<T>(path: string, body: Record<string, unknown>): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, {
    method: 'POST', credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
    throw new ApiError(response.status, String(problem.detail ?? problem.message ?? '企业微信入职处理失败'), problem)
  }
  return response.json() as Promise<T>
}

export async function startDirectoryOnboarding(invitationToken: string): Promise<string> {
  const result = await publicPost<{ authorizationUri: string }>('/integrations/wecom/directory-onboarding/start', { invitationToken })
  const target = new URL(result.authorizationUri)
  if (target.protocol !== 'https:' || target.hostname !== 'open.weixin.qq.com') throw new Error('服务端返回的企业微信授权地址无效，系统已停止跳转。')
  return target.toString()
}

export function exchangeDirectoryOnboarding(exchangeCode: string) {
  return publicPost<{ sessionToken: string; expiresAt: string; status: DirectoryOnboardingStatus }>('/integrations/wecom/directory-onboarding/exchange', { exchangeCode })
}

export function loadDirectoryOnboardingContext(sessionToken: string) {
  return publicPost<DirectoryOnboardingContext>('/integrations/wecom/directory-onboarding/context', { sessionToken })
}

export function submitDirectoryOnboarding(
  sessionToken: string,
  displayName: string,
  loginName: string,
  password: string,
  passwordConfirmation: string,
  orgUnitId: string,
  positionId: string,
  expectedVersion: number,
) {
  return publicPost<DirectoryOnboardingSubmitResponse>('/integrations/wecom/directory-onboarding/submit', {
    sessionToken, displayName, loginName, password, passwordConfirmation,
    orgUnitId, positionId, expectedVersion,
  })
}

const adminBase = '/integrations/wecom/directory-onboarding/candidates'

export async function loadDirectoryCandidates(identity: RoleContext, status?: string): Promise<DirectoryCandidate[]> {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  const payload = await apiRequest<{ items?: DirectoryCandidate[] } | DirectoryCandidate[]>(`${adminBase}${query}`, identity)
  return Array.isArray(payload) ? payload : payload.items ?? []
}

export function approveDirectoryCandidate(
  identity: RoleContext,
  candidate: DirectoryCandidate,
  reason?: string,
  transferExistingBinding = false,
) {
  return apiRequest<DirectoryApprovalResponse>(`${adminBase}/${candidate.id}/approve`, identity, {
    method: 'POST', body: JSON.stringify({
      expectedVersion: candidate.rowVersion,
      reason: reason || null,
      transferExistingBinding,
    }),
  })
}

export function rejectDirectoryCandidate(identity: RoleContext, candidate: DirectoryCandidate, reason: string) {
  return apiRequest(`${adminBase}/${candidate.id}/reject`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: candidate.rowVersion, reason }),
  })
}

export function retryDirectoryCandidate(identity: RoleContext, candidate: DirectoryCandidate, reason?: string) {
  return apiRequest<DirectoryInvitationActionResponse>(`${adminBase}/${candidate.id}/retry`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: candidate.rowVersion, reason: reason || null }),
  })
}

export function regenerateDirectoryInvitation(identity: RoleContext, candidate: DirectoryCandidate, reason?: string) {
  return apiRequest<DirectoryInvitationActionResponse>(`${adminBase}/${candidate.id}/regenerate-invitation`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: candidate.rowVersion, reason: reason || null }),
  })
}

export async function loadDirectoryEvents(identity: RoleContext): Promise<DirectoryEventRow[]> {
  const payload = await apiRequest<{ items?: DirectoryEventRow[] } | DirectoryEventRow[]>(
    '/integrations/wecom/directory-onboarding/directory-events?status=DEAD_LETTER',
    identity,
  )
  return Array.isArray(payload) ? payload : payload.items ?? []
}

export function retryDirectoryEvent(identity: RoleContext, event: DirectoryEventRow, reason?: string) {
  return apiRequest<DirectoryEventRetryResponse>(
    `/integrations/wecom/directory-onboarding/directory-events/${event.id}/retry`,
    identity,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion: event.rowVersion, reason: reason || null }),
    },
  )
}
