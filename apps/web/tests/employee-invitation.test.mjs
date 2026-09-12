import assert from 'node:assert/strict'
import test from 'node:test'
import { approvalAsBindingInvitation } from '../src/features/wecom/employeeInvitationPolicy.ts'

test('approved employee invitation exposes a binding link only when all secret-bearing fields exist', () => {
  assert.deepEqual(approvalAsBindingInvitation({
    invitationId: 'employee-invite-1',
    accountId: 'account-1',
    employeeId: 'employee-1',
    status: 'APPROVED',
    bindingRequestId: 'binding-1',
    enrollmentUrl: 'https://example.test/#/wecom-bind?token=secret',
    expiresAt: '2026-09-12T12:00:00+08:00',
    message: 'ok',
  }), {
    requestId: 'binding-1',
    enrollmentUrl: 'https://example.test/#/wecom-bind?token=secret',
    expiresAt: '2026-09-12T12:00:00+08:00',
    status: 'WAITING_SCAN',
  })

  assert.equal(approvalAsBindingInvitation({
    invitationId: 'employee-invite-2',
    accountId: 'account-2',
    employeeId: 'employee-2',
    status: 'APPROVED',
    message: 'existing binding preserved',
  }), undefined)
})
