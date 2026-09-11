# 集团管理角色与周/月工作计划 V1.1 实施计划

| 项目 | 当前值 |
|---|---|
| 任务编号 | GROUP-MANAGEMENT-WORK-PLAN-V1 |
| 实施计划 | IMPLEMENTATION-PLAN-1.0 |
| 产品基线 | PRODUCT-V1.4 |
| 业务设计 | DESIGN-1.1 FROZEN |
| 技术设计 | TECH-DESIGN-1.0 FROZEN |
| 代码基线 | `main@788cfe724c082fc9716f28b66c276ac81c9488e9` |
| 目标技术版本 | `TECH-V0.2-PILOT.8` |
| 目标制品 | Core API / Web `0.2.0-pilot.8`，OpenAPI `0.2.5-pilot.8` |
| 目标数据库迁移 | `V39__group_management_roles_and_work_plans.sql` |
| 状态 | BATCH A/B CODE COMPLETE / FEATURES OFF / C—D NOT STARTED / NOT DEPLOYED |
| 编制/更新日期 | 2026-09-11 / 2026-09-12 |

本计划把正式技术冻结拆成可执行、可单独验收、可停止的实施批次。2026-09-11完成批次A，产品负责人随后明确“下一步”，2026-09-12完成批次B代码候选。当前授权不包含C/D、真实人员映射、Pilot启用或部署。正式技术合同以`../GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-TECHNICAL-FREEZE.md`为准；本计划与其冲突时，先停止实施并修订冻结，不以任务单覆盖技术合同。

## 1. 实施原则

- 只在收到明确“开始编码”指令后进入批次A。
- A、B、C、D依次设置门禁；上一批次未通过，不进入下一批次。
- 所有新能力默认关闭。`GROUP_MANAGEMENT_WORK_PLANS_ENABLED`、`GROUP_MANAGEMENT_EXECUTIVE_TASKS_ENABLED`、`GROUP_MANAGEMENT_REMINDER_WORKER_ENABLED`默认为false，`GROUP_MANAGEMENT_TENANT_IDS`默认为空；有效能力还要求当前租户进入白名单。完成代码和迁移不等于允许目标租户启用，更不等于允许生产部署。
- 只新增V39，不修改V1—V38；V39内的结构、角色、岗位、权限和默认方案原子提交。
- 不猜测真实董事长、CEO、总经理、副总经理或行政人事人员，不自动生成真实任职和汇报关系。
- 区域经理保持`DEFERRED / NOT IN USE`；禁止新增`REGIONAL_MANAGER`或把`OTA_OPERATION_MANAGER`解释为区域经理。
- 账号级授权、业务动作任职、直属审批、间接领导只读和任务参与人授权分别验证，不使用角色名称推断业务责任。
- 每个批次完成后更新Change Log、技术版本状态、自动化测试结果和遗留风险，再由负责人决定是否进入下一批次。

## 2. 批次和依赖

| 批次 | 目标 | 前置 | 结束状态 |
|---|---|---|---|
| A | 身份、V39、权限与API契约底座 | 明确开工授权 | 已完成本地编码与验证；功能关闭，未部署 |
| B | 董事长受限交办纵向闭环 | A全部通过 | 已完成本地编码与安全验证；董事长只能交办GM/副总并验收本人交办任务 |
| C | 周/月计划纵向闭环 | A、B全部通过 | 直属主管整单审批并原子生成任务 |
| D | 提醒、全量回归和Pilot启用准备 | A—C全部通过 | 形成可部署候选与逐租户启用包，仍不自动部署 |

推荐实施顺序：

```text
A0基线预检
  ├─ A1业务任职身份链
  ├─ A2单一V39迁移
  └─ A3 OpenAPI与前端身份合同
          ↓ A门禁
B1后端专用交办 → B2董事长页面 → B3安全验收
          ↓ B门禁
C1计划领域服务 → C2计划页面 → C3原子性与并发验收
          ↓ C门禁
D1提醒Worker → D2全量回归 → D3 Pilot数据包与发布评审
```

## 3. 批次A：身份、迁移与契约底座

### A0 基线与冲突预检

工作：

