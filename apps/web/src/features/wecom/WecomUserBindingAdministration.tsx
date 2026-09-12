import { useEffect, useMemo, useState } from 'react'
import QRCode from 'qrcode'
import type { RoleContext } from '../../domain'
import {
  bulkInviteBindings, bulkSuspendBindings, decideBinding, inviteBinding, loadBindingDashboard,
  loadBindingHistory, rebind, selectPreferredAssignment, updateBinding,
  type AuditEntry, type BindingDashboard, type BindingPerson, type Invitation,
} from './bindingAdminApi'
import {
  approvalAsBindingInvitation, approveEmployeeInvitation, createEmployeeInvitation,
  loadEmployeeInvitationDashboard, rejectEmployeeInvitation,
  type EmployeeInvitationAssignment, type EmployeeInvitationDashboard,
  type EmployeeInvitationRequest,
} from './employeeInvitationApi'
import styles from './wecomBinding.module.css'

const statusLabel: Record<string, string> = {
  WAITING_SCAN: '待员工扫描', WAITING_APPROVAL: '待管理员确认', ACTIVE: '已启用',
  SUSPENDED: '已暂停', ABNORMAL: '异常绑定', EXPIRED: '已过期', UNBOUND: '未绑定', REVOKED: '已解除',
}

function displayTime(value?: string) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

function defaultAssignment(person: BindingPerson) {
  return person.assignments.find((item) => item.id === person.preferredAssignmentId)
    ?? person.assignments.find((item) => item.primary) ?? person.assignments[0]
}

const emptyEmployeeInvite = { displayName: '', mobile: '', loginName: '', employeeNo: '', note: '' }
const newAssignment = (primary = false): EmployeeInvitationAssignment => ({
  orgUnitId: '', positionId: '', managerAssignmentId: undefined, primary, assignmentType: 'PERMANENT',
})

