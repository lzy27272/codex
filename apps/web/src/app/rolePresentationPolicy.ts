import type { AppRouteId } from './routeConfig'

export type RolePresentationKey =
  | 'EMPLOYEE'
  | 'OTA_ASSISTANT'
  | 'SUPERVISOR'
  | 'ASSISTANT_MANAGER'
  | 'HOTEL_MANAGER'
  | 'REGIONAL_OPERATIONS'
  | 'HR'
  | 'GROUP_VICE_PRESIDENT'
  | 'CEO'
  | 'PLATFORM_ADMIN'
  | 'GENERIC'

export type MobilePresentationTab = Readonly<{
  slot: 'primary' | 'secondary' | 'domain' | 'notifications' | 'profile'
  label: string
  target: MobilePresentationTarget
}>

export type MobilePresentationTarget =
  | 'workbench'
  | 'tasks'
  | 'daily-reports-my'
  | 'team-work'
  | 'daily-operations'
  | 'hotel-dashboard'
  | 'operations-dashboard'
  | 'organization'
  | 'kpi-center'
  | 'investments'
  | 'notifications'
  | 'all-functions'

export type RolePresentationPolicy = Readonly<{
  key: RolePresentationKey
  knownRole: boolean
  focus: string
  desktopModuleIds: readonly AppRouteId[] | null
  mobileTabs: readonly MobilePresentationTab[]
}>

const tabs = (
  primaryLabel: string,
  primaryTarget: MobilePresentationTarget,
  domainLabel: string,
  domainTarget: MobilePresentationTarget,
  secondaryLabel = '待办',
  secondaryTarget: MobilePresentationTarget = 'tasks',
): readonly MobilePresentationTab[] => Object.freeze([
  Object.freeze({ slot: 'primary', label: primaryLabel, target: primaryTarget }),
  Object.freeze({ slot: 'secondary', label: secondaryLabel, target: secondaryTarget }),
  Object.freeze({ slot: 'domain', label: domainLabel, target: domainTarget }),
  Object.freeze({ slot: 'notifications', label: '消息', target: 'notifications' }),
  Object.freeze({ slot: 'profile', label: '我的', target: 'all-functions' }),
])

const modules = (...ids: AppRouteId[]): readonly AppRouteId[] => Object.freeze(ids)

