import { useMemo, useState } from 'react'
import type { Navigate, RoleContext, RouteParams } from '../../domain'
import { hasPermission, permissions } from '../../app/permissions'
import { useResource } from '../../useResource'
import { FeatureHeader, featureStyles as styles, formatLocalDateTime } from '../shared/FeatureUI'
import {
  approveExecutiveTask,
  createExecutiveTask,
  loadExecutiveTask,
  loadExecutiveTasks,
  loadExecutiveTaskTargets,
  reworkExecutiveTask,
  type ExecutiveTask,
} from './api'

const statusLabels: Record<string, string> = {
  PENDING_ACK: '待确认', IN_PROGRESS: '执行中', RESULT_SUBMITTED: '待验收',
  AWAITING_REVIEW: '待验收', REWORK: '返工中', COMPLETED: '已完成', CANCELLED: '已取消',
  CHAIRMAN_DIRECTIVE: '董事长交办', HIGH: '高', URGENT: '紧急', NORMAL: '普通', LOW: '低',
}

function valueLabel(value?: string) {
  return value ? statusLabels[value] ?? value : '—'
}

function TaskCard({ task, selected, onSelect }: { task: ExecutiveTask; selected: boolean; onSelect: () => void }) {
  return <button type="button" className={`${styles.reportItem} ${selected ? styles.conflict : ''}`} onClick={onSelect}>
    <strong>{task.title}</strong>
    <span className={styles.meta}>
      <span>{task.code}</span><span>{task.assigneeName} · {task.assigneePositionName}</span>
      <span>{formatLocalDateTime(task.dueAt)}</span><span>{valueLabel(task.status)}</span>
    </span>
  </button>
}

