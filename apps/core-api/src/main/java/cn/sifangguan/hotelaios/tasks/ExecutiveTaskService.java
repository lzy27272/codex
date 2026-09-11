package cn.sifangguan.hotelaios.tasks;

import cn.sifangguan.hotelaios.notifications.NotificationService;
import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.features.GroupManagementException;
import cn.sifangguan.hotelaios.shared.features.GroupManagementFeatureGate;
import cn.sifangguan.hotelaios.shared.idempotency.CommandIdempotencyService;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.BusinessIdentityException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ExecutiveTaskService {
    private static final String SOURCE = "CHAIRMAN_DIRECTIVE";

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final GroupManagementFeatureGate featureGate;
    private final CommandIdempotencyService idempotency;
    private final AuditWriter auditWriter;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    public ExecutiveTaskService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            GroupManagementFeatureGate featureGate,
            CommandIdempotencyService idempotency,
            AuditWriter auditWriter,
            NotificationService notificationService,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.featureGate = featureGate;
        this.idempotency = idempotency;
        this.auditWriter = auditWriter;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<ExecutiveTaskModels.ExecutiveTaskSummary> list() {
        RequestIdentity identity = requireChairman("executive-task.read");
        return jdbc.query(summarySql() + " order by task.created_at desc limit 500", base(identity.principal()),
                (rs, rowNum) -> new ExecutiveTaskModels.ExecutiveTaskSummary(
                        rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("title"),
                        rs.getString("lifecycle_status"), rs.getString("sla_status"), rs.getString("priority"),
                        rs.getObject("due_at", OffsetDateTime.class), rs.getLong("row_version"),
                        rs.getString("creation_source"), rs.getObject("org_unit_id", UUID.class),
                        rs.getString("org_unit_name"), rs.getObject("assignee_assignment_id", UUID.class),
                        rs.getString("assignee_name"), rs.getString("assignee_position_name"),
                        rs.getObject("reviewer_assignment_id", UUID.class), rs.getString("reviewer_name"),
                        rs.getInt("progress")));
    }

    @Transactional(readOnly = true)
    public ExecutiveTaskModels.ExecutiveTaskDetail detail(UUID taskId) {
        RequestIdentity identity = requireChairman("executive-task.read");
        return detail(identity, taskId);
    }

    @Transactional(readOnly = true)
    public List<ExecutiveTaskModels.ExecutiveTaskTarget> targets() {
        RequestIdentity identity = requireChairman("executive-task.assign");
        return jdbc.query("""
                select assignment.id as assignment_id, employee.name as employee_name,
                       position.code as position_code, position.name as position_name,
                       organization.id as organization_id, organization.name as organization_name
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                                                   and position.id = assignment.position_id
                join org_unit organization on organization.tenant_id = assignment.tenant_id
                                          and organization.id = assignment.org_unit_id
                where assignment.tenant_id = :tenantId
                  and position.code in ('GROUP_GENERAL_MANAGER', 'GROUP_VICE_PRESIDENT')
                  and assignment.status = 'ACTIVE'
                  and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE'
                  and employee.account_id is not null
                  and position.status = 'ACTIVE'
                  and organization.status = 'ACTIVE'
                  and organization.unit_type = 'GROUP' and organization.parent_id is null
                order by case position.code when 'GROUP_GENERAL_MANAGER' then 0 else 1 end,
                         employee.name, assignment.id
                """, base(identity.principal()), (rs, rowNum) -> new ExecutiveTaskModels.ExecutiveTaskTarget(
                rs.getObject("assignment_id", UUID.class), rs.getString("employee_name"),
                rs.getString("position_code"), rs.getString("position_name"),
                rs.getObject("organization_id", UUID.class), rs.getString("organization_name")));
    }

    @Transactional
    public ExecutiveTaskModels.ExecutiveTaskDetail assignByChairman(
            ExecutiveTaskModels.CreateExecutiveTask request,
            String idempotencyKey
    ) {
        RequestIdentity identity = requireChairman("executive-task.assign");
        rejectUnknown(request.unknownFields());
        if (!request.clientCommandId().toString().equals(idempotencyKey)) {
            throw new IllegalArgumentException("Idempotency-Key必须等于clientCommandId的规范UUID文本");
        }
        String priority = normalizePriority(request.priority());
        List<OffsetDateTime> reminderTimes = ExecutiveTaskModels.sortedDistinct(request.reminderTimes());
        validateSchedule(request.dueAt(), reminderTimes);
        Target target = requireTarget(identity.principal(), request.targetAssignmentId());
        Map<String, Object> commandRequest = Map.of(
                "chairmanAssignmentId", identity.assignmentId(),
                "clientCommandId", request.clientCommandId(),
                "targetAssignmentId", request.targetAssignmentId(),
                "title", request.title().trim(),
                "description", request.description().trim(),
                "priority", priority,
                "dueAt", request.dueAt(),
                "reminderTimes", reminderTimes
        );
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "EXECUTIVE_TASK_ASSIGN:" + identity.assignmentId(), idempotencyKey,
                commandRequest, identity.principal().correlationId());
        if (reservation.replayed()) return replay(reservation.responseSnapshot());

        UUID taskId = UUID.randomUUID();
        String taskNo = "T-" + OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + taskId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String internalKey = "chairman-directive:" + identity.assignmentId() + ":" + request.clientCommandId();
        jdbc.update("""
                insert into management_task
                    (id, tenant_id, task_no, idempotency_key, org_unit_id, title, description,
                     lifecycle_status, priority, due_at, source_snapshot, responsibility_snapshot,
                     created_by, creation_source, created_by_assignment_id, row_version)
                values
                    (:id, :tenantId, :taskNo, :internalKey, :orgUnitId, :title, :description,
                     'PENDING_ACK', :priority, :dueAt, '{}'::jsonb,
                     jsonb_build_object('assigneeAssignmentId', cast(:targetAssignmentId as text),
                                        'reviewerAssignmentId', cast(:chairmanAssignmentId as text)),
                     :actorId, 'CHAIRMAN_DIRECTIVE', :chairmanAssignmentId, 1)
                """, base(identity.principal())
                .addValue("id", taskId).addValue("taskNo", taskNo).addValue("internalKey", internalKey)
                .addValue("orgUnitId", target.organizationId()).addValue("title", request.title().trim())
                .addValue("description", request.description().trim()).addValue("priority", priority)
                .addValue("dueAt", request.dueAt()).addValue("targetAssignmentId", target.assignmentId())
                .addValue("chairmanAssignmentId", identity.assignmentId())
                .addValue("actorId", identity.principal().actorId()));
        insertParticipant(identity.principal(), taskId, "ASSIGNEE", target.assignmentId());
        insertParticipant(identity.principal(), taskId, "REVIEWER", identity.assignmentId());
        jdbc.update("""
                insert into executive_task_directive
                    (tenant_id, task_id, chairman_assignment_id, target_assignment_id,
                     client_command_id, request_hash)
                values (:tenantId, :taskId, :chairmanAssignmentId, :targetAssignmentId,
                        :clientCommandId, :requestHash)
                """, base(identity.principal()).addValue("taskId", taskId)
                .addValue("chairmanAssignmentId", identity.assignmentId())
                .addValue("targetAssignmentId", target.assignmentId())
                .addValue("clientCommandId", request.clientCommandId().toString())
                .addValue("requestHash", hash(commandRequest)));
        insertTransition(identity.principal(), taskId, null, "PROPOSED", "CREATE", 0,
                internalKey + ":create", identity.assignmentId(), Map.of("source", SOURCE));
        insertTransition(identity.principal(), taskId, "PROPOSED", "PENDING_ACK", "DISPATCH", 1,
                internalKey + ":dispatch", identity.assignmentId(), Map.of("source", SOURCE));
        for (OffsetDateTime reminderTime : reminderTimes) {
            jdbc.update("""
                    insert into task_reminder (tenant_id, task_id, remind_at, channel)
                    values (:tenantId, :taskId, :remindAt, 'IN_APP')
                    """, base(identity.principal()).addValue("taskId", taskId).addValue("remindAt", reminderTime));
        }
        auditWriter.record("EXECUTIVE_TASK_ASSIGNED", "TASK", taskId,
                json(Map.of("taskId", taskId, "targetAssignmentId", target.assignmentId(), "source", SOURCE)));
        auditWriter.emit("TASK", taskId, "ExecutiveTaskAssigned",
                json(Map.of("taskId", taskId, "targetAssignmentId", target.assignmentId(), "source", SOURCE)));
        notificationService.createForAssignment(target.assignmentId(), "TASK_ASSIGNED", "收到董事长交办任务",
                request.title().trim(), "TASK", taskId, "chairman-task-assigned:" + taskId);
        ExecutiveTaskModels.ExecutiveTaskDetail response = detail(identity, taskId);
        idempotency.succeed(reservation, "EXECUTIVE_TASK", taskId, 201, response);
        return response;
    }

    @Transactional
    public ExecutiveTaskModels.ExecutiveTaskDetail approve(
            UUID taskId,
            ExecutiveTaskModels.ApproveExecutiveTask request,
            String idempotencyKey
    ) {
        rejectUnknown(request.unknownFields());
        return review(taskId, "APPROVE", request.expectedVersion(), request.comment(), idempotencyKey);
    }

    @Transactional
    public ExecutiveTaskModels.ExecutiveTaskDetail rework(
            UUID taskId,
            ExecutiveTaskModels.ReworkExecutiveTask request,
            String idempotencyKey
    ) {
        rejectUnknown(request.unknownFields());
        return review(taskId, "REWORK", request.expectedVersion(), request.reason(), idempotencyKey);
    }

    private ExecutiveTaskModels.ExecutiveTaskDetail review(
            UUID taskId,
            String command,
            long expectedVersion,
            String comment,
            String idempotencyKey
    ) {
        RequestIdentity identity = requireChairman("executive-task.assign");
        Map<String, Object> commandRequest = Map.of(
                "taskId", taskId, "command", command, "expectedVersion", expectedVersion,
                "comment", comment == null ? "" : comment.trim(), "chairmanAssignmentId", identity.assignmentId());
        CommandIdempotencyService.Reservation reservation = idempotency.reserve(
                "EXECUTIVE_TASK_" + command + ":" + taskId, idempotencyKey,
                commandRequest, identity.principal().correlationId());
        if (reservation.replayed()) return replay(reservation.responseSnapshot());

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select task.lifecycle_status, task.row_version, task.title,
                       directive.target_assignment_id
                from management_task task
                join executive_task_directive directive
                  on directive.tenant_id = task.tenant_id and directive.task_id = task.id
                join task_participant reviewer
                  on reviewer.tenant_id = task.tenant_id and reviewer.task_id = task.id
                 and reviewer.participant_type = 'REVIEWER' and reviewer.valid_to is null
                where task.tenant_id = :tenantId and task.id = :taskId
                  and task.creation_source = 'CHAIRMAN_DIRECTIVE'
                  and task.created_by_assignment_id = :chairmanAssignmentId
                  and directive.chairman_assignment_id = :chairmanAssignmentId
                  and reviewer.position_assignment_id = :chairmanAssignmentId
                for update of task
                """, base(identity.principal()).addValue("taskId", taskId)
                .addValue("chairmanAssignmentId", identity.assignmentId()));
        if (rows.size() != 1) {
            throw GroupManagementException.forbidden(
                    "EXECUTIVE_TASK_ACTION_FORBIDDEN", "只能验收或退回当前董事长任职本人权威交办的任务");
        }
        Map<String, Object> row = rows.getFirst();
        long currentVersion = ((Number) row.get("row_version")).longValue();
        if (currentVersion != expectedVersion) {
            throw GroupManagementException.conflict("TASK_VERSION_CONFLICT", "任务版本已变化，请刷新后重试");
        }
        String from = String.valueOf(row.get("lifecycle_status"));
        if (!List.of("RESULT_SUBMITTED", "AWAITING_REVIEW").contains(from)) {
            throw GroupManagementException.conflict("TASK_STATE_CONFLICT", "当前任务状态不允许验收或退回");
        }
        String to = "APPROVE".equals(command) ? "COMPLETED" : "REWORK";
        int changed = jdbc.update("""
                update management_task
                set lifecycle_status = :toStatus,
                    completed_at = case when :toStatus = 'COMPLETED' then now() else null end,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :taskId
                  and row_version = :expectedVersion and lifecycle_status = :fromStatus
                """, base(identity.principal()).addValue("taskId", taskId)
                .addValue("toStatus", to).addValue("expectedVersion", expectedVersion)
                .addValue("fromStatus", from));
        if (changed != 1) {
            throw GroupManagementException.conflict("TASK_VERSION_CONFLICT", "任务版本或状态已变化，请刷新后重试");
        }
        insertTransition(identity.principal(), taskId, from, to, command, expectedVersion + 1,
                idempotencyKey, identity.assignmentId(), Map.of("comment", comment == null ? "" : comment.trim()));
        if ("APPROVE".equals(command)) {
            jdbc.update("""
                    update task_reminder set status = 'CANCELLED'
                    where tenant_id = :tenantId and task_id = :taskId
                      and status in ('SCHEDULED', 'FAILED')
                    """, base(identity.principal()).addValue("taskId", taskId));
        }
        UUID targetAssignmentId = (UUID) row.get("target_assignment_id");
        String eventType = "APPROVE".equals(command) ? "ExecutiveTaskApproved" : "ExecutiveTaskReworkRequested";
        auditWriter.record("EXECUTIVE_TASK_" + command, "TASK", taskId,
                json(Map.of("from", from, "to", to, "comment", comment == null ? "" : comment.trim())));
        auditWriter.emit("TASK", taskId, eventType,
                json(Map.of("taskId", taskId, "from", from, "to", to)));
        if ("REWORK".equals(command)) {
            notificationService.createForAssignment(targetAssignmentId, "TASK_REWORK", "董事长要求任务返工",
                    String.valueOf(row.get("title")), "TASK", taskId,
                    "chairman-task-rework:" + taskId + ":" + (expectedVersion + 1));
        }
        ExecutiveTaskModels.ExecutiveTaskDetail response = detail(identity, taskId);
        idempotency.succeed(reservation, "EXECUTIVE_TASK", taskId, 200, response);
        return response;
    }

    private ExecutiveTaskModels.ExecutiveTaskDetail detail(RequestIdentity identity, UUID taskId) {
        List<ExecutiveTaskModels.ExecutiveTaskDetail> rows = jdbc.query("""
                select task.id, task.task_no, task.title, task.lifecycle_status, task.sla_status,
                       task.priority, task.due_at, task.row_version, task.creation_source,
                       task.org_unit_id, organization.name as org_unit_name,
                       assignee.position_assignment_id as assignee_assignment_id,
                       assignee.employee_snapshot ->> 'name' as assignee_name,
                       assignee.position_snapshot ->> 'name' as assignee_position_name,
                       reviewer.position_assignment_id as reviewer_assignment_id,
                       reviewer.employee_snapshot ->> 'name' as reviewer_name,
                       case task.lifecycle_status
                         when 'PROPOSED' then 0 when 'PENDING_ACK' then 10 when 'IN_PROGRESS' then 45
                         when 'RESULT_SUBMITTED' then 85 when 'AWAITING_REVIEW' then 90
                         when 'REWORK' then 55 when 'COMPLETED' then 100 when 'CANCELLED' then 100 else 0
                       end as progress,
                       directive.chairman_assignment_id = :chairmanAssignmentId as own_directive,
                       task.description,
                       coalesce(task.result_snapshot #>> '{result,summary}', task.result_snapshot ->> 'summary') as result_summary,
                       (select count(*) from task_evidence evidence
                        where evidence.tenant_id = task.tenant_id and evidence.task_id = task.id
                          and evidence.scan_status <> 'REJECTED') as evidence_count
                from management_task task
                join org_unit organization on organization.tenant_id = task.tenant_id
                                          and organization.id = task.org_unit_id
                left join task_participant assignee on assignee.tenant_id = task.tenant_id
                                                   and assignee.task_id = task.id
                                                   and assignee.participant_type = 'ASSIGNEE'
                                                   and assignee.valid_to is null
                left join task_participant reviewer on reviewer.tenant_id = task.tenant_id
                                                   and reviewer.task_id = task.id
                                                   and reviewer.participant_type = 'REVIEWER'
                                                   and reviewer.valid_to is null
                left join executive_task_directive directive on directive.tenant_id = task.tenant_id
                                                           and directive.task_id = task.id
                where task.tenant_id = :tenantId and task.id = :taskId
                  and exists (
                    select 1 from org_unit_closure scope
                    join org_unit root on root.tenant_id = scope.tenant_id
                                      and root.id = scope.ancestor_id
                                      and root.unit_type = 'GROUP' and root.parent_id is null
                                      and root.status = 'ACTIVE'
                    where scope.tenant_id = task.tenant_id and scope.descendant_id = task.org_unit_id
                  )
                """, base(identity.principal()).addValue("taskId", taskId)
                .addValue("chairmanAssignmentId", identity.assignmentId()), (rs, rowNum) -> {
            boolean own = rs.getBoolean("own_directive");
            return new ExecutiveTaskModels.ExecutiveTaskDetail(
                    rs.getObject("id", UUID.class), rs.getString("task_no"), rs.getString("title"),
                    rs.getString("lifecycle_status"), rs.getString("sla_status"), rs.getString("priority"),
                    rs.getObject("due_at", OffsetDateTime.class), rs.getLong("row_version"),
                    rs.getString("creation_source"), rs.getObject("org_unit_id", UUID.class),
                    rs.getString("org_unit_name"), rs.getObject("assignee_assignment_id", UUID.class),
                    rs.getString("assignee_name"), rs.getString("assignee_position_name"),
                    rs.getObject("reviewer_assignment_id", UUID.class), rs.getString("reviewer_name"),
                    rs.getInt("progress"), own ? rs.getString("description") : null,
                    own ? rs.getString("result_summary") : null,
                    own ? rs.getInt("evidence_count") : null,
                    own ? reminderTimes(identity.principal(), taskId) : null);
        });
        if (rows.isEmpty()) throw GroupManagementException.notFound();
        return rows.getFirst();
    }

    private String summarySql() {
        return """
                select task.id, task.task_no, task.title, task.lifecycle_status, task.sla_status,
                       task.priority, task.due_at, task.row_version, task.creation_source,
                       task.org_unit_id, organization.name as org_unit_name,
                       assignee.position_assignment_id as assignee_assignment_id,
                       assignee.employee_snapshot ->> 'name' as assignee_name,
                       assignee.position_snapshot ->> 'name' as assignee_position_name,
                       reviewer.position_assignment_id as reviewer_assignment_id,
                       reviewer.employee_snapshot ->> 'name' as reviewer_name,
                       case task.lifecycle_status
                         when 'PROPOSED' then 0 when 'PENDING_ACK' then 10 when 'IN_PROGRESS' then 45
                         when 'RESULT_SUBMITTED' then 85 when 'AWAITING_REVIEW' then 90
                         when 'REWORK' then 55 when 'COMPLETED' then 100 when 'CANCELLED' then 100 else 0
                       end as progress
                from management_task task
                join org_unit organization on organization.tenant_id = task.tenant_id
                                          and organization.id = task.org_unit_id
                left join task_participant assignee on assignee.tenant_id = task.tenant_id
                                                   and assignee.task_id = task.id
                                                   and assignee.participant_type = 'ASSIGNEE'
                                                   and assignee.valid_to is null
                left join task_participant reviewer on reviewer.tenant_id = task.tenant_id
                                                   and reviewer.task_id = task.id
                                                   and reviewer.participant_type = 'REVIEWER'
                                                   and reviewer.valid_to is null
                where task.tenant_id = :tenantId
                  and exists (
                    select 1 from org_unit_closure scope
                    join org_unit root on root.tenant_id = scope.tenant_id
                                      and root.id = scope.ancestor_id
                                      and root.unit_type = 'GROUP' and root.parent_id is null
                                      and root.status = 'ACTIVE'
                    where scope.tenant_id = task.tenant_id and scope.descendant_id = task.org_unit_id
                  )
                """;
    }

    private RequestIdentity requireChairman(String permission) {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        if (!featureGate.executiveTasksEnabled(principal.tenantId())) {
            throw GroupManagementException.featureNotEnabled();
        }
        UUID assignmentId = accessPolicy.requireBusinessActorAssignment();
        Integer valid = jdbc.queryForObject("""
                select count(*)
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                                                   and position.id = assignment.position_id
                join org_unit organization on organization.tenant_id = assignment.tenant_id
                                          and organization.id = assignment.org_unit_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and employee.account_id = :actorId
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE' and position.status = 'ACTIVE'
                  and position.code = 'GROUP_CHAIRMAN'
                  and organization.status = 'ACTIVE' and organization.unit_type = 'GROUP'
                  and organization.parent_id is null
                """, base(principal).addValue("assignmentId", assignmentId)
                .addValue("actorId", principal.actorId()), Integer.class);
        if (valid == null || valid != 1) throw BusinessIdentityException.roleMismatch();
        accessPolicy.requirePermission(permission);
        return new RequestIdentity(principal, assignmentId);
    }

    private Target requireTarget(TenantPrincipal principal, UUID assignmentId) {
        List<Target> targets = jdbc.query("""
                select assignment.id, assignment.org_unit_id
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                                                   and position.id = assignment.position_id
                join org_unit organization on organization.tenant_id = assignment.tenant_id
                                          and organization.id = assignment.org_unit_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and position.code in ('GROUP_GENERAL_MANAGER', 'GROUP_VICE_PRESIDENT')
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                  and employee.employment_status = 'ACTIVE' and employee.account_id is not null
                  and position.status = 'ACTIVE' and organization.status = 'ACTIVE'
                  and organization.unit_type = 'GROUP' and organization.parent_id is null
                """, base(principal).addValue("assignmentId", assignmentId),
                (rs, rowNum) -> new Target(rs.getObject("id", UUID.class), rs.getObject("org_unit_id", UUID.class)));
        if (targets.size() != 1) {
            throw GroupManagementException.forbidden(
                    "EXECUTIVE_TASK_TARGET_FORBIDDEN", "目标必须是同租户集团根组织下有效的集团总经理或集团副总经理任职");
        }
        return targets.getFirst();
    }

    private void insertParticipant(TenantPrincipal principal, UUID taskId, String type, UUID assignmentId) {
        int inserted = jdbc.update("""
                insert into task_participant
                    (tenant_id, task_id, participant_type, position_assignment_id,
                     employee_snapshot, position_snapshot, org_snapshot)
                select :tenantId, :taskId, :type, assignment.id,
                       jsonb_build_object('id', employee.id, 'name', employee.name, 'employeeNo', employee.employee_no),
                       jsonb_build_object('id', position.id, 'code', position.code, 'name', position.name),
                       jsonb_build_object('id', organization.id, 'code', organization.code,
                                          'name', organization.name, 'unitType', organization.unit_type)
                from employee_position_assignment assignment
                join employee on employee.tenant_id = assignment.tenant_id
                             and employee.id = assignment.employee_id
                join position_definition position on position.tenant_id = assignment.tenant_id
                                                   and position.id = assignment.position_id
                join org_unit organization on organization.tenant_id = assignment.tenant_id
                                          and organization.id = assignment.org_unit_id
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                """, base(principal).addValue("taskId", taskId).addValue("type", type)
                .addValue("assignmentId", assignmentId));
        if (inserted != 1) throw new IllegalArgumentException("任务参与人任职不存在");
    }

    private void insertTransition(
            TenantPrincipal principal,
            UUID taskId,
            String from,
            String to,
            String command,
            long version,
            String idempotencyKey,
            UUID actorAssignmentId,
            Object payload
    ) {
        jdbc.update("""
                insert into task_transition
                    (tenant_id, task_id, from_status, to_status, command, actor_account_id,
                     actor_assignment_id, task_version, idempotency_key, payload)
                values
                    (:tenantId, :taskId, :fromStatus, :toStatus, :command, :actorId,
                     :actorAssignmentId, :taskVersion, :idempotencyKey, cast(:payload as jsonb))
                """, base(principal).addValue("taskId", taskId).addValue("fromStatus", from)
                .addValue("toStatus", to).addValue("command", command).addValue("actorId", principal.actorId())
                .addValue("actorAssignmentId", actorAssignmentId).addValue("taskVersion", version)
                .addValue("idempotencyKey", idempotencyKey).addValue("payload", json(payload)));
    }

    private List<OffsetDateTime> reminderTimes(TenantPrincipal principal, UUID taskId) {
        return jdbc.query("""
                select remind_at from task_reminder
                where tenant_id = :tenantId and task_id = :taskId
                order by remind_at
                """, base(principal).addValue("taskId", taskId),
                (rs, rowNum) -> rs.getObject("remind_at", OffsetDateTime.class));
    }

    private void validateSchedule(OffsetDateTime dueAt, List<OffsetDateTime> reminders) {
        OffsetDateTime now = OffsetDateTime.now();
        if (!dueAt.isAfter(now)) throw new IllegalArgumentException("dueAt必须晚于当前时间");
        for (OffsetDateTime reminder : reminders) {
            if (!reminder.isAfter(now) || !reminder.isBefore(dueAt)) {
                throw new IllegalArgumentException("提醒时间必须晚于当前时间且早于截止时间");
            }
        }
    }

    private String normalizePriority(String value) {
        String priority = value == null || value.isBlank() ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "NORMAL", "HIGH", "URGENT").contains(priority)) {
            throw new IllegalArgumentException("priority必须是LOW、NORMAL、HIGH或URGENT");
        }
        return priority;
    }

    private void rejectUnknown(Map<String, Object> unknownFields) {
        if (unknownFields != null && !unknownFields.isEmpty()) {
            throw new IllegalArgumentException("请求包含不允许的字段: " + String.join(",", unknownFields.keySet()));
        }
    }

    private ExecutiveTaskModels.ExecutiveTaskDetail replay(JsonNode snapshot) {
        try {
            return objectMapper.treeToValue(snapshot, ExecutiveTaskModels.ExecutiveTaskDetail.class);
        } catch (Exception exception) {
            throw new IllegalStateException("幂等响应快照无效", exception);
        }
    }

    private String hash(Object value) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(objectMapper.valueToTree(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算命令请求哈希", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法序列化任务数据", exception);
        }
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private record RequestIdentity(TenantPrincipal principal, UUID assignmentId) {
    }

    private record Target(UUID assignmentId, UUID organizationId) {
    }
}
