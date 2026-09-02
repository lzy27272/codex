import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'

export type BindingStatus = 'WAITING_SCAN' | 'WAITING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'ABNORMAL' | 'EXPIRED' | 'UNBOUND' | 'REVOKED'
export type AssignmentOption = { id: string; orgUnitId: string; hotelCode?: string; hotelName?: string; departmentName?: string; positionName: string; primary: boolean }
export type BindingPerson = {
  accountId: string; employeeId: string; hotelCode?: string; hotelName?: string; departmentName?: string
  employeeName: string; loginName: string; positionName?: string; bindingStatus: BindingStatus
  requestId?: string; requestStatus?: string; expiresAt?: string; expiringSoon: boolean
  preferredAssignmentId?: string; defaultAssignment?: string; userIdFingerprint?: string
  lastVerifiedAt?: string; updatedBy?: string; updatedAt?: string; rowVersion: number
  reason?: string; recommendedAction: string; assignments: AssignmentOption[]
}
export type BindingDashboard = {
  counters: { waitingScan: number; waitingApproval: number; active: number; suspended: number; abnormal: number }
  capabilities: { canRead: boolean; canManage: boolean; canApprove: boolean }
  people: BindingPerson[]
}
export type Invitation = { requestId: string; enrollmentUrl: string; expiresAt: string; status: string }
export type AuditEntry = { action: string; actorId?: string; actorName?: string; createdAt: string; summary?: string }

const base = '/integrations/wecom/user-bindings'

export function loadBindingDashboard(identity: RoleContext): Promise<BindingDashboard> {
  return apiRequest(base, identity)
}

export function inviteBinding(identity: RoleContext, accountId: string, preferredAssignmentId: string): Promise<Invitation> {
  return apiRequest(`${base}/invitations`, identity, { method: 'POST', body: JSON.stringify({ accountId, preferredAssignmentId }) })
}

export function bulkInviteBindings(identity: RoleContext, invitations: Array<{ accountId: string; preferredAssignmentId: string }>) {
  return apiRequest<{ invitations: Invitation[] }>(`${base}/invitations/bulk`, identity, { method: 'POST', body: JSON.stringify({ invitations }) })
}

export function decideBinding(identity: RoleContext, requestId: string, action: 'approve' | 'reject' | 'cancel' | 'retry', body: Record<string, unknown>) {
  return apiRequest(`${base}/requests/${requestId}/${action}`, identity, { method: 'POST', body: JSON.stringify(body) })
}

export function updateBinding(identity: RoleContext, accountId: string, action: 'suspend' | 'resume' | 'revoke', expectedVersion: number, reason?: string) {
  return apiRequest(`${base}/${accountId}/${action}`, identity, { method: 'POST', body: JSON.stringify({ expectedVersion, reason }) })
}

export function selectPreferredAssignment(identity: RoleContext, person: BindingPerson, preferredAssignmentId: string) {
  return apiRequest(`${base}/${person.accountId}/preferred-assignment`, identity, {
    method: 'POST', body: JSON.stringify({ preferredAssignmentId, expectedVersion: person.rowVersion }),
  })
}

export function rebind(identity: RoleContext, person: BindingPerson, preferredAssignmentId: string, reason?: string) {
  return apiRequest<Invitation>(`${base}/${person.accountId}/rebind`, identity, {
    method: 'POST', body: JSON.stringify({ preferredAssignmentId, expectedVersion: person.rowVersion, confirmed: true, reason }),
  })
}

export function bulkSuspendBindings(identity: RoleContext, people: BindingPerson[], reason?: string) {
  return apiRequest(`${base}/suspend/bulk`, identity, {
    method: 'POST', body: JSON.stringify({ confirmed: true, reason, bindings: people.map((person) => ({ accountId: person.accountId, expectedVersion: person.rowVersion })) }),
  })
}

export function loadBindingHistory(identity: RoleContext, accountId: string): Promise<AuditEntry[]> {
  return apiRequest(`${base}/${accountId}/history`, identity)
}