export function ExecutiveTaskRoutes({
  identity,
  grantedPermissions,
  routeParams,
  go,
}: {
  identity: RoleContext
  grantedPermissions: string[]
  routeParams: RouteParams
  go: Navigate
}) {
  const canAssign = hasPermission(grantedPermissions, permissions.executiveTask.assign)
  const list = useResource(`${identity.key}:executive-tasks`, () => loadExecutiveTasks(identity), [], 15_000)
  const targets = useResource(`${identity.key}:executive-task-targets:${canAssign}`, () => canAssign
    ? loadExecutiveTaskTargets(identity)
    : Promise.resolve({ data: [], source: 'api' as const }), [])
  const selectedId = routeParams.taskId || list.data[0]?.id
  const detail = useResource<ExecutiveTask | undefined>(
    `${identity.key}:executive-task:${selectedId ?? 'none'}`,
    () => selectedId ? loadExecutiveTask(identity, selectedId) : Promise.resolve({ data: undefined, source: 'api' as const }),
    undefined,
  )
  const [creating, setCreating] = useState(false)
  const [targetAssignmentId, setTargetAssignmentId] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [priority, setPriority] = useState('NORMAL')
  const [dueAt, setDueAt] = useState('')
  const [reminders, setReminders] = useState('')
  const [reviewNote, setReviewNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const task = detail.data
  const canReview = Boolean(task && ['RESULT_SUBMITTED', 'AWAITING_REVIEW'].includes(task.status)
    && task.creationSource === 'CHAIRMAN_DIRECTIVE'
    && task.reviewerAssignmentId === identity.businessActorAssignmentId)
  const parsedReminderTimes = useMemo(() => reminders.split(/\r?\n/).map((value) => value.trim()).filter(Boolean), [reminders])

  const refresh = async (taskId?: string) => {
    await list.reload()
    if (taskId) go('tasks', { taskId })
    else await detail.reload()
  }

  const submit = async () => {
    if (!targetAssignmentId || !title.trim() || !description.trim() || !dueAt) return
    setBusy(true); setError(undefined)
    try {
      const created = await createExecutiveTask(identity, {
        targetAssignmentId,
        title: title.trim(),
        description: description.trim(),
        priority,
        dueAt: new Date(dueAt).toISOString(),
        reminderTimes: parsedReminderTimes.map((value) => new Date(value).toISOString()),
      })
      setCreating(false); setTitle(''); setDescription(''); setDueAt(''); setReminders('')
      await refresh(created.id)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '交办失败') }
    finally { setBusy(false) }
  }

  const review = async (action: 'approve' | 'rework') => {
    if (!task || (action === 'rework' && !reviewNote.trim())) return
    setBusy(true); setError(undefined)
    try {
      if (action === 'approve') await approveExecutiveTask(identity, task.id, task.version, reviewNote)
      else await reworkExecutiveTask(identity, task.id, task.version, reviewNote)
      setReviewNote('')
      await Promise.all([list.reload(), detail.reload()])
    } catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败') }
    finally { setBusy(false) }
  }

  return <section className={styles.page}>
    <FeatureHeader eyebrow="EXECUTIVE TASKS" title="集团任务" description="字段最小化查看全集团任务；董事长仅可向集团总经理或副总经理交办。" actions={canAssign && <button className="primary" onClick={() => setCreating((current) => !current)}>{creating ? '收起' : '＋ 交办高管任务'}</button>} />
    {creating && <section className={styles.section}>
      <header><h2>交办高管任务</h2><span className={styles.badge}>验收人固定为当前董事长任职</span></header>
      <div className={styles.formGrid}>
        <label>执行人<select value={targetAssignmentId} onChange={(event) => setTargetAssignmentId(event.target.value)}><option value="">请选择集团总经理/副总经理</option>{targets.data.map((item) => <option key={item.assignmentId} value={item.assignmentId}>{item.employeeName} · {item.positionName}</option>)}</select></label>
        <label>优先级<select value={priority} onChange={(event) => setPriority(event.target.value)}><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option><option value="LOW">低</option></select></label>
        <label className={styles.full}>任务标题<input maxLength={240} value={title} onChange={(event) => setTitle(event.target.value)} /></label>
        <label className={styles.full}>执行要求<textarea rows={4} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
        <label>完成节点<input type="datetime-local" value={dueAt} onChange={(event) => setDueAt(event.target.value)} /></label>
        <label>提醒时间（每行一个，最多8个）<textarea rows={3} value={reminders} onChange={(event) => setReminders(event.target.value)} placeholder="2026-09-15T09:00" /></label>
      </div>
      {targets.error && <div className="inline-error">{targets.error}</div>}{error && <div className="inline-error">{error}</div>}
      <div className={styles.actions}><button className="primary" disabled={busy || targets.loading || !targetAssignmentId || !title.trim() || !description.trim() || !dueAt || parsedReminderTimes.length > 8} onClick={() => void submit()}>{busy ? '正在交办…' : '确认交办'}</button><button className="secondary" onClick={() => setCreating(false)}>取消</button></div>
    </section>}
    {list.loading && <div className="state-card"><div className="spinner" /><strong>正在读取集团任务</strong></div>}
    {list.error && <div className="state-card error-state"><strong>任务读取失败</strong><span>{list.error}</span><button className="secondary" onClick={() => void list.reload()}>重试</button></div>}
    {!list.loading && !list.error && !list.data.length && <div className="state-card"><strong>当前集团暂无任务</strong></div>}
    {!!list.data.length && <div className={styles.split}>
      <section className={styles.section}><header><h2>集团任务清单</h2><span className={styles.badge}>{list.data.length} 项</span></header><div className={styles.stack}>{list.data.map((item) => <TaskCard key={item.id} task={item} selected={item.id === selectedId} onSelect={() => go('tasks', { taskId: item.id })} />)}</div></section>
      <aside className={styles.stack}>
        <section className={styles.section}><header><h2>任务详情</h2>{task && <span className={styles.badge}>{valueLabel(task.status)}</span>}</header>
          {detail.loading && <p>正在读取…</p>}{detail.error && <div className="inline-error">{detail.error}</div>}
          {task && <div className={styles.stack}><h3>{task.title}</h3><div className={styles.meta}><span>{task.code}</span><span>{task.targetOrgName}</span><span>{task.assigneeName} · {task.assigneePositionName}</span><span>截止 {formatLocalDateTime(task.dueAt)}</span><span>来源 {valueLabel(task.creationSource)}</span><span>进度 {task.progress}%</span></div>
            {task.description && <p>{task.description}</p>}{task.resultSummary && <p><strong>执行结果：</strong>{task.resultSummary}</p>}{task.evidenceCount !== undefined && <p>证据数量：{task.evidenceCount}（首期不提供证据内容访问）</p>}
            {task.reminderTimes?.length ? <p>提醒：{task.reminderTimes.map(formatLocalDateTime).join('、')}</p> : null}
            {canReview && <><label className={styles.formGrid}>验收意见<textarea rows={3} value={reviewNote} onChange={(event) => setReviewNote(event.target.value)} /></label><div className={styles.actions}><button className="primary" disabled={busy} onClick={() => void review('approve')}>验收通过</button><button className="secondary" disabled={busy || !reviewNote.trim()} onClick={() => void review('rework')}>退回返工</button></div></>}
          </div>}
        </section>
      </aside>
    </div>}
    {!creating && error && <div className="inline-error">{error}</div>}
  </section>
}
