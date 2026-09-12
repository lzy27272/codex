import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const routes = readFileSync(new URL('../src/features/dailyReports/DailyReportRoutes.tsx', import.meta.url), 'utf8')
const api = readFileSync(new URL('../src/features/dailyReports/api.ts', import.meta.url), 'utf8')

test('团队日报为无默认门店的管理员解析可管理门店', () => {
  assert.match(routes, /loadDailyReportOrgOptions\(identity, signal\)/)
  assert.match(routes, /options\.data\[0\]/)
  assert.match(routes, /<label>门店<select value=\{orgUnitId\}/)
  assert.match(api, /\/org\/units\?unitType=HOTEL/)
  assert.match(api, /status\)\.toUpperCase\(\) === 'ACTIVE'/)
})