- 固定开工提交并确认工作树中无被误纳入的业务改动。
- 核对当前Flyway最高版本仍为V38，OpenAPI仍为`0.2.4-pilot.7`，Core API和Web仍为`0.2.0-pilot.7`。
- 对测试数据库执行同码CUSTOM角色、同码岗位墓碑、CEO默认角色方案占用、直属关系自指/环/失效数据预检。
- 输出“可自动迁移、需人工归并、阻断迁移”三类差异，不修改真实人员数据。

验收：预检结果可重复；任何冲突均失败关闭且给出租户、对象类型和稳定标识，不输出敏感个人字段。

### A1 业务动作任职身份链

任务ID：`GM-WP-A1`

主要文件：

- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/context/TenantPrincipal.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/context/TenantContextFilter.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/security/EffectiveIdentityService.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/security/AccessPolicy.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/iam/IamService.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/iam/IamModels.java`

工作：

- 增加可空`businessActorAssignmentId`，验证`X-Assignment-Id`属于当前账号、同租户且有效。
- 保持`assignmentIds`现有兼容语义；新增责任动作只以`businessActorAssignmentId`作为请求操作者，不把账号的其他任职并入动作授权。
- 保持CEO/平台管理员账号级权限和TENANT范围，同时保留显式选择的业务任职；受信账号级解析不带业务任职，不能直接执行责任动作。
- 本版本新增接口及本功能涉及的既有任务责任动作先校验当前业务任职；只有以CEO/总经理身份执行的审批或任务动作额外要求`GROUP_GENERAL_MANAGER`，董事长和其他岗位按各自合同校验。日报、KPI等其他既有责任接口只做回归，不在本批次重构。
- 新接口不接收操作者任职ID；兼容旧接口只比较`actor/creator/submittedBy/reviewer-as-command-actor`，不得把负责人、目标或被指定验收人误判为操作者。
- 增加稳定错误码：缺头/格式错误400，非本人或失效任职403，登录身份无效401；禁止继续把业务任职失败统一映射为认证失败。

验收：CEO配置后台回归不降权；CEO未选、错选、伪造总经理任职时责任动作403；平台管理员不能通过选任职代行总经理。

### A2 单一V39迁移

任务ID：`GM-WP-A2`

主要文件：`database/migrations/V39__group_management_roles_and_work_plans.sql`

工作：

- 增加三个SYSTEM角色、四个岗位、八项权限和受控默认岗位方案；`executive-task.read/assign`及`dashboard.ceo`按正式冻结收紧岗位方案边界。
- 新增正式技术冻结列出的十一张租户表。
- 为`management_task`增加可信`creation_source`和`created_by_assignment_id`，唯一枚举为`LEGACY/MANUAL/RULE_ENGINE/TASK_CANDIDATE/WORK_PLAN/CHAIRMAN_DIRECTIVE`；保留`DEFAULT LEGACY`兼容旧应用，新代码显式写入；用可延迟约束校验新来源与权威关系。
- 增加直属与间接关系统一图防环、同租户复合外键、状态转换、不可变触发器、索引、精确GRANT和强制RLS；安装前扫描既有直属环。
- 多租户种子复用V36/V37的`tenant NO FORCE RLS → 逐租户set_config → 恢复FORCE`模式。
- 只更新可证明未被人工修改的默认方案；其他方案只进入差异清单。无合格平台管理员/CEO发布人时只留DRAFT并使该租户`NO-GO`。

验收：空库V1→V39和真实结构V38→V39通过；PILOT.7旧应用连接V39兼容；冲突时整笔回滚；所有新租户表`ENABLE/FORCE RLS`；区域经理相关数据零新增、OTA历史零改写。

### A3 OpenAPI与前端身份合同

任务ID：`GM-WP-A3`

主要文件：

- `apps/core-api/src/main/resources/application.yml`
- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/features/GroupManagementFeatureGate.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/api/ApiExceptionHandler.java`
- `docs/openapi.yaml`
- `apps/web/src/api/client.ts`
- `apps/web/src/api/resources.ts`
- `apps/web/src/App.tsx`
- `apps/web/src/domain.ts`
- `apps/web/src/app/permissions.ts`
- `apps/web/src/app/routeConfig.ts`
- `apps/web/src/app/rolePresentationPolicy.ts`
- `apps/web/src/shared/MobileAppNavigation.tsx`
- `apps/web/src/data/roles.ts`
- `apps/web/src/P0Pages.tsx`、`Pilot6Pages.tsx`、`product.ts`
- `apps/core-api/pom.xml`、`apps/web/package.json`、`apps/web/.env.pilot`

