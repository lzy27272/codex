import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const entry = readFileSync(new URL('../src/features/wecom/WecomDirectoryOnboardingEntry.tsx', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/features/wecom/directoryOnboardingApi.ts', import.meta.url), 'utf8')
const bindingAdministration = readFileSync(new URL('../src/features/wecom/WecomUserBindingAdministration.tsx', import.meta.url), 'utf8')
const migration = readFileSync(new URL('../../../database/migrations/V41__wecom_directory_account_registration.sql', import.meta.url), 'utf8')
const manualInvitationMigration = readFileSync(new URL('../../../database/migrations/V42__manual_wecom_onboarding_invitation.sql', import.meta.url), 'utf8')

test('verified new members register their account before choosing assignment', () => {
  assert.match(entry, /个人姓名/)
  assert.match(entry, /登录账号/)
  assert.match(entry, /登录密码/)
  assert.match(entry, /确认密码/)
  assert.match(entry, /行政人事或行政人事主管审核/)
  assert.match(entry, /审核前账号不可登录/)
})

test('registration submits confirmation but never persists secrets in browser storage', () => {
  assert.match(api, /passwordConfirmation/)
  assert.match(api, /sessionToken, displayName, loginName, password, passwordConfirmation/)
  assert.doesNotMatch(entry, /localStorage|sessionStorage/)
  assert.doesNotMatch(api, /localStorage|sessionStorage/)
})

test('migration reserves open logins and grants both HR reviewer roles', () => {
  assert.match(migration, /ux_wecom_onboarding_open_login/)
  assert.match(migration, /requested_password_hash LIKE 'pbkdf2_sha256\$%'/)
  assert.match(migration, /'HR_ADMINISTRATION', 'HR_ADMINISTRATION_SUPERVISOR'/)
  assert.match(migration, /'wecom-onboarding\.review'/)
})

test('one-click invitation reuses employee self-registration instead of administrator prefill', () => {
  assert.match(bindingAdministration, /createDirectoryOnboardingInvitation/)
  assert.match(bindingAdministration, /新员工入职邀请/)
  assert.match(bindingAdministration, /自行填写姓名、账号、密码、门店和岗位/)
  assert.doesNotMatch(bindingAdministration, /createEmployeeInvitation|employeeInviteForm/)
  assert.match(api, /directory-onboarding\/invitations/)
  assert.match(entry, /context\.invitationSource === 'MANUAL_LINK'/)
})

test('manual invitations store no employee profile before verified registration', () => {
  assert.match(manualInvitationMigration, /invitation_source/)
  assert.match(manualInvitationMigration, /MANUAL_LINK/)
  assert.match(manualInvitationMigration, /invitation_created_by/)
})
