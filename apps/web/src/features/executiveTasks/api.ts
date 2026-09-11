import { apiMutation, apiRequest, asList, type ApiIdentity } from '../../api/client'
import type { ManagementTask } from '../../domain'

export type ExecutiveTaskTarget = {
  assignmentId: string
  employeeName: string
  positionCode: 'GROUP_GENERAL_MANAGER' | 'GROUP_VICE_PRESIDENT'
  positionName: string
  organizationId: string
  organizationName: string
}

export type ExecutiveTask = ManagementTask & {
  assigneePositionName?: string
  progress: number
  creationSource: string
  resultSummary?: string
  evidenceCount?: number
  reminderTimes?: string[]
}

type ExecutiveTaskPayload = {
  id: string
  taskNo: string
  title: string
  lifecycleStatus: string
  slaStatus: string
  priority: string
  dueAt?: string
  rowVersion: number
  creationSource: string
  orgUnitId?: string
  orgUnitName: string
  assigneeAssignmentId?: string
  assigneeName: string
  assigneePositionName?: string
  reviewerAssignmentId?: string
  reviewerName: string
  progress?: number
  description?: string
  resultSummary?: string
  evidenceCount?: number
  reminderTimes?: string[]
}

function normalizeTask(item: ExecutiveTaskPayload): ExecutiveTask {
  return {
    id: item.id,
    code: item.taskNo,
    title: item.title,
    status: item.lifecycleStatus,
    slaStatus: item.slaStatus,
    priority: item.priority,
    dueAt: item.dueAt,
    version: item.rowVersion,
    creationSource: item.creationSource,
    sourceType: item.creationSource,
    targetOrgUnitId: item.orgUnitId,
    targetOrgName: item.orgUnitName,
    assigneeAssignmentId: item.assigneeAssignmentId,
    assigneeName: item.assigneeName,
    assigneePositionName: item.assigneePositionName,
    reviewerAssignmentId: item.reviewerAssignmentId,
    reviewerName: item.reviewerName,
    progress: item.progress ?? 0,
    description: item.description,
    resultSummary: item.resultSummary,
    evidenceCount: item.evidenceCount,
    reminderTimes: item.reminderTimes,
  }
}

export async function loadExecutiveTasks(identity: ApiIdentity) {
  const payload = await apiRequest<unknown>('/executive-tasks', identity)
  return { data: asList<ExecutiveTaskPayload>(payload).map(normalizeTask), source: 'api' as const }
}

export async function loadExecutiveTask(identity: ApiIdentity, taskId: string) {
  const payload = await apiRequest<ExecutiveTaskPayload>(`/executive-tasks/${encodeURIComponent(taskId)}`, identity)
  return { data: normalizeTask(payload), source: 'api' as const }
}

export async function loadExecutiveTaskTargets(identity: ApiIdentity) {
  const payload = await apiRequest<unknown>('/executive-tasks/targets', identity)
  return { data: asList<ExecutiveTaskTarget>(payload), source: 'api' as const }
}

export async function createExecutiveTask(identity: ApiIdentity, input: {
  targetAssignmentId: string
  title: string
  description: string
  priority: string
  dueAt: string
  reminderTimes: string[]
}) {
  const clientCommandId = crypto.randomUUID()
  const payload = await apiMutation<ExecutiveTaskPayload>('/executive-tasks', identity, {
    body: { ...input, clientCommandId },
    idempotencyKey: clientCommandId,
  })
  return normalizeTask(payload)
}

export async function approveExecutiveTask(identity: ApiIdentity, taskId: string, expectedVersion: number, comment?: string) {
  const payload = await apiMutation<ExecutiveTaskPayload>(`/executive-tasks/${encodeURIComponent(taskId)}/actions/approve`, identity, {
    body: { expectedVersion, ...(comment?.trim() ? { comment: comment.trim() } : {}) },
    idempotencyKey: crypto.randomUUID(),
  })
  return normalizeTask(payload)
}

export async function reworkExecutiveTask(identity: ApiIdentity, taskId: string, expectedVersion: number, reason: string) {
  const payload = await apiMutation<ExecutiveTaskPayload>(`/executive-tasks/${encodeURIComponent(taskId)}/actions/rework`, identity, {
    body: { expectedVersion, reason: reason.trim() },
    idempotencyKey: crypto.randomUUID(),
  })
  return normalizeTask(payload)
}
