import { apiBase, ApiError } from '../../api/client'

export type EnrollmentPreview = {
  employeeName: string
  hotelName?: string
  departmentName?: string
  positionName: string
  expiresAt: string
  status: string
  expiringSoon: boolean
  canStart: boolean
  message: string
}

async function publicPost<T>(path: string, token: string): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
    body: JSON.stringify({ token }),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
    throw new ApiError(response.status, String(problem.detail ?? '绑定邀请处理失败'), problem)
  }
  return response.json() as Promise<T>
}

export function previewBindingInvitation(token: string): Promise<EnrollmentPreview> {
  return publicPost('/integrations/wecom/binding-enrollment/preview', token)
}

export async function startBindingEnrollment(token: string): Promise<string> {
  const response = await publicPost<{ authorizationUri: string }>('/integrations/wecom/binding-enrollment/start', token)
  const target = new URL(response.authorizationUri)
  if (target.protocol !== 'https:' || target.hostname !== 'open.weixin.qq.com') {
    throw new Error('服务端返回的企业微信授权地址无效，系统已停止跳转。')
  }
  return target.toString()
}
