type ApprovalResult = {
  bindingRequestId?: string
  enrollmentUrl?: string
  expiresAt?: string
}

export type BindingInvitationPreview = {
  requestId: string
  enrollmentUrl: string
  expiresAt: string
  status: 'WAITING_SCAN'
}

export function approvalAsBindingInvitation(result: ApprovalResult): BindingInvitationPreview | undefined {
  if (!result.bindingRequestId || !result.enrollmentUrl || !result.expiresAt) return undefined
  return {
    requestId: result.bindingRequestId,
    enrollmentUrl: result.enrollmentUrl,
    expiresAt: result.expiresAt,
    status: 'WAITING_SCAN',
  }
}
