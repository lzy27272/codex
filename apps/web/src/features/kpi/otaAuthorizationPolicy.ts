export const CTRIP_AUTHORIZATION_PERMISSION = 'ota-authorization.start'
export const CTRIP_PILOT_HOTEL_ID = '20000000-0000-4000-8000-000000000002'
export const CTRIP_PILOT_HOTEL_CODE = '002'
export const CTRIP_PLATFORM_CODE = 'CTRIP'
export const CTRIP_OFFICIAL_PORTAL_URL = 'https://ebooking.ctrip.com/'

export type CtripBindingStatus = 'DISCOVERY_REQUIRED' | 'BOUND'
export type CtripSessionStatus = 'REAUTH_REQUIRED' | 'AUTHORIZED'
export type CtripAuthorizationAction = 'DISCOVERY' | 'AUTHORIZE'

export type CtripAuthorizationStatus = {
  hotelId: typeof CTRIP_PILOT_HOTEL_ID
  hotelCode: typeof CTRIP_PILOT_HOTEL_CODE
  platformCode: typeof CTRIP_PLATFORM_CODE
  bindingStatus: CtripBindingStatus
  sessionStatus: CtripSessionStatus
  expiresAt: string | null
}

export type CtripAuthorizationStartResult = {
  challengeId: string
  status: string
  authorizationRequired: boolean
  authorizationUrl: string | null
  expiresAt: string | null
  bindingStatus: CtripBindingStatus
  sessionStatus: CtripSessionStatus
}

export type CtripCredentialStatus = {
  hotelId: typeof CTRIP_PILOT_HOTEL_ID
  hotelCode: typeof CTRIP_PILOT_HOTEL_CODE
  platformCode: typeof CTRIP_PLATFORM_CODE
  loginUrl: typeof CTRIP_OFFICIAL_PORTAL_URL
  configured: boolean
  updatedAt: string | null
  algorithm: 'AES-256-GCM' | null
  sessionStatus: CtripSessionStatus
  sessionExpiresAt: string | null
}

export type CtripCredentialLoginStatus =
  | 'LOGIN_RUNNING'
  | 'OTP_REQUIRED'
  | 'INTERACTIVE_VERIFICATION_REQUIRED'
  | 'AUTHENTICATED'
  | 'FAILED'

export type CtripCredentialChallenge = {
  challengeId: string
  status: CtripCredentialLoginStatus
  verificationType: string | null
  expiresAt: string | null
  reasonCode: string | null
  authenticated: boolean
  fallbackAuthorizationUrl: string | null
  sessionStatus: CtripSessionStatus
  sessionExpiresAt: string | null
}

const challengeIdPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu
const challengeTokenPattern = /^[A-Za-z0-9_-]{40,96}$/u
const serverStatusPattern = /^[A-Z][A-Z0-9_]{1,63}$/u

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return value as Record<string, unknown>
}

function nullableTimestamp(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string' || !value || Number.isNaN(Date.parse(value))) {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return value
}

function nullableString(value: unknown): string | null {
  if (value === null || value === undefined) return null
  if (typeof value !== 'string') throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  return value
}

