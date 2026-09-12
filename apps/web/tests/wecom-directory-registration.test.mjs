import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const entry = readFileSync(new URL('../src/features/wecom/WecomDirectoryOnboardingEntry.tsx', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/features/wecom/directoryOnboardingApi.ts', import.meta.url), 'utf8')
const bindingAdministration = readFileSync(new URL('../src/features/wecom/WecomUserBindingAdministration.tsx', import.meta.url), 'utf8')
const migration = readFileSync(new URL('../../../database/migrations/V41__wecom_directory_account_registration.sql', import.meta.url), 'utf8')
const manualInvitationMigration = readFileSync(new URL('../../../database/migrations/V42__manual_wecom_onboarding_invitation.sql', import.meta.url), 'utf8')
const onboardingDefaultsMigration = readFileSync(new URL('../../../database/migrations/V44__enable_reviewed_wecom_onboarding_positions.sql', import.meta.url), 'utf8')

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

test('employees without a platform account use reviewed self-registration', () => {
  assert.match(bindingAdministration, /createDirectoryOnboardingInvitation/)
  assert.match(bindingAdministration, /无中台账号注册邀请/)
  assert.match(bindingAdministration, /自行填写姓名、账号、密码、门店和岗位/)
  assert.doesNotMatch(bindingAdministration, /createEmployeeInvitation|employeeInviteForm/)
  assert.match(api, /directory-onboarding\/invitations/)
  assert.match(entry, /context\.invitationSource === 'MANUAL_LINK'/)
})

test('existing employees receive an account-specific binding invitation without registering again', () => {
  assert.match(bindingAdministration, /已有中台账号绑定/)
  assert.match(bindingAdministration, /选择在职员工/)
  assert.match(bindingAdministration, /bindablePeople/)
  assert.match(bindingAdministration, /inviteBinding\(identity, person\.accountId, assignment\.id\)/)
  assert.match(bindingAdministration, /不会被要求重新注册账号/)
})

test('manual invitations store no employee profile before verified registration', () => {
  assert.match(manualInvitationMigration, /invitation_source/)
  assert.match(manualInvitationMigration, /MANUAL_LINK/)
  assert.match(manualInvitationMigration, /invitation_created_by/)
})

test('registration explains unavailable options and never exposes protected positions by default', () => {
  assert.match(entry, /暂无可申请的门店岗位/)
  assert.match(entry, /disabled=\{!hasHotelOptions\}/)
  assert.match(entry, /positionOptions\.length === 0/)
  assert.match(onboardingDefaultsMigration, /FRONT_DESK/)
  assert.match(onboardingDefaultsMigration, /protected_permission\.delegable_to_position = false/)
  assert.doesNotMatch(onboardingDefaultsMigration, /GROUP_CHAIRMAN|GROUP_GENERAL_MANAGER|GROUP_VICE_PRESIDENT|HR_KPI_ADMIN|PLATFORM_ADMIN|OTA_OPERATION_MANAGER/)
})
