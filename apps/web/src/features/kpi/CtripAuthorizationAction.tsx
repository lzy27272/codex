import { useEffect, useState } from 'react'
import { ApiError } from '../../api/client'
import type { RoleContext } from '../../domain'
import { featureStyles as styles } from '../shared/FeatureUI'
import {
  loadCtripCredentialChallenge,
  loadCtripCredentialStatus,
  requestCtripVerificationCode,
  saveCtripCredentials,
  startCtripCredentialLogin,
  submitCtripVerificationCode,
} from './otaAuthorizationApi'
import {
  canRenderCtripAuthorization,
  type CtripCredentialChallenge,
  type CtripCredentialStatus,
} from './otaAuthorizationPolicy'

type Props = {
  identity: RoleContext
  grantedPermissions: string[]
  hotelId: string
  hotelCode: string
  platformCode: string
}

const operationKey = (action: string) =>
  `ctrip-002-${action}-${crypto.randomUUID()}`

export function CtripAuthorizationAction(props: Props) {
  if (!canRenderCtripAuthorization(props)) return null
  return <CloudCredentialLogin identity={props.identity}/>
}

function CloudCredentialLogin({ identity }: { identity: RoleContext }) {
  const [status, setStatus] = useState<CtripCredentialStatus>()
  const [challenge, setChallenge] = useState<CtripCredentialChallenge>()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [verificationCode, setVerificationCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [statusLoaded, setStatusLoaded] = useState(false)
  const [message, setMessage] = useState('正在读取002携程登录配置…')

  useEffect(() => {
    const controller = new AbortController()
    loadCtripCredentialStatus(identity, controller.signal)
      .then((value) => {
        setStatus(value)
        setStatusLoaded(true)
        setMessage(value.configured
          ? '账号凭证已加密配置，可由云端发起自动登录。'
          : '尚未配置账号凭证，请先填写携程账号和密码。')
      })
      .catch((error) => {
        if (!controller.signal.aborted) {
          setStatusLoaded(true)
          setMessage(errorMessage(error))
        }
      })
    return () => controller.abort()
  }, [identity])

  useEffect(() => {
    if (!challenge || challenge.status !== 'LOGIN_RUNNING') return undefined
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      loadCtripCredentialChallenge(identity, challenge.challengeId, controller.signal)
        .then((value) => {
          setChallenge(value)
          setMessage(challengeMessage(value))
        })
        .catch((error) => {
          if (!controller.signal.aborted) setMessage(errorMessage(error))
        })
    }, 2_000)
    return () => {
      controller.abort()
      window.clearTimeout(timer)
    }
  }, [challenge, identity])

  const saveAndLogin = async () => {
    setBusy(true)
    setMessage('正在加密保存凭证…')
    try {
      const saved = await saveCtripCredentials(identity, username, password, operationKey('save'))
      setStatus(saved)
      setStatusLoaded(true)
      setUsername('')
      setPassword('')
      setChallenge(undefined)
      setMessage('凭证已加密保存，正在启动携程登录识别…')
      const value = await startCtripCredentialLogin(identity, operationKey('login'))
      setChallenge(value)
      setMessage(challengeMessage(value))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setPassword('')
      setBusy(false)
    }
  }

  const login = async () => {
    setBusy(true)
    setMessage('云端正在访问固定携程官方地址并尝试登录…')
    try {
      const value = await startCtripCredentialLogin(identity, operationKey('login'))
      setChallenge(value)
      setMessage(challengeMessage(value))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  const sendCode = async () => {
    if (!challenge) return
    setBusy(true)
    try {
      const value = await requestCtripVerificationCode(
        identity,
        challenge.challengeId,
        operationKey('send-code'),
      )
      setChallenge(value)
      setMessage('验证码发送动作已提交，请查看携程账号绑定的手机或邮箱。')
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  const submitCode = async () => {
    if (!challenge) return
    setBusy(true)
    try {
      const value = await submitCtripVerificationCode(
        identity,
        challenge.challengeId,
        verificationCode,
        operationKey('submit-code'),
      )
      setVerificationCode('')
      setChallenge(value)
      setMessage(challengeMessage(value))
      if (value.authenticated) setStatus(await loadCtripCredentialStatus(identity))
    } catch (error) {
      setMessage(errorMessage(error))
    } finally {
      setVerificationCode('')
      setBusy(false)
    }
  }

  return <section className={styles.section} aria-label="002门店携程云端自动登录试点">
    <header>
      <div>
        <h3>携程云端自动登录试点</h3>
        <p>固定登录地址：<a href="https://ebooking.ctrip.com/" target="_blank" rel="noreferrer">ebooking.ctrip.com</a>。正常流程不再打开本机或远程浏览器。</p>
      </div>
      <span className={styles.badge}>002 · 携程</span>
    </header>
    <div className={styles.formGrid}>
      <label>携程登录账号
        <input autoComplete="off" maxLength={256} placeholder={status?.configured ? '已配置；重新填写可覆盖更新' : '请输入携程账号'} value={username} onChange={(event) => setUsername(event.target.value)}/>
      </label>
      <label>携程登录密码
        <input type="password" autoComplete="new-password" maxLength={256} placeholder={status?.configured ? '已加密保存；重新填写可覆盖更新' : '请输入携程密码'} value={password} onChange={(event) => setPassword(event.target.value)}/>
      </label>
    </div>
    <div className={styles.toolbar}>
      <button className="primary" disabled={busy || !username.trim() || !password} onClick={() => void saveAndLogin()}>{busy ? '处理中…' : status?.configured ? '更新凭证并重新登录识别' : '保存并登录识别'}</button>
      <button className="secondary" disabled={busy || !status?.configured} onClick={() => void login()}>使用已保存凭证重新登录</button>
      <span>{!statusLoaded ? '凭证：读取中' : status?.configured ? `凭证：已加密配置${status.updatedAt ? `（${new Date(status.updatedAt).toLocaleString('zh-CN')}）` : ''}` : status ? '凭证：未配置' : '凭证：状态读取失败'}</span>
      <span>会话：{!statusLoaded ? '读取中' : status?.sessionStatus === 'AUTHORIZED' ? '已授权' : status ? '需登录' : '状态读取失败'}</span>
    </div>
    {challenge?.status === 'OTP_REQUIRED' && <div className={styles.formGrid}>
      <label>短信或邮箱验证码
        <input inputMode="numeric" autoComplete="one-time-code" maxLength={8} placeholder="输入4至8位验证码" value={verificationCode} onChange={(event) => setVerificationCode(event.target.value.replace(/\D/gu, ''))}/>
      </label>
      <div className={styles.toolbar}>
        <button className="secondary" disabled={busy} onClick={() => void sendCode()}>发送验证码</button>
        <button className="primary" disabled={busy || !/^[0-9]{4,8}$/u.test(verificationCode)} onClick={() => void submitCode()}>提交验证码并继续</button>
      </div>
    </div>}
    {challenge?.status === 'INTERACTIVE_VERIFICATION_REQUIRED' && challenge.fallbackAuthorizationUrl && <div className={styles.locked}>携程要求滑块、扫码或设备确认，系统不会尝试绕过。 <a href={challenge.fallbackAuthorizationUrl} target="_blank" rel="noreferrer">打开受控人工验证</a></div>}
    <div className={styles.locked}>{message}</div>
    <small>账号、密码和验证码只用于固定携程登录流程；密码以 AES-256-GCM 加密落盘，验证码不落盘，审计仅记录动作和结果。</small>
  </section>
}

function challengeMessage(value: CtripCredentialChallenge): string {
  switch (value.status) {
    case 'AUTHENTICATED': return '携程登录已识别，002门店身份已核验，会话已加密保存，可继续抓取数据。'
    case 'OTP_REQUIRED': return '携程要求验证码，请先发送验证码，再在中台提交。'
    case 'INTERACTIVE_VERIFICATION_REQUIRED': return '携程要求滑块、扫码或设备确认，需要进入受控人工验证；系统不会绕过平台验证。'
    case 'FAILED': return value.reasonCode === 'OTA_CTRIP_CLOUD_HOTEL_IDENTITY_CONFIRMATION_REQUIRED'
      ? '已识别002携程门店身份，等待平台管理员确认绑定；为安全起见，本次身份发现不会保存登录会话。'
      : '携程拒绝了本次登录，请检查账号状态或更新密码后重试。'
    default: return '登录请求已提交，正在识别携程登录结果。'
  }
}

function errorMessage(error: unknown): string {
  const code = error instanceof ApiError
    ? String(error.problem?.code ?? error.message)
    : error instanceof Error ? error.message : ''
  if (code.includes('AUTHORIZATION_NOT_ENABLED')) return '系统尚未启用002携程自动登录，请联系平台管理员。'
  if (error instanceof ApiError && error.status === 403) return '当前账号没有002携程授权权限，请联系平台管理员。'
  if (code.includes('NOT_CONFIGURED')) return '请先保存携程账号和密码。'
  if (code.includes('ALREADY_ACTIVE')) return '已有一次携程登录正在处理中，请稍后刷新。'
  if (code.includes('HOTEL_IDENTITY_DISCOVERY_REQUIRED')) return '002携程门店身份尚未绑定；请使用“保存并登录识别”完成首次身份识别。'
  if (code.includes('INVALID')) return '输入内容或携程返回数据格式无效，请检查后重试。'
  const safeCode = /^[A-Z][A-Z0-9_]{1,79}$/u.test(code) ? code : null
  const status = error instanceof ApiError ? `HTTP ${error.status}` : null
  const diagnostic = [safeCode, status].filter(Boolean).join(' / ')
  return `携程云端登录暂未完成${diagnostic ? `（${diagnostic}）` : ''}；系统未记录或回显账号密码。`
}
