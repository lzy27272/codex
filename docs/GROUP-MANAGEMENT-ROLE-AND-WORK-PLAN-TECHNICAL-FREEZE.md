# 集团管理角色与周/月工作计划正式技术冻结

| 项目 | 冻结值 |
|---|---|
| 技术设计 | TECH-DESIGN-1.0 |
| 状态 | FROZEN / IMPLEMENTATION READY / NOT CODED / NOT DEPLOYED |
| 产品基线 | PRODUCT-V1.4 |
| 需求基线 | GROUP-MANAGEMENT-WORK-PLAN-V1 / DESIGN-1.1 |
| 代码基线 | `main@788cfe724c082fc9716f28b66c276ac81c9488e9` |
| 目标技术版本 | `TECH-V0.2-PILOT.8` |
| 目标应用版本 | Core API / Web `0.2.0-pilot.8` |
| 目标OpenAPI制品 | `0.2.5-pilot.8`，API主版本仍为`/api/v1` |
| 目标数据库迁移 | `V39__group_management_roles_and_work_plans.sql` |
| 冻结日期 | 2026-09-11 |

本文件是该需求当前正式技术合同，取代`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE-DRAFT.md`的当前效力。它允许据此安排实现，但本文件本身不表示代码、迁移、账号、页面或部署已经完成。

## 1. 总体边界

本次实现包含：

1. 集团董事长、集团总经理（集团CEO）、集团副总经理、行政人事主管、行政人事的角色与岗位兼容。
2. 直属与间接领导关系。
3. CEO账号级授权与总经理业务动作任职分离。
4. 董事长向集团总经理或集团副总经理的受限任务交办。
5. 管理岗周/月计划、直属主管逐项审批、批准后原子生成任务。
6. 任务提醒、审计、Outbox、页面、OpenAPI和全链路测试。

不包含：区域经理启用、普通行政人事提交计划、副总代理审批行政人事计划、董事长向其他岗位派活、董事长进入管理后台、修改历史迁移、生产部署或真实人员关系的猜测回填。

所有新能力默认关闭。应用配置固定为`app.group-management.work-plans-enabled=false`、`executive-tasks-enabled=false`、`reminder-worker-enabled=false`及空的`tenant-ids`白名单；任一布尔开关为true时，白名单必须非空且全为合法UUID，否则应用启动失败。有效开关等于“对应布尔开关为true且当前租户在白名单内”。工作计划全部API、董事长专用API和提醒Worker分别由统一`GroupManagementFeatureGate`在服务端强制检查；前端只能使用`/iam/me.capabilities`决定是否展示，不得把隐藏菜单当成安全边界。只有应用实现、V39升级、岗位方案发布、真实任职清单确认和负向权限测试通过后，才能通过受控部署配置在指定Pilot租户开启。

```yaml
app:
  group-management:
    work-plans-enabled: ${GROUP_MANAGEMENT_WORK_PLANS_ENABLED:false}
    executive-tasks-enabled: ${GROUP_MANAGEMENT_EXECUTIVE_TASKS_ENABLED:false}
    reminder-worker-enabled: ${GROUP_MANAGEMENT_REMINDER_WORKER_ENABLED:false}
    tenant-ids: ${GROUP_MANAGEMENT_TENANT_IDS:}
```

## 2. 身份、角色和岗位合同

### 2.1 集团总经理与CEO

- `CEO`继续是唯一SYSTEM角色；不得新增`GROUP_GENERAL_MANAGER`角色。
- 新增唯一岗位`GROUP_GENERAL_MANAGER`，显示名“集团总经理（集团CEO）”，集团根组织、`GROUP_MANAGEMENT`岗位族；集团级默认岗位方案映射既有`CEO`角色。
- 既有CEO账号角色、TENANT数据范围和配置治理权限保持不变。
- CEO必须有真实员工及有效`GROUP_GENERAL_MANAGER`任职，才能成为直属主管、计划审批人或任务参与人。

### 2.2 业务动作任职

- `TenantPrincipal`新增可空的`businessActorAssignmentId`，作为当前请求唯一业务操作者任职。现有`assignmentIds`保持兼容语义：普通HTTP身份为当前所选任职的单元素集合，账号级身份及受信三参数解析可包含全部有效任职；它只供旧代码兼容、资源归属和可见性判断，新增责任动作不得仅凭它授权。
- `X-Assignment-Id`仍是唯一业务任职请求头，不新增第二个客户端身份头。
- 身份服务必须验证请求头任职属于当前账号、同租户且有效，再保存为`businessActorAssignmentId`。非账号级HTTP身份未传请求头时沿用现有主任职/唯一有效任职选择并保存为业务任职；`CEO`和`PLATFORM_ADMIN`只有显式传头时才设置业务任职，未传时为`null`。
- `CEO`和`PLATFORM_ADMIN`仍按账号级授权计算角色、权限和数据范围，但不得再因`fullAccountLevel`丢弃已验证的业务动作任职。受信三参数身份解析保持账号级兼容且业务任职固定为`null`，不能直接执行责任动作。
- 本版本新增接口及本功能调用的既有任务责任动作必须先要求业务任职非空、仍有效且属于当前账号，再校验参与人、直属关系或专用目标规则。只有以CEO/集团总经理身份执行计划审批或任务动作时，才额外要求岗位代码恰为`GROUP_GENERAL_MANAGER`；董事长和其他岗位按各自岗位及参与人合同校验。日报、KPI等未被本功能调用的既有责任接口本期只做回归，不借本需求扩大身份重构范围。
- 新接口不接收冗余操作者任职ID，直接从服务端主体取值。兼容旧接口时，仅`actorAssignmentId`、`creatorAssignmentId`、`submittedByAssignmentId`及审批/复核命令中的操作者任职必须等于`businessActorAssignmentId`；`assigneeAssignmentId`、`targetAssignmentId`和被指定的`reviewerAssignmentId`属于业务目标，不适用相等规则。
- `AccessPolicy`新增`requireBusinessActorAssignment()`及`requireBusinessActorAssignment(UUID claimed)`；“属于同一账号的任意任职”不足以授权当前责任动作。
- 配置后台继续使用CEO账号级授权，不因选择业务任职而被错误降权。平台管理员即使选择任职也不能代行总经理业务审批。

