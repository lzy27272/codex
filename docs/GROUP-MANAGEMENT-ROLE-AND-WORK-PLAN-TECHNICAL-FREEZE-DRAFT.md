# 集团管理角色与周/月工作计划技术冻结草案 V0.2

> 历史状态（2026-09-11）：本草案评审完成，已由`docs/GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE.md`（TECH-DESIGN-1.0）取代为当前正式技术合同。本文仅保留评审轨迹；代码、迁移和部署仍未开始。

| 项目 | 当前值 |
|---|---|
| 文档状态 | DRAFT-V0.2 / DESIGN INPUT FROZEN / TECHNICAL REVIEW PENDING / NOT CODED / NOT DEPLOYED |
| 产品基线 | PRODUCT-V1.4 |
| 需求冻结 | GROUP-MANAGEMENT-WORK-PLAN-V1 / DESIGN-1.1 FROZEN |
| 代码基线 | `main@788cfe724c082fc9716f28b66c276ac81c9488e9` |
| 技术版本 | 候选`TECH-V0.2-PILOT.8`；开工批准时确认，不改变当前技术发行 |
| API边界 | 候选在`/api/v1`向后兼容新增 |
| 数据库边界 | 当前历史迁移止于V38；实施时从下一可用编号追加 |

本草案把已经冻结的业务规则翻译为可评审的技术合同。它不是开工、迁移或部署授权；`docs/openapi.yaml`、运行代码和数据库在本轮保持不变。

## 1. 现有能力复用与禁止误用

复用：

- `employee_position_assignment.manager_assignment_id`作为直属主管来源。
- 既有`management_task`、`task_participant`、状态迁移、审计、事务Outbox和命令幂等能力。
- 日报的修订、提交、审核和不可变历史模式。
- 现有通知及统一自动化Worker。
- V37已经冻结的“导航模块权限与API动作权限分离”原则。

禁止误用：

- `work_package`是已发布岗位模板，不是员工提交的计划。
- `daily_report`是已完成工作的汇报，不是未来计划。
- `task_escalation`是逾期升级，不得冒充到期前提醒。
- 角色优先级只用于主展示，不得推断直属主管。
- 现有门店任务目标解析含门店祖先限制，不能未经修改直接用于集团任职。

## 2. 角色和岗位变更候选

- 新增SYSTEM角色及同码岗位`GROUP_CHAIRMAN`，集团根组织、岗位族`GROUP_MANAGEMENT`。董事长必须有真实有效任职，供任务发起人、`REVIEWER`和审计使用；该岗位本身不授予任何管理后台权限。
- 不新增`GROUP_GENERAL_MANAGER`系统角色。新增唯一岗位`GROUP_GENERAL_MANAGER`，展示名“集团总经理（集团CEO）”，岗位族`GROUP_MANAGEMENT`、集团根组织；其默认岗位方案映射既有SYSTEM角色`CEO`。
- 实施迁移需要为既有CEO账号建立或补齐员工、`GROUP_GENERAL_MANAGER`岗位任职；既有`CEO`账号角色和配置治理权限保持不变，但计划审批、汇报和任务参与人仍必须使用精确任职ID。
- 身份模型必须把“账号级授权范围”与“本次业务动作任职”拆开。扩展请求主体保留经服务端验证的`businessActorAssignmentId`；即使`CEO`仍属于`FULL_ACCOUNT_LEVEL_ROLES`，也不得丢弃客户端明确选择的有效`GROUP_GENERAL_MANAGER`任职。
- 沿用并把`GROUP_VICE_PRESIDENT`角色和岗位展示名统一为“集团副总经理”；保留历史ID、授权和任职。
- 新增SYSTEM角色及同码岗位`HR_ADMINISTRATION`和`HR_ADMINISTRATION_SUPERVISOR`，分别表示行政人事普通岗位和行政人事主管管理岗位；两者均位于集团根组织。
- 不复用、重命名或默认附带既有补充角色`HR_KPI_ADMIN`。如确需KPI管理能力，必须对具体账号另行显式授权。
- 不新增或启用`REGIONAL_MANAGER`，也不把`OTA_OPERATION_MANAGER`映射、别名或显示为区域经理。现有OTA角色、岗位、任职和数据保持原义。
- 更新保留系统角色代码、主展示优先级、前端展示策略和岗位默认方案；特别移除把“人事/行政人事”泛化显示为`HR_KPI_ADMIN`的别名，只保留“行政人事KPI管理员”的精确展示。
- 集团副总经理及新增行政人事岗位不会因岗位建立而自动成为IAM或系统配置管理员。

