import { useEffect, useMemo, useState } from 'react'
import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'

type HotelOption = { id: string; code: string; name: string }
type PermissionOption = { permissionCode: string; label: string; category: string; delegable: boolean }
type ProfileSummary = {
  profileId?: string
  draftVersion?: number
  draftRowVersion?: number
  publishedVersion?: number
  status: string
  draftDirty?: boolean
  permissionCodes: string[]
  authorizationScopeType?: 'SELF' | 'ORG_UNIT' | 'ORG_TREE' | 'TENANT'
  wecomSelfSelectable?: boolean
}
export type PositionSummary = {
  id: string
  name: string
  status: string
  rowVersion: number
  appliesToAllHotels: boolean
  applicableHotels: HotelOption[]
  activeAssignmentCount: number
  profile: ProfileSummary
}

type PositionDraft = {
  name: string
  appliesToAllHotels: boolean
  applicableHotelIds: string[]
  copyFromPositionId: string
  permissionCodes: string[]
  authorizationScopeType: 'SELF' | 'ORG_UNIT' | 'ORG_TREE' | 'TENANT'
  wecomSelfSelectable: boolean
}

const emptyDraft: PositionDraft = {
  name: '', appliesToAllHotels: true, applicableHotelIds: [], copyFromPositionId: '', permissionCodes: [],
  authorizationScopeType: 'SELF', wecomSelfSelectable: false,
}

function profileVersionLabel(profile: ProfileSummary) {
  if (!profile.publishedVersion) return `草稿 V${profile.draftVersion ?? 1}`
  if (profile.draftDirty) return `已发布 V${profile.publishedVersion} · 草稿 V${profile.draftVersion ?? profile.publishedVersion + 1}`
  return `已发布 V${profile.publishedVersion}`
}

function asPositionList(payload: unknown): PositionSummary[] {
  if (Array.isArray(payload)) return payload as PositionSummary[]
  if (payload && typeof payload === 'object') {
    const value = payload as Record<string, unknown>
    for (const key of ['items','data','content']) if (Array.isArray(value[key])) return value[key] as PositionSummary[]
  }
  return []
}

function PositionModal({ title, draft, hotels, positions, options, saving, configureProfile, onChange, onClose, onSave }: {
  title: string
  draft: PositionDraft
  hotels: HotelOption[]
  positions: PositionSummary[]
  options: PermissionOption[]
  saving: boolean
  configureProfile: boolean
  onChange: (next: PositionDraft) => void
  onClose: () => void
  onSave: () => void
}) {
  const groups = useMemo(() => {
    const result = new Map<string, PermissionOption[]>()
    options.forEach((option) => result.set(option.category || '其他功能', [...(result.get(option.category || '其他功能') ?? []), option]))
    return [...result.entries()]
  }, [options])
  const copyProfile = (positionId: string) => {
    const source = positions.find((position) => position.id === positionId)
    onChange({
      ...draft,
      copyFromPositionId: positionId,
      permissionCodes: source?.profile.permissionCodes ?? draft.permissionCodes,
      authorizationScopeType: source?.profile.authorizationScopeType ?? draft.authorizationScopeType,
      wecomSelfSelectable: source?.profile.wecomSelfSelectable ?? draft.wecomSelfSelectable,
    })
  }
  return <div className="modal-backdrop"><section className="modal position-editor-modal" role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">POSITION SETTINGS</span><h2>{title}</h2></div><button className="close" onClick={onClose}>×</button></header>
    <div className="position-editor-body">
      <div className="form-grid">
        <label>岗位名称<input autoFocus value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="例如 前厅接待" /></label>
        {configureProfile && <><label>功能方案来源<select value={draft.copyFromPositionId} onChange={(event) => copyProfile(event.target.value)}><option value="">基础空白方案</option>{positions.map((position) => <option key={position.id} value={position.id}>复制“{position.name}”</option>)}</select></label>
        <label>数据范围<select value={draft.authorizationScopeType} onChange={(event) => onChange({ ...draft, authorizationScopeType: event.target.value as PositionDraft['authorizationScopeType'] })}><option value="SELF">仅本人</option><option value="ORG_UNIT">当前部门</option><option value="ORG_TREE">当前门店/组织树</option><option value="TENANT">集团全部范围</option></select></label></>}
        <label className="checkbox-label"><input type="checkbox" checked={draft.appliesToAllHotels} onChange={(event) => onChange({ ...draft, appliesToAllHotels: event.target.checked, applicableHotelIds: event.target.checked ? [] : draft.applicableHotelIds })} />适用于全部门店</label>
        {configureProfile && <label className="checkbox-label"><input type="checkbox" checked={draft.wecomSelfSelectable} onChange={(event) => onChange({ ...draft, wecomSelfSelectable: event.target.checked })} />允许企业微信新员工申请该岗位</label>}
      </div>
      {!draft.appliesToAllHotels && <fieldset className="position-hotel-picker"><legend>适用门店</legend>{hotels.map((hotel) => <label key={hotel.id}><input type="checkbox" checked={draft.applicableHotelIds.includes(hotel.id)} onChange={(event) => onChange({ ...draft, applicableHotelIds: event.target.checked ? [...draft.applicableHotelIds, hotel.id] : draft.applicableHotelIds.filter((id) => id !== hotel.id) })} />{hotel.code ? `${hotel.code} · ` : ''}{hotel.name}</label>)}</fieldset>}
      {configureProfile ? <section className="position-permission-picker"><header><h3>功能与操作权限</h3><small>保护权限不可下放；未明确允许的新增权限默认关闭。</small></header>{groups.map(([group, permissions]) => <div key={group}><h4>{group}</h4><div>{permissions.map((permission) => <label className={!permission.delegable ? 'protected' : ''} key={permission.permissionCode}><input type="checkbox" disabled={!permission.delegable} checked={draft.permissionCodes.includes(permission.permissionCode)} onChange={(event) => onChange({ ...draft, permissionCodes: event.target.checked ? [...draft.permissionCodes, permission.permissionCode] : draft.permissionCodes.filter((code) => code !== permission.permissionCode) })} /><span>{permission.label}</span>{!permission.delegable && <small>受保护</small>}</label>)}</div></div>)}</section> : <div className="inline-warning">岗位名称与适用门店在此维护；功能权限请在岗位详情中保存草稿并单独发布。</div>}
    </div>
    <footer><button className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={saving || !draft.name.trim() || (!draft.appliesToAllHotels && draft.applicableHotelIds.length === 0)} onClick={onSave}>{saving ? '保存中…' : '保存岗位'}</button></footer>
  </section></div>
}