### 2.3 董事长及人事岗位

- 新增SYSTEM角色及同码岗位`GROUP_CHAIRMAN`。董事长真实任职用于任务创建、`REVIEWER`和审计，但不加入`FULL_ACCOUNT_LEVEL_ROLES`或配置管理员名单。
- 沿用`GROUP_VICE_PRESIDENT`角色和岗位，只把标准显示名统一为“集团副总经理”。
- 新增SYSTEM角色及同码岗位`HR_ADMINISTRATION_SUPERVISOR`和`HR_ADMINISTRATION`。
- `HR_ADMINISTRATION_SUPERVISOR`是管理岗位；`HR_ADMINISTRATION`是普通岗位。
- 两个人事岗位不得复用、别名或默认附带补充账号角色`HR_KPI_ADMIN`。
- 不新增`REGIONAL_MANAGER`；`OTA_OPERATION_MANAGER`保持OTA运营经理本义，历史数据不改写。

## 3. 汇报关系合同

- `employee_position_assignment.manager_assignment_id`继续唯一表达直属主管。
- 集团总经理任职直属董事长任职；集团总经理不提交计划，因此董事长不进入工作计划审批队列。
- 集团副总经理、行政人事主管、行政人事三类任职均直属集团总经理任职。
- 新表`position_assignment_reporting_relation`表达非直属关系，V1只允许`INDIRECT_LEADER`。
- 每条间接关系显式绑定具体副总任职和具体人事任职。多位副总时不得按角色自动扩散。
- 间接领导关系只进入授权后的团队只读查询，不进入主管解析、待审批列表、计划审批、任务`REVIEWER`、代理审批或任务派发。
- 所有直属和间接关系须同租户、有效期覆盖、禁止自指、禁止成环；并发写入使用租户级事务锁防止并发成环。

## 4. 数据库冻结

### 4.1 迁移策略

- 使用一个事务型迁移`V39__group_management_roles_and_work_plans.sql`，不拆分V39/V40，避免角色、权限、关系与计划结构处于半完成状态。
- 不修改V1—V38；失败时V39整笔回滚。
- V39只写可确定的系统结构、角色、岗位、权限和默认方案，不自动猜测真实人员、账号或上下级任职。
- 生产回退采用迁移前备份恢复或后续前向修复，不提供删除V39对象的生产down脚本。

### 4.2 角色、岗位与权限

V39新增SYSTEM角色：

- `GROUP_CHAIRMAN`
- `HR_ADMINISTRATION_SUPERVISOR`
- `HR_ADMINISTRATION`

V39新增岗位：

- `GROUP_CHAIRMAN`
- `GROUP_GENERAL_MANAGER`
- `HR_ADMINISTRATION_SUPERVISOR`
- `HR_ADMINISTRATION`

V39新增权限：

- `ui.module.work-plans`
- `work-plan.read`
- `work-plan.submit`
- `work-plan.team-read`
- `work-plan.review`
- `work-record.team-read`
- `executive-task.read`
- `executive-task.assign`

权限元数据：

- `ui.module.work-plans`为`delegable_to_position=true / function_category=UI_MODULE`。
- `work-plan.*`和`work-record.team-read`为`delegable_to_position=true / function_category=WORK`。
- `executive-task.read/assign`为`delegable_to_position=true / function_category=TASK`，但`enforce_position_profile_permission_boundary()`必须限制二者只能进入默认SYSTEM角色为`GROUP_CHAIRMAN`的集团岗位方案。
- 既有`dashboard.ceo`改为可进入系统岗位方案，但同一边界函数只允许默认SYSTEM角色为`CEO`或`GROUP_CHAIRMAN`的集团岗位方案包含，其他岗位一律拒绝。

新增岗位的默认方案：

| 岗位代码 | `authorization_scope_type` | 权限合同 |
|---|---|---|
| `GROUP_CHAIRMAN` | `TENANT` | 使用下方董事长闭合白名单 |
| `GROUP_GENERAL_MANAGER` | `ORG_TREE` | 在既有CEO能力上新增`ui.module.work-plans`、`work-plan.team-read`、`work-plan.review`、`work-record.team-read`；不新增`work-plan.submit` |
| `HR_ADMINISTRATION_SUPERVISOR` | `ORG_UNIT` | `org.read`、`work-record.read`、`work-record.team-read`、`task.read`、`task.act`、`task.review`、`notification.read`、`work-plan.read`、`work-plan.submit`、`work-plan.team-read`、`work-plan.review`、`ui.module.workbench`、`ui.module.team-work`、`ui.module.tasks`、`ui.module.notifications`、`ui.module.work-plans` |
| `HR_ADMINISTRATION` | `SELF` | `org.read`、`task.read`、`task.act`、`notification.read`、`ui.module.workbench`、`ui.module.tasks`、`ui.module.notifications`；无任何`work-plan.*`、团队读或`task.review` |

董事长闭合业务白名单同时用于`GROUP_CHAIRMAN`角色和默认岗位方案：

```text
org.read
dashboard.ceo
dashboard.hotel
dashboard.operations
work-record.team-read
task.review
daily-report.team-read
daily-operation.read
daily-operation.cross-hotel-read
operation-snapshot.read
operation-snapshot.compare
evaluation.read
notification.read
work-plan.team-read
executive-task.read
executive-task.assign
ui.module.workbench
ui.module.hotel-dashboard
ui.module.operations-dashboard
ui.module.team-work
ui.module.tasks
ui.module.daily-reports-my
ui.module.daily-operations
ui.module.evaluations
ui.module.notifications
ui.module.work-plans
```