## 3. 数据模型候选

所有租户表必须含`tenant_id`、同租户复合外键、必要索引、`ENABLE/FORCE ROW LEVEL SECURITY`和租户策略；时间字段使用`TIMESTAMPTZ`，写模型使用`row_version`。

### 3.1 计划聚合

- `management_work_plan`：计划编号、`WEEKLY/MONTHLY`、周期起止、时区快照、所有者任职/组织、聚合状态、当前修订和乐观锁版本。聚合状态只允许`DRAFT/PENDING_APPROVAL/APPROVED/REJECTED`；唯一键为租户+所有者任职+类型+周期起始日。
- `management_work_plan_revision`：修订号、`DRAFT/SUBMITTED/APPROVED/RETURNED/REJECTED/WITHDRAWN`状态、摘要、内容哈希、前序修订、提交账号/任职/时间、提交时直属主管任职及主管快照。主管字段在草稿中为空、提交时冻结，确保每次修订保留自己的审批路由历史。提交后不可原地修改。
- `management_work_plan_item`：稳定项编号、排序、标题、说明、优先级和提议完成时间。单修订最多100项。
- `management_work_plan_item_reminder`：提议提醒时间，单项最多8个且唯一；提交时必须晚于提交时间并早于完成时间，审批后的最终提醒还必须晚于批准时间。

### 3.2 审批和任务链接

- `management_work_plan_review`：追加式总决定、审核账号/任职、意见、跟踪ID和时间；退回/拒绝意见必填。
- `management_work_plan_item_decision`：逐项任务内容决定、完成时间决定及最终值、提醒决定、调整原因。
- `management_work_plan_item_decision_reminder`：逐项批准后的最终提醒时间。
- `management_work_plan_task_link`：计划、修订、计划项、审批决定和任务的不可重复链接；使用`UNIQUE(tenant_id,item_decision_id)`和`UNIQUE(tenant_id,task_id)`保证每个已批准计划项恰好关联一个任务且任务不被重复挂接。
- `task_reminder`：任务、接收任职、提醒时间、`PENDING/PROCESSING/RETRY_WAIT/SENT/CANCELLED/DEAD_LETTER`、幂等键、`locked_by/locked_until/next_attempt_at/attempt_count/last_error/sent_at`。接收任职固定为任务`ASSIGNEE`，不自动包含审批人；对可领取状态和`next_attempt_at`建立局部索引。

提交后的修订、审批记录、逐项决定和任务链接必须通过权限与数据库保护保持历史不可变。

### 3.3 直属与间接领导关系

- 继续以`employee_position_assignment.manager_assignment_id`唯一表达直属主管。集团总经理任职指向有效董事长任职；集团副总、行政人事主管、行政人事三类任职都指向集团总经理（集团CEO）的有效`GROUP_GENERAL_MANAGER`任职。集团总经理不提交计划，因此董事长不进入工作计划审批队列。
- 新增候选表`position_assignment_reporting_relation`表达非直属关系：租户、下属任职、领导任职、关系类型、有效期、创建账号、行版本和审计时间。V1.1关系类型只开放`INDIRECT_LEADER`。
- 为行政人事主管和行政人事分别建立指向具体集团副总经理任职的`INDIRECT_LEADER`关系。多位副总时必须显式选择，不得按角色代码自动扩散。
- 直属和间接关系都必须使用同租户复合外键、有效期校验、禁止自指与成环，并启用/强制RLS。
- 间接关系首期只参与有权限的`team`只读查询，不进入`approval`查询、主管解析、工作计划审批、任务`REVIEWER`解析或代理授权。

### 3.4 董事长交办任务来源

- 复用现有`management_task`、`task_participant`和状态迁移；董事长、负责人和验收人都绑定精确`employee_position_assignment`，不扩展为账号级参与人。
- `source_snapshot`必须保存`creationSource=CHAIRMAN_DIRECTIVE`、董事长任职、目标任职、目标岗位代码和命令关联ID；工作计划任务继续使用`creationSource=WORK_PLAN`。
- 董事长交办任务幂等键固定为`chairman-directive:{chairmanAssignmentId}:{clientCommandId}`并受现有`UNIQUE(tenant_id,idempotency_key)`保护。

## 4. 状态机

### 4.1 工作计划

计划聚合：

