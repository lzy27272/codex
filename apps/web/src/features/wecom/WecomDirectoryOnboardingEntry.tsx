import { useEffect, useMemo, useState } from 'react'
import { product } from '../../product'
import type { WecomBindingEntry } from './bindingEntryRoute'
import {
  exchangeDirectoryOnboarding,
  loadDirectoryOnboardingContext,
  startDirectoryOnboarding,
  submitDirectoryOnboarding,
  type DirectoryOnboardingContext,
} from './directoryOnboardingApi'

type Selection = { orgUnitId: string; positionId: string }

const oauthErrors = {
  OAUTH_SESSION_INVALID: {
    title: '验证会话已失效',
    message: '本次入职邀请已过期、已使用或验证会话无效。请从企业微信重新打开最新邀请。',
  },
  OAUTH_IDENTITY_MISMATCH: {
    title: '企业微信身份不匹配',
    message: '本次验证身份与邀请对应人员不一致，系统未创建绑定。请联系管理员核对人员与企业身份。',
  },
  OAUTH_PROVIDER_UNAVAILABLE: {
    title: '企业微信暂时不可用',
    message: '企业微信身份服务暂时无法完成验证。请稍后从原邀请重新进入；持续失败时联系管理员重试。',
  },
  OAUTH_VERIFICATION_FAILED: {
    title: '身份验证未完成',
    message: '企业微信未能完成本次身份验证，系统未创建绑定。请从原邀请重试或联系管理员。',
  },
} as const

