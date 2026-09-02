export function bootstrapAssignmentId(authMode: string, acceptanceAssignmentId?: string): string {
  return authMode === 'dev-header' ? acceptanceAssignmentId ?? '' : ''
}

export function bootstrapAssignments<T>(authMode: string, acceptanceAssignments: T[]): T[] {
  return authMode === 'dev-header' ? acceptanceAssignments : []
}

export function canLoadSecondaryResources(
  authMode: string,
  identityLoading: boolean,
  identityError?: string,
): boolean {
  return authMode === 'dev-header' || (!identityLoading && !identityError)
}
