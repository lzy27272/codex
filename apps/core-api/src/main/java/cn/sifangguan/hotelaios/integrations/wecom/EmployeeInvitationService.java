package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.organization.OrganizationModels;
import cn.sifangguan.hotelaios.organization.OrganizationService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static cn.sifangguan.hotelaios.integrations.wecom.EmployeeInvitationModels.*;

@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class EmployeeInvitationService {
    private static final Set<String> OPEN_BINDING_STATES = Set.of(
            "WAITING_SCAN", "AUTHORIZING", "PENDING_APPROVAL", "CONFLICT"
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final OrganizationService organizationService;
    private final WeComUserBindingAdministrationService bindingService;
    private final WeComProperties properties;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;

    public EmployeeInvitationService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            OrganizationService organizationService,
            WeComUserBindingAdministrationService bindingService,
            WeComProperties properties,
            AuditWriter auditWriter,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.organizationService = organizationService;
        this.bindingService = bindingService;
        this.properties = properties;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        TenantPrincipal principal = prepare("wecom-binding.read");
        boolean canCreate = allowed(principal, "wecom-binding.manage");
        boolean canApprove = allowed(principal, "wecom-binding.approve")
                && allowed(principal, "org.manage") && principal.hasTenantScope();
        List<RequestRow> items = jdbc.query("""
                select request.*, requester.display_name as requested_by_name,
                       reviewer.display_name as reviewed_by_name
                from employee_invitation_request request
                join user_account requester
                  on requester.tenant_id = request.tenant_id and requester.id = request.requested_by
                left join user_account reviewer
                  on reviewer.tenant_id = request.tenant_id and reviewer.id = request.reviewed_by
                where request.tenant_id = :tenantId
                order by case request.status when 'PENDING_REVIEW' then 0 else 1 end,
                         request.created_at desc, request.id
                limit 200
                """, base(principal), this::mapRow);
        List<AccountOption> accounts = canApprove ? jdbc.query("""
                select account.id, account.login_name, account.display_name,
                       employee.id as employee_id, employee.name as employee_name,
                       exists (
                           select 1 from role_assignment grant_item
                           join app_role role on role.tenant_id = grant_item.tenant_id
                                             and role.id = grant_item.role_id
                           where grant_item.tenant_id = account.tenant_id
                             and grant_item.account_id = account.id
                             and role.code = 'PLATFORM_ADMIN' and role.role_type = 'SYSTEM'
                             and grant_item.valid_from <= now()
                             and (grant_item.valid_to is null or grant_item.valid_to > now())
                       ) as platform_admin
                from user_account account
                left join lateral (
                    select employee_row.id, employee_row.name
                    from employee employee_row
                    where employee_row.tenant_id = account.tenant_id
                      and employee_row.account_id = account.id
                      and employee_row.employment_status = 'ACTIVE'
                      and employee_row.deleted_at is null
                    order by employee_row.created_at, employee_row.id
                    limit 1
                ) employee on true
                where account.tenant_id = :tenantId and account.status = 'ACTIVE'
                order by platform_admin desc, account.login_name
                """, base(principal), (rs, rowNum) -> new AccountOption(
                rs.getObject("id", UUID.class), rs.getString("login_name"),
                rs.getString("display_name"), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getBoolean("platform_admin")
        )) : List.of();
        List<OrgUnitOption> orgUnits = canApprove ? jdbc.query("""
                select id, name, unit_type from org_unit
                where tenant_id = :tenantId and status = 'ACTIVE'
                order by sort_order, name, id
                """, base(principal), (rs, rowNum) -> new OrgUnitOption(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("unit_type")
        )) : List.of();
        List<PositionOption> positions = canApprove ? jdbc.query("""
                select position.id, position.name
                from position_definition position
                where position.tenant_id = :tenantId and position.status = 'ACTIVE'
                  and position.deleted_at is null and position.permanently_deleted_at is null
                  and exists (
                      select 1 from position_function_profile profile
                      join position_function_profile_version version
                        on version.tenant_id = profile.tenant_id
                       and version.profile_id = profile.id
                       and version.lifecycle_status = 'PUBLISHED'
                      where profile.tenant_id = position.tenant_id
                        and profile.position_id = position.id
                        and profile.scope_type = 'GROUP'
                        and profile.default_role_id is not null
                  )
                order by position.name, position.id
                """, base(principal), (rs, rowNum) -> new PositionOption(
                rs.getObject("id", UUID.class), rs.getString("name")
        )) : List.of();
        List<ManagerOption> managers = canApprove ? jdbc.query("""
                select assignment.id, employee.name as employee_name, position.name as position_name
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                                                 and position.id = assignment.position_id
                where assignment.tenant_id = :tenantId and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE' and employee.deleted_at is null
                order by employee.name, assignment.is_primary desc, position.name
                """, base(principal), (rs, rowNum) -> new ManagerOption(
                rs.getObject("id", UUID.class), rs.getString("employee_name"),
                rs.getString("position_name")
        )) : List.of();
        return new Dashboard(
                new Capabilities(canCreate, canApprove),
                items, accounts, orgUnits, positions, managers
        );
    }

    @Transactional
    public RequestRow create(CreateRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        String loginName = request.loginName().trim().toLowerCase();
        String employeeNo = request.employeeNo().trim();
        Integer pending = jdbc.queryForObject("""
                select count(*) from employee_invitation_request
                where tenant_id = :tenantId and status = 'PENDING_REVIEW'
                  and (lower(login_name) = :loginName or lower(employee_no) = lower(:employeeNo))
                """, base(principal).addValue("loginName", loginName)
                .addValue("employeeNo", employeeNo), Integer.class);
        if (pending != null && pending > 0) {
            throw new IllegalArgumentException("该登录账号或员工编号已有待审核邀请");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into employee_invitation_request
                    (id, tenant_id, display_name, mobile, login_name, employee_no,
                     note, requested_by)
                values
                    (:id, :tenantId, :displayName, :mobile, :loginName, :employeeNo,
                     :note, :actorId)
                """, base(principal).addValue("id", id)
                .addValue("displayName", request.displayName().trim())
                .addValue("mobile", trimToNull(request.mobile()))
                .addValue("loginName", loginName).addValue("employeeNo", employeeNo)
                .addValue("note", trimToNull(request.note())).addValue("actorId", principal.actorId()));
        auditWriter.record("EMPLOYEE_INVITATION_SUBMITTED", "employee_invitation_request", id,
                json(Map.of("loginName", loginName, "employeeNo", employeeNo, "status", "PENDING_REVIEW")));
        return requireRow(principal, id, false);
    }

    @Transactional
    public ApprovalResponse approve(UUID invitationId, ApproveRequest request) {
        accessPolicy.requirePermission("wecom-binding.approve");
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope()) {
            throw new IllegalArgumentException("员工建档和跨岗位分配仅允许集团级管理员审批");
        }
        LockedRequest current = lockRequest(principal, invitationId, request.expectedVersion());
        validateAssignments(request.assignments());

        AccountEmployee target = request.existingAccountId() == null
                ? createAccountAndEmployee(principal, current)
                : resolveExistingAccountAndEmployee(principal, current, request.existingAccountId());

        UUID primaryAssignmentId = null;
        for (AssignmentSelection selection : request.assignments()) {
            rejectDuplicateAssignment(principal, target.employeeId(), selection);
            Map<String, Object> result = organizationService.assignPosition(
                    target.employeeId(), new OrganizationModels.CreatePositionAssignment(
                            selection.orgUnitId(), selection.positionId(), selection.managerAssignmentId(),
                            selection.primary(), assignmentType(selection.assignmentType()),
                            LocalDate.now(), null));
            UUID assignmentId = (UUID) result.get("id");
            if (selection.primary()) primaryAssignmentId = assignmentId;
            jdbc.update("""
                    insert into employee_invitation_assignment
                        (id, tenant_id, invitation_id, org_unit_id, position_id,
                         manager_assignment_id, is_primary, assignment_type, resulting_assignment_id)
                    values
                        (:id, :tenantId, :invitationId, :orgUnitId, :positionId,
                         :managerAssignmentId, :primary, :assignmentType, :assignmentId)
                    """, base(principal).addValue("id", UUID.randomUUID())
                    .addValue("invitationId", invitationId)
                    .addValue("orgUnitId", selection.orgUnitId())
                    .addValue("positionId", selection.positionId())
                    .addValue("managerAssignmentId", selection.managerAssignmentId())
                    .addValue("primary", selection.primary())
                    .addValue("assignmentType", assignmentType(selection.assignmentType()))
                    .addValue("assignmentId", assignmentId));
        }

        int updated = jdbc.update("""
                update employee_invitation_request
                set status = 'APPROVED', reviewed_by = :actorId, reviewed_at = now(),
                    decision_reason = :reason, target_account_id = :accountId,
                    target_employee_id = :employeeId, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                  and status = 'PENDING_REVIEW' and row_version = :version
                """, base(principal).addValue("id", invitationId)
                .addValue("version", request.expectedVersion()).addValue("actorId", principal.actorId())
                .addValue("reason", trimToNull(request.reason())).addValue("accountId", target.accountId())
                .addValue("employeeId", target.employeeId()));
        if (updated != 1) throw stale();

        WeComUserBindingModels.InviteResponse bindingInvitation = null;
        if (!hasBindingOrOpenRequest(principal, target.accountId())) {
            bindingInvitation = bindingService.invite(new WeComUserBindingModels.InviteRequest(
                    target.accountId(), primaryAssignmentId));
            jdbc.update("""
                    update employee_invitation_request set binding_request_id = :bindingRequestId
                    where tenant_id = :tenantId and id = :id
                    """, base(principal).addValue("id", invitationId)
                    .addValue("bindingRequestId", bindingInvitation.requestId()));
        }

        auditWriter.record("EMPLOYEE_INVITATION_APPROVED", "employee_invitation_request", invitationId,
                json(Map.of(
                        "accountId", target.accountId(), "employeeId", target.employeeId(),
                        "assignmentCount", request.assignments().size(),
                        "existingAccount", request.existingAccountId() != null,
                        "platformAdminRolePreserved", target.platformAdmin()
                )));
        String message = target.platformAdmin()
                ? "已保留平台管理员全部功能，并叠加所选管理岗位"
                : "已创建人员及多岗位权限";
        if (bindingInvitation == null) message += "；现有绑定或绑定申请保持不变";
        else message += "；已生成120分钟企业微信绑定邀请";
        return new ApprovalResponse(
                invitationId, target.accountId(), target.employeeId(), "APPROVED",
                bindingInvitation == null ? null : bindingInvitation.requestId(),
                bindingInvitation == null ? null : bindingInvitation.enrollmentUrl(),
                bindingInvitation == null ? null : bindingInvitation.expiresAt(), message
        );
    }

    @Transactional
    public RequestRow reject(UUID invitationId, RejectRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        lockRequest(principal, invitationId, request.expectedVersion());
        int updated = jdbc.update("""
                update employee_invitation_request
                set status = 'REJECTED', reviewed_by = :actorId, reviewed_at = now(),
                    decision_reason = :reason, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                  and status = 'PENDING_REVIEW' and row_version = :version
                """, base(principal).addValue("id", invitationId)
                .addValue("version", request.expectedVersion()).addValue("actorId", principal.actorId())
                .addValue("reason", request.reason().trim()));
        if (updated != 1) throw stale();
        auditWriter.record("EMPLOYEE_INVITATION_REJECTED", "employee_invitation_request", invitationId,
                json(Map.of("reason", request.reason().trim())));
        return requireRow(principal, invitationId, false);
    }

    private AccountEmployee createAccountAndEmployee(TenantPrincipal principal, LockedRequest current) {
        Integer duplicates = jdbc.queryForObject("""
                select (select count(*) from user_account where tenant_id = :tenantId
                        and lower(login_name) = lower(:loginName))
                     + (select count(*) from employee where tenant_id = :tenantId
                        and lower(employee_no) = lower(:employeeNo))
                """, base(principal).addValue("loginName", current.loginName())
                .addValue("employeeNo", current.employeeNo()), Integer.class);
        if (duplicates != null && duplicates > 0) {
            throw new IllegalArgumentException("登录账号或员工编号已存在；如需给 sfglzy 等现有账号兼岗，请在审核时选择该账号");
        }
        UUID accountId = UUID.randomUUID();
        UUID employeeId = UUID.randomUUID();
        MapSqlParameterSource params = base(principal).addValue("accountId", accountId)
                .addValue("employeeId", employeeId).addValue("loginName", current.loginName())
                .addValue("employeeNo", current.employeeNo()).addValue("displayName", current.displayName())
                .addValue("mobile", current.mobile());
        jdbc.update("""
                insert into user_account (id, tenant_id, login_name, display_name, mobile)
                values (:accountId, :tenantId, :loginName, :displayName, :mobile)
                """, params);
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, mobile, hired_on)
                values (:employeeId, :tenantId, :accountId, :employeeNo, :displayName, :mobile, current_date)
                """, params);
        return new AccountEmployee(accountId, employeeId, false);
    }

    private AccountEmployee resolveExistingAccountAndEmployee(
            TenantPrincipal principal, LockedRequest current, UUID accountId
    ) {
        List<AccountState> accounts = jdbc.query("""
                select account.id, exists (
                    select 1 from role_assignment grant_item
                    join app_role role on role.tenant_id = grant_item.tenant_id
                                      and role.id = grant_item.role_id
                    where grant_item.tenant_id = account.tenant_id
                      and grant_item.account_id = account.id
                      and role.code = 'PLATFORM_ADMIN' and role.role_type = 'SYSTEM'
                      and grant_item.valid_from <= now()
                      and (grant_item.valid_to is null or grant_item.valid_to > now())
                ) as platform_admin
                from user_account account
                where account.tenant_id = :tenantId and account.id = :accountId
                  and account.status = 'ACTIVE'
                for update
                """, base(principal).addValue("accountId", accountId), (rs, rowNum) ->
                new AccountState(rs.getObject("id", UUID.class), rs.getBoolean("platform_admin")));
        if (accounts.size() != 1) throw new IllegalArgumentException("所选现有账号不存在或已停用");
        List<UUID> employees = jdbc.query("""
                select id from employee
                where tenant_id = :tenantId and account_id = :accountId
                  and employment_status = 'ACTIVE' and deleted_at is null
                for update
                """, base(principal).addValue("accountId", accountId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (employees.size() > 1) throw new IllegalArgumentException("所选账号关联了多个有效员工，请先清理异常数据");
        if (employees.size() == 1) {
            return new AccountEmployee(accountId, employees.getFirst(), accounts.getFirst().platformAdmin());
        }
        Integer duplicateNumber = jdbc.queryForObject("""
                select count(*) from employee
                where tenant_id = :tenantId and lower(employee_no) = lower(:employeeNo)
                """, base(principal).addValue("employeeNo", current.employeeNo()), Integer.class);
        if (duplicateNumber != null && duplicateNumber > 0) {
            throw new IllegalArgumentException("员工编号已存在，不能为现有账号新建人员档案");
        }
        UUID employeeId = UUID.randomUUID();
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, mobile, hired_on)
                values (:employeeId, :tenantId, :accountId, :employeeNo, :displayName, :mobile, current_date)
                """, base(principal).addValue("employeeId", employeeId).addValue("accountId", accountId)
                .addValue("employeeNo", current.employeeNo()).addValue("displayName", current.displayName())
                .addValue("mobile", current.mobile()));
        return new AccountEmployee(accountId, employeeId, accounts.getFirst().platformAdmin());
    }

    private void rejectDuplicateAssignment(
            TenantPrincipal principal, UUID employeeId, AssignmentSelection selection
    ) {
        Integer count = jdbc.queryForObject("""
                select count(*) from employee_position_assignment
                where tenant_id = :tenantId and employee_id = :employeeId
                  and org_unit_id = :orgUnitId and position_id = :positionId
                  and status = 'ACTIVE' and valid_from <= current_date
                  and (valid_to is null or valid_to >= current_date)
                """, base(principal).addValue("employeeId", employeeId)
                .addValue("orgUnitId", selection.orgUnitId())
                .addValue("positionId", selection.positionId()), Integer.class);
        if (count != null && count > 0) throw new IllegalArgumentException("所选人员已拥有相同组织和岗位的有效任职");
    }

    private boolean hasBindingOrOpenRequest(TenantPrincipal principal, UUID accountId) {
        Integer count = jdbc.queryForObject("""
                select (select count(*) from wecom_user_binding
                        where tenant_id = :tenantId and account_id = :accountId
                          and status in ('ACTIVE','SUSPENDED'))
                     + (select count(*) from wecom_user_binding_request
                        where tenant_id = :tenantId and account_id = :accountId
                          and status in (:openStates))
                """, base(principal).addValue("accountId", accountId)
                .addValue("openStates", OPEN_BINDING_STATES), Integer.class);
        return count != null && count > 0;
    }

    private void validateAssignments(List<AssignmentSelection> assignments) {
        long primaryCount = assignments.stream().filter(AssignmentSelection::primary).count();
        if (primaryCount != 1) throw new IllegalArgumentException("审核时必须且只能设置一个主岗");
        Set<String> distinct = assignments.stream()
                .map(item -> item.orgUnitId() + ":" + item.positionId())
                .collect(Collectors.toSet());
        if (distinct.size() != assignments.size()) throw new IllegalArgumentException("同一组织和岗位不能重复分配");
        assignments.forEach(item -> assignmentType(item.assignmentType()));
    }

    private String assignmentType(String value) {
        String normalized = value == null || value.isBlank() ? "PERMANENT" : value.trim().toUpperCase();
        if (!Set.of("PERMANENT", "TEMPORARY", "ACTING").contains(normalized)) {
            throw new IllegalArgumentException("任职类型必须为正式、临时或代理");
        }
        return normalized;
    }

    private LockedRequest lockRequest(TenantPrincipal principal, UUID id, long expectedVersion) {
        List<LockedRequest> rows = jdbc.query("""
                select id, display_name, mobile, login_name, employee_no, status, row_version
                from employee_invitation_request
                where tenant_id = :tenantId and id = :id
                for update
                """, base(principal).addValue("id", id), (rs, rowNum) -> new LockedRequest(
                rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("mobile"),
                rs.getString("login_name"), rs.getString("employee_no"), rs.getString("status"),
                rs.getLong("row_version")
        ));
        if (rows.size() != 1) throw new IllegalArgumentException("邀请申请不存在或不属于当前租户");
        LockedRequest row = rows.getFirst();
        if (!"PENDING_REVIEW".equals(row.status())) throw new IllegalArgumentException("该邀请申请已处理");
        if (row.rowVersion() != expectedVersion) throw stale();
        return row;
    }

    private RequestRow requireRow(TenantPrincipal principal, UUID id, boolean lock) {
        List<RequestRow> rows = jdbc.query("""
                select request.*, requester.display_name as requested_by_name,
                       reviewer.display_name as reviewed_by_name
                from employee_invitation_request request
                join user_account requester
                  on requester.tenant_id = request.tenant_id and requester.id = request.requested_by
                left join user_account reviewer
                  on reviewer.tenant_id = request.tenant_id and reviewer.id = request.reviewed_by
                where request.tenant_id = :tenantId and request.id = :id
                """ + (lock ? " for update of request" : ""),
                base(principal).addValue("id", id), this::mapRow);
        if (rows.size() != 1) throw new IllegalArgumentException("邀请申请不存在或不属于当前租户");
        return rows.getFirst();
    }

    private RequestRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RequestRow(
                rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("mobile"),
                rs.getString("login_name"), rs.getString("employee_no"), rs.getString("note"),
                rs.getString("status"), rs.getString("requested_by_name"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("reviewed_by_name"),
                rs.getObject("reviewed_at", OffsetDateTime.class), rs.getString("decision_reason"),
                rs.getObject("target_account_id", UUID.class), rs.getObject("target_employee_id", UUID.class),
                rs.getLong("row_version")
        );
    }

    private TenantPrincipal prepare(String permission) {
        accessPolicy.requirePermission(permission);
        return prepare();
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        if (!principal.tenantId().equals(properties.tenantId())) {
            throw new IllegalArgumentException("当前租户与企业微信配置不一致");
        }
        return principal;
    }

    private boolean allowed(TenantPrincipal principal, String permission) {
        return principal.hasPermission(permission) || principal.hasPermission("*");
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计记录序列化失败", exception);
        }
    }

    private IllegalArgumentException stale() {
        return new IllegalArgumentException("邀请申请已变化，请刷新后重试");
    }

    private record LockedRequest(
            UUID id, String displayName, String mobile, String loginName,
            String employeeNo, String status, long rowVersion
    ) { }
    private record AccountState(UUID id, boolean platformAdmin) { }
    private record AccountEmployee(UUID accountId, UUID employeeId, boolean platformAdmin) { }
}