董事长明确不得获得`task.read/create/dispatch/act/cancel`、`work-plan.read/submit/review`、`work-record.read/submit/review`、`ui.module.all-functions`以及IAM、组织维护、岗位方案、工作包、模板、规则、KPI、投资、企微、凭据、敏感证据或导出管理权限。`task.review`只供专用高管交办验收服务内部执行参与人状态机，董事长访问通用任务读取、动作和证据接口仍为403。

既有默认管理岗位只追加下列能力，未列出的既有权限和范围原样保留，V39不得重算或削减：

| 岗位代码 | 保持范围 | V39最小新增权限 |
|---|---|---|
| `GROUP_VICE_PRESIDENT` | `ORG_TREE` | `ui.module.work-plans`、`work-plan.read`、`work-plan.submit`、`work-plan.team-read`、`work-plan.review`、`work-record.team-read`、`task.review` |
| `OTA_OPERATION_MANAGER` | `ORG_TREE` | `ui.module.work-plans`、`work-plan.read`、`work-plan.submit`、`work-plan.team-read`、`work-plan.review`、`work-record.team-read` |
| `GENERAL_MANAGER` | `ORG_TREE` | 同上 |
| `ASSISTANT_GENERAL_MANAGER` | `ORG_TREE` | 同上 |
| `FRONT_OFFICE_SUPERVISOR` | `ORG_UNIT` | 同上 |
| `HOUSEKEEPING_SUPERVISOR` | `ORG_UNIT` | 同上 |

上述六个代码是V1.1默认管理岗完整集合，不得按名称、岗位族或级别扩大；`GROUP_VICE_PRESIDENT`补齐既有方案缺失的`task.review`，其余五个保留既有`task.review`。自定义管理岗位只有人工显式配置并发布计划权限后才可启用。

角色兼容规则：

- 三个新SYSTEM角色的`role_permission`与各自默认岗位方案保持同一业务权限集合；`GROUP_CHAIRMAN`范围为`TENANT`、人事主管为`ORG_UNIT`、普通行政人事为`SELF`。
- `HR_ADMINISTRATION_SUPERVISOR`和`HR_ADMINISTRATION`不得默认附带`HR_KPI_ADMIN`、IAM、组织/岗位方案/KPI/企微管理或通用任务创建、派发、取消权限。
- 既有`CEO`及六个默认管理角色同步追加上表对应权限，以兼容V34任职来源授权；静态权限不能代替业务任职、精确直属关系或任务参与人校验。

种子与发布约束：

- 更新V38保留代码约束，把三个新SYSTEM角色代码加入保留集合；任一租户已有同码CUSTOM角色时迁移失败关闭。
- 预检同码岗位回收站/墓碑冲突，以及`ux_position_profile_group_role`是否已有其他岗位方案映射`CEO`。冲突不得自动覆盖。
- 每租户默认方案发布人依次选择当前有效TENANT范围SYSTEM `PLATFORM_ADMIN`、SYSTEM `CEO`；同优先级多人只按稳定账号ID排序承担系统发布审计，不用于人员或主管关系选择。
- 有合格发布人时，新岗位方案发布V1并复制V2草稿，完整写`published_by/published_at`和审计；无合格发布人时只创建方案及V1 `DRAFT`，不得伪造账号或审计，该租户保持功能关闭并标记`NO-GO`。
- 人工编辑或门店减权方案不静默扩权。既有岗位只更新带V35/V36系统默认发布审计、无后续人工变化的发布版及对应未修改草稿，其余生成差异清单。

### 4.3 新增表

V39新增十一张租户表：

1. `management_work_plan`
2. `management_work_plan_revision`
3. `management_work_plan_item`
4. `management_work_plan_item_reminder`
5. `management_work_plan_review`
6. `management_work_plan_item_decision`
7. `management_work_plan_item_decision_reminder`
8. `management_work_plan_task_link`
9. `task_reminder`
10. `position_assignment_reporting_relation`
11. `executive_task_directive`

同时为`management_task`增加：

- `creation_source`：`LEGACY/MANUAL/RULE_ENGINE/TASK_CANDIDATE/WORK_PLAN/CHAIRMAN_DIRECTIVE`，与现有`TaskService.CreationSource`保持同名，不引入第二套别名；列为`NOT NULL DEFAULT 'LEGACY'`以允许PILOT.7旧应用在回退窗口继续写入，新代码仍必须显式写规范来源。
- `created_by_assignment_id`：同租户任职外键；历史或系统任务可空，`WORK_PLAN`和`CHAIRMAN_DIRECTIVE`必须有值。

```sql
creation_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY'
    CHECK (creation_source IN (
        'LEGACY', 'MANUAL', 'RULE_ENGINE', 'TASK_CANDIDATE',
        'WORK_PLAN', 'CHAIRMAN_DIRECTIVE'
    ))
```

V39只从服务端写入的结构化事实回填：唯一CREATE迁移`payload.source=MANUAL`为`MANUAL`；`source_action_id`非空或CREATE来源为`RULE_ENGINE`时为`RULE_ENGINE`；CREATE来源为`TASK_CANDIDATE`或权威候选任务关系唯一对应时为`TASK_CANDIDATE`；缺失、冲突、多条CREATE或不能证明时为`LEGACY`。不得依据客户端可写的`source_snapshot`猜测。通用任务新代码由服务器固定写`MANUAL`，两类新来源只允许内部专用服务写入；数据库默认值仅用于旧应用兼容，不作为新代码省略来源的许可。

### 4.4 关键约束