function bindingStatus(value: unknown): CtripBindingStatus {
  if (value !== 'DISCOVERY_REQUIRED' && value !== 'BOUND') {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return value
}

function sessionStatus(value: unknown): CtripSessionStatus {
  if (value !== 'REAUTH_REQUIRED' && value !== 'AUTHORIZED') {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return value
}

export function canRenderCtripAuthorization(input: {
  hotelId: string
  hotelCode: string
  platformCode: string
  grantedPermissions: readonly string[]
}): boolean {
  return input.hotelId === CTRIP_PILOT_HOTEL_ID
    && input.hotelCode === CTRIP_PILOT_HOTEL_CODE
    && input.platformCode === CTRIP_PLATFORM_CODE
    && (input.grantedPermissions.includes('*')
      || input.grantedPermissions.includes(CTRIP_AUTHORIZATION_PERMISSION))
}

export function parseCtripAuthorizationStatus(value: unknown): CtripAuthorizationStatus {
  const source = record(value)
  if (
    source.hotelId !== CTRIP_PILOT_HOTEL_ID
    || source.hotelCode !== CTRIP_PILOT_HOTEL_CODE
    || source.platformCode !== CTRIP_PLATFORM_CODE
  ) {
    throw new Error('OTA_AUTHORIZATION_SCOPE_MISMATCH')
  }
  return {
    hotelId: CTRIP_PILOT_HOTEL_ID,
    hotelCode: CTRIP_PILOT_HOTEL_CODE,
    platformCode: CTRIP_PLATFORM_CODE,
    bindingStatus: bindingStatus(source.bindingStatus),
    sessionStatus: sessionStatus(source.sessionStatus),
    expiresAt: nullableTimestamp(source.expiresAt),
  }
}

export function parseCtripAuthorizationStartResult(value: unknown): CtripAuthorizationStartResult {
  const source = record(value)
  if (
    typeof source.challengeId !== 'string'
    || !challengeIdPattern.test(source.challengeId)
    || typeof source.status !== 'string'
    || !serverStatusPattern.test(source.status)
    || typeof source.authorizationRequired !== 'boolean'
    || (source.authorizationUrl !== null && typeof source.authorizationUrl !== 'string')
    || (source.authorizationRequired && typeof source.authorizationUrl !== 'string')
    || (!source.authorizationRequired && source.authorizationUrl !== null)
  ) {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return {
    challengeId: source.challengeId,
    status: source.status,
    authorizationRequired: source.authorizationRequired,
    authorizationUrl: source.authorizationUrl,
    expiresAt: nullableTimestamp(source.expiresAt),
    bindingStatus: bindingStatus(source.bindingStatus),
    sessionStatus: sessionStatus(source.sessionStatus),
  }
}

export function parseCtripCredentialStatus(value: unknown): CtripCredentialStatus {
  const source = record(value)
  if (
    source.hotelId !== CTRIP_PILOT_HOTEL_ID
    || source.hotelCode !== CTRIP_PILOT_HOTEL_CODE
    || source.platformCode !== CTRIP_PLATFORM_CODE
    || source.loginUrl !== CTRIP_OFFICIAL_PORTAL_URL
    || typeof source.configured !== 'boolean'
    || (source.algorithm !== null && source.algorithm !== 'AES-256-GCM')
    || source.configured !== (source.algorithm === 'AES-256-GCM')
  ) {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return {
    hotelId: CTRIP_PILOT_HOTEL_ID,
    hotelCode: CTRIP_PILOT_HOTEL_CODE,
    platformCode: CTRIP_PLATFORM_CODE,
    loginUrl: CTRIP_OFFICIAL_PORTAL_URL,
    configured: source.configured,
    updatedAt: nullableTimestamp(source.updatedAt),
    algorithm: source.algorithm,
    sessionStatus: sessionStatus(source.sessionStatus),
    sessionExpiresAt: nullableTimestamp(source.sessionExpiresAt),
  }
}

export function parseCtripCredentialChallenge(value: unknown): CtripCredentialChallenge {
  const source = record(value)
  const statuses: CtripCredentialLoginStatus[] = [
    'LOGIN_RUNNING',
    'OTP_REQUIRED',
    'INTERACTIVE_VERIFICATION_REQUIRED',
    'AUTHENTICATED',
    'FAILED',
  ]
  const verificationType = nullableString(source.verificationType)
  const reasonCode = nullableString(source.reasonCode)
  const fallbackAuthorizationUrl = nullableString(source.fallbackAuthorizationUrl)
  if (
    typeof source.challengeId !== 'string'
    || !challengeIdPattern.test(source.challengeId)
    || typeof source.status !== 'string'
    || !statuses.includes(source.status as CtripCredentialLoginStatus)
    || typeof source.authenticated !== 'boolean'
    || source.authenticated !== (source.status === 'AUTHENTICATED')
    || (source.status === 'INTERACTIVE_VERIFICATION_REQUIRED')
      !== (fallbackAuthorizationUrl !== null)
  ) {
    throw new Error('OTA_AUTHORIZATION_RESPONSE_INVALID')
  }
  return {
    challengeId: source.challengeId,
    status: source.status as CtripCredentialLoginStatus,
    verificationType,
    expiresAt: nullableTimestamp(source.expiresAt),
    reasonCode,
    authenticated: source.authenticated,
    fallbackAuthorizationUrl: fallbackAuthorizationUrl === null
      ? null
      : validateCtripAuthorizationUrl(fallbackAuthorizationUrl, 'https://www.sfgzt.cn'),
    sessionStatus: sessionStatus(source.sessionStatus),
    sessionExpiresAt: nullableTimestamp(source.sessionExpiresAt),
  }
}

export function ctripAuthorizationActionFor(status: CtripAuthorizationStatus): CtripAuthorizationAction {
  return status.bindingStatus === 'DISCOVERY_REQUIRED' ? 'DISCOVERY' : 'AUTHORIZE'
}

export function validateCtripAuthorizationUrl(value: unknown, expectedOrigin: string): string {
  if (typeof value !== 'string' || value.length > 2048 || value !== value.trim()) {
    throw new Error('OTA_AUTHORIZATION_URL_REJECTED')
  }
  let parsed: URL
  let expected: URL
  try {
    parsed = new URL(value)
    expected = new URL(expectedOrigin)
  } catch {
    throw new Error('OTA_AUTHORIZATION_URL_REJECTED')
  }
  if (
    parsed.href !== value
    || parsed.origin !== expected.origin
    || parsed.protocol !== 'https:'
    || parsed.username
    || parsed.password
    || parsed.pathname !== '/ota-pilot/authorize'
    || parsed.search
    || !challengeTokenPattern.test(parsed.hash.slice(1))
  ) {
    throw new Error('OTA_AUTHORIZATION_URL_REJECTED')
  }
  return parsed.href
}