```text
DRAFT → PENDING_APPROVAL → APPROVED
                 ├──────→ RETURN_AND_CREATE_REVISION → DRAFT
                 ├──────→ WITHDRAW_AND_CREATE_REVISION → DRAFT
                 └──────→ REJECTED
```

`RETURN_AND_CREATE_REVISION`与`WITHDRAW_AND_CREATE_REVISION`都是单事务命令：旧修订分别写`RETURNED/WITHDRAWN`，创建并复制直属主管字段为空的新`DRAFT`修订，计划聚合写`DRAFT`且`current_revision_id`指向新修订。再次提交时重新解析并冻结当时的直属主管。`RETURNED/WITHDRAWN`不作为计划聚合状态；同一任职、类型和周期始终只有一个计划聚合。

批准动作只有`APPROVE_AND_DISPATCH`。该动作必须在一个短数据库事务中完成：

1. `SELECT ... FOR UPDATE`锁计划与当前修订，校验`expectedVersion`和幂等键。
2. 校验审核账号持有冻结的主管任职，该任职仍有效、非本人、同租户且范围覆盖。
3. 校验每个计划项三类决定完整，提议完成时间晚于提交时间，最终完成时间晚于批准时间且位于周期内，最终提醒晚于批准时间并早于最终完成时间。
4. 追加审批及逐项决定。
5. 调用无公网入口的`TaskService.createFromApprovedWorkPlan`，每项创建`management_task`，写`ASSIGNEE`/`REVIEWER`，执行`CREATE`和`DISPATCH`到`PENDING_ACK`；该路径不得经过现有带HOTEL祖先限制的手工任务目标解析。
6. 写任务链接、提醒、审计和事务Outbox后提交。

事务内不得调用外部HTTP或切换成平台/系统管理员上下文。任何一步失败全部回滚。任务键字符串固定为`work-plan:{planId}:{revisionId}:{itemId}`并由`UNIQUE(tenant_id,idempotency_key)`约束；同一命令重试返回原结果，并发状态漂移返回409。任务`created_by`、状态迁移、审计和Outbox的因果操作者均为审批账号/任职，来源快照记录`creationSource=WORK_PLAN`、计划修订和审批决定。

### 4.2 董事长交办任务

交办命令走专用的`ExecutiveTaskService.assignByChairman`候选服务：

1. 验证操作者持有有效`GROUP_CHAIRMAN`任职和`executive-task.assign`。
2. 验证负责人是同租户、集团根组织下有效的`GROUP_GENERAL_MANAGER`或`GROUP_VICE_PRESIDENT`任职。
3. 在一个事务中创建`management_task`，写入董事长为`REVIEWER`、目标任职为`ASSIGNEE`，追加`CREATE`和`DISPATCH`迁移到`PENDING_ACK`。
4. 写来源快照、审计和事务Outbox后提交；重复命令返回原任务。

该服务不得复用当前带HOTEL祖先限制的门店目标解析，也不得把TENANT数据范围解释成“可向租户内任意岗位派发”。董事长后续`APPROVE/REWORK`仍走任务参与人校验，只有当前董事长任职恰为任务有效`REVIEWER`时才允许；V1.1不开放取消、转派或编辑已派发任务。

## 5. 主管解析和范围校验

- 提交前从所有者有效任职读取`manager_assignment_id`，不得按角色名回退或自动越级。
- 验证主管与所有者同租户、有效期覆盖提交时刻、不是同一任职、属于管理岗、组织范围覆盖且汇报图无环。
- 提交时保存主管任职快照；审批只接受该精确任职。
- 主管在待审期间失效时返回可识别冲突，提交人撤回并按新关系重新提交。
- `GROUP_VICE_PRESIDENT`、`HR_ADMINISTRATION_SUPERVISOR`和`HR_ADMINISTRATION`的直属主管均冻结为精确`GROUP_GENERAL_MANAGER`任职；其中只有前两者属于工作计划管理岗。集团副总的间接领导关系不能进入审批查询或代替该字段。
- 既有`CEO`账号级权限、通配权限或配置管理员身份都不能替代`GROUP_GENERAL_MANAGER`任职，也不能绕过“必须是提交时冻结直属主管”的条件。
- 管理岗提交资格来自已发布岗位权限方案中的`work-plan.submit`。V1.1默认代码集合固定为`GROUP_VICE_PRESIDENT`、`HR_ADMINISTRATION_SUPERVISOR`、`OTA_OPERATION_MANAGER`、`GENERAL_MANAGER`、`ASSISTANT_GENERAL_MANAGER`、`FRONT_OFFICE_SUPERVISOR`、`HOUSEKEEPING_SUPERVISOR`，且排除集团总经理、董事长、`HR_ADMINISTRATION`和一线岗位。
- `OTA_OPERATION_MANAGER`只按OTA运营经理本义保留，其主管必须显式配置；系统不存在区域经理别名、回退或中间路由。
- 自定义管理岗位必须显式配置权限，不按名称或`level_code`推断。系统未人工修改的默认岗位方案可受控升级；已经人工编辑或门店减权的方案只生成差异清单，由授权管理员逐项发布，不得静默覆盖。