- 计划唯一：`(tenant_id, owner_assignment_id, plan_type, period_start)`。
- 修订唯一：`(tenant_id, plan_id, revision_no)`；提交后正文和提醒不可更新或删除。
- 审批对修订唯一；逐项决定对`(tenant_id, review_id, item_id)`唯一。
- 计划任务链接分别唯一约束`item_decision_id`和`task_id`。
- `executive_task_directive.task_id`唯一；同时保存`client_command_id`和`request_hash`，唯一约束`(tenant_id, chairman_assignment_id, client_command_id)`，并保存董事长任职、目标任职和任务。它是董事长来源授权及命令关联的权威关系，`source_snapshot`仅作不可变快照。
- 周计划数据库校验周一开始和七天周期；月计划校验月首至月末。
- 计划项上限100、单项提醒上限8由事务锁定后校验；有提醒的任务必须有截止时间，提醒必须晚于当前/批准时间且早于截止时间；完成时间、提醒、状态、租约和有效期均设置CHECK。
- 已提交修订、审批、逐项决定、任务链接和董事长指令使用追加式或不可变触发器保护。
- 直属边`assignment→manager_assignment`和间接边`subordinate_assignment→leader_assignment`组成同一租户汇报图；两类写入共用同一租户级`pg_advisory_xact_lock`和可延迟递归校验，禁止自指、跨租户及任意组合环。安装触发器前必须递归扫描全部既有直属关系，发现历史环则V39失败回滚。
- 事务结束时以可延迟约束触发器校验可信来源：`WORK_PLAN`恰有一条计划任务链接且无董事长指令；`CHAIRMAN_DIRECTIVE`恰有一条权威指令且无计划任务链接；其他来源不得挂两类关系。两类新来源的创建任职分别等于精确审批任职或权威董事长任职，目标/参与人也必须相符。
- 所有新表使用同租户复合外键、必要索引、`ENABLE/FORCE ROW LEVEL SECURITY`及`tenant_isolation USING/WITH CHECK`。

### 4.5 RLS种子与运行账号GRANT

V39多租户种子复用V36/V37模式：事务内临时对`tenant`执行`NO FORCE ROW LEVEL SECURITY`，按`tenant.id`稳定顺序逐租户设置`set_config('app.tenant_id', tenantId, true)`并完成种子、方案和审计，最后恢复`FORCE ROW LEVEL SECURITY`。任何异常使整个V39回滚，禁止出现“迁移成功但零租户种子”。

V4可能向未来新表应用宽泛默认权限，因此V39必须先显式收回，再向`hotel_ai_os_app`授予：

| 表 | 运行账号权限 |
|---|---|
| `management_work_plan` | `SELECT, INSERT, UPDATE` |
| `management_work_plan_revision` | `SELECT, INSERT, UPDATE`，触发器只允许状态迁移 |
| `management_work_plan_item` | `SELECT, INSERT, UPDATE, DELETE`，仅DRAFT可变 |
| `management_work_plan_item_reminder` | `SELECT, INSERT, UPDATE, DELETE`，仅DRAFT可变 |
| `management_work_plan_review` | `SELECT, INSERT` |
| `management_work_plan_item_decision` | `SELECT, INSERT` |
| `management_work_plan_item_decision_reminder` | `SELECT, INSERT` |
| `management_work_plan_task_link` | `SELECT, INSERT` |
| `task_reminder` | `SELECT, INSERT, UPDATE` |
| `position_assignment_reporting_relation` | `SELECT, INSERT, UPDATE`，以结束有效期替代删除 |
| `executive_task_directive` | `SELECT, INSERT` |

追加式表同时由不可变触发器保护，不能只依赖GRANT。

## 5. 工作计划事务合同

计划聚合状态：

```text
DRAFT → PENDING_APPROVAL → APPROVED
                       ├─→ REJECTED
                       └─→ DRAFT
                           仅与旧修订RETURNED/WITHDRAWN及新DRAFT修订同事务发生
```

计划修订只允许`DRAFT → SUBMITTED → APPROVED/RETURNED/REJECTED/WITHDRAWN`；`RETURNED`和`WITHDRAWN`永远是修订状态，不是聚合状态。总审批决定只允许`APPROVE/RETURN/REJECT`。提交后修订正文、计划项和提议提醒不可变，修订行只能由受控命令更新状态及对应审计字段。

- 草稿属于一个精确任职、`WEEKLY/MONTHLY`类型和租户时区周期。
- 创建、保存、提交和撤回时，计划所有者任职必须等于当前`businessActorAssignmentId`并属于当前账号；服务端不得根据请求体替换计划所有者。
- 提交时冻结不可变修订、内容哈希、提交任职、直属主管任职及主管快照。
- 待审主管失效时审批失败；撤回后按新直属关系重新提交。
- 审批人逐项决定任务内容、完成时间和提醒。任一任务内容不同意时整单退回，不生成任务。
- 只有全部决定完整合法时允许`APPROVE_AND_DISPATCH`。

`APPROVE_AND_DISPATCH`必须在一个数据库事务中：

1. 锁定计划和当前修订，校验`expectedVersion`、请求哈希和精确直属主管任职。
2. 写入总审批和逐项决定。
3. 通过内部`TaskService.createFromApprovedWorkPlan`为N项创建恰好N个任务。
4. 每个任务`ASSIGNEE`为提交任职，`REVIEWER`为实际审批任职，状态到`PENDING_ACK`。
5. 写计划—任务链接、提醒、审计和事务Outbox后提交。

任一步失败全部回滚。内部任务键为`work-plan:{planId}:{revisionId}:{itemId}`；重放时还须核对权威计划项关系，不能只凭字符串键返回其他来源任务。

## 6. 董事长任务交办合同

