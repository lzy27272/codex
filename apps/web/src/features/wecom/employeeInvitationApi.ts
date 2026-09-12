import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'
export { approvalAsBindingInvitation } from './employeeInvitationPolicy'

export type EmployeeInvitationRequest = {
  id: string
  displayName: string
  mobile?: string
  loginName: string
  employeeNo: string
  note?: string
  status: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  requestedByName: string
  createdAt: string
  reviewedByName?: string
  reviewedAt?: string
  decisionReason?: string
  targetAccountId?: string
  targetEmployeeId?: string
  rowVersion: number
}

export type EmployeeInvitationDashboard = {
  capabilities: { canCreate: boolean; canApprove: boolean }
  items: EmployeeInvitationRequest[]
  accounts: Array<{
    id: string; loginName: string; displayName: string; employeeId?: string
    employeeName?: string; platformAdmin: boolean
  }>
  orgUnits: Array<{ id: string; name: string; unitType: string }>
  positions: Array<{ id: string; name: string }>
  managers: Array<{ assignmentId: string; employeeName: string; positionName: string }>
}

export type EmployeeInvitationAssignment = {
  orgUnitId: string
  positionId: string
  managerAssignmentId?: string
  primary: boolean
  assignmentType: 'PERMANENT' | 'TEMPORARY' | 'ACTING'
}

export type EmployeeInvitationApproval = {
  invitationId: string
  accountId: string
  employeeId: string
  status: string
  bindingRequestId?: string
  enrollmentUrl?: string
  expiresAt?: string
  message: string
}

const base = '/integrations/wecom/employee-invitations'

export function loadEmployeeInvitationDashboard(identity: RoleContext) {
  return apiRequest<EmployeeInvitationDashboard>(base, identity)
}

export function createEmployeeInvitation(identity: RoleContext, body: {
  displayName: string; mobile?: string; loginName: string; employeeNo: string; note?: string
}) {
  return apiRequest<EmployeeInvitationRequest>(base, identity, {
    method: 'POST', body: JSON.stringify(body),
  })
}

export function approveEmployeeInvitation(
  identity: RoleContext,
  request: EmployeeInvitationRequest,
  existingAccountId: string | undefined,
  assignments: EmployeeInvitationAssignment[],
  reason?: string,
) {
  return apiRequest<EmployeeInvitationApproval>(`${base}/${request.id}/approve`, identity, {
    method: 'POST', body: JSON.stringify({
      expectedVersion: request.rowVersion, existingAccountId, assignments, reason,
    }),
  })
}

export function rejectEmployeeInvitation(
  identity: RoleContext, request: EmployeeInvitationRequest, reason: string,
) {
  return apiRequest<EmployeeInvitationRequest>(`${base}/${request.id}/reject`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: request.rowVersion, reason }),
  })
}