const POLICIES: Readonly<Record<RolePresentationKey, RolePresentationPolicy>> = Object.freeze({
  EMPLOYEE: Object.freeze({
    key: 'EMPLOYEE',
    knownRole: true,
    focus: '本人任务、岗位日报、岗位标准与个人KPI',
    desktopModuleIds: modules(
      'workbench', 'my-work', 'tasks', 'daily-reports-my', 'evaluations',
      'kpi-center', 'notifications', 'all-functions',
    ),
    mobileTabs: tabs('工作台', 'workbench', '日报', 'daily-reports-my'),
  }),
  OTA_ASSISTANT: Object.freeze({
    key: 'OTA_ASSISTANT',
    knownRole: true,
    focus: '已分配门店的OTA巡检、日报与个人KPI',
    desktopModuleIds: modules(
      'workbench', 'my-work', 'tasks', 'daily-reports-my', 'kpi-center',
      'notifications', 'all-functions',
    ),
    mobileTabs: tabs('工作台', 'workbench', '日报', 'daily-reports-my'),
  }),
  SUPERVISOR: Object.freeze({
    key: 'SUPERVISOR',
    knownRole: true,
    focus: '部门团队任务、日报、评价、KPI与异常',
    desktopModuleIds: modules(
      'workbench', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
      'daily-operations', 'evaluations', 'kpi-center', 'notifications',
      'all-functions',
    ),
    mobileTabs: tabs('工作台', 'workbench', '团队', 'team-work'),
  }),
  ASSISTANT_MANAGER: Object.freeze({
    key: 'ASSISTANT_MANAGER',
    knownRole: true,
    focus: '门店团队、日运营、异常确认与跨部门协调',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'team-work', 'tasks', 'daily-reports-my',
      'daily-report-templates', 'daily-operations', 'evaluations', 'kpi-center',
      'notifications', 'all-functions',
    ),
    mobileTabs: tabs('工作台', 'workbench', '运营', 'daily-operations'),
  }),
  HOTEL_MANAGER: Object.freeze({
    key: 'HOTEL_MANAGER',
    knownRole: true,
    focus: '全店任务、日报、经营指标、规则与验收',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'team-work', 'tasks', 'daily-reports-my',
      'daily-operations', 'kpi-center', 'rules', 'evaluations', 'notifications',
      'all-functions',
    ),
    mobileTabs: tabs('门店', 'hotel-dashboard', '运营', 'daily-operations'),
  }),
  REGIONAL_OPERATIONS: Object.freeze({
    key: 'REGIONAL_OPERATIONS',
    knownRole: true,
    focus: '授权区域的跨店任务、日运营、OTA巡检与汇总',
    desktopModuleIds: modules(
      'workbench', 'operations-dashboard', 'tasks', 'daily-reports-my',
      'daily-operations', 'kpi-center', 'rules', 'notifications', 'all-functions',
    ),
    mobileTabs: tabs('区域', 'operations-dashboard', '运营', 'daily-operations'),
  }),
  HR: Object.freeze({
    key: 'HR',
    knownRole: true,
    focus: '人员任职、企微绑定、KPI考核与人事审计',
    desktopModuleIds: modules(
      'workbench', 'tasks', 'organization', 'wecom-bindings', 'wecom-onboarding',
      'kpi-center', 'notifications', 'all-functions',
    ),
    mobileTabs: tabs('人事', 'organization', 'KPI', 'kpi-center'),
  }),
  GROUP_VICE_PRESIDENT: Object.freeze({
    key: 'GROUP_VICE_PRESIDENT',
    knownRole: true,
    focus: '授权范围的集团经营、跨店任务、日报与规则',
    desktopModuleIds: modules(
      'workbench', 'operations-dashboard', 'tasks', 'daily-reports-my',
      'daily-operations', 'kpi-center', 'work-packages', 'rules',
      'notifications', 'all-functions',
    ),
    mobileTabs: tabs('集团', 'workbench', '经营', 'daily-operations'),
  }),
  CEO: Object.freeze({
    key: 'CEO',
    knownRole: true,
    focus: '集团经营决策、投资测算、标准规则与重大审批',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'operations-dashboard', 'investments',
      'work-packages', 'team-work', 'tasks', 'daily-reports-my',
      'daily-report-templates', 'daily-operations', 'kpi-center', 'rules',
      'evaluations', 'templates',
      'organization', 'wecom-bindings', 'wecom-onboarding', 'notifications',
      'all-functions',
    ),
    mobileTabs: tabs('集团', 'workbench', '经营', 'daily-operations', '决策', 'investments'),
  }),
  PLATFORM_ADMIN: Object.freeze({
    key: 'PLATFORM_ADMIN',
    knownRole: true,
    focus: '岗位功能、组织权限、系统配置、审计与故障处理',
    desktopModuleIds: modules(
      'workbench', 'hotel-dashboard', 'operations-dashboard', 'investments',
      'work-packages', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
      'daily-report-templates', 'daily-operations', 'kpi-center', 'rules',
      'evaluations', 'templates', 'organization', 'wecom-webhooks',
      'wecom-bindings', 'wecom-onboarding', 'notifications', 'all-functions',
    ),
    mobileTabs: tabs('平台', 'workbench', '配置', 'organization'),
  }),
  GENERIC: Object.freeze({
    key: 'GENERIC',
    knownRole: false,
    focus: '当前任职允许的工作与业务功能',
    desktopModuleIds: null,
    mobileTabs: tabs('工作台', 'workbench', '日报', 'daily-reports-my'),
  }),
})

