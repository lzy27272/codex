import { useEffect, useState } from 'react'
import { product } from '../../product'
import type { WecomBindingEntry } from './bindingEntryRoute'
import { previewBindingInvitation, startBindingEnrollment, type EnrollmentPreview } from './bindingEnrollmentApi'
import { WecomDirectoryOnboardingEntry } from './WecomDirectoryOnboardingEntry'

function displayTime(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}

export function WecomBindingEnrollmentEntry({ entry, onReturn }: { entry: WecomBindingEntry; onReturn: () => void }) {
  if (entry.flow === 'directory') return <WecomDirectoryOnboardingEntry entry={entry} onReturn={onReturn} />
  const [preview, setPreview] = useState<EnrollmentPreview>()
  const [error, setError] = useState(entry.securityError)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!entry.token) return
    let active = true
    void previewBindingInvitation(entry.token).then((value) => {
      if (active) setPreview(value)
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : '绑定邀请读取失败')
    })
    return () => { active = false }
  }, [entry.token])

  const start = async () => {
    if (!entry.token) return
    setBusy(true); setError(undefined)
    try {
      const authorizationUri = await startBindingEnrollment(entry.token)
      window.location.assign(authorizationUri)
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '无法打开企业微信身份验证')
      setBusy(false)
    }
  }

  if (entry.result) return <main className="login-screen wecom-entry-screen">
    <section className="login-brand"><div className="login-logo">四</div><div><span className="eyebrow">WECOM IDENTITY RESULT</span><h1>{product.name}</h1><p>企业微信身份验证结果只与您本人相关，不显示其他员工或完整 UserID。</p></div></section>
    <section className="login-card wecom-entry-card"><header><span className="panel-kicker">人员绑定</span><h2>{entry.result === 'conflict' ? '身份待管理员核对' : entry.result === 'failed' ? '账号或任职状态异常' : entry.result === 'expired' ? '邀请已过期' : '验证完成，等待确认'}</h2><p>{entry.result === 'conflict' ? '系统发现该企业微信身份已有关联记录，管理员审批前不会启用。' : entry.result === 'failed' ? '当前中台账号、员工或任职已失效，系统没有创建绑定。请联系管理员处理。' : entry.result === 'expired' ? '过期邀请不能恢复，请联系管理员重新生成120分钟邀请。' : '集团CEO或平台管理员确认后，绑定才会正式启用。'}</p></header><div className="inline-warning">绑定成功不等于开启企业微信群推送；群推送开关保持独立。</div><button className="secondary wecom-entry-action" onClick={onReturn}>返回中台登录</button></section>
  </main>

  return <main className="login-screen wecom-entry-screen">
    <section className="login-brand"><div className="login-logo">四</div><div><span className="eyebrow">WECOM IDENTITY ENROLLMENT</span><h1>{product.name}</h1><p>请在企业微信内核对本人信息并确认。邀请令牌已从浏览器地址栏清除，不会写入本地存储。</p></div></section>
    <section className="login-card wecom-entry-card" aria-live="polite">
      <header><span className="panel-kicker">企业微信人员绑定</span><h2>{error ? '无法继续绑定' : preview ? '请确认本人信息' : '正在读取邀请'}</h2><p>{error ?? preview?.message ?? '请稍候，系统正在验证邀请有效期。'}</p></header>
      {preview && <div className="binding-identity-card"><strong>{preview.employeeName}</strong><span>{[preview.hotelName, preview.departmentName, preview.positionName].filter(Boolean).join(' · ')}</span><small>有效期至 {displayTime(preview.expiresAt)}</small>{preview.expiringSoon && <b>即将过期</b>}</div>}
      {error && <div className="inline-error">{error}</div>}
      {!error && !preview && <div className="wecom-entry-progress"><div className="spinner" /><strong>正在验证邀请</strong></div>}
      {preview?.canStart && <button className="primary wecom-entry-action" disabled={busy} onClick={() => void start()}>{busy ? '正在打开企业微信…' : '使用企业微信确认身份'}</button>}
      {(!preview?.canStart || error) && <button className="secondary wecom-entry-action" onClick={onReturn}>返回中台登录</button>}
      <small>系统不会在页面、接口或审计记录中显示完整 UserID，也不会因本次绑定自动开启群推送。</small>
    </section>
  </main>
}
