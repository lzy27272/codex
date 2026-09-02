export const CTRIP_LOCAL_HELPER_ORIGIN = 'http://127.0.0.1:17891'

export type CtripLocalHelperHealth = {
  status: string
  helperVersion: string
  hotelCode: '002'
  platformCode: 'CTRIP'
}

export type CtripLocalCapture = {
  status: 'CAPTURED'
  loginState: 'AUTHENTICATED'
  hotelCode: '002'
  platformCode: 'CTRIP'
  capturedAt: string
  dataScope: string
  recordCount: number
  detectedDimensions: string[]
  hotelScopeStatus: 'VERIFIED_FROM_CTRIP_RESPONSE'
  stableIdentityStatus: string
}

function record(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('LOCAL_HELPER_RESPONSE_INVALID')
  }
  return value as Record<string, unknown>
}

function responseData(value: unknown): Record<string, unknown> {
  return record(record(value).data)
}

function safeStatus(value: unknown): string {
  if (typeof value !== 'string' || !/^[A-Z][A-Z0-9_]{1,80}$/u.test(value)) {
    throw new Error('LOCAL_HELPER_RESPONSE_INVALID')
  }
  return value
}

export function parseCtripLocalHelperHealth(value: unknown): CtripLocalHelperHealth {
  const data = responseData(value)
  if (
    typeof data.helperVersion !== 'string'
    || !/^\d+\.\d+\.\d+$/u.test(data.helperVersion)
    || data.hotelCode !== '002'
    || data.platformCode !== 'CTRIP'
  ) {
    throw new Error('LOCAL_HELPER_SCOPE_MISMATCH')
  }
  return {
    status: safeStatus(data.status),
    helperVersion: data.helperVersion,
    hotelCode: '002',
    platformCode: 'CTRIP',
  }
}

export function parseCtripLocalCapture(value: unknown): CtripLocalCapture {
  const data = responseData(value)
  const capturedAt = typeof data.capturedAt === 'string'
    ? data.capturedAt
    : ''
  const dimensions = Array.isArray(data.detectedDimensions)
    ? data.detectedDimensions
    : []
  if (
    data.status !== 'CAPTURED'
    || data.loginState !== 'AUTHENTICATED'
    || data.hotelCode !== '002'
    || data.platformCode !== 'CTRIP'
    || typeof data.dataScope !== 'string'
    || data.dataScope.length < 1
    || data.dataScope.length > 80
    || !Number.isInteger(data.recordCount)
    || Number(data.recordCount) < 0
    || dimensions.length > 12
    || dimensions.some((item) =>
      typeof item !== 'string' || !/^[A-Z][A-Z0-9_]{1,40}$/u.test(item))
    || Number.isNaN(Date.parse(capturedAt))
    || data.hotelScopeStatus !== 'VERIFIED_FROM_CTRIP_RESPONSE'
    || typeof data.stableIdentityStatus !== 'string'
  ) {
    throw new Error('LOCAL_HELPER_RESPONSE_INVALID')
  }
  return {
    status: 'CAPTURED',
    loginState: 'AUTHENTICATED',
    hotelCode: '002',
    platformCode: 'CTRIP',
    capturedAt,
    dataScope: data.dataScope,
    recordCount: Number(data.recordCount),
    detectedDimensions: dimensions as string[],
    hotelScopeStatus: 'VERIFIED_FROM_CTRIP_RESPONSE',
    stableIdentityStatus: data.stableIdentityStatus,
  }
}

async function readJson(response: Response): Promise<unknown> {
  let value: unknown
  try {
    value = await response.json()
  } catch {
    throw new Error('LOCAL_HELPER_RESPONSE_INVALID')
  }
  if (!response.ok) {
    const error = record(value).error
    const code = error && typeof error === 'object' && !Array.isArray(error)
      ? (error as Record<string, unknown>).code
      : null
    throw new Error(typeof code === 'string' ? code : 'LOCAL_HELPER_REQUEST_FAILED')
  }
  return value
}

export async function probeCtripLocalHelper(): Promise<CtripLocalHelperHealth> {
  const response = await fetch(`${CTRIP_LOCAL_HELPER_ORIGIN}/health`, {
    method: 'GET',
    mode: 'cors',
    cache: 'no-store',
  })
  return parseCtripLocalHelperHealth(await readJson(response))
}

export async function collectCtripLocally(): Promise<CtripLocalCapture> {
  const response = await fetch(
    `${CTRIP_LOCAL_HELPER_ORIGIN}/api/v1/ctrip/collect`,
    {
      method: 'POST',
      mode: 'cors',
      cache: 'no-store',
    },
  )
  return parseCtripLocalCapture(await readJson(response))
}