const ROLE_ALIASES: Readonly<Record<Exclude<RolePresentationKey, 'GENERIC'>, readonly string[]>> = Object.freeze({
  EMPLOYEE: Object.freeze([
    'FRONT_DESK', 'FRONT_DESK_EMPLOYEE', 'front-desk', '前台员工', '前台',
    'HOUSEKEEPING_ATTENDANT', 'ROOM_ATTENDANT', 'housekeeping-attendant', '客房服务员', '客房员工',
  ]),
  OTA_ASSISTANT: Object.freeze([
    'OTA_OPERATION_ASSISTANT', 'OTA_ASSISTANT', 'ota-assistant', 'OTA运营助理',
  ]),
  SUPERVISOR: Object.freeze([
    'FRONT_OFFICE_SUPERVISOR', 'FRONT_SUPERVISOR', 'front-supervisor', '前厅主管',
    'HOUSEKEEPING_SUPERVISOR', 'HK_SUPERVISOR', 'housekeeping-supervisor', '客房主管',
  ]),
  ASSISTANT_MANAGER: Object.freeze([
    'ASSISTANT_GENERAL_MANAGER', 'ASSISTANT_GM', 'assistant-gm', '店助', '店长助理',
  ]),
  HOTEL_MANAGER: Object.freeze([
    'GENERAL_MANAGER', 'HOTEL_GENERAL_MANAGER', 'HOTEL_MANAGER', 'STORE_MANAGER',
    'general-manager', '店长', '店总',
  ]),
  REGIONAL_OPERATIONS: Object.freeze([
    'OTA_OPERATION_MANAGER', 'REGIONAL_OPERATION_MANAGER', 'REGIONAL_OPERATIONS',
    'REGIONAL_MANAGER', 'regional-operations', 'OTA运营经理', '区域运营经理',
    '区域/运营经理', '区域/运营', '区域经理',
  ]),
  HR: Object.freeze([
    'HR_KPI_ADMIN', 'HR_ADMIN', 'HUMAN_RESOURCES', 'hr', '人事', '行政人事', '行政人事KPI管理员',
  ]),
  GROUP_VICE_PRESIDENT: Object.freeze([
    'GROUP_VICE_PRESIDENT', 'GROUP_VP', 'group-vice-president', '集团副总',
  ]),
  CEO: Object.freeze(['CEO', 'GROUP_CEO', 'ceo', '集团CEO']),
  PLATFORM_ADMIN: Object.freeze([
    'PLATFORM_ADMIN', 'GROUP_ADMIN', 'SYSTEM_ADMIN', 'platform-admin', '平台管理员', '系统管理员',
  ]),
})

function normalizeAlias(value: string): string {
  return value.trim().replace(/[\s\-/／]+/g, '_').toUpperCase()
}

const ALIAS_TO_POLICY = new Map<string, Exclude<RolePresentationKey, 'GENERIC'>>()
Object.entries(ROLE_ALIASES).forEach(([key, aliases]) => {
  aliases.forEach((alias) => ALIAS_TO_POLICY.set(
    normalizeAlias(alias),
    key as Exclude<RolePresentationKey, 'GENERIC'>,
  ))
})

const DEFAULT_MODULE_SETS = new Map<RolePresentationKey, ReadonlySet<AppRouteId>>(
  Object.values(POLICIES)
    .filter((policy) => policy.desktopModuleIds !== null)
    .map((policy) => [policy.key, new Set(policy.desktopModuleIds ?? [])]),
)

/** Resolve only explicit, reviewed role codes and aliases. Custom positions fail open to backend visibility. */
export function resolveRolePresentationPolicy(roleCodeOrAlias?: string | null): RolePresentationPolicy {
  if (!roleCodeOrAlias?.trim()) return POLICIES.GENERIC
  const key = ALIAS_TO_POLICY.get(normalizeAlias(roleCodeOrAlias))
  return key ? POLICIES[key] : POLICIES.GENERIC
}

export function shouldUseRoleDefaultsFallback(input: {
  identitySource: 'api' | 'demo'
  fullAccountLevel: boolean
  assignmentPermissionsPresent: boolean
}): boolean {
  return input.identitySource === 'demo'
    || (!input.fullAccountLevel && !input.assignmentPermissionsPresent)
}

/** Keep account-wide UI semantics aligned with EffectiveIdentityService. */
export function isFullAccountPresentationRole(roleCode?: string | null): boolean {
  return roleCode === 'PLATFORM_ADMIN' || roleCode === 'CEO'
}