工作：

- OpenAPI升至`0.2.5-pilot.8`，API主路径保持`/api/v1`。
- 完整实现正式冻结第9节的请求/响应、成功码、稳定错误码、`PositionAssignment`和可选/必填任职头合同。
- 统一功能门禁；任一能力开关开启而租户白名单为空/非法时启动失败，关闭或租户不在白名单时新API返回404。
- Web请求演员字段只使用`ApiIdentity.businessActorAssignmentId`并映射`X-Assignment-Id`；`/iam/me`返回服务端确认值和`capabilities.groupManagement`。
- CEO继续显示账号级身份，同时在业务页面显示并保存独立任职选择。
- 新增董事长和两个人事岗位展示；移除行政人事→`HR_KPI_ADMIN`、区域经理→`OTA_OPERATION_MANAGER`的泛化别名。
- 同步Core API、Web、`product.ts`和`.env.pilot`到PILOT.8；删除页面PILOT.7硬编码但不覆盖PILOT.7历史脚本。

验收：首次`/iam/me`可无任职获取列表；后续业务请求携带已选任职；角色正负别名和既有登录/导航回归通过。

### A4 A批次测试与停点

任务ID：`GM-WP-A4`

- 增加身份绑定、任职伪造、一人多岗、账号级权限不降级测试。
- 增加V39升级、冲突回滚、RLS、跨租户外键、关系成环和默认方案保护测试。
- 增加功能开关启动/404、OpenAPI、前端任职选择与角色展示合同测试。
- 确认功能开关关闭，未创建真实人员任职，未部署目标环境。

A批次退出条件：A0—A4全部有证据；任一安全负向测试失败即停止，不进入B。

批次A当前结论：本地代码与自动化验证已完成，三项功能开关保持关闭，未创建真实人员任职，未部署。证据见`GROUP-MANAGEMENT-ROLE-AND-WORK-PLAN-BATCH-A-IMPLEMENTATION-REPORT.md`；进入B仍需单独指令。

## 4. 批次B：董事长受限交办闭环

### B1 后端专用交办

任务ID：`GM-WP-B1`

主要文件：

- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/ExecutiveTaskController.java`
- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/ExecutiveTaskService.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/TaskController.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/TaskService.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/TaskModels.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/TaskTargetPolicy.java`

工作：

- 实现专用列表、详情、目标、创建、验收、退回六个`/api/v1/executive-tasks`接口；所有接口强制功能开关和当前董事长业务任职。
- 只返回同租户集团根组织下有效GM/副总任职。
- 原子创建任务、参与人、`executive_task_directive`、审计和Outbox并派发到`PENDING_ACK`。
- 不放宽通用`TaskTargetPolicy`；禁止客户端设置来源、验收人和目标岗位安全字段。
- 董事长验收/退回同时校验当前业务任职、`REVIEWER`、可信来源及权威交办记录。

### B2 字段最小化读取与页面

任务ID：`GM-WP-B2`

主要文件：

