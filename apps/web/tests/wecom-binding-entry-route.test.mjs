import test from 'node:test'
import assert from 'node:assert/strict'

test('binding invitation token is consumed once and cleared before API use', async () => {
  let replacement = ''
  globalThis.window = { history: { replaceState: (_state, _title, value) => { replacement = value } } }
  const { consumeWecomBindingEntry } = await import('../src/features/wecom/bindingEntryRoute.ts')
  const token = 'A'.repeat(43)
  const location = { href: `https://www.sfgzt.cn/#/wecom-bind?token=${token}` }
  const entry = consumeWecomBindingEntry(location)
  assert.equal(entry?.token, token)
  assert.equal(replacement, '/#/wecom-bind')
  assert.equal(replacement.includes(token), false)
})

test('directory onboarding exchange code is consumed and removed from the hash', async () => {
  let replacement = ''
  globalThis.window = { history: { replaceState: (_state, _title, value) => { replacement = value } } }
  const moduleUrl = new URL('../src/features/wecom/bindingEntryRoute.ts?directory=1', import.meta.url)
  const { consumeWecomBindingEntry } = await import(moduleUrl)
  const exchangeCode = `x_${'B'.repeat(30)}`
  const entry = consumeWecomBindingEntry({ href: `https://www.sfgzt.cn/#/wecom-onboarding?exchange_code=${exchangeCode}` })
  assert.equal(entry?.flow, 'directory')
  assert.equal(entry?.exchangeCode, exchangeCode)
  assert.equal(replacement, '/#/wecom-onboarding')
  assert.equal(replacement.includes(exchangeCode), false)
})

test('directory onboarding rejects mixed or unexpected credentials', async () => {
  let replacement = ''
  globalThis.window = { history: { replaceState: (_state, _title, value) => { replacement = value } } }
  const moduleUrl = new URL('../src/features/wecom/bindingEntryRoute.ts?directory=2', import.meta.url)
  const { consumeWecomBindingEntry } = await import(moduleUrl)
  const entry = consumeWecomBindingEntry({ href: `https://www.sfgzt.cn/#/wecom-onboarding?token=${'A'.repeat(43)}&exchange_code=${'B'.repeat(32)}` })
  assert.match(entry?.securityError ?? '', /缺失、重复|无效/)
  assert.equal(replacement, '/#/wecom-onboarding')
})

test('directory onboarding accepts only allowlisted OAuth error codes and clears them', async () => {
  let replacement = ''
  globalThis.window = { history: { replaceState: (_state, _title, value) => { replacement = value } } }
  const moduleUrl = new URL('../src/features/wecom/bindingEntryRoute.ts?directory=3', import.meta.url)
  const { consumeWecomBindingEntry } = await import(moduleUrl)
  const entry = consumeWecomBindingEntry({ href: 'https://www.sfgzt.cn/#/wecom-onboarding?error_code=OAUTH_IDENTITY_MISMATCH' })
  assert.equal(entry?.flow, 'directory')
  assert.equal(entry?.errorCode, 'OAUTH_IDENTITY_MISMATCH')
  assert.equal(replacement, '/#/wecom-onboarding')
  assert.equal(replacement.includes('OAUTH_'), false)
})

test('directory onboarding rejects unknown OAuth errors without reflecting them', async () => {
  let replacement = ''
  globalThis.window = { history: { replaceState: (_state, _title, value) => { replacement = value } } }
  const moduleUrl = new URL('../src/features/wecom/bindingEntryRoute.ts?directory=4', import.meta.url)
  const { consumeWecomBindingEntry } = await import(moduleUrl)
  const entry = consumeWecomBindingEntry({ href: 'https://www.sfgzt.cn/#/wecom-onboarding?error_code=RAW_BACKEND_EXCEPTION' })
  assert.match(entry?.securityError ?? '', /结果无效/)
  assert.equal(entry?.securityError?.includes('RAW_BACKEND_EXCEPTION'), false)
  assert.equal(replacement, '/#/wecom-onboarding')
})

test('directory onboarding rejects mixed OAuth error and credential parameters', async () => {
  let replacement = ''
  globalThis.window = { history: { replaceState: (_state, _title, value) => { replacement = value } } }
  const moduleUrl = new URL('../src/features/wecom/bindingEntryRoute.ts?directory=5', import.meta.url)
  const { consumeWecomBindingEntry } = await import(moduleUrl)
  const entry = consumeWecomBindingEntry({ href: `https://www.sfgzt.cn/#/wecom-onboarding?error_code=OAUTH_SESSION_INVALID&token=${'A'.repeat(43)}` })
  assert.match(entry?.securityError ?? '', /重复|非预期/)
  assert.equal(entry?.token, undefined)
  assert.equal(entry?.errorCode, undefined)
  assert.equal(replacement, '/#/wecom-onboarding')
})