- 使用专用`ExecutiveTaskService.assignByChairman`，不得放宽或直接复用通用`TaskTargetPolicy`。
- 当前租户`executive-tasks-enabled`有效开关必须为true；发起任职必须是当前有效`GROUP_CHAIRMAN`，并拥有`executive-task.assign`。
- 目标只能是同租户、集团根组织下有效`GROUP_GENERAL_MANAGER`或`GROUP_VICE_PRESIDENT`任职。
- 客户端不得指定验收人、`creationSource`、任意岗位代码或`source_snapshot`中的安全字段。有提醒时必须提供截止时间；提醒晚于创建时刻且早于截止时间。
- 在一个事务中创建任务、写`executive_task_directive`、写董事长为`REVIEWER`、目标为`ASSIGNEE`、执行CREATE/DISPATCH到`PENDING_ACK`并写审计与Outbox。
- 幂等键为`chairman-directive:{chairmanAssignmentId}:{clientCommandId}`，外部命令同时使用请求哈希防止同键异载荷。
- 董事长`APPROVE/REWORK`必须同时满足：业务动作任职为任务有效`REVIEWER`、`creation_source=CHAIRMAN_DIRECTIVE`、权威指令记录的创建董事长任职与当前任职相同。
- 首期不开放董事长取消、转派、编辑已派发任务或向其他岗位派发。

## 7. 任务读取与敏感字段

- 董事长不获得通用`task.read`，只以`executive-task.read`访问专用字段最小化投影；读取权不产生任何动作权。
- 专用列表返回任务ID/编号、标题、组织最小信息、负责人/验收人姓名与岗位、状态、优先级、截止时间、可信来源、行版本和进度，不返回描述全文、结果正文、`source_snapshot`、证据信息或联系方式。
- 专用详情对全集团任务仍使用最小投影；本人权威交办可额外返回交办说明、结果摘要和`evidenceCount`，但不返回对象键、原文件名、哈希或下载地址。
- V1不提供董事长证据内容接口；董事长访问现有通用任务列表、详情、动作和`/tasks/**/evidence/**`统一403。
- 董事长只可通过专用动作接口对本人权威交办记录对应的任务执行验收/退回；其他任务全部动作403。
- 普通行政人事只可读、执行本人任职作为有效`ASSIGNEE`的任务，默认无`task.review`。

## 8. 提醒执行合同

- 只有当前租户在白名单且`reminder-worker-enabled=true`时Worker才领取；工作计划提醒还要求`work-plans-enabled=true`，董事长任务提醒还要求`executive-tasks-enabled=true`。
- `task_reminder`接收任职固定为任务`ASSIGNEE`；完成或取消任务时取消未发送提醒。
- Worker以`FOR UPDATE SKIP LOCKED`领取`PENDING/RETRY_WAIT`且到达`next_attempt_at`的记录，写入`PROCESSING`及有限租约。
- 租约过期可恢复；失败有上限指数退避，最终进入`DEAD_LETTER`并产生指标/告警。
- 站内通知、Outbox事件和提醒状态在同一事务中幂等提交。
- 外部通道只承诺至少一次尝试，并在供应商支持时传递幂等键，不宣称网络严格一次投递。
- `task_escalation`继续只处理逾期升级，不承担到期前提醒。

## 9. API与错误合同

所有请求和响应字段使用camelCase，命令请求均`additionalProperties: false`。本次新增的九个工作计划接口和六个高管接口全部要求`X-Assignment-Id`；`GET /api/v1/iam/me`可不带该头完成身份发现。本冻结不改变其他既有接口的请求头合同。`/iam/me`新增必返回字段：

```json
{
  "businessActorAssignmentId": null,
  "capabilities": {
    "groupManagement": {
      "workPlansEnabled": false,
      "executiveTasksEnabled": false
    }
  }
}
```

`businessActorAssignmentId`就是本请求已经验证的`X-Assignment-Id`，未传时为null。`capabilities`只表示当前部署及租户是否开放，不代替权限判断。`positionAssignments`固定为强类型数组，每项包含`id, organizationId, organizationCode, organizationName, positionId, positionCode, positionName, primary, assignmentType, validFrom, validTo, permissionCodes, authorizationScopeType, wecomSelfSelectable`。

所有工作计划和高管交办接口均要求`X-Assignment-Id`；所有POST/PUT命令另要求1—200字符`Idempotency-Key`，与既有`CommandIdempotencyService`及数据库列一致；修改和状态命令要求非负`expectedVersion`。工作计划接口：

```text
GET  /api/v1/work-plans?view=mine|approval|team&type=&periodStart=&status=
POST /api/v1/work-plans
GET  /api/v1/work-plans/{planId}
PUT  /api/v1/work-plans/{planId}/revisions/{revisionId}
POST /api/v1/work-plans/{planId}/submit
POST /api/v1/work-plans/{planId}/actions/approve
POST /api/v1/work-plans/{planId}/actions/return
POST /api/v1/work-plans/{planId}/actions/reject
POST /api/v1/work-plans/{planId}/actions/withdraw
```

`view`必填：`mine`只返回当前业务任职所有计划，`approval`只返回当前修订冻结直属主管等于当前业务任职的计划，`team`要求`work-plan.team-read`并按当前任职直属/显式间接关系只读；董事长须选择有效董事长任职，按TENANT最小投影读取。详情接口每次重新执行“本人所有者、冻结主管、授权团队/董事长”之一的可见性校验，不能因知道`planId`绕过。

董事长专用接口：

```text
GET  /api/v1/executive-tasks
GET  /api/v1/executive-tasks/{taskId}
GET  /api/v1/executive-tasks/targets
POST /api/v1/executive-tasks
POST /api/v1/executive-tasks/{taskId}/actions/approve
POST /api/v1/executive-tasks/{taskId}/actions/rework
```

### 9.1 工作计划请求模型