export function PositionAdministration({ identity, canManage, canPublish, hotels, positionId }: { identity: RoleContext; canManage: boolean; canPublish: boolean; hotels: HotelOption[]; positionId?: string }) {
  const [positions, setPositions] = useState<PositionSummary[]>([])
  const [deleted, setDeleted] = useState<PositionSummary[]>([])
  const [options, setOptions] = useState<PermissionOption[]>([])
  const [view, setView] = useState<'active' | 'deleted'>('active')
  const [selectedId, setSelectedId] = useState<string>()
  const [draftPermissions, setDraftPermissions] = useState<string[]>([])
  const [draftScope, setDraftScope] = useState<PositionDraft['authorizationScopeType']>('SELF')
  const [draftSelfSelectable, setDraftSelfSelectable] = useState(false)
  const [editor, setEditor] = useState<{ mode: 'create' | 'edit'; draft: PositionDraft; position?: PositionSummary }>()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()

  const reload = async () => {
    setLoading(true); setError(undefined)
    try {
      const [activeRaw, deletedRaw, optionRaw] = await Promise.all([
        apiRequest<unknown>('/org/positions', identity),
        canManage ? apiRequest<unknown>('/org/positions/deleted', identity) : Promise.resolve([]),
        apiRequest<unknown>('/org/positions/function-options', identity),
      ])
      const active = asPositionList(activeRaw)
      setPositions(active); setDeleted(asPositionList(deletedRaw))
      setOptions(Array.isArray(optionRaw) ? optionRaw as PermissionOption[] : ((optionRaw as { items?: PermissionOption[] })?.items ?? []))
      setSelectedId((current) => active.some((item) => item.id === current) ? current : active[0]?.id)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '岗位配置加载失败') }
    finally { setLoading(false) }
  }
  useEffect(() => { void reload() }, [identity.key, canManage])
  useEffect(() => {
    if (!positionId) return
    if (positions.some((position) => position.id === positionId)) {
      setView('active'); setSelectedId(positionId)
    } else if (deleted.some((position) => position.id === positionId)) {
      setView('deleted'); setSelectedId(positionId)
    }
  }, [positionId, positions, deleted])
  const source = (view === 'active' ? positions : deleted)
  const selected = source.find((position) => position.id === selectedId) ?? source[0]
  useEffect(() => {
    setDraftPermissions(selected?.profile.permissionCodes ?? [])
    setDraftScope(selected?.profile.authorizationScopeType ?? 'SELF')
    setDraftSelfSelectable(selected?.profile.wecomSelfSelectable ?? false)
  }, [selected?.id, selected?.profile.draftVersion, selected?.profile.draftRowVersion])

  const run = async (operation: () => Promise<unknown>, success: string) => {
    setSaving(true); setError(undefined); setNotice(undefined)
    try { await operation(); setNotice(success); await reload(); return true }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败'); return false }
    finally { setSaving(false) }
  }
  const createOrEdit = async () => {
    if (!editor) return
    const body = {
      name: editor.draft.name.trim(), appliesToAllHotels: editor.draft.appliesToAllHotels,
      applicableHotelIds: editor.draft.applicableHotelIds,
    }
    const currentHotelIds = editor.position?.applicableHotels.map((hotel) => hotel.id).sort() ?? []
    const requestedHotelIds = [...body.applicableHotelIds].sort()
    const applicabilityChanged = editor.mode === 'edit' && (
      editor.position!.appliesToAllHotels !== body.appliesToAllHotels
      || (!body.appliesToAllHotels && currentHotelIds.join('|') !== requestedHotelIds.join('|'))
    )
    if (editor.mode === 'edit' && applicabilityChanged) {
      type ApplicabilityImpact = {
        rowVersion: number
        activeAssignmentCount?: number
        affectedEmployeeCount?: number
        affectedActiveBindingCount?: number
        affectedRoleGrantCount?: number
        removedHotels?: HotelOption[]
      }
      let impact: ApplicabilityImpact
      setSaving(true); setError(undefined); setNotice(undefined)
      try {
        impact = await apiRequest<ApplicabilityImpact>(`/org/positions/${editor.position!.id}/impact-preview`, identity, {
          method: 'POST',
          body: JSON.stringify({
            operation: 'UPDATE_APPLICABILITY',
            appliesToAllHotels: body.appliesToAllHotels,
            applicableHotelIds: body.applicableHotelIds,
          }),
        })
      } catch (reason) {
        setError(reason instanceof Error ? `无法取得适用门店影响预览：${reason.message}` : '无法取得适用门店影响预览，已阻止本次修改')
        return
      } finally {
        setSaving(false)
      }
      if (impact.rowVersion !== editor.position!.rowVersion) {
        setError('岗位信息已被其他管理员修改，请刷新后重新操作')
        return
      }
      if (impact.removedHotels?.length) {
        const hotelNames = impact.removedHotels.map((hotel) => hotel.code ? `${hotel.code} · ${hotel.name}` : hotel.name).join('、')
        if (!window.confirm(`确认缩小“${editor.position!.name}”的适用门店？\n\n移除：${hotelNames}\n将暂停 ${impact.activeAssignmentCount ?? 0} 个有效任职、${impact.affectedRoleGrantCount ?? 0} 个岗位来源权限、${impact.affectedActiveBindingCount ?? 0} 个企业微信绑定，影响 ${impact.affectedEmployeeCount ?? 0} 名员工；群推送开关不变。`)) return
      }
    }
    const done = editor.mode === 'create'
      ? await run(() => apiRequest('/org/positions', identity, { method: 'POST', body: JSON.stringify({ ...body, copyFromPositionId: editor.draft.copyFromPositionId || null, permissionCodes: editor.draft.permissionCodes, authorizationScopeType: editor.draft.authorizationScopeType, wecomSelfSelectable: editor.draft.wecomSelfSelectable }) }), '岗位已创建，功能方案为草稿')
      : await run(() => apiRequest(`/org/positions/${editor.position!.id}`, identity, { method: 'PUT', body: JSON.stringify({ ...body, expectedVersion: editor.position!.rowVersion }) }), '岗位信息已更新')
    if (done) setEditor(undefined)
  }
  const saveProfile = () => selected && run(() => apiRequest(`/org/positions/${selected.id}/profile/draft`, identity, { method: 'PUT', body: JSON.stringify({ expectedProfileVersion: selected.profile.draftRowVersion ?? 0, permissionCodes: draftPermissions, authorizationScopeType: draftScope, wecomSelfSelectable: draftSelfSelectable }) }), '岗位功能草稿已保存')
  const publishProfile = async () => {
    if (!selected) return
    type PublishImpact = { affectedEmployeeCount?: number; addedPermissionCodes?: string[]; removedPermissionCodes?: string[]; blockedPermissionCodes?: string[] }
    let impact: PublishImpact
    setSaving(true); setError(undefined)
    try {
      impact = await apiRequest<PublishImpact>(`/org/positions/${selected.id}/impact-preview`, identity, { method: 'POST', body: JSON.stringify({ operation: 'PUBLISH_PROFILE', permissionCodes: draftPermissions }) })
    } catch (reason) {
      setError(reason instanceof Error ? `无法取得发布影响预览：${reason.message}` : '无法取得发布影响预览，已阻止本次发布')
      return
    } finally {
      setSaving(false)
    }
    if (impact.blockedPermissionCodes?.length) { setError(`包含受保护权限，不能发布：${impact.blockedPermissionCodes.join('、')}`); return }
    if (!window.confirm(`确认发布“${selected.name}”功能方案？\n\n预计影响 ${impact.affectedEmployeeCount ?? selected.activeAssignmentCount} 名在职员工；权限将在重新读取身份后生效。`)) return
    await run(() => apiRequest(`/org/positions/${selected.id}/profile/publish`, identity, { method: 'POST', body: JSON.stringify({ expectedProfileVersion: selected.profile.draftRowVersion ?? 0, expectedPositionVersion: selected.rowVersion }) }), '岗位功能方案已发布')
  }
  const deletePosition = async (position: PositionSummary) => {
    let impact: { rowVersion?: number; affectedEmployeeCount?: number; activeAssignmentCount?: number; affectedActiveBindingCount?: number; affectedRoleGrantCount?: number }
    setSaving(true); setError(undefined); setNotice(undefined)
    try {
      impact = await apiRequest<{ rowVersion?: number; affectedEmployeeCount?: number; activeAssignmentCount?: number; affectedActiveBindingCount?: number; affectedRoleGrantCount?: number }>(`/org/positions/${position.id}/impact-preview`, identity, { method: 'POST', body: JSON.stringify({ operation: 'DELETE' }) })
    } catch (reason) {
      setError(reason instanceof Error ? `无法取得删除影响预览：${reason.message}` : '无法取得删除影响预览，已阻止本次删除')
      return
    } finally {
      setSaving(false)
    }
    if (impact.rowVersion !== undefined && impact.rowVersion !== position.rowVersion) {
      setError('岗位信息已被其他管理员修改，请刷新后重新操作')
      return
    }
    if (!window.confirm(`确认删除“${position.name}”并移入“已删除”？\n\n将暂停 ${impact.activeAssignmentCount ?? position.activeAssignmentCount} 个有效任职、${impact.affectedRoleGrantCount ?? 0} 个岗位来源权限、${impact.affectedActiveBindingCount ?? 0} 个企业微信绑定，影响 ${impact.affectedEmployeeCount ?? 0} 名员工；历史任务和日报保留。`)) return
    if (await run(() => apiRequest(`/org/positions/${position.id}?expectedVersion=${position.rowVersion}`, identity, { method: 'DELETE' }), `“${position.name}”已移入“已删除”`)) setView('deleted')
  }
  const restorePosition = async (position: PositionSummary) => {
    const restoreAssignments = window.confirm(`恢复“${position.name}”时，是否同时尝试恢复原有效任职？\n\n选择“取消”仍可继续恢复岗位，但不恢复任职。`)
    if (!window.confirm(`确认恢复岗位“${position.name}”？${restoreAssignments ? '\n系统会逐项校验并恢复仍有效的原任职。' : '\n原任职继续保持暂停。'}`)) return
    if (await run(() => apiRequest(`/org/positions/${position.id}/restore`, identity, { method: 'POST', body: JSON.stringify({ expectedVersion: position.rowVersion, restoreAssignments }) }), `“${position.name}”已恢复`)) setView('active')
  }
  const permanentlyDelete = async (position: PositionSummary) => {
    const confirmation = window.prompt(`永久删除后不能找回；历史记录只保留“${position.name}（已删除）”。\n\n请输入岗位名称确认：`)
    if (confirmation !== position.name) return
    await run(() => apiRequest(`/org/positions/${position.id}/permanent?expectedVersion=${position.rowVersion}`, identity, { method: 'DELETE' }), `“${position.name}”已永久删除`)
  }

  if (loading) return <div className="state-card"><div className="spinner"/><strong>正在读取岗位与功能方案</strong></div>
  return <>
    {notice && <div className="inline-success page-success">{notice}</div>}{error && <div className="inline-error page-error">{error}</div>}
    <article className="panel position-administration">
      <header><div><span className="panel-kicker">POSITION ACCESS</span><h2>岗位与功能配置</h2></div>{canManage && <div className="panel-actions"><button className="secondary" onClick={() => setView(view === 'active' ? 'deleted' : 'active')}>{view === 'active' ? `已删除（${deleted.length}）` : '返回岗位列表'}</button>{view === 'active' && <button className="primary" onClick={() => setEditor({ mode: 'create', draft: emptyDraft })}>＋ 新建岗位</button>}</div>}</header>
      <div className="position-admin-layout">
        <aside className="position-list"><label>搜索岗位<input placeholder="输入岗位名称" onChange={(event) => { const match = source.find((item) => item.name.includes(event.target.value.trim())); if (match) setSelectedId(match.id) }} /></label>{source.length ? source.map((position) => <button className={selected?.id === position.id ? 'active' : ''} key={position.id} onClick={() => setSelectedId(position.id)}><span><strong>{position.name}</strong><small>{position.activeAssignmentCount} 名在职员工</small></span><b>{profileVersionLabel(position.profile)}</b></button>) : <div className="position-empty">“已删除”中暂无岗位</div>}</aside>
        {selected ? <section className="position-profile-panel"><header><div><h3>{selected.name} · {view === 'active' ? '功能方案' : '已删除岗位'}</h3><p>{selected.appliesToAllHotels ? '集团标准，适用于全部门店' : `适用于 ${selected.applicableHotels.length} 家门店`}</p></div>{canManage && <div className="panel-actions">{view === 'active' ? <><button className="secondary" onClick={() => setEditor({ mode: 'edit', position: selected, draft: { name: selected.name, appliesToAllHotels: selected.appliesToAllHotels, applicableHotelIds: selected.applicableHotels.map((hotel) => hotel.id), copyFromPositionId: '', permissionCodes: selected.profile.permissionCodes, authorizationScopeType: selected.profile.authorizationScopeType ?? 'SELF', wecomSelfSelectable: selected.profile.wecomSelfSelectable ?? false } })}>编辑岗位</button><button className="secondary danger-action" onClick={() => void deletePosition(selected)}>删除</button></> : <><button className="secondary" onClick={() => void restorePosition(selected)}>恢复</button><button className="secondary danger-action" onClick={() => void permanentlyDelete(selected)}>永久删除</button></>}</div>}</header>
          {view === 'active' && <><div className="position-profile-controls"><label>数据范围<select value={draftScope} disabled={!canManage} onChange={(event) => setDraftScope(event.target.value as PositionDraft['authorizationScopeType'])}><option value="SELF">仅本人</option><option value="ORG_UNIT">当前部门</option><option value="ORG_TREE">当前门店/组织树</option><option value="TENANT">集团全部范围</option></select></label><label className="checkbox-label"><input type="checkbox" checked={draftSelfSelectable} disabled={!canManage} onChange={(event) => setDraftSelfSelectable(event.target.checked)} />允许企微新员工申请</label></div><div className="position-permission-table"><div className="position-permission-head"><span>功能模块</span><span>可用</span><span>说明</span></div>{options.map((option) => <label className={!option.delegable ? 'protected' : ''} key={option.permissionCode}><span><strong>{option.label}</strong><small>{option.category}</small></span><input type="checkbox" disabled={!canManage || !option.delegable} checked={draftPermissions.includes(option.permissionCode)} onChange={(event) => setDraftPermissions(event.target.checked ? [...draftPermissions, option.permissionCode] : draftPermissions.filter((code) => code !== option.permissionCode))} /><span>{option.delegable ? option.permissionCode : '受保护权限，不可下放'}</span></label>)}</div>{(canManage || canPublish) && <footer><span>预计影响 <strong>{selected.activeAssignmentCount}</strong> 名在职员工</span>{canManage && <button className="secondary" disabled={saving} onClick={() => void saveProfile()}>保存草稿</button>}{canPublish && <button className="primary" disabled={saving} onClick={() => void publishProfile()}>提交发布</button>}</footer>}</>}
          {view === 'deleted' && <div className="recycle-bin-note"><strong>岗位已从可选列表隐藏。</strong><span>恢复后可选择是否恢复原任职；再次删除将永久从管理界面消失。</span></div>}
        </section> : <div className="state-card"><strong>请选择岗位</strong></div>}
        {selected && view === 'active' && <aside className="position-preview"><h3>员工端预览</h3><small>{selected.name}</small><div>{options.filter((option) => draftPermissions.includes(option.permissionCode)).slice(0,8).map((option) => <span key={option.permissionCode}><i>✓</i>{option.label}</span>)}{!draftPermissions.length && <p>当前岗位尚未配置可见功能</p>}</div></aside>}
      </div>
    </article>
    {editor && <PositionModal title={editor.mode === 'create' ? '新建岗位' : '编辑岗位'} draft={editor.draft} hotels={hotels} positions={positions} options={options} saving={saving} configureProfile={editor.mode === 'create'} onChange={(draft) => setEditor({ ...editor, draft })} onClose={() => setEditor(undefined)} onSave={() => void createOrEdit()} />}
  </>
}