export function resolveFullAccountPresentationRole(roleCodes: readonly string[]): 'PLATFORM_ADMIN' | 'CEO' | undefined {
  if (roleCodes.includes('PLATFORM_ADMIN')) return 'PLATFORM_ADMIN'
  if (roleCodes.includes('CEO')) return 'CEO'
  return undefined
}

/**
 * Last-resort compatibility for demo data or an older identity payload that does not
 * expose assignment permissionCodes. Live published profiles are authoritative and
 * must not pass through this default matrix, otherwise an administrator could not
 * add ordinary modules to a position.
 */
export function applyRoleDefaultsForFallback<T extends { id: AppRouteId }>(
  permissionVisibleItems: readonly T[],
  roleCodeOrAlias?: string | null,
): T[] {
  const policy = resolveRolePresentationPolicy(roleCodeOrAlias)
  const allowed = DEFAULT_MODULE_SETS.get(policy.key)
  if (!allowed) return [...permissionVisibleItems]
  return permissionVisibleItems.filter((item) => allowed.has(item.id))
}

/** Normalize a page-level route to the desktop module that owns it. */
export function desktopModuleForRoute(route: AppRouteId): AppRouteId {
  if (
    route === 'daily-reports-team'
    || route === 'daily-report-detail'
    || route === 'daily-report-correction'
  ) return 'daily-reports-my'
  if (
    route === 'daily-report-template-detail'
    || route === 'daily-report-template-version'
  ) return 'daily-report-templates'
  if (
    route === 'daily-operation-action-items'
    || route === 'daily-operation-issues'
    || route === 'daily-operation-issue-detail'
    || route === 'daily-operation-snapshots'
    || route === 'daily-operation-snapshot-detail'
    || route === 'daily-operation-exports'
  ) return 'daily-operations'
  if (
    route === 'kpi-scorecards'
    || route === 'kpi-scorecard-detail'
    || route === 'kpi-templates'
    || route === 'kpi-template-detail'
    || route === 'kpi-relations'
    || route === 'kpi-settlements'
    || route === 'kpi-inspections'
  ) return 'kpi-center'
  if (
    route === 'investment-project'
    || route === 'investment-parameters'
    || route === 'investment-professional'
  ) return 'investments'
  return route
}

/**
 * Companion guard for the same demo/legacy fallback only. Live page access is
 * decided by the published assignment permissions and backend policy.
 */
export function isRouteAllowedByRoleDefaults(
  roleCodeOrAlias: string | null | undefined,
  route: AppRouteId,
  backendAllowed: boolean,
): boolean {
  if (!backendAllowed) return false
  const policy = resolveRolePresentationPolicy(roleCodeOrAlias)
  const allowed = DEFAULT_MODULE_SETS.get(policy.key)
  return allowed ? allowed.has(desktopModuleForRoute(route)) : true
}

const TEAM_DAILY_REPORT_ROLES = new Set<RolePresentationKey>([
  'SUPERVISOR',
  'ASSISTANT_MANAGER',
  'HOTEL_MANAGER',
  'REGIONAL_OPERATIONS',
  'GROUP_VICE_PRESIDENT',
  'CEO',
  'PLATFORM_ADMIN',
])

/** Managers land on the team/store/region report view even when they can also read their own report. */
export function prefersTeamDailyReports(roleCodeOrAlias?: string | null): boolean {
  return TEAM_DAILY_REPORT_ROLES.has(resolveRolePresentationPolicy(roleCodeOrAlias).key)
}

export function resolveDailyReportsTarget(input: {
  roleCode?: string | null
  hasAssignment: boolean
  canUseOwnReport: boolean
  canUseTeamReport: boolean
}): 'daily-reports-my' | 'daily-reports-team' {
  if (prefersTeamDailyReports(input.roleCode) && input.canUseTeamReport) return 'daily-reports-team'
  if (input.hasAssignment && input.canUseOwnReport) return 'daily-reports-my'
  if (input.canUseTeamReport) return 'daily-reports-team'
  return 'daily-reports-my'
}