- 新增`apps/web/src/features/executiveTasks/`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/workpackage/WorkPackageService.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/workdata/WorkDataService.java`
- `apps/web/src/app/routeConfig.ts`、`permissions.ts`
- `apps/web/src/shared/MobileAppNavigation.tsx`

工作：

- 为董事长提供字段最小化的全集团任务列表和专用“交办高管任务”入口。
- 以`executive-task.read`提供专用列表/详情；董事长的通用任务列表、详情、动作和证据接口全部403。
- 董事长工作台任务卡片和计数改用`/executive-tasks`，不得因`task.review`权限触发通用`/tasks`请求。
- `work-record.team-read`只扩展团队只读，不获得复核、写入或附件读取；原始任务证据、联系方式、薪酬、密钥和完整审计导出不可见。
- 只在拥有`executive-task.assign`且业务任职为董事长时显示专用入口。
- 董事长桌面业务路由必须与正式冻结白名单精确相等；移动端“我的”只保留改密和退出，不经过`all-functions`。

### B3 B批次安全验收

任务ID：`GM-WP-B3`

- GM/副总目标成功；行政人事、行政人事主管、店总、OTA和跨租户目标403。
- 伪造`creationSource`、验收人、董事长任职、来源快照或幂等键异载荷失败关闭。
- 非本人交办、其他董事长交办、非`CHAIRMAN_DIRECTIVE`任务的所有动作403。
- 通用任务读取/动作/证据端点对董事长全部403；专用列表不含描述全文和证据元数据。
- 重复相同命令只生成一条任务；任一中途失败时任务、参与人、权威记录、审计和Outbox均零残留。

B批次退出条件：纵向闭环和全部负向用例通过；功能仍关闭；未部署。

## 5. 批次C：周/月工作计划闭环

### C1 工作计划领域服务与API

任务ID：`GM-WP-C1`

主要文件：

- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/workplans/WorkPlanController.java`
- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/workplans/WorkPlanService.java`
- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/workplans/WorkPlanModels.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/tasks/TaskService.java`

工作：

- 实现正式技术冻结中的九个`/api/v1/work-plans`接口。
- 所有计划接口强制当前业务任职；创建、保存、提交、撤回的计划所有者必须等于该任职，详情按所有者/冻结主管/授权团队重新校验。
- 实现草稿、不可变修订、提交、直属主管冻结、退回、拒绝、撤回和整单批准，并严格使用正式冻结的强类型请求模型。
- POST禁止计划项ID；PUT对当前DRAFT修订完整替换，新增/保留/删除及重复、未知、跨修订ID错误按正式冻结执行。
- `APPROVE_AND_DISPATCH`在单事务内逐项决策并生成恰好N个可信`WORK_PLAN`任务。
- 计划审批只认提交时冻结的精确直属主管任职；间接领导只进入`team`只读查询。

### C2 工作计划前端

任务ID：`GM-WP-C2`

主要文件：

- 新增`apps/web/src/features/workPlans/`
- `apps/web/src/api/resources.ts`
- `apps/web/src/app/notificationNavigation.ts`
- 工作计划导航、懒加载路由和任务来源标签

工作：

- 实现我的计划、待我审批、下属计划和董事长集团计划只读视图。
- 实现逐项内容、完成时间和提醒审批；不提供部分批准。
- `WORK_PLAN_*`通知优先路由到计划详情；计划生成任务显示可信来源。
- 团队只读页面和复核按钮分别校验`work-record.team-read`与`work-record.review`，不可共用写权限判断。

### C3 C批次业务与原子性验收

任务ID：`GM-WP-C3`

- 副总、行政人事主管和默认管理岗可以提交；GM、董事长、普通行政人事和无权限岗位不可提交。
- 副总和行政人事主管只能由集团总经理业务任职审批；指定副总作为间接领导只能团队只读。
- 周/月周期、完成时间、提醒、100项和每项8提醒边界通过。
- 并发审批只成功一次；同键异载荷409；N项批准生成恰好N任务。
- 在第1项、第N项、审计和Outbox处注入失败均整单回滚，不出现部分任务。

C批次退出条件：业务验收矩阵、事务原子性和跨租户负向测试全部通过；功能仍关闭；未部署。

## 6. 批次D：提醒、回归与Pilot启用准备

### D1 提醒可靠性

任务ID：`GM-WP-D1`

主要文件：

- 新增`apps/core-api/src/main/java/cn/sifangguan/hotelaios/workplans/WorkPlanReminderService.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/events/ManagementAutomationWorker.java`
- `apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/events/AutomationWorkerMetrics.java`

工作：只对白名单租户且`reminder-worker-enabled=true`的记录实现`SKIP LOCKED`领取、租约、过期恢复、有限指数退避、死信、通知幂等和任务结束取消；不复用逾期升级表冒充到期前提醒。