```text
WorkPlanItemInput
  itemId? uuid
  title string
  description? string
  priority LOW|NORMAL|HIGH|URGENT
  proposedDueAt date-time
  proposedReminderTimes date-time[0..8]

CreateWorkPlan
  planType WEEKLY|MONTHLY
  periodStart date
  summary? string
  items WorkPlanItemInput[1..100]

UpdateWorkPlanRevision
  expectedVersion int64 >= 0
  summary? string
  items WorkPlanItemInput[1..100]

PlanCommand
  expectedVersion int64 >= 0

ItemDecisionInput
  itemId uuid
  contentDecision ACCEPT|CHANGE_REQUIRED
  dueAtDecision ACCEPT|ADJUST|CHANGE_REQUIRED
  finalDueAt? date-time
  reminderDecision ACCEPT|ADJUST|CHANGE_REQUIRED
  finalReminderTimes? date-time[0..8]
  reason? string

ApproveWorkPlan
  expectedVersion int64 >= 0
  comment? string
  decisions ItemDecisionInput[1..100]

ReturnWorkPlan
  expectedVersion int64 >= 0
  comment string
  decisions ItemDecisionInput[1..100]

RejectWorkPlan
  expectedVersion int64 >= 0
  reason string

WithdrawWorkPlan
  expectedVersion int64 >= 0
  reason? string
```

POST创建时禁止提交`itemId`。PUT只允许对当前`DRAFT`修订执行完整替换：已有`itemId`必须属于该修订，省略表示新增，未出现在请求中的原DRAFT项表示删除；重复ID返回400 `WORK_PLAN_ITEM_DUPLICATE`，未知、跨修订或跨租户ID统一返回404 `WORK_PLAN_ITEM_NOT_FOUND`且不泄露存在性。

创建时服务端派生所有者任职、周期结束日、时区、主管任职、来源、验收人和组织。批准必须恰好覆盖当前修订全部项，内容决定只能`ACCEPT`，截止和提醒可`ACCEPT/ADJUST`；调整必须有最终值和原因。退回也必须覆盖全部项，至少一个字段为`CHANGE_REQUIRED`且逐项给出原因。上述安全派生字段禁止出现在请求体。

### 9.2 工作计划响应模型

```text
WorkPlanSummary
  id, planNo, planType, periodStart, periodEnd,
  ownerAssignmentId, ownerName, ownerPositionName,
  ownerOrganizationId, ownerOrganizationName,
  status, currentRevisionId, currentRevisionNo, rowVersion,
  submittedAt?, managerAssignmentId?, managerName?

WorkPlanDetail
  WorkPlanSummary全部字段, timezone, currentRevision, reviews[], taskLinks[]

WorkPlanRevision
  id, revisionNo, status, summary?, contentHash, previousRevisionId?,
  submittedByAssignmentId?, submittedAt?, managerAssignmentId?, managerName?, items[]

WorkPlanItem
  id, itemNo, title, description?, priority, proposedDueAt, proposedReminderTimes[]

WorkPlanReview
  id, revisionId, action APPROVE_AND_DISPATCH|RETURN|REJECT,
  reviewerAssignmentId, reviewerName, comment?, reviewedAt, decisions[]

WorkPlanDecision
  itemId, contentDecision, dueAtDecision, finalDueAt?,
  reminderDecision, finalReminderTimes[], reason?

TaskLink
  itemId, decisionId, taskId, taskNo, lifecycleStatus, creationSource=WORK_PLAN
```

### 9.3 高管交办模型

```text
ExecutiveTaskTarget
  assignmentId, employeeName, positionCode, positionName,
  organizationId, organizationName

CreateExecutiveTask
  clientCommandId uuid
  targetAssignmentId uuid
  title string
  description string
  priority LOW|NORMAL|HIGH|URGENT
  dueAt date-time
  reminderTimes date-time[0..8]

ApproveExecutiveTask
  expectedVersion int64 >= 0
  comment? string

ReworkExecutiveTask
  expectedVersion int64 >= 0
  reason string

ExecutiveTaskSummary
  id, taskNo, title, lifecycleStatus, slaStatus, priority, dueAt, rowVersion,
  creationSource, orgUnitId, orgUnitName,
  assigneeAssignmentId, assigneeName, assigneePositionName,
  reviewerAssignmentId, reviewerName, progress

ExecutiveTaskDetail
  ExecutiveTaskSummary全部字段；仅本人权威交办可增加description、resultSummary、evidenceCount、reminderTimes[]
```

创建命令的`Idempotency-Key`必须等于`clientCommandId`的规范UUID文本。批准和退回请求也为`additionalProperties: false`，要求`Idempotency-Key`，成功返回200 `ExecutiveTaskDetail`。客户端不得提交`reviewerAssignmentId`、`creationSource`、`orgUnitId`、目标岗位代码、董事长任职或`sourceSnapshot`。

任务响应顶层可信`creationSource`固定为`LEGACY|MANUAL|RULE_ENGINE|TASK_CANDIDATE|WORK_PLAN|CHAIRMAN_DIRECTIVE`；前端任务模型使用该字段，不再从`sourceType`或`source_snapshot`推断。

### 9.4 成功与错误

