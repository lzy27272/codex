package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComBindingReconciliationWorker {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final WeComProperties properties;
    private final ObjectMapper objectMapper;

    public WeComBindingReconciliationWorker(
            NamedParameterJdbcTemplate jdbc, TenantDatabaseContext databaseContext,
            WeComProperties properties, ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.wecom.binding.reconciliation-delay-ms:60000}",
            initialDelayString = "${app.wecom.binding.reconciliation-initial-delay-ms:30000}")
    @Transactional
    public void reconcile() {
        databaseContext.apply(properties.tenantId());
        expireRequests();
        purgeRetainedRequests();
        List<BindingCandidate> bindings = jdbc.query("""
                select binding.id, binding.account_id, binding.preferred_assignment_id,
                       binding.status, binding.status_reason, binding.assignment_snapshot_hash,
                       account.status as account_status,
                       exists (
                           select 1 from employee employee
                           where employee.tenant_id = binding.tenant_id
                             and employee.account_id = binding.account_id
                             and employee.employment_status = 'ACTIVE'
                       ) as employee_active
                from wecom_user_binding binding
                join user_account account
                  on account.tenant_id = binding.tenant_id and account.id = binding.account_id
                where binding.tenant_id = :tenantId and binding.corp_id = :corpId
                  and binding.status <> 'REVOKED'
                order by binding.id
                for update of binding
                """, params(), (rs, rowNum) -> new BindingCandidate(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("preferred_assignment_id", UUID.class), rs.getString("status"),
                rs.getString("status_reason"), rs.getString("assignment_snapshot_hash"),
                rs.getString("account_status"), rs.getBoolean("employee_active")
        ));
        for (BindingCandidate binding : bindings) reconcile(binding);
    }

    private void reconcile(BindingCandidate binding) {
        List<UUID> assignments = activeAssignments(binding.accountId());
        String snapshot = WeComUserBindingAdministrationService.sha256(assignments.stream()
                .map(UUID::toString).sorted().reduce((left, right) -> left + "," + right).orElse(""));
        if (!"ACTIVE".equals(binding.accountStatus()) || !binding.employeeActive() || assignments.isEmpty()) {
            transition(binding, "SUSPENDED", null, snapshot, false,
                    "ACCOUNT_OR_ASSIGNMENT_INACTIVE", "账号、员工或任职已失效，绑定已自动暂停");
            return;
        }
        if (assignments.size() == 1) {
            UUID preferred = assignments.getFirst();
            boolean wasAssignmentSuspension = binding.reason() != null && List.of(
                    "ACCOUNT_OR_ASSIGNMENT_INACTIVE", "MULTIPLE_ACTIVE_ASSIGNMENTS",
                    "PREFERRED_ASSIGNMENT_INACTIVE"
            ).contains(binding.reason());
            transition(binding, binding.status(), preferred, snapshot, false, binding.reason(),
                    wasAssignmentSuspension
                            ? "任职已恢复为唯一有效任职，绑定仍保持暂停；请管理员在中台确认后恢复。"
                            : null);
            return;
        }
        boolean preferredValid = binding.preferredAssignmentId() != null
                && assignments.contains(binding.preferredAssignmentId());
        boolean changed = !snapshot.equals(binding.snapshot());
        if (!preferredValid || changed) {
            transition(binding, "SUSPENDED", preferredValid ? binding.preferredAssignmentId() : null,
                    snapshot, true, "MULTIPLE_ACTIVE_ASSIGNMENTS",
                    "检测到多个有效任职，请人事选择默认企微任职后由管理员恢复");
        }
    }

    private void transition(
            BindingCandidate before, String status, UUID preferredAssignmentId, String snapshot,
            boolean selectionRequired, String reason, String notificationContent
    ) {
        boolean unchanged = status.equals(before.status())
                && java.util.Objects.equals(preferredAssignmentId, before.preferredAssignmentId())
                && java.util.Objects.equals(snapshot, before.snapshot())
                && java.util.Objects.equals(reason, before.reason());
        if (unchanged) return;
        jdbc.update("""
                update wecom_user_binding
                set status = :status, preferred_assignment_id = :preferredAssignmentId,
                    assignment_snapshot_hash = :snapshot,
                    assignment_selection_required = :selectionRequired,
                    status_reason = :reason, updated_by = null, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", before.id()).addValue("status", status)
                .addValue("preferredAssignmentId", preferredAssignmentId).addValue("snapshot", snapshot)
                .addValue("selectionRequired", selectionRequired).addValue("reason", reason));
        if (notificationContent != null) {
            notifyAccount(before.accountId(), before.id(), status, notificationContent);
            notifyGovernance(before.id(), status, notificationContent);
        }
        audit(before, status, preferredAssignmentId, reason);
    }

    private List<UUID> activeAssignments(UUID accountId) {
        return jdbc.query("""
                select assignment.id
                from employee employee
                join employee_position_assignment assignment
                  on assignment.tenant_id = employee.tenant_id and assignment.employee_id = employee.id
                where employee.tenant_id = :tenantId and employee.account_id = :accountId
                  and employee.employment_status = 'ACTIVE'
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                order by assignment.id
                """, params().addValue("accountId", accountId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    private void expireRequests() {
        List<ExpiredRequest> expiring = jdbc.query("""
                select id, account_id from wecom_user_binding_request
                where tenant_id = :tenantId
                  and status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                  and expires_at <= now() for update
                """, params(), (rs, rowNum) -> new ExpiredRequest(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class)));
        for (ExpiredRequest request : expiring) {
            jdbc.update("""
                    update wecom_user_binding_request
                    set status = 'EXPIRED', candidate_wecom_user_id = null,
                        oauth_state_hash = null, browser_verifier_hash = null,
                        failure_code = 'INVITATION_EXPIRED', row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, params().addValue("id", request.id()));
            auditRequest(request, "WECOM_BINDING_INVITATION_EXPIRED", "INVITATION_EXPIRED");
        }
    }

    private void purgeRetainedRequests() {
        jdbc.update("""
                delete from wecom_user_binding_request
                where tenant_id = :tenantId and retain_until < now()
                  and status in ('APPROVED','REJECTED','CANCELLED','EXPIRED','FAILED')
                """, params());
    }

    private void notifyAccount(UUID accountId, UUID sourceId, String status, String content) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                values
                    (:tenantId, :accountId, 'WECOM_BINDING_RECONCILED', '企业微信绑定状态已变更',
                     :content, 'WECOM_BINDING', :sourceId,
                     'wecom-binding:' || cast(:sourceId as text) || ':reconciled:' || :status)
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, params().addValue("accountId", accountId).addValue("sourceId", sourceId)
                .addValue("status", status).addValue("content", content));
    }

    private void notifyGovernance(UUID sourceId, String status, String content) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                select distinct :tenantId, assignment.account_id, 'WECOM_BINDING_RECONCILED',
                       '企业微信绑定状态已变更', :content, 'WECOM_BINDING', :sourceId,
                       'wecom-binding:' || cast(:sourceId as text) || ':governance-reconciled:'
                       || :status || ':' || assignment.account_id::text
                from role_assignment assignment
                join app_role role on role.tenant_id = assignment.tenant_id and role.id = assignment.role_id
                where assignment.tenant_id = :tenantId and role.code in ('CEO','PLATFORM_ADMIN','HR_KPI_ADMIN')
                  and assignment.valid_from <= now()
                  and (assignment.valid_to is null or assignment.valid_to >= now())
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, params().addValue("sourceId", sourceId).addValue("status", status)
                .addValue("content", content));
    }

    private void audit(
            BindingCandidate before, String status, UUID preferredAssignmentId, String reason
    ) {
        systemAudit("WECOM_BINDING_RECONCILED", "wecom_user_binding", before.id(), Map.of(
                "accountId", before.accountId(), "status", status,
                "preferredAssignmentId", preferredAssignmentId == null ? "" : preferredAssignmentId.toString(),
                "reason", reason == null ? "" : reason
        ));
    }

    private void auditRequest(ExpiredRequest request, String action, String reason) {
        systemAudit(action, "wecom_user_binding_request", request.id(), Map.of(
                "accountId", request.accountId(), "reason", reason
        ));
    }

    private void systemAudit(String action, String resourceType, UUID resourceId, Map<String, ?> data) {
        jdbc.update("""
                insert into audit_log
                    (tenant_id, actor_id, action, resource_type, resource_id,
                     correlation_id, trace_id, outcome, sensitivity_level, after_data)
                values
                    (:tenantId, null, :action, :resourceType, :resourceId,
                     :correlationId, :correlationId, 'SUCCESS', 'INTERNAL', cast(:afterData as jsonb))
                """, params().addValue("action", action).addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId).addValue("correlationId", UUID.randomUUID())
                .addValue("afterData", json(data)));
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenantId", properties.tenantId()).addValue("corpId", properties.corpId());
    }

    private String json(Map<String, ?> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("审计数据序列化失败", exception); }
    }

    private record BindingCandidate(
            UUID id, UUID accountId, UUID preferredAssignmentId, String status,
            String reason, String snapshot, String accountStatus, boolean employeeActive
    ) { }
    private record ExpiredRequest(UUID id, UUID accountId) { }
}
