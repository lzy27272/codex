import type { AppNavigate, AppRouteId, AppSectionId } from '../app/routeConfig'

export type FunctionNavigationItem = {
  id: AppRouteId
  sectionId?: string
  label: string
  icon: string
  group?: string
}

type MobileTab = 'workbench' | 'tasks' | 'daily-reports-my' | 'notifications' | 'all-functions'

const tabItems: ReadonlyArray<{ id: MobileTab; label: string }> = [
  { id: 'workbench', label: '工作台' },
  { id: 'tasks', label: '待办' },
  { id: 'daily-reports-my', label: '日报' },
  { id: 'notifications', label: '消息' },
  { id: 'all-functions', label: '我的' },
]

function TabIcon({ id }: { id: MobileTab }) {
  const common = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': true } as const
  if (id === 'workbench') return <svg {...common}><path d="M3.5 10.2 12 3.5l8.5 6.7v9.3a1 1 0 0 1-1 1h-5v-6h-5v6h-5a1 1 0 0 1-1-1z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /></svg>
  if (id === 'tasks') return <svg {...common}><rect x="5" y="4.5" width="14" height="16" rx="2" stroke="currentColor" strokeWidth="1.8" /><path d="M9 4.5v-1h6v1M8.5 10.5l1.5 1.5 3-3M8.5 16h7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
  if (id === 'daily-reports-my') return <svg {...common}><path d="M6 3.5h9l3 3v14H6z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /><path d="M15 3.5v3h3M9 11h6M9 15h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
  if (id === 'notifications') return <svg {...common}><path d="M5 10a7 7 0 0 1 14 0v4l1.5 2.5h-17L5 14zM9.5 20h5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
  return <svg {...common}><circle cx="12" cy="8" r="3.5" stroke="currentColor" strokeWidth="1.8" /><path d="M5.5 20a6.5 6.5 0 0 1 13 0" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
}

function currentTab(sectionId: AppSectionId): MobileTab {
  if (sectionId === 'notifications') return 'notifications'
  if (sectionId === 'daily-reports' || sectionId === 'daily-report-templates') return 'daily-reports-my'
  if (sectionId === 'tasks' || sectionId === 'my-work' || sectionId === 'team-work') return 'tasks'
  if (sectionId === 'all-functions') return 'all-functions'
  return 'workbench'
}

export function MobileBottomNavigation({
  sectionId,
  go,
  resolveTarget,
  unreadCount,
}: {
  sectionId: AppSectionId
  go: AppNavigate
  resolveTarget: (id: MobileTab) => AppRouteId
  unreadCount: number
}) {
  const active = currentTab(sectionId)
  return <nav className="mobile-bottom-nav" aria-label="手机端主导航">
    {tabItems.map((item) => <button
      type="button"
      key={item.id}
      className={active === item.id ? 'active' : ''}
      onClick={() => go(resolveTarget(item.id))}
      aria-current={active === item.id ? 'page' : undefined}
    >
      <span className="mobile-tab-icon"><TabIcon id={item.id} />{item.id === 'notifications' && unreadCount > 0 && <b>{unreadCount > 99 ? '99+' : unreadCount}</b>}</span>
      <span>{item.label}</span>
    </button>)}
  </nav>
}

const groupOrder = ['日常工作', '管理驾驶舱', '标准与工作', '日报与运营', '行政人事', '管理闭环', '投资决策', '系统配置', '其他功能']

function displayGroup(item: FunctionNavigationItem): string {
  if (['my-work', 'tasks', 'notifications'].includes(item.id)) return '日常工作'
  return item.group ?? '其他功能'
}

export function AllFunctionsPage({
  items,
  roleLabel,
  orgName,
  go,
  resolveTarget,
  onChangePassword,
  onLogout,
}: {
  items: FunctionNavigationItem[]
  roleLabel: string
  orgName: string
  go: AppNavigate
  resolveTarget: (id: AppRouteId) => AppRouteId
  onChangePassword?: () => void
  onLogout?: () => void
}) {
  const grouped = new Map<string, FunctionNavigationItem[]>()
  items.filter((item) => item.id !== 'workbench').forEach((item) => {
    const group = displayGroup(item)
    grouped.set(group, [...(grouped.get(group) ?? []), item])
  })
  const groups = [...grouped.entries()].sort(([left], [right]) => {
    const leftIndex = groupOrder.indexOf(left)
    const rightIndex = groupOrder.indexOf(right)
    return (leftIndex < 0 ? groupOrder.length : leftIndex) - (rightIndex < 0 ? groupOrder.length : rightIndex)
  })

  return <section className="all-functions-page">
    <header>
      <h1>全部功能</h1>
      <p>仅显示当前岗位可使用的功能</p>
      <div className="mobile-identity-summary"><strong>{roleLabel}</strong><span>{orgName}</span></div>
    </header>
    <div className="all-function-groups">
      {groups.map(([group, groupItems]) => <section key={group}>
        <h2>{group}</h2>
        <div className="all-function-list">
          {groupItems.map((item) => <button type="button" key={item.id} onClick={() => go(resolveTarget(item.id))}>
            <i aria-hidden="true">{item.icon}</i><span>{item.label}</span><b aria-hidden="true">›</b>
          </button>)}
        </div>
      </section>)}
      {(onChangePassword || onLogout) && <section>
        <h2>个人与系统</h2>
        <div className="all-function-list">
          {onChangePassword && <button type="button" onClick={onChangePassword}><i aria-hidden="true">⌁</i><span>修改密码</span><b aria-hidden="true">›</b></button>}
          {onLogout && <button type="button" onClick={onLogout}><i aria-hidden="true">↪</i><span>退出登录</span><b aria-hidden="true">›</b></button>}
        </div>
      </section>}
    </div>
  </section>
}