- 列表成功为200数组，GET空结果为200空数组；创建成功为201；其他成功命令为200并返回最新详情。相同命令重放返回首次成功的相同状态码和语义结果。
- 缺少必填业务任职头返回400 `BUSINESS_ASSIGNMENT_REQUIRED`；格式错误返回400 `BUSINESS_ASSIGNMENT_INVALID_FORMAT`。
- 任职不存在、跨租户、非本人、停用或过期统一返回403 `BUSINESS_ASSIGNMENT_FORBIDDEN`，不得泄露具体原因；请求体兼容操作者不一致返回403 `BUSINESS_ACTOR_MISMATCH`；本人任职岗位不符合动作返回403 `BUSINESS_ASSIGNMENT_ROLE_MISMATCH`。
- 登录身份、账号或JWT无效返回401 `AUTHENTICATION_FAILED`。业务任职异常必须使用独立异常映射，不能继续复用当前统一401的`IdentityAuthenticationException`。
- 格式、枚举、周期、数量、时间或请求体不合法返回400稳定错误码；权限、精确主管、参与人或目标不满足返回403。
- 功能关闭或当前租户不在白名单返回404 `FEATURE_NOT_ENABLED`；资源不存在、不可见或跨租户同样返回404且不泄露真实存在性。
- 版本/状态漂移、同周期计划冲突、同幂等键异载荷或命令仍在并发处理中返回409稳定错误码。
- RFC 9457 `application/problem+json`中的`Problem.code`为必填字符串，稳定错误码只放在该字段；`type/title/status/detail/instance`保持标准语义，前端不得解析中文`detail`判断错误类型。

## 10. 前端冻结

- Web传输身份字段固定为`ApiIdentity.businessActorAssignmentId`，客户端只把它映射到`X-Assignment-Id`；不得继续用泛义`ApiIdentity.assignmentId`表示请求演员。业务对象自身的负责人/目标`assignmentId`字段不受此命名约束。
- `App.tsx`保留CEO账号级展示，同时维护独立`businessActorAssignmentId`并显示任职选择器。初次`/iam/me`请求可不带任职以取得完整列表；业务页面选择后，后续请求持续携带该任职，进入配置页面也不得清空它或降低CEO账号级权限。
- 导航、直接路由和动作按钮必须同时检查`capabilities.groupManagement`与权限；capability不是授权替代品。功能关闭时深链不得加载业务数据。
- 新增懒加载`features/workPlans/`和`features/executiveTasks/`，不得继续把完整页面堆入`App.tsx`。
- 新增工作计划导航、我的计划、待我审批、下属计划和董事长集团计划只读视图。
- `WorkPackageService.teamExpectations()`和`WorkDataService`团队读取同时接受`work-record.team-read|work-record.review`，但详情范围继续按当前业务任职直属/间接关系校验，复核和写动作仍只接受`work-record.review`及精确参与人；不得由团队只读隐式获得附件、写入或复核能力。
- 董事长任务中心显示专用“交办高管任务”，不得复用通用任务创建弹窗或`/tasks/targets`。
- 董事长工作台的任务卡片和计数必须改用`/executive-tasks`专用最小投影，不能因其内部持有`task.review`而调用通用`/tasks`；其他角色工作台行为保持不变。
- 董事长业务路由集合必须精确等于`workbench, hotel-dashboard, operations-dashboard, team-work, tasks, daily-reports-my, daily-operations, evaluations, notifications, work-plans`。明确禁止`all-functions, work-packages, rules, kpi-center及子路由, investments及子路由, templates, daily-report-templates, organization, wecom-webhooks, wecom-bindings, wecom-onboarding, my-work`。
- 董事长移动端“我的”只含修改密码/退出的账号自助弹层，不得复用`all-functions`功能目录；“区域多门店”改为中性“多门店经营”或角色化“集团多门店”。
- 角色展示固定为：`CEO/GROUP_GENERAL_MANAGER`→“集团总经理（集团CEO）”、`GROUP_CHAIRMAN`→“集团董事长”、`GROUP_VICE_PRESIDENT`→“集团副总经理”、`HR_ADMINISTRATION_SUPERVISOR`→“行政人事主管”、`HR_ADMINISTRATION`→“行政人事”、`OTA_OPERATION_MANAGER`→“OTA运营经理”。
- 移除“行政人事”到`HR_KPI_ADMIN`及`REGIONAL_MANAGER/区域经理/区域运营经理/区域/运营`到`OTA_OPERATION_MANAGER`的泛化别名。
- `WORK_PLAN_*`通知先于通用`WORK_*`规则路由到工作计划详情。
- 计划生成任务与董事长交办任务只按顶层`creationSource`显示可信来源标签，删除任务模型以`sourceType/sourceSnapshot`推断来源的逻辑。
- 实施批次A同步版本落点：`docs/openapi.yaml`、`apps/core-api/pom.xml`、`apps/web/package.json`、`apps/web/src/product.ts`、`apps/web/.env.pilot`和现存PILOT.7硬编码；新建PILOT.8证据与部署脚本，不覆盖PILOT.7历史资产。

## 11. 代码影响范围

后端主要修改：

- `apps/core-api/src/main/resources/application.yml`
- 新增`shared/features/GroupManagementFeatureGate.java`
- `shared/context/TenantPrincipal.java`
- `shared/context/TenantContextFilter.java`
- `shared/security/EffectiveIdentityService.java`
- `shared/security/AccessPolicy.java`
- `shared/api/ApiExceptionHandler.java`
- `iam/IamService.java`、`iam/IamModels.java`
- `tasks/TaskController.java`、`TaskService.java`、`TaskModels.java`、`TaskTargetPolicy.java`
- 新增`tasks/ExecutiveTaskController.java`、`ExecutiveTaskService.java`
- 新增`workplans/WorkPlanController.java`、`WorkPlanService.java`、`WorkPlanModels.java`、`WorkPlanReminderService.java`
- `workpackage/WorkPackageService.java`、`workdata/WorkDataService.java`
- `shared/events/ManagementAutomationWorker.java`、`AutomationWorkerMetrics.java`

前端主要修改：

