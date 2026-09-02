import assert from 'node:assert/strict'
import test from 'node:test'

import {
  parseCtripLocalCapture,
  parseCtripLocalHelperHealth,
} from '../src/features/kpi/ctripLocalHelperApi.ts'

test('本机助手健康响应固定为002携程范围', () => {
  const health = parseCtripLocalHelperHealth({ data: {
    status: 'READY',
    helperVersion: '1.0.0',
    hotelCode: '002',
    platformCode: 'CTRIP',
  } })
  assert.equal(health.status, 'READY')
  assert.throws(() => parseCtripLocalHelperHealth({ data: {
    ...health,
    hotelCode: '009',
  } }), /SCOPE_MISMATCH/u)
})

test('脱敏抓取响应拒绝订单明细和漂移字段', () => {
  const valid = { data: {
    status: 'CAPTURED',
    loginState: 'AUTHENTICATED',
    hotelCode: '002',
    platformCode: 'CTRIP',
    capturedAt: '2026-08-30T10:00:00.000Z',
    dataScope: '携程订单列表当前查询结果',
    recordCount: 7,
    detectedDimensions: ['SALES', 'CHANNEL'],
    hotelScopeStatus: 'VERIFIED_FROM_CTRIP_RESPONSE',
    stableIdentityStatus: 'DISCOVERED',
  } }
  assert.equal(parseCtripLocalCapture(valid).recordCount, 7)
  assert.throws(() => parseCtripLocalCapture({ data: {
    ...valid.data,
    detectedDimensions: ['guestName'],
  } }), /RESPONSE_INVALID/u)
  assert.throws(() => parseCtripLocalCapture({ data: {
    ...valid.data,
    hotelCode: '001',
  } }), /RESPONSE_INVALID/u)
})