export function WecomDirectoryOnboardingEntry({ entry, onReturn }: { entry: WecomBindingEntry; onReturn: () => void }) {
  const [sessionToken, setSessionToken] = useState<string>()
  const [context, setContext] = useState<DirectoryOnboardingContext>()
  const [hotelId, setHotelId] = useState('')
  const [selection, setSelection] = useState<Selection>({ orgUnitId: '', positionId: '' })
  const [displayName, setDisplayName] = useState('')
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [submittedStatus, setSubmittedStatus] = useState<'PENDING_APPROVAL' | 'CONFLICT'>()
  const [busy, setBusy] = useState(Boolean(entry.exchangeCode))
  const [error, setError] = useState(entry.securityError)
  const oauthError = entry.errorCode ? oauthErrors[entry.errorCode] : undefined

  useEffect(() => {
    if (!entry.exchangeCode) return
    let active = true
    setBusy(true); setError(undefined)
    void exchangeDirectoryOnboarding(entry.exchangeCode)
      .then(async (exchange) => ({ exchange, context: await loadDirectoryOnboardingContext(exchange.sessionToken) }))
      .then(({ exchange, context: value }) => {
        if (!active) return
        setSessionToken(exchange.sessionToken)
        setContext(value)
        setDisplayName(value.displayName ?? '')
        setLoginName(value.loginName ?? '')
        const firstHotel = value.hotels[0]
        setHotelId(firstHotel?.id ?? '')
      })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : '企业微信身份验证失败') })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [entry.exchangeCode])

  const hotel = context?.hotels.find((item) => item.id === hotelId)
  const positionOptions = useMemo(() => (hotel?.departments ?? []).flatMap((department) => department.positions.map((position) => ({
    orgUnitId: department.id, positionId: position.id, label: `${department.name} · ${position.name}`,
  }))), [hotel])
  const accountValid = !context?.requiresAccountRegistration || (
    displayName.trim().length > 0
    && /^[A-Za-z0-9][A-Za-z0-9._-]{2,119}$/.test(loginName.trim())
    && password.length >= 10 && password.length <= 128
    && password === passwordConfirmation
  )

  const start = async () => {
    if (!entry.token) return
    setBusy(true); setError(undefined)
    try { window.location.assign(await startDirectoryOnboarding(entry.token)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '无法打开企业微信身份验证'); setBusy(false) }
  }

  const submit = async () => {
    if (!context || !sessionToken || !selection.orgUnitId || !selection.positionId || !accountValid) return
    setBusy(true); setError(undefined)
    try {
      const result = await submitDirectoryOnboarding(
        sessionToken, displayName.trim(), loginName.trim(), password, passwordConfirmation,
        selection.orgUnitId, selection.positionId, context.rowVersion,
      )
      setPassword(''); setPasswordConfirmation('')
      setSubmittedStatus(result.status === 'CONFLICT' ? 'CONFLICT' : 'PENDING_APPROVAL')
      setSessionToken(undefined)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '入职申请提交失败') }
    finally { setBusy(false) }
  }

  if (oauthError) return <main className="wecom-onboarding-shell">
    <header className="wecom-onboarding-brand"><span>四</span><strong>{product.name}</strong></header>
    <section className="wecom-onboarding-card" aria-live="polite">
      <div className="inline-error"><strong>{oauthError.title}</strong><p>{oauthError.message}</p></div>
      <button className="secondary" onClick={onReturn}>返回中台登录</button>
      <small className="onboarding-privacy">系统未创建人员绑定，也不会开启企业微信群推送；地址栏中的验证结果已清除。</small>
    </section>
  </main>

  if (submittedStatus || context?.status === 'PENDING_APPROVAL' || context?.status === 'CONFLICT') {
    const conflict = submittedStatus === 'CONFLICT' || context?.status === 'CONFLICT'
    return <main className="wecom-onboarding-shell">
    <header className="wecom-onboarding-brand"><span>四</span><strong>{product.name}</strong></header>
    <section className="wecom-onboarding-card submitted" aria-live="polite">
      <div className="onboarding-success" aria-hidden="true">{conflict ? '!' : '✓'}</div><h1>申请已提交</h1><h2>{conflict ? '身份关联异常，等待管理员核对' : '等待管理员确认'}</h2>
      <p>{conflict ? '系统发现该企业微信身份已有受控关联记录，人事审核前不会启用；您无需重复提交。' : '行政人事审核通过后，注册账号、任职权限与企业微信绑定会同时启用。'}</p>
      <dl><div><dt>门店</dt><dd>{hotel?.name ?? '—'}</dd></div><div><dt>岗位</dt><dd>{positionOptions.find((item) => item.positionId === selection.positionId)?.label ?? '—'}</dd></div><div><dt>状态</dt><dd>{conflict ? '异常待核对' : '待审核'}</dd></div></dl>
      <button className="primary" onClick={onReturn}>返回企业微信</button>
    </section>
  </main>
  }

  return <main className="wecom-onboarding-shell">
    <header className="wecom-onboarding-brand"><span>四</span><strong>{product.name}</strong></header>
    <section className="wecom-onboarding-card" aria-live="polite">
      <h1>{context ? '完成入职绑定' : '企业微信入职验证'}</h1>
      <p>{context ? '企业微信身份已验证，请注册账号并补充任职信息。' : '系统只会核验您本人的企业微信身份，不公开其他员工信息。'}</p>
      {context && <>
        <div className="onboarding-person"><i aria-hidden="true">人</i><span><strong>{context.displayName}</strong><small>企业微信成员</small></span><b>● 身份已验证</b></div>
        {context.requiresAccountRegistration && <div className="onboarding-registration-fields">
          <label>个人姓名<input value={displayName} maxLength={120} autoComplete="name" onChange={(event) => setDisplayName(event.target.value)} placeholder="请输入真实姓名" /></label>
          <label>登录账号<input value={loginName} maxLength={120} autoCapitalize="none" autoComplete="username" onChange={(event) => setLoginName(event.target.value)} placeholder="3位以上字母、数字、点、下划线或短横线" /></label>
          <label>登录密码<input type="password" value={password} minLength={10} maxLength={128} autoComplete="new-password" onChange={(event) => setPassword(event.target.value)} placeholder="10至128位" /></label>
          <label>确认密码<input type="password" value={passwordConfirmation} minLength={10} maxLength={128} autoComplete="new-password" onChange={(event) => setPasswordConfirmation(event.target.value)} placeholder="请再次输入密码" /></label>
          {passwordConfirmation && password !== passwordConfirmation && <small className="field-error">两次输入的密码不一致</small>}
        </div>}
        <label>选择门店<select value={hotelId} onChange={(event) => { setHotelId(event.target.value); setSelection({ orgUnitId: '', positionId: '' }) }}><option value="">请选择门店</option>{context.hotels.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>选择岗位<select value={`${selection.orgUnitId}:${selection.positionId}`} disabled={!hotelId} onChange={(event) => { const [orgUnitId, positionId] = event.target.value.split(':'); setSelection({ orgUnitId, positionId }) }}><option value=":">请选择岗位</option>{positionOptions.map((item) => <option key={`${item.orgUnitId}:${item.positionId}`} value={`${item.orgUnitId}:${item.positionId}`}>{item.label}</option>)}</select></label>
        <small className="onboarding-note">提交后由行政人事或行政人事主管审核；审核前账号不可登录，也不会开通岗位权限。</small>
      </>}
      {busy && !context && <div className="wecom-entry-progress"><div className="spinner"/><strong>正在验证企业微信身份</strong></div>}
      {error && <div className="inline-error">{error}</div>}
      {context ? <button className="primary" disabled={busy || !selection.positionId || !accountValid} onClick={() => void submit()}>{busy ? '正在提交…' : '提交审核'}</button>
        : entry.token && !busy ? <button className="primary" onClick={() => void start()}>使用企业微信验证身份</button> : null}
      {error && <button className="secondary" onClick={onReturn}>返回</button>}
      <small className="onboarding-privacy">绑定成功不会自动开启企业微信群推送；UserID不会在页面、通知或审计中显示。</small>
    </section>
  </main>
}