## 6. 权限候选

| 权限代码 | 用途 | 岗位可配置 |
|---|---|---:|
| `ui.module.work-plans` | 显示工作计划模块 | 是 |
| `work-plan.read` | 查看本人任职计划 | 是 |
| `work-plan.submit` | 新建、修订、提交和撤回本人计划 | 是 |
| `work-plan.team-read` | 查看组织范围内下属计划 | 是 |
| `work-plan.review` | 审批计划；仍强制直属主管校验 | 是 |
| `work-record.team-read` | 只读查看授权团队工作记录 | 是 |
| `task.read` | 按角色数据范围或任务参与人查看任务；读取范围不产生动作权 | 是 |
| `task.act` | 执行本人任职作为有效`ASSIGNEE`参与的任务 | 是 |
| `task.review` | 验收本人任职作为有效`REVIEWER`参与的任务 | 是 |
| `executive-task.assign` | 董事长向GM/副总交办任务；后端固定目标白名单 | 否，仅董事长系统岗位方案 |

- 董事长不授予任何工作计划写入或审批动作；工作计划模块只提供租户级`work-plan.team-read`。
- 董事长业务读取及交办权限必须使用闭合白名单：`dashboard.ceo`、`dashboard.hotel`、`dashboard.operations`、`work-record.read`、`work-record.team-read`、`task.read`、`task.review`、`executive-task.assign`、`daily-report.read`、`daily-report.team-read`、`daily-operation.read`、`daily-operation.cross-hotel-read`、`operation-snapshot.read`、`operation-snapshot.compare`、`evaluation.read`、`notification.read`、`work-plan.team-read`。其中`task.review`仍受精确`REVIEWER`参与人限制。不得通过`*`、角色别名或配置管理员特殊分支扩权。
- 董事长导航模块白名单为`workbench`、`hotel-dashboard`、`operations-dashboard`、`team-work`、`tasks`、`daily-reports-my`、`daily-operations`、`evaluations`、`notifications`、`work-plans`；不授予`all-functions`和任何配置模块。
- 团队工作查询允许`work-record.team-read`只读访问，复核动作继续单独要求`work-record.review`；前端也必须按两项权限分别控制查看与审核按钮。
- 董事长查询采用TENANT范围但必须字段最小化，明确排除薪酬、奖金、个人联系方式、凭证、密钥及批量导出。
- 董事长的TENANT范围`task.read`允许字段最小化地读取全集团任务；`task.review`只允许当前董事长任职作为有效`REVIEWER`且`creationSource=CHAIRMAN_DIRECTIVE`的任务。读取范围不得被复用为动作范围。
- 董事长不授予通用`task.create`、`task.dispatch`、`task.act`或`task.cancel`；`executive-task.assign`只能通过专用服务命中`GROUP_GENERAL_MANAGER`和`GROUP_VICE_PRESIDENT`任职。
- 集团总经理岗位映射既有`CEO`角色并拥有`team-read/review`，不拥有`work-plan.submit`；既有CEO配置治理权限不在本批迁移中削减。
- 集团副总经理及以下管理岗位方案静态拥有`read/submit/team-read/review`；运行时按精确`manager_assignment_id`过滤，没有直属下属时返回空列表且不能审批。
- `HR_ADMINISTRATION_SUPERVISOR`按管理岗获得计划权限；`HR_ADMINISTRATION`默认不获得`work-plan.submit/team-read/review`或`task.review`，只按本人`ASSIGNEE`参与人获得`task.read/task.act`。
- `view=approval`只按冻结直属主管任职过滤；`view=team`才可把显式`INDIRECT_LEADER`关系纳入只读范围。间接关系不能产生审批或任务验收资格。
- 所有计划审批岗位方案还必须静态拥有`task.read/task.review`；TaskService必须校验当前任职是目标任务的有效`REVIEWER`，权限本身不能越过参与人边界。
- 通配权限不能绕过“必须是冻结直属主管”的业务条件。
- 自动建任务使用上述内部专用服务，不向审批人扩散通用任务创建/派发权限。

