import assert from 'node:assert/strict'
import test from 'node:test'

import {
  bootstrapAssignmentId,
  bootstrapAssignments,
  canLoadSecondaryResources,
} from '../src/app/authBootstrap.ts'

test('server-resolved sessions never bootstrap with acceptance-role assignments', () => {
  const acceptanceAssignment = { id: 'acceptance-only' }

  assert.equal(bootstrapAssignmentId('bearer', acceptanceAssignment.id), '')
  assert.deepEqual(bootstrapAssignments('bearer', [acceptanceAssignment]), [])
  assert.equal(bootstrapAssignmentId('server', acceptanceAssignment.id), '')
  assert.deepEqual(bootstrapAssignments('server', [acceptanceAssignment]), [])
})

test('development header mode preserves acceptance-role bootstrap context', () => {
  const acceptanceAssignment = { id: 'acceptance-only' }

  assert.equal(bootstrapAssignmentId('dev-header', acceptanceAssignment.id), acceptanceAssignment.id)
  assert.deepEqual(bootstrapAssignments('dev-header', [acceptanceAssignment]), [acceptanceAssignment])
})

test('server-resolved sessions defer secondary requests until identity succeeds', () => {
  assert.equal(canLoadSecondaryResources('bearer', true), false)
  assert.equal(canLoadSecondaryResources('bearer', false, '身份读取失败'), false)
  assert.equal(canLoadSecondaryResources('bearer', false), true)
  assert.equal(canLoadSecondaryResources('server', true), false)
  assert.equal(canLoadSecondaryResources('dev-header', true), true)
})