验收：并发Worker无重复站内通知；进程中断可恢复；失败达到上限进入死信并告警；外部通道只声明至少一次尝试。

### D2 全量回归与制品证据

任务ID：`GM-WP-D2`

- 后端全量、OpenAPI、Web合同、TypeScript、普通/Pilot构建全部通过。
- 回归身份、任务、岗位方案、KPI、企业微信、驾驶舱、工作包、发布门禁和数据库恢复。
- 记录测试总数、失败/跳过原因、Git提交、迁移校验、制品哈希和浏览器/移动端证据。
- 在桌面1440×900和移动端390×844执行开关关闭/白名单缺失、董事长闭合导航、CEO任职持久化、目标篡改、专用任务读取、计划只读/审批、通知和来源标签用例；保存截图、网络断言和角色—路由JSON。
- 将版本状态从“开发中”推进到“待验收”只能发生在证据齐全之后；不得提前标记已发布。

### D3 Pilot数据包与启用评审

任务ID：`GM-WP-D3`

逐租户数据包必须明确：

- 租户和唯一集团根组织。
- CEO账号、员工和唯一`GROUP_GENERAL_MANAGER`任职。
- 董事长、副总经理、行政人事主管、行政人事的员工与任职。
- 每个人事任职对应的具体`INDIRECT_LEADER`副总任职。
- 其他计划管理岗位的有效直属主管。
- 人工编辑或减权岗位方案的差异处理和发布批准。

数据包只允许稳定ID和人工确认结果，不按姓名、角色数量或组织祖先自动选择。多CEO、多集团根、主管失效、关系成环、同码冲突或方案占用时结论为`NO-GO`。

D批次退出条件：形成可复现候选制品、完整证据和逐租户`GO/NO-GO`结论；功能默认仍关闭，等待单独部署与启用批准。

Pilot启用配置必须显式设置三项布尔开关及`GROUP_MANAGEMENT_TENANT_IDS`；任一白名单外租户仍不可访问。配置变更、目标租户、窗口和回退证据纳入部署审批。

## 7. 验证命令基线

开工后按实际构建工具确认并执行：

```powershell
mvn -f apps/core-api/pom.xml test
node --test --experimental-strip-types apps/web/tests/*.test.mjs
pnpm --dir apps/web build
pnpm --dir apps/web build:pilot
```

数据库必须另行执行空库V1→V39、V38副本→V39、冲突失败回滚、RLS和跨租户集成测试。现有PILOT.7测试结果不能替代本计划新增用例。

## 8. 回滚与停止规则

- A阶段迁移失败：V39事务整体回滚，不手工删除部分对象，不修改V1—V38。
- B/C/D阶段应用失败：保持功能开关关闭，回退应用制品；数据库结构留在V39并通过前向修复处理，不编写生产破坏性down脚本。
- 迁移后尚无新业务写入的受控窗口，只有在备份恢复演练通过时才可恢复迁移前备份；一旦存在PILOT.8新写入，不得用旧备份覆盖，必须关闭开关、回退兼容应用并前向修复。
- 已写入测试数据：仅用明确租户和测试批次标识的受控清理脚本处理；禁止广泛删除。
- 发现冻结冲突、安全越权、跨租户泄漏、部分任务生成或不可恢复数据修改时立即停止当前批次并回到技术评审。
- 生产回退最终以部署前备份恢复演练、制品回退和前向迁移方案为准，未完成演练不得给出生产`GO`。

## 9. 开工与发布授权边界

当前已具备：产品冻结、业务设计冻结、正式技术冻结、实施批次和验收门槛，以及批次A本地代码与测试证据。

当前尚不具备：批次B/C/D实施授权与实现、真实人员映射、目标租户Pilot启用批准和生产部署批准。V39目前只存在于代码候选和测试数据库，尚未施加到运行环境。

下一状态转换必须由明确指令触发：

1. “开始编码”——只授权从批次A开始实施，不自动部署。
2. “进入下一批次”——只在上一批次证据通过后进入B、C或D。
3. “部署Pilot”——必须先完成D3并明确目标租户、备份、窗口和回退方案。
4. “正式发布”——必须另有业务验收和生产变更批准。
