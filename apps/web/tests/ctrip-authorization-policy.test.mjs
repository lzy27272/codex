import assert from 'node:assert/strict'
import test from 'node:test'
import {
  CTRIP_OFFICIAL_PORTAL_URL,
  CTRIP_PILOT_HOTEL_ID,
  canRenderCtripAuthorization,
  ctripAuthorizationActionFor,
  parseCtripAuthorizationStartResult,
  parseCtripAuthorizationStatus,
  parseCtripCredentialChallenge,
  validateCtripAuthorizationUrl,
} from '../src/features/kpi/otaAuthorizationPolicy.ts'

const token = 'A'.repeat(43)
const status = {
  hotelId: CTRIP_PILOT_HOTEL_ID,
  hotelCode: '002',
  platformCode: 'CTRIP',
  bindingStatus: 'BOUND',
  sessionStatus: 'REAUTH_REQUIRED',
  expiresAt: null,
}

test('授权入口仅对002、携程和授权账号显示，通配权限有效', () => {
  assert.equal(canRenderCtripAuthorization({ ...status, grantedPermissions: ['ota-authorization.start'] }), true)
  assert.equal(canRenderCtripAuthorization({ ...status, grantedPermissions: ['*'] }), true)
  assert.equal(canRenderCtripAuthorization({ ...status, hotelCode: '001', grantedPermissions: ['*'] }), false)
  assert.equal(canRenderCtripAuthorization({ ...status, platformCode: 'MEITUAN', grantedPermissions: ['*'] }), false)
  assert.equal(canRenderCtripAuthorization({ ...status, grantedPermissions: ['kpi.template.manage'] }), false)
})

test('本机登录试点只打开携程官方HTTPS后台', () => {
  const portal = new URL(CTRIP_OFFICIAL_PORTAL_URL)
  assert.equal(portal.protocol, 'https:')
  assert.equal(portal.hostname, 'ebooking.ctrip.com')
  assert.equal(portal.username, '')
  assert.equal(portal.password, '')
})

test('首次核验与正式授权严格由稳定身份绑定状态决定', () => {
  const discovery = parseCtripAuthorizationStatus({ ...status, bindingStatus: 'DISCOVERY_REQUIRED' })
  const authorize = parseCtripAuthorizationStatus(status)
  assert.equal(ctripAuthorizationActionFor(discovery), 'DISCOVERY')
  assert.equal(ctripAuthorizationActionFor(authorize), 'AUTHORIZE')
  assert.throws(() => parseCtripAuthorizationStatus({ ...status, hotelCode: '009' }), /SCOPE_MISMATCH/)
  assert.throws(() => parseCtripAuthorizationStatus({ ...status, sessionStatus: 'UNKNOWN' }), /RESPONSE_INVALID/)
})

test('授权链接只允许当前HTTPS源、固定路径、无查询且含合规片段令牌', () => {
  const valid = `https://www.sfgzt.cn/ota-pilot/authorize#${token}`
  assert.equal(validateCtripAuthorizationUrl(valid, 'https://www.sfgzt.cn'), valid)
  assert.throws(() => validateCtripAuthorizationUrl(`https://evil.example/ota-pilot/authorize#${token}`, 'https://www.sfgzt.cn'))
  assert.throws(() => validateCtripAuthorizationUrl(`https://www.sfgzt.cn/other#${token}`, 'https://www.sfgzt.cn'))
  assert.throws(() => validateCtripAuthorizationUrl(`https://www.sfgzt.cn/ota-pilot/authorize?next=x#${token}`, 'https://www.sfgzt.cn'))
  assert.throws(() => validateCtripAuthorizationUrl('https://user:pass@www.sfgzt.cn/ota-pilot/authorize#' + token, 'https://www.sfgzt.cn'))
  assert.throws(() => validateCtripAuthorizationUrl('http://www.sfgzt.cn/ota-pilot/authorize#' + token, 'http://www.sfgzt.cn'))
  assert.throws(() => validateCtripAuthorizationUrl('https://www.sfgzt.cn/ota-pilot/authorize#short', 'https://www.sfgzt.cn'))
})

test('启动响应拒绝无URL、无效challenge及状态字段漂移', () => {
  const result = parseCtripAuthorizationStartResult({
    challengeId: '123e4567-e89b-42d3-a456-426614174000',
    status: 'WAITING_FOR_USER',
    authorizationRequired: true,
    authorizationUrl: `https://www.sfgzt.cn/ota-pilot/authorize#${token}`,
    expiresAt: '2026-08-29T10:10:00Z',
    bindingStatus: 'BOUND',
    sessionStatus: 'REAUTH_REQUIRED',
  })
  assert.equal(result.authorizationRequired, true)
  assert.throws(() => parseCtripAuthorizationStartResult({ ...result, authorizationUrl: null }))
  assert.throws(() => parseCtripAuthorizationStartResult({ ...result, challengeId: 'not-a-uuid' }))
  assert.throws(() => parseCtripAuthorizationStartResult({ ...result, status: '<script>' }))
})

test('首次凭证登录可返回待确认身份，但不能冒充已授权会话', () => {
  const result = parseCtripCredentialChallenge({
    challengeId: '123e4567-e89b-42d3-a456-426614174000',
    status: 'FAILED',
    verificationType: null,
    expiresAt: '2026-08-29T10:10:00Z',
    reasonCode: 'OTA_CTRIP_CLOUD_HOTEL_IDENTITY_CONFIRMATION_REQUIRED',
    authenticated: false,
    fallbackAuthorizationUrl: null,
    sessionStatus: 'REAUTH_REQUIRED',
    sessionExpiresAt: null,
  })
  assert.equal(result.status, 'FAILED')
  assert.equal(result.reasonCode, 'OTA_CTRIP_CLOUD_HOTEL_IDENTITY_CONFIRMATION_REQUIRED')
  assert.equal(result.authenticated, false)
  assert.throws(() => parseCtripCredentialChallenge({ ...result, authenticated: true }))
})

test('凭证登录兼容后端省略空字段并保留人工验证入口', () => {
  const result = parseCtripCredentialChallenge({
    challengeId: '123e4567-e89b-42d3-a456-426614174000',
    status: 'INTERACTIVE_VERIFICATION_REQUIRED',
    verificationType: 'SLIDER',
    expiresAt: '2026-08-29T10:10:00Z',
    authenticated: false,
    fallbackAuthorizationUrl: `https://www.sfgzt.cn/ota-pilot/authorize#${token}`,
    sessionStatus: 'REAUTH_REQUIRED',
  })
  assert.equal(result.reasonCode, null)
  assert.equal(result.sessionExpiresAt, null)
  assert.equal(result.status, 'INTERACTIVE_VERIFICATION_REQUIRED')
  assert.equal(result.fallbackAuthorizationUrl, `https://www.sfgzt.cn/ota-pilot/authorize#${token}`)
})