export function WecomUserBindingAdministration({ identity, requestId, onClearRequest }: { identity: RoleContext; requestId?: string; onClearRequest?: () => void }) {
  const [dashboard, setDashboard] = useState<BindingDashboard>()
  const [employeeInvitations, setEmployeeInvitations] = useState<EmployeeInvitationDashboard>()
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [hotel, setHotel] = useState('')
  const [department, setDepartment] = useState('')
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [invitation, setInvitation] = useState<Invitation>()
  const [qrData, setQrData] = useState<string>()
  const [history, setHistory] = useState<{ person: BindingPerson; entries: AuditEntry[] }>()
  const [showEmployeeInvite, setShowEmployeeInvite] = useState(false)
  const [employeeInviteForm, setEmployeeInviteForm] = useState(emptyEmployeeInvite)
  const [reviewing, setReviewing] = useState<EmployeeInvitationRequest>()
  const [reviewAccountId, setReviewAccountId] = useState('')
  const [reviewAssignments, setReviewAssignments] = useState<EmployeeInvitationAssignment[]>([newAssignment(true)])

  const reload = async () => {
    setLoading(true); setError(undefined)
    try {
      const [bindingData, invitationData] = await Promise.all([
        loadBindingDashboard(identity), loadEmployeeInvitationDashboard(identity),
      ])
      setDashboard(bindingData)
      setEmployeeInvitations(invitationData)
      setSelected(new Set())
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '企业微信人员绑定加载失败')
    } finally { setLoading(false) }
  }

  useEffect(() => { void reload() }, [identity.key])
  useEffect(() => {
    if (!invitation) { setQrData(undefined); return }
    let active = true
    void QRCode.toDataURL(invitation.enrollmentUrl, { width: 232, margin: 2, errorCorrectionLevel: 'M' })
      .then((value) => { if (active) setQrData(value) })
      .catch(() => { if (active) setError('二维码生成失败，可复制安全邀请链接继续') })
    return () => { active = false }
  }, [invitation])

  const hotels = useMemo(() => [...new Set((dashboard?.people ?? []).map((item) => item.hotelName).filter(Boolean))].sort(), [dashboard])
  const departments = useMemo(() => [...new Set((dashboard?.people ?? []).map((item) => item.departmentName).filter(Boolean))].sort(), [dashboard])
  const people = useMemo(() => {
    const normalized = query.trim().toLowerCase()
    return (dashboard?.people ?? []).filter((person) =>
      (!requestId || person.requestId === requestId)
      && (!hotel || person.hotelName === hotel) && (!department || person.departmentName === department)
      && (!status || person.bindingStatus === status)
      && (!normalized || `${person.employeeName} ${person.loginName}`.toLowerCase().includes(normalized)))
  }, [dashboard, hotel, department, query, requestId, status])

  const command = async (key: string, operation: () => Promise<unknown>, success: string) => {
    setBusy(key); setError(undefined); setNotice(undefined)
    try { await operation(); setNotice(success); await reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败') }
    finally { setBusy(undefined) }
  }

  const showInvitation = async (promise: Promise<Invitation>) => {
    setBusy('invite'); setError(undefined); setNotice(undefined)
    try { setInvitation(await promise); await reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '绑定邀请生成失败') }
    finally { setBusy(undefined) }
  }

  const invite = (person: BindingPerson) => {
    const assignment = defaultAssignment(person)
    if (!assignment) { setError('该员工没有有效任职，不能发起绑定'); return }
    void showInvitation(inviteBinding(identity, person.accountId, assignment.id))
  }

  const approve = (person: BindingPerson) => {
    if (!person.requestId) return
    const conflict = person.requestStatus === 'CONFLICT'
    const text = conflict
      ? '该身份已绑定其他账号。确认后将原子转移：原账号立即失效，新账号启用。是否继续？'
      : '确认员工身份与中台账号一致并启用绑定？群推送开关不会改变。'
    if (!window.confirm(text)) return
    void command(`approve:${person.accountId}`, () => decideBinding(identity, person.requestId!, 'approve', {
      expectedVersion: person.rowVersion, transferExistingBinding: conflict, reason: conflict ? 'IDENTITY_TRANSFER_APPROVED' : 'IDENTITY_CONFIRMED',
    }), '绑定已确认启用；群推送仍保持独立关闭状态')
  }

  const reject = (person: BindingPerson) => {
    if (!person.requestId) return
    const reason = window.prompt('请输入拒绝原因（员工本人将收到处理结果）：')
    if (reason === null) return
    void command(`reject:${person.accountId}`, () => decideBinding(identity, person.requestId!, 'reject', {
      expectedVersion: person.rowVersion, reason,
    }), '绑定申请已拒绝')
  }

  const cancel = (person: BindingPerson) => {
    if (!person.requestId || !window.confirm('取消后当前邀请立即失效，确认取消？')) return
    void command(`cancel:${person.accountId}`, () => decideBinding(identity, person.requestId!, 'cancel', {
      expectedVersion: person.rowVersion, reason: 'INVITATION_CANCELLED_BY_OPERATOR',
    }), '邀请已取消')
  }

  const retry = (person: BindingPerson) => {
    if (!person.requestId || !window.confirm('技术故障重试会生成新的120分钟邀请，旧邀请继续失效。确认继续？')) return
    void showInvitation(decideBinding(identity, person.requestId, 'retry', {
      expectedVersion: person.rowVersion, reason: 'TECHNICAL_RETRY',
    }) as Promise<Invitation>)
  }

  const changeState = (person: BindingPerson, action: 'suspend' | 'resume' | 'revoke') => {
    const prompts = { suspend: '确认暂停该员工的企业微信绑定？', resume: '确认账号和任职有效并恢复绑定？', revoke: '解除后原身份立即失效且必须重新绑定。确认解除？' }
    if (!window.confirm(prompts[action])) return
    void command(`${action}:${person.accountId}`, () => updateBinding(identity, person.accountId, action, person.rowVersion, `MANUAL_${action.toUpperCase()}`), {
      suspend: '绑定已暂停', resume: '绑定已恢复', revoke: '绑定已解除',
    }[action])
  }

  const startRebind = (person: BindingPerson) => {
    const assignment = defaultAssignment(person)
    if (!assignment || !window.confirm('重新绑定会立即使原企业微信身份失效并生成新邀请。确认继续？')) return
    void showInvitation(rebind(identity, person, assignment.id, 'MANUAL_REBIND'))
  }

  const setPreferred = (person: BindingPerson, assignmentId: string) => {
    if (assignmentId === person.preferredAssignmentId) return
    void command(`assignment:${person.accountId}`, () => selectPreferredAssignment(identity, person, assignmentId), '默认企微任职已更新')
  }

  const viewHistory = async (person: BindingPerson) => {
    setBusy(`history:${person.accountId}`); setError(undefined)
    try { setHistory({ person, entries: await loadBindingHistory(identity, person.accountId) }) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作记录读取失败') }
    finally { setBusy(undefined) }
  }

  const submitEmployeeInvite = async () => {
    if (!employeeInviteForm.displayName.trim() || !employeeInviteForm.loginName.trim() || !employeeInviteForm.employeeNo.trim()) {
      setError('请完整填写员工姓名、登录账号和员工编号'); return
    }
    setBusy('employee-invite'); setError(undefined); setNotice(undefined)
    try {
      await createEmployeeInvitation(identity, employeeInviteForm)
      setEmployeeInviteForm(emptyEmployeeInvite); setShowEmployeeInvite(false)
      setNotice('员工邀请资料已提交审核；审批前不会创建账号或岗位权限')
      await reload()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '员工邀请提交失败') }
    finally { setBusy(undefined) }
  }

  const startEmployeeReview = (request: EmployeeInvitationRequest) => {
    setReviewing(request); setReviewAccountId(''); setReviewAssignments([newAssignment(true)])
  }

  const updateReviewAssignment = (index: number, patch: Partial<EmployeeInvitationAssignment>) => {
    setReviewAssignments((current) => current.map((item, itemIndex) => {
      const next = itemIndex === index ? { ...item, ...patch } : item
      if (patch.primary && itemIndex !== index) return { ...next, primary: false }
      return next
    }))
  }

  const approveEmployee = async () => {
    if (!reviewing) return
    if (reviewAssignments.some((item) => !item.orgUnitId || !item.positionId)) {
      setError('请为每一项任职选择组织和岗位'); return
    }
    if (reviewAssignments.filter((item) => item.primary).length !== 1) {
      setError('必须且只能设置一个主岗'); return
    }
    setBusy('employee-review'); setError(undefined); setNotice(undefined)
    try {
      const result = await approveEmployeeInvitation(
        identity, reviewing, reviewAccountId || undefined, reviewAssignments, 'INVITATION_PROFILE_APPROVED',
      )
      setReviewing(undefined); setNotice(result.message)
      const bindingInvitation = approvalAsBindingInvitation(result)
      if (bindingInvitation) setInvitation(bindingInvitation)
      await reload()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '员工邀请审批失败') }
    finally { setBusy(undefined) }
  }

  const rejectEmployee = (request: EmployeeInvitationRequest) => {
    const reason = window.prompt('请输入拒绝原因：')
    if (!reason?.trim()) return
    void command(`employee-reject:${request.id}`, () => rejectEmployeeInvitation(identity, request, reason), '员工邀请已拒绝')
  }

  const selectedPeople = (dashboard?.people ?? []).filter((person) => selected.has(person.accountId))
  const canBulkInvite = selectedPeople.length > 0 && selectedPeople.every((person) => ['UNBOUND', 'EXPIRED', 'REVOKED'].includes(person.bindingStatus) && defaultAssignment(person))
  const canBulkSuspend = selectedPeople.length > 0 && selectedPeople.every((person) => person.bindingStatus === 'ACTIVE')
  const bulkInvite = () => {
    if (!canBulkInvite || !window.confirm(`确认批量生成 ${selectedPeople.length} 个120分钟绑定邀请？`)) return
    const payload = selectedPeople.map((person) => ({ accountId: person.accountId, preferredAssignmentId: defaultAssignment(person)!.id }))
    setBusy('bulk-invite'); setError(undefined)
    void bulkInviteBindings(identity, payload).then(async (result) => {
      setNotice(`已生成 ${result.invitations.length} 个邀请。为防止令牌泄漏，批量邀请请逐一安全发送，不会在刷新后回显。`)
      await reload()
    }).catch((reason) => setError(reason instanceof Error ? reason.message : '批量邀请失败')).finally(() => setBusy(undefined))
  }
  const bulkSuspend = () => {
    if (!canBulkSuspend || !window.confirm(`确认批量暂停 ${selectedPeople.length} 个绑定？该操作已进入二次确认。`)) return
    void command('bulk-suspend', () => bulkSuspendBindings(identity, selectedPeople, 'BULK_SUSPENDED'), '所选绑定已批量暂停')
  }

  const capabilities = dashboard?.capabilities
  return <section className="page-section configuration-page">
    <header className="page-title"><div><span className="eyebrow">WECOM IDENTITY GOVERNANCE</span><h1>企业微信人员绑定</h1><p>人员变动在中台完成：邀请资料审核、人员与多岗位分配、员工自助验证、异常处理与全程审计。</p></div><div className="page-actions">{employeeInvitations?.capabilities.canCreate && <button className="primary" disabled={Boolean(busy)} onClick={() => setShowEmployeeInvite(true)}>一键邀请员工</button>}<span className="source-flag api">UserID 仅显示脱敏指纹</span><button className="secondary" disabled={loading} onClick={() => void reload()}>刷新</button></div></header>
    <div className="inline-warning page-error">人员绑定与企业微信群推送完全独立。绑定启用不会打开任何群机器人、应用消息或日报自动推送开关。</div>
    {requestId && <div className="inline-success page-error">已定位到通知对应的绑定申请。<button className="link-button" onClick={onClearRequest}>查看全部人员绑定</button></div>}
    {notice && <div className="inline-success page-error">{notice}</div>}{error && <div className="inline-error page-error">{error}</div>}
    <div className={styles.metrics}>{[
      ['待员工扫描', dashboard?.counters.waitingScan ?? 0, 'scan'], ['待管理员确认', dashboard?.counters.waitingApproval ?? 0, 'approval'],
      ['已启用', dashboard?.counters.active ?? 0, 'active'], ['已暂停', dashboard?.counters.suspended ?? 0, 'suspended'],
      ['异常绑定', dashboard?.counters.abnormal ?? 0, 'abnormal'],
    ].map(([label, value, tone]) => <article key={String(label)} className={styles.metric}><span>{label}</span><strong className={styles[String(tone)]}>{value}</strong></article>)}</div>
    <article className={`panel config-panel ${styles.employeeInvites}`}><header><div><span className="panel-kicker">EMPLOYEE INVITATION REVIEW</span><h2>员工邀请审核</h2><p>资料提交后不立即开通；审核时可选择新建人员，或关联 sfglzy 等现有账号并一次配置多个岗位。</p></div><span className={styles.pendingBadge}>{employeeInvitations?.items.filter((item) => item.status === 'PENDING_REVIEW').length ?? 0} 待审核</span></header>
      <div className={styles.inviteRequestList}>{employeeInvitations?.items.length ? employeeInvitations.items.map((item) => <div className={styles.inviteRequest} key={item.id}><div><strong>{item.displayName}</strong><span>{item.employeeNo} · {item.loginName}{item.mobile ? ` · ${item.mobile}` : ''}</span><small>{item.note || '未填写备注'} · {item.requestedByName} 于 {displayTime(item.createdAt)} 提交</small></div><b className={`${styles.requestStatus} ${styles[item.status.toLowerCase()]}`}>{item.status === 'PENDING_REVIEW' ? '待审核' : item.status === 'APPROVED' ? '已通过' : item.status === 'REJECTED' ? '已拒绝' : '已取消'}</b><div className={styles.actions}>{item.status === 'PENDING_REVIEW' && employeeInvitations.capabilities.canApprove && <><button onClick={() => startEmployeeReview(item)}>审核并分配岗位</button><button className={styles.danger} onClick={() => rejectEmployee(item)}>拒绝</button></>}{item.status !== 'PENDING_REVIEW' && <small>{item.reviewedByName ? `${item.reviewedByName} · ${displayTime(item.reviewedAt)}` : '—'}{item.decisionReason ? ` · ${item.decisionReason}` : ''}</small>}</div></div>) : <div className="state-card"><b>◇</b><strong>暂无员工邀请申请</strong><span>点击右上角“一键邀请员工”填写资料。</span></div>}</div>
    </article>
    <article className="panel table-panel config-panel"><header><div><span className="panel-kicker">EMPLOYEE BINDINGS</span><h2>人员绑定状态</h2></div><div className="panel-actions">{capabilities?.canManage && <><button className="secondary" disabled={!canBulkInvite || Boolean(busy)} onClick={bulkInvite}>批量生成邀请</button><button className="secondary" disabled={!canBulkSuspend || Boolean(busy)} onClick={bulkSuspend}>批量暂停</button></>}</div></header>
      <div className={styles.filters}><select value={hotel} onChange={(event) => setHotel(event.target.value)}><option value="">全部门店</option>{hotels.map((value) => <option key={value} value={value}>{value}</option>)}</select><select value={department} onChange={(event) => setDepartment(event.target.value)}><option value="">全部部门/岗位</option>{departments.map((value) => <option key={value} value={value}>{value}</option>)}</select><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="员工姓名或中台账号"/><select value={status} onChange={(event) => setStatus(event.target.value)}><option value="">全部状态</option>{Object.entries(statusLabel).map(([key, value]) => <option key={key} value={key}>{value}</option>)}</select></div>
      {loading ? <div className="state-card"><div className="spinner"/><strong>正在读取人员绑定状态</strong></div> : !people.length ? <div className="state-card"><b>◇</b><strong>{requestId ? '通知对应的绑定申请已处理或不在当前权限范围' : '没有符合条件的人员'}</strong><span>{requestId ? '可以返回全部人员绑定继续查看。' : '请调整筛选条件或先维护员工有效任职。'}</span></div> : <div className={styles.table}>
        <div className={styles.head}><span>选择</span><span>门店 / 员工</span><span>账号 / 岗位</span><span>企微状态</span><span>默认企微任职</span><span>验证 / 更新</span><span>UserID 指纹</span><span>操作</span></div>
        {people.map((person) => <div className={styles.row} key={person.accountId}><span><input type="checkbox" checked={selected.has(person.accountId)} onChange={(event) => setSelected((current) => { const next = new Set(current); event.target.checked ? next.add(person.accountId) : next.delete(person.accountId); return next })}/></span><span><strong>{person.hotelCode ? `${person.hotelCode} · ` : ''}{person.hotelName ?? '未归属门店'}</strong><small>{person.employeeName}</small></span><span><strong>{person.loginName}</strong><small>{person.departmentName ? `${person.departmentName} · ` : ''}{person.positionName ?? '无有效任职'}</small></span><span><b className={`${styles.status} ${styles[person.bindingStatus.toLowerCase()]}`}>{statusLabel[person.bindingStatus] ?? person.bindingStatus}</b>{person.expiringSoon && <em>即将过期</em>}<small>{person.reason || person.recommendedAction}</small></span><span>{person.assignments.length > 1 && capabilities?.canManage ? <select value={person.preferredAssignmentId ?? defaultAssignment(person)?.id ?? ''} disabled={busy === `assignment:${person.accountId}`} onChange={(event) => setPreferred(person, event.target.value)}>{person.assignments.map((assignment) => <option key={assignment.id} value={assignment.id}>{[assignment.hotelName, assignment.departmentName, assignment.positionName].filter(Boolean).join(' · ')}</option>)}</select> : <small>{person.defaultAssignment ?? '—'}</small>}</span><span><small>验证：{displayTime(person.lastVerifiedAt)}</small><small>更新：{displayTime(person.updatedAt)}{person.updatedBy ? ` · ${person.updatedBy}` : ' · 系统'}</small></span><span><code>{person.userIdFingerprint ?? '—'}</code></span><span className={styles.actions}>{capabilities?.canManage && ['UNBOUND','EXPIRED','REVOKED'].includes(person.bindingStatus) && <button onClick={() => invite(person)}>发起绑定</button>}{person.bindingStatus === 'WAITING_SCAN' && capabilities?.canManage && <button onClick={() => cancel(person)}>取消邀请</button>}{person.bindingStatus === 'WAITING_APPROVAL' && person.requestStatus === 'AUTHORIZING' && capabilities?.canManage && <button onClick={() => cancel(person)}>取消本次授权</button>}{person.bindingStatus === 'WAITING_APPROVAL' && person.requestStatus === 'PENDING_APPROVAL' && capabilities?.canApprove && <><button onClick={() => approve(person)}>确认启用</button><button className={styles.danger} onClick={() => reject(person)}>拒绝</button></>}{person.bindingStatus === 'ABNORMAL' && person.requestStatus === 'FAILED' && capabilities?.canApprove && <button onClick={() => retry(person)}>技术重试</button>}{person.bindingStatus === 'ABNORMAL' && person.requestStatus === 'CONFLICT' && capabilities?.canApprove && <><button onClick={() => approve(person)}>审批转移</button><button className={styles.danger} onClick={() => reject(person)}>拒绝</button></>}{person.bindingStatus === 'ACTIVE' && capabilities?.canManage && <button onClick={() => changeState(person, 'suspend')}>暂停</button>}{person.bindingStatus === 'SUSPENDED' && capabilities?.canApprove && <button onClick={() => changeState(person, 'resume')}>恢复</button>}{['ACTIVE','SUSPENDED'].includes(person.bindingStatus) && capabilities?.canApprove && <><button onClick={() => startRebind(person)}>重新绑定</button><button className={styles.danger} onClick={() => changeState(person, 'revoke')}>解除</button></>}<button onClick={() => void viewHistory(person)}>操作记录</button></span></div>)}
      </div>}
    </article>
    {showEmployeeInvite && <div className="modal-backdrop"><section className={`modal configuration-modal ${styles.employeeInviteModal}`} role="dialog" aria-modal="true"><header><div><span className="panel-kicker">ONE-CLICK INVITATION</span><h2>一键邀请员工</h2></div><button className="close" onClick={() => setShowEmployeeInvite(false)}>×</button></header><div className={`form-body configuration-form ${styles.employeeInviteForm}`}><label>员工姓名<input value={employeeInviteForm.displayName} onChange={(event) => setEmployeeInviteForm({ ...employeeInviteForm, displayName: event.target.value })}/></label><label>手机号（选填）<input value={employeeInviteForm.mobile} onChange={(event) => setEmployeeInviteForm({ ...employeeInviteForm, mobile: event.target.value })}/></label><label>建议登录账号<input value={employeeInviteForm.loginName} onChange={(event) => setEmployeeInviteForm({ ...employeeInviteForm, loginName: event.target.value })} placeholder="例如 zhangsan"/></label><label>员工编号<input value={employeeInviteForm.employeeNo} onChange={(event) => setEmployeeInviteForm({ ...employeeInviteForm, employeeNo: event.target.value })} placeholder="例如 EMP-001"/></label><label className={styles.fullField}>备注<textarea rows={3} value={employeeInviteForm.note} onChange={(event) => setEmployeeInviteForm({ ...employeeInviteForm, note: event.target.value })} placeholder="入职说明或兼岗测试用途"/></label><div className={`inline-warning ${styles.fullField}`}>提交后仅形成待审核申请，不会立即创建账号、岗位权限或发送企业微信链接。</div></div><footer><button className="secondary" onClick={() => setShowEmployeeInvite(false)}>取消</button><button className="primary" disabled={busy === 'employee-invite'} onClick={() => void submitEmployeeInvite()}>{busy === 'employee-invite' ? '提交中…' : '提交审核'}</button></footer></section></div>}
    {reviewing && employeeInvitations && <div className="modal-backdrop"><section className={`modal configuration-modal ${styles.employeeReviewModal}`} role="dialog" aria-modal="true"><header><div><span className="panel-kicker">ACCOUNT + MULTI-POSITION</span><h2>审核 {reviewing.displayName}</h2></div><button className="close" onClick={() => setReviewing(undefined)}>×</button></header><div className={`form-body configuration-form ${styles.reviewBody}`}><div className={styles.reviewSummary}><strong>{reviewing.employeeNo} · {reviewing.loginName}</strong><span>{reviewing.note || '无补充说明'}</span></div><label className={styles.fullField}>人员/账号归属<select value={reviewAccountId} onChange={(event) => setReviewAccountId(event.target.value)}><option value="">新建员工和中台账号</option>{employeeInvitations.accounts.map((account) => <option value={account.id} key={account.id}>{account.platformAdmin ? '全功能管理员 · ' : ''}{account.loginName} · {account.employeeName || account.displayName}</option>)}</select><small>{reviewAccountId ? '将保留该账号原有全部权限，并叠加以下岗位；适合 sfglzy 兼岗测试。' : '审批通过后才会创建人员档案与账号。'}</small></label><div className={styles.assignmentEditor}><div className={styles.assignmentHeader}><strong>岗位分配（支持一人多岗）</strong><button className="secondary" disabled={reviewAssignments.length >= 8} onClick={() => setReviewAssignments((current) => [...current, newAssignment(false)])}>＋ 增加岗位</button></div>{reviewAssignments.map((assignment, index) => <div className={styles.assignmentCard} key={index}><label>组织<select value={assignment.orgUnitId} onChange={(event) => updateReviewAssignment(index, { orgUnitId: event.target.value })}><option value="">请选择</option>{employeeInvitations.orgUnits.map((item) => <option value={item.id} key={item.id}>{item.name} · {item.unitType}</option>)}</select></label><label>岗位<select value={assignment.positionId} onChange={(event) => updateReviewAssignment(index, { positionId: event.target.value })}><option value="">请选择</option>{employeeInvitations.positions.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>直属上级<select value={assignment.managerAssignmentId ?? ''} onChange={(event) => updateReviewAssignment(index, { managerAssignmentId: event.target.value || undefined })}><option value="">暂不设置</option>{employeeInvitations.managers.map((item) => <option value={item.assignmentId} key={item.assignmentId}>{item.employeeName} · {item.positionName}</option>)}</select></label><label>任职类型<select value={assignment.assignmentType} onChange={(event) => updateReviewAssignment(index, { assignmentType: event.target.value as EmployeeInvitationAssignment['assignmentType'] })}><option value="PERMANENT">正式</option><option value="TEMPORARY">临时</option><option value="ACTING">代理</option></select></label><label className={styles.primaryChoice}><input type="radio" name="primary-position" checked={assignment.primary} onChange={() => updateReviewAssignment(index, { primary: true })}/>主岗</label>{reviewAssignments.length > 1 && <button className={styles.removeAssignment} onClick={() => setReviewAssignments((current) => { const next = current.filter((_, itemIndex) => itemIndex !== index); return next.some((item) => item.primary) ? next : next.map((item, itemIndex) => ({ ...item, primary: itemIndex === 0 })) })}>移除</button>}</div>)}</div><div className={`inline-warning ${styles.fullField}`}>选择 sfglzy 时，平台管理员“所有功能”授权保持不变；所选管理岗位只用于增加任职身份、上下级任务关系及测试视角。</div></div><footer><button className="secondary" onClick={() => setReviewing(undefined)}>取消</button><button className="primary" disabled={busy === 'employee-review'} onClick={() => void approveEmployee()}>{busy === 'employee-review' ? '审批中…' : '批准并生成邀请'}</button></footer></section></div>}
    {invitation && <div className="modal-backdrop"><section className={`modal ${styles.invitation}`} role="dialog" aria-modal="true"><header><div><span className="panel-kicker">120 MINUTES</span><h2>员工绑定邀请</h2></div><button className="close" onClick={() => setInvitation(undefined)}>×</button></header><div className={styles.invitationBody}>{qrData ? <img src={qrData} alt="企业微信人员绑定二维码"/> : <div className="spinner"/>}<strong>请员工使用企业微信扫码或打开链接</strong><small>有效期至 {displayTime(invitation.expiresAt)}。链接过期后不能恢复，只能重新生成。</small><input readOnly value={invitation.enrollmentUrl}/><button className="secondary" onClick={() => void navigator.clipboard.writeText(invitation.enrollmentUrl).then(() => setNotice('邀请链接已复制，请通过安全方式单独发送给员工'))}>复制邀请链接</button></div></section></div>}
    {history && <div className="modal-backdrop"><section className={`modal ${styles.history}`} role="dialog" aria-modal="true"><header><div><span className="panel-kicker">APPEND-ONLY AUDIT</span><h2>{history.person.employeeName} · 操作记录</h2></div><button className="close" onClick={() => setHistory(undefined)}>×</button></header><div className={styles.historyList}>{history.entries.length ? history.entries.map((entry, index) => <div key={`${entry.createdAt}-${index}`}><strong>{entry.action}</strong><span>{entry.actorName ?? '系统'} · {displayTime(entry.createdAt)}</span><small>{entry.summary || '已写入不可覆盖的审计记录'}</small></div>) : <div className="state-card"><strong>暂无操作记录</strong></div>}</div></section></div>}
  </section>
}
