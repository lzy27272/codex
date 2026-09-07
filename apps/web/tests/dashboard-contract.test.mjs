import assert from 'node:assert/strict'
import test from 'node:test'
import { dashboardApiPaths } from '../src/app/dashboardContract.ts'

test('single-hotel discovery never depends on the regional dashboard endpoint', () => {
  assert.equal(dashboardApiPaths.accessibleHotels, '/dashboards/hotels')
  assert.equal(dashboardApiPaths.hotel('hotel-123'), '/dashboards/hotels/hotel-123')
  assert.equal(dashboardApiPaths.operations, '/dashboards/operations')
  assert.notEqual(dashboardApiPaths.accessibleHotels, dashboardApiPaths.operations)
})
