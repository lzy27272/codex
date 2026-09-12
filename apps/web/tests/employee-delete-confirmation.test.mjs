import assert from 'node:assert/strict'
import test from 'node:test'
import {
  EMPLOYEE_PERMANENT_DELETE_CONFIRMATION,
  isEmployeePermanentDeleteConfirmation,
} from '../src/features/organization/employeeDeleteConfirmation.ts'

test('employee permanent deletion uses one stable confirmation phrase', () => {
  assert.equal(EMPLOYEE_PERMANENT_DELETE_CONFIRMATION, '确认')
  assert.equal(isEmployeePermanentDeleteConfirmation('确认'), true)
  assert.equal(isEmployeePermanentDeleteConfirmation('  确认  '), true)
})

test('employee permanent deletion rejects employee numbers and cancelled prompts', () => {
  assert.equal(isEmployeePermanentDeleteConfirmation('E-FD-001'), false)
  assert.equal(isEmployeePermanentDeleteConfirmation('确定'), false)
  assert.equal(isEmployeePermanentDeleteConfirmation(''), false)
  assert.equal(isEmployeePermanentDeleteConfirmation(null), false)
})