## 7. API候选

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
GET  /api/v1/executive-tasks/targets
POST /api/v1/executive-tasks
```

- `GET /api/v1/iam/me`按多任职模型返回董事长、集团总经理和集团副总经理的有效任职，并区分账号级授权上下文与可选业务动作任职。
- 请求继续用`X-Assignment-Id`传递业务任职选择。身份解析需要新增`businessActorAssignmentId`语义：先验证该任职属于当前账号、同租户且有效，再保留到请求主体；对`CEO`仍按既有账号级角色计算权限和数据范围，但不得像现状一样因`fullAccountLevel`而清空所选任职。
- 工作计划审批、任务验收及需要记录业务操作者任职的命令必须要求`businessActorAssignmentId`。总经理动作还要验证岗位代码恰为`GROUP_GENERAL_MANAGER`；缺失、选择本人其他任职或伪造任职返回403。
- Web身份状态必须单独保存`businessActorAssignmentId`，不能因账号含`CEO`而把所选任职强制设为`undefined`；账号级配置页面仍可使用既有CEO授权范围，不把它错误收窄为岗位权限。
- 董事长目标查询只返回同租户、集团根组织下有效`GROUP_GENERAL_MANAGER`和`GROUP_VICE_PRESIDENT`任职；POST命令必须再次执行相同服务端校验，不能信任查询结果或前端岗位代码。
- `POST /api/v1/executive-tasks`要求`executive-task.assign`、董事长任职、`Idempotency-Key`以及标题、说明、优先级、完成时间、提醒和一个目标任职；成功后原子返回已到`PENDING_ACK`的任务。
- 董事长验收或退回复用现有任务状态命令和`task.review`，但仍要求当前董事长任职是目标任务的有效`REVIEWER`且来源为`CHAIRMAN_DIRECTIVE`。
- 所有命令必须携带`Idempotency-Key`；更新及状态命令携带`expectedVersion`。
- 版本或状态冲突返回409；无权限、非精确主管或范围外访问返回403；不存在或不可见资源不泄露跨租户信息。
- 批准和退回请求都必须提交全部计划项的三类决定；退回至少一个决定不同意，每个不同意或要求调整项含原因。拒绝请求必须含整单原因。
- 当前OpenAPI不在本轮修改；实现时以本节为输入补充正式契约。

## 8. 页面候选

- 新增`工作计划`导航模块。
- 页面包含“我的计划”“待我审批”“下属计划”和董事长“集团计划”只读视图。
- “待我审批”只显示当前任职是冻结直属主管的计划；“下属计划”可在授权后显示`INDIRECT_LEADER`关系覆盖的只读记录，但不渲染审批按钮。
- 周/月列表复用统一交互，草稿编辑与提交后只读状态明确区分。
- 审批页逐项展示提议内容、提议完成时间、提议提醒和主管最终值。
- 批准后展示生成任务链接；董事长集团计划视图不渲染计划写入或审批按钮。
- 董事长任务中心增加“交办高管任务”入口，目标选择器只显示集团总经理（集团CEO）和集团副总经理有效任职；任务列表用“董事长交办”和“工作计划生成”来源标签明确区分。
- 董事长仍不显示`all-functions`或任何管理后台配置导航。
- CEO/集团总经理在工作计划和任务业务页面必须选择有效总经理任职；页面传递`businessActorAssignmentId`，但不得借此改变其既有账号级配置授权。

## 9. 提醒执行候选

- 沿用统一自动化Worker，以`FOR UPDATE SKIP LOCKED`领取`PENDING/RETRY_WAIT`且已到`next_attempt_at`的提醒，原子写`PROCESSING`和有限租约；崩溃后由过期租约恢复。
- 失败采用有上限的指数退避进入`RETRY_WAIT`，超过阈值进入`DEAD_LETTER`并告警；`(tenant_id,idempotency_key)`唯一。
- Worker在同一事务内幂等创建唯一站内`notification`和Outbox事件后把提醒记为`SENT`。这保证内部效果不重复；外部通道只承诺至少一次尝试，并在供应商支持时传递幂等键，不宣称网络投递严格一次。
- 任务进入`COMPLETED`或`CANCELLED`时取消未发送提醒。
- 失败记录次数和脱敏错误，进入统一告警；不得在锁事务内调用外部通知通道。

## 10. 必须通过的测试

- 角色/岗位种子、展示名兼容、既有CEO和副总账号/任职/授权不丢失。
- 不存在新增`GROUP_GENERAL_MANAGER`角色；同码岗位只映射既有`CEO`角色，CEO账号补齐真实GM任职且不会形成CEO+同义角色权限叠加。
- CEO账号级治理权限继续有效；正确选择`GROUP_GENERAL_MANAGER`任职时可按参与人审批/验收，未选择、选择本人其他任职或伪造任职时均返回403。后端主体和Web请求都保留相同`businessActorAssignmentId`。
- 董事长有有效`GROUP_CHAIRMAN`任职及全集团业务读取能力，但IAM、组织、岗位、模板、规则、KPI、企微、系统配置及通用任务创建均返回403。
- 董事长可以字段最小化读取全集团任务；对非本人交办、来源不是`CHAIRMAN_DIRECTIVE`或本人任职不是`REVIEWER`的任务，全部动作返回403。
- 董事长只能查询、创建并立即派发给有效`GROUP_GENERAL_MANAGER`或`GROUP_VICE_PRESIDENT`任职；向行政人事、主管、店总、OTA或其他岗位以及伪造目标全部返回403。
- 董事长能`APPROVE/REWORK`本人任职作为`REVIEWER`的`CHAIRMAN_DIRECTIVE`任务，不能验收其他任务；多次幂等重试只生成一个任务。
- 无`REGIONAL_MANAGER`种子、任职、别名、导航或审批路由；`OTA_OPERATION_MANAGER`保持本义和历史数据。
- `HR_ADMINISTRATION`、`HR_ADMINISTRATION_SUPERVISOR`角色岗位与显示准确，且不会继承或别名到`HR_KPI_ADMIN`。
- 两个人事岗位的`manager_assignment_id`都指向GM任职；指定副总的`INDIRECT_LEADER`关系可只读查看，但审批、代理和验收均返回403。
- `work-record.team-read`可以打开团队工作只读列表但不能复核，`work-record.review`仍是唯一复核权限。
- 默认管理岗位代码矩阵完整；`HR_ADMINISTRATION_SUPERVISOR`可提交，`HR_ADMINISTRATION`提交返回403；自定义、人工编辑和门店减权方案不被迁移静默扩权。
- `HR_ADMINISTRATION`可以读取、执行本人任职作为有效`ASSIGNEE`参与的任务，但默认`task.review`返回403。
- 集团总经理不能提交但能按GM任职审批直属副总和行政人事主管；董事长、间接领导、其他副总及平台管理员不能代审。
- 集团总经理和集团副总经理可验收自己批准并作为`REVIEWER`参与的生成任务，不能验收其他任务。
- 无主管、失效主管、自指、环、跨租户、范围外、越级、自审和管理员代审全部失败关闭。
- 周/月唯一性、周期时区、空清单、项数上限、提议完成时间晚于提交、最终完成时间晚于批准且位于周期、提醒约束。
- 提交不可变；退回/撤回命令原子更新旧修订、新草稿和聚合当前修订；拒绝、过期主管冲突及完整审计。
- N项批准恰好生成N个`PENDING_ACK`任务；参与人、完成时间、提醒、来源快照和链接准确。
- 幂等重试仍为N项，并发审批仅一次成功，中途失败整单零副作用。
- `WORK_PLAN`和`CHAIRMAN_DIRECTIVE`任务在来源、权限、目标、列表、审计和幂等键上严格分离。
- 提醒并发、租约过期、Worker重启、退避重试、死信告警、内部通知幂等、外部不确定回执及任务完成/取消后的取消逻辑。
- 所有新表空库安装与V38升级、强制RLS、跨租户/跨组织、一人多岗及现有全量回归。
- Web构建、导航/动作权限分离、桌面与移动端、浏览器控制台和可访问性回归。

## 11. 实施与发布门禁

1. 产品负责人确认本需求冻结，技术负责人批准本草案转为正式技术冻结。
2. 开工时确认目标TECH编号和下一可用Flyway编号；不得静默占用已规划给AI的`TECH-V0.3`。
3. 实施后同步OpenAPI、页面说明、测试报告、用户指南、Change Log和技术版本记录。
4. 先在隔离数据库完成V38升级与回滚演练，再进入Pilot。
5. 角色权限负向测试、原子任务生成、提醒可靠性和全量回归全部通过后，才可标记待验收。
6. 业务验收、制品校验、备份恢复和部署回滚证据齐全后，才可部署；本草案本身不授权部署。
