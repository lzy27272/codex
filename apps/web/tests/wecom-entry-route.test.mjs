import assert from 'node:assert/strict'
import test from 'node:test'
import {
  buildAppHashLocation,
  consumeWecomTaskEntry,
  safeTaskDeepLink,
} from '../src/features/wecom/entryRoute.ts'

test('企微回调只读取 exchange_code 并立即清除地址栏凭证', () => {
  let replaced = ''
  globalThis.window = {
    location: { origin: 'https://www.sfgzt.cn' },
    history: { replaceState: (_state, _title, url) => { replaced = url } },
  }
  const entry = consumeWecomTaskEntry({
    href: 'https://www.sfgzt.cn/wecom-auth?exchange_code=Abcd_efghijklmnopqrstuvwxyz0123456789-AB',
  })
  assert.equal(entry?.code, 'Abcd_efghijklmnopqrstuvwxyz0123456789-AB')
  assert.equal(replaced, '/wecom-auth')
})

test('企微深链允许工作台、站内任务及日报详情，并要求安全目标', () => {
  globalThis.window = { location: { origin: 'https://www.sfgzt.cn' } }
  assert.equal(
    safeTaskDeepLink('#/tasks?view=mine&taskId=123e4567-e89b-42d3-a456-426614174000'),
    '#/tasks?view=mine&taskId=123e4567-e89b-42d3-a456-426614174000',
  )
  assert.equal(
    safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000'),
    '#/daily-reports/123e4567-e89b-42d3-a456-426614174000',
  )
  assert.equal(safeTaskDeepLink('#/workbench'), '#/workbench')
  assert.throws(() => safeTaskDeepLink('https://evil.example/tasks?taskId=123e4567-e89b-42d3-a456-426614174000'))
  assert.throws(() => safeTaskDeepLink('#/notifications?taskId=123e4567-e89b-42d3-a456-426614174000'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/not-a-uuid'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000?next=https://evil.example'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000/extra'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000#extra'))
  assert.throws(() => safeTaskDeepLink('#/workbench?next=/admin'))
})

test('完成和取消都回到应用根路径，不保留 wecom-auth pathname', () => {
  assert.equal(buildAppHashLocation('#/tasks?taskId=1', '/'), '/#/tasks?taskId=1')
  assert.equal(buildAppHashLocation('#/', '/console/'), '/console/#/')
  assert.equal(buildAppHashLocation('#/tasks', '//evil.example/'), '/#/tasks')
})
