import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(
  new URL('../src/features/kpi/CtripAuthorizationAction.tsx', import.meta.url),
  'utf8',
)

test('002试点在中台配置加密凭证并按需提交验证码', () => {
  assert.match(source, /携程云端自动登录试点/u)
  assert.match(source, /saveCtripCredentials/u)
  assert.match(source, /startCtripCredentialLogin/u)
  assert.match(source, /requestCtripVerificationCode/u)
  assert.match(source, /submitCtripVerificationCode/u)
  assert.match(source, /AES-256-GCM/u)
  assert.match(source, /验证码不落盘/u)
  assert.match(source, /系统不会尝试绕过/u)
  assert.match(source, /AUTHORIZATION_NOT_ENABLED/u)
  assert.match(source, /当前账号没有002携程授权权限/u)
  assert.match(source, /保存并登录识别/u)
  assert.match(source, /OTA_CTRIP_CLOUD_HOTEL_IDENTITY_CONFIRMATION_REQUIRED/u)
  assert.match(source, /状态读取失败/u)
  assert.doesNotMatch(source, /probeCtripLocalHelper/u)
  assert.doesNotMatch(source, /collectCtripLocally/u)
  assert.doesNotMatch(source, /Sifangguan-Ctrip-002-Helper/u)
  assert.doesNotMatch(source, /window\.open/u)
})
