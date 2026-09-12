export const EMPLOYEE_PERMANENT_DELETE_CONFIRMATION = '确认'

export function isEmployeePermanentDeleteConfirmation(value: string | null): boolean {
  return value?.trim() === EMPLOYEE_PERMANENT_DELETE_CONFIRMATION
}
