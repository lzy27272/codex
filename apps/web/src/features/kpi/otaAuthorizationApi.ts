import type { RoleContext } from '../../domain'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import {
  CTRIP_PILOT_HOTEL_ID,
  CTRIP_PLATFORM_CODE,
  parseCtripAuthorizationStartResult,
  parseCtripAuthorizationStatus,
  parseCtripCredentialChallenge,
  parseCtripCredentialStatus,
  type CtripAuthorizationAction,
  type CtripAuthorizationStartResult,
  type CtripAuthorizationStatus,
  type CtripCredentialChallenge,
  type CtripCredentialStatus,
} from './otaAuthorizationPolicy'

const authorizationBase = '/ota/connector-authorizations'

export async function loadCtripAuthorizationStatus(
  identity: RoleContext,
  signal?: AbortSignal,
): Promise<CtripAuthorizationStatus> {
  const query = new URLSearchParams({
    hotelId: CTRIP_PILOT_HOTEL_ID,
    platformCode: CTRIP_PLATFORM_CODE,
  })
  const response = await featureApiRequest<unknown>(`${authorizationBase}?${query.toString()}`, identity, { signal })
  return parseCtripAuthorizationStatus(response)
}

export async function startCtripAuthorization(
  identity: RoleContext,
  action: CtripAuthorizationAction,
  idempotencyKey: string,
): Promise<CtripAuthorizationStartResult> {
  const response = await featureApiMutation<unknown>(`${authorizationBase}/actions/start`, identity, {
    method: 'POST',
    idempotencyKey,
    body: {
      hotelId: CTRIP_PILOT_HOTEL_ID,
      platformCode: CTRIP_PLATFORM_CODE,
      action,
      reason: action === 'DISCOVERY' ? '平台管理员首次核验002携程门店身份' : '平台管理员发起002携程云端授权',
      idempotencyKey,
    },
  })
  return parseCtripAuthorizationStartResult(response)
}

const credentialScope = () => ({
  hotelId: CTRIP_PILOT_HOTEL_ID,
  platformCode: CTRIP_PLATFORM_CODE,
})

export async function loadCtripCredentialStatus(
  identity: RoleContext,
  signal?: AbortSignal,
): Promise<CtripCredentialStatus> {
  const query = new URLSearchParams(credentialScope())
  const response = await featureApiRequest<unknown>(
    `${authorizationBase}/credentials?${query.toString()}`,
    identity,
    { signal },
  )
  return parseCtripCredentialStatus(response)
}

export async function saveCtripCredentials(
  identity: RoleContext,
  username: string,
  password: string,
  idempotencyKey: string,
): Promise<CtripCredentialStatus> {
  const response = await featureApiMutation<unknown>(`${authorizationBase}/credentials`, identity, {
    method: 'PUT',
    idempotencyKey,
    body: { ...credentialScope(), username, password, idempotencyKey },
  })
  return parseCtripCredentialStatus(response)
}

export async function startCtripCredentialLogin(
  identity: RoleContext,
  idempotencyKey: string,
): Promise<CtripCredentialChallenge> {
  const response = await featureApiMutation<unknown>(
    `${authorizationBase}/credentials/actions/login`,
    identity,
    {
      method: 'POST',
      idempotencyKey,
      body: { ...credentialScope(), idempotencyKey },
    },
  )
  return parseCtripCredentialChallenge(response)
}

export async function loadCtripCredentialChallenge(
  identity: RoleContext,
  challengeId: string,
  signal?: AbortSignal,
): Promise<CtripCredentialChallenge> {
  const query = new URLSearchParams(credentialScope())
  const response = await featureApiRequest<unknown>(
    `${authorizationBase}/credentials/challenges/${encodeURIComponent(challengeId)}?${query.toString()}`,
    identity,
    { signal },
  )
  return parseCtripCredentialChallenge(response)
}

export async function requestCtripVerificationCode(
  identity: RoleContext,
  challengeId: string,
  idempotencyKey: string,
): Promise<CtripCredentialChallenge> {
  const response = await featureApiMutation<unknown>(
    `${authorizationBase}/credentials/challenges/${encodeURIComponent(challengeId)}/actions/send-code`,
    identity,
    {
      method: 'POST',
      idempotencyKey,
      body: { ...credentialScope(), idempotencyKey },
    },
  )
  return parseCtripCredentialChallenge(response)
}

export async function submitCtripVerificationCode(
  identity: RoleContext,
  challengeId: string,
  code: string,
  idempotencyKey: string,
): Promise<CtripCredentialChallenge> {
  const response = await featureApiMutation<unknown>(
    `${authorizationBase}/credentials/challenges/${encodeURIComponent(challengeId)}/actions/submit-code`,
    identity,
    {
      method: 'POST',
      idempotencyKey,
      body: { ...credentialScope(), challengeId, code, idempotencyKey },
    },
  )
  return parseCtripCredentialChallenge(response)
}