- `apps/web/src/App.tsx`
- `apps/web/src/domain.ts`、`product.ts`、`P0Pages.tsx`、`Pilot6Pages.tsx`
- `apps/web/src/api/client.ts`、`api/resources.ts`
- `apps/web/src/app/permissions.ts`、`routeConfig.ts`、`rolePresentationPolicy.ts`
- `apps/web/src/app/notificationNavigation.ts`
- `apps/web/src/shared/MobileAppNavigation.tsx`
- `apps/web/src/data/roles.ts`
- 新增`apps/web/src/features/workPlans/`
- 新增`apps/web/src/features/executiveTasks/`

契约与数据：

- `database/migrations/V39__group_management_roles_and_work_plans.sql`
- `docs/openapi.yaml`、`docs/API.md`
- `apps/core-api/pom.xml`、`apps/web/package.json`、`apps/web/.env.pilot`
- 相关后端集成测试、Web合同测试和发布证据文档。

## 12. 实施批次

### A. 身份、迁移与契约底座

- 完成V39、角色/岗位/权限、可信任务来源和间接关系。
- 完成`GroupManagementFeatureGate`、`businessActorAssignmentId`全链路、稳定错误码、版本落点及OpenAPI类型。
- 功能保持关闭。

完成门槛：空库V1→V39、V38→V39、PILOT.7应用连接V39兼容、RLS、冲突失败原子性、开关启动失败保护和CEO账号级权限回归全部通过。

### B. 董事长交办纵向闭环

- 专用目标查询、创建/派发、任务来源、列表投影和本人验收/退回。
- 董事长页面与目标白名单负向测试。

完成门槛：董事长只可交办GM/副总；其他目标、来源伪造、敏感证据及非本人任务动作全部403。

### C. 周/月计划纵向闭环

- 草稿、修订、提交、直属审批、退回/拒绝/撤回及批准转任务。
- 工作计划页面、任务链接、来源标签和通知路由。

完成门槛：N项批准恰好生成N任务，任何中途故障零副作用；间接领导只能团队只读。

### D. 提醒、全量回归与Pilot启用准备

- 提醒Worker、租约、重试、死信和告警。
- 全角色、跨租户、一人多岗、浏览器、移动端、OpenAPI和发布门禁回归。
- 生成真实人员任职启用清单和岗位方案差异清单。

完成门槛：所有测试和业务验收通过，功能仍保持关闭，等待单独部署批准。

## 13. 测试与证据门禁

必须覆盖：

- JWT与开发头的业务任职绑定、缺失/伪造/他岗任职。
- CEO账号级配置权与GM业务动作任职分离。
- 董事长目标白名单、权威来源、防伪、敏感字段和参与人动作。
- 副总/行政人事主管直属审批、行政人事普通岗拒绝提交、间接领导只读。
- 区域经理不存在且OTA历史零改写。
- 周/月周期、修订不可变、逐项决定、并发审批、请求哈希、幂等碰撞和事务回滚。
- V1→V39、V38→V39、SYSTEM/CUSTOM冲突、CEO岗位方案唯一冲突、RLS、跨租户复合外键、直属/间接关系环和并发环。
- 提醒领取、租约恢复、退避、死信、通知幂等和任务结束取消。
- 现有身份、任务、岗位方案、KPI、企业微信、驾驶舱、工作包和发布门禁全量回归。
- Web角色正负别名、任职选择、工作计划路由、通知、专用交办入口、来源标签、移动端和构建。
- 开关全关、布尔开关开启但租户白名单为空/不含当前租户、深链访问和API直调的关闭行为。
- PILOT.7旧应用只读/运行于V39结构的向后兼容，以及PILOT.8制品回退到PILOT.7的演练。

浏览器门禁至少在桌面1440×900和移动端390×844各执行一次：董事长导航集合必须与白名单精确相等；所有禁止路由不请求配置API；CEO选择GM任职后刷新仍携带同一请求头且配置权不降级；董事长未选/错选/伪造任职、目标篡改、非本人任务动作和证据直调全部按合同失败；工作计划只读/审批按钮、`WORK_PLAN_*`通知、顶层来源标签和移动端“我的”均符合冻结。全程不得有未捕获Console错误、401循环或越权2xx，并保存截图、网络断言及角色—路由JSON证据。

建议验证命令：

```powershell
mvn -f apps/core-api/pom.xml test
node --test --experimental-strip-types apps/web/tests/*.test.mjs
pnpm --dir apps/web build
pnpm --dir apps/web build:pilot
```

不得用现有PILOT.7测试通过替代PRODUCT-V1.4新增验收证据。

## 14. Pilot数据启用门禁

部署前必须提供每个目标租户的明确清单：

- 集团根组织。
- CEO账号对应的员工和唯一集团总经理任职。
- 董事长员工与任职。
- 集团副总经理员工与任职。
- 行政人事主管、行政人事员工与任职。
- 两个人事任职对应的具体间接领导副总任职。
- 其他计划管理岗位的有效直属主管。
- 人工编辑或减权岗位方案的逐项差异确认。

多CEO、多集团根、同码CUSTOM角色、同码岗位墓碑、其他岗位已占用CEO默认角色、失效主管、自指或成环时一律停止启用，不自动选择。

## 15. 发布与变更控制

- 本技术冻结不授权生产部署，也不把当前技术发行从TECH-V0.1改为TECH-V0.2。
- 开始编码、完成每个批次、进入Pilot、正式部署都必须分别更新Change Log、技术版本状态和证据。
- V39后尚无新业务写入时，只有迁移前备份恢复演练通过才可恢复旧库；一旦产生PILOT.8写入，不得用旧备份覆盖，必须关闭功能、回退经V39兼容验证的应用制品并前向修复。
- 修改角色代码、区域经理状态、CEO/GM同一性、董事长目标、两个人事岗位直属关系、部分批准策略、任务来源权威关系、V39边界或业务任职模型，必须先更新产品/需求/技术冻结。
- 当前剩余外部依赖只有真实人员任职启用清单；它不阻塞通用代码实现，但阻塞目标租户功能开启和部署。
