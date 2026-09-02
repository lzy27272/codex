package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
class WeComBindingEnrollmentStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final WeComProperties properties;
    private final ObjectMapper objectMapper;

    WeComBindingEnrollmentStore(
            NamedParameterJdbcTemplate jdbc, TenantDatabaseContext databaseContext,
            WeComProperties properties, ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    PreviewRecord preview(String tokenHash) {
        apply();
        expireByToken(tokenHash);
        List<PreviewRecord> rows = jdbc.query("""
                select request.id, request.status, request.expires_at,
                       employee.name as employee_name, position.name as position_name,
                       case when org.unit_type = 'DEPARTMENT' then org.name end as department_name,
                       hotel.name as hotel_name
                from wecom_user_binding_request request
                join user_account account
                  on account.tenant_id = request.tenant_id and account.id = request.account_id
                join employee employee
                  on employee.tenant_id = account.tenant_id and employee.account_id = account.id
                join employee_position_assignment assignment
                  on assignment.tenant_id = request.tenant_id
                 and assignment.id = request.preferred_assignment_id
                join position_definition position
                  on position.tenant_id = assignment.tenant_id and position.id = assignment.position_id
                join org_unit org
                  on org.tenant_id = assignment.tenant_id and org.id = assignment.org_unit_id
                left join lateral (
                    select ancestor.name
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = assignment.tenant_id
                      and closure.descendant_id = assignment.org_unit_id
                      and ancestor.unit_type = 'HOTEL'
                    order by closure.depth asc limit 1
                ) hotel on true
                where request.tenant_id = :tenantId and request.token_hash = :tokenHash
                """, params().addValue("tokenHash", tokenHash), (rs, rowNum) -> new PreviewRecord(
                rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("employee_name"),
                rs.getString("hotel_name"), rs.getString("department_name"), rs.getString("position_name")
        ));
        if (rows.size() != 1) throw new IllegalArgumentException("绑定邀请不存在或已失效");
        return rows.getFirst();
    }

    @Transactional
    UUID start(String tokenHash, String stateHash, String verifierHash) {
        apply();
        expireByToken(tokenHash);
        List<UUID> ids = jdbc.query("""
                select id from wecom_user_binding_request
                where tenant_id = :tenantId and token_hash = :tokenHash
                  and status = 'WAITING_SCAN' and expires_at > now()
                for update
                """, params().addValue("tokenHash", tokenHash), (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.size() != 1) throw new IllegalArgumentException("邀请已使用、已过期或不可继续");
        UUID id = ids.getFirst();
        jdbc.update("""
                update wecom_user_binding_request
                set status = 'AUTHORIZING', oauth_state_hash = :stateHash,
                    browser_verifier_hash = :verifierHash, failure_code = null,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", id).addValue("stateHash", stateHash)
                .addValue("verifierHash", verifierHash));
        return id;
    }

    @Transactional
    UUID claim(String stateHash, String verifierHash, String providerCodeHash) {
        apply();
        List<UUID> ids = jdbc.query("""
                select id from wecom_user_binding_request
                where tenant_id = :tenantId and oauth_state_hash = :stateHash
                  and browser_verifier_hash = :verifierHash and status = 'AUTHORIZING'
                  and provider_code_hash is null and expires_at > now()
                for update
                """, params().addValue("stateHash", stateHash).addValue("verifierHash", verifierHash),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        if (ids.size() != 1) throw new IllegalArgumentException("企业微信绑定授权状态无效或已过期");
        UUID id = ids.getFirst();
        jdbc.update("""
                update wecom_user_binding_request
                set provider_code_hash = :providerCodeHash, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", id).addValue("providerCodeHash", providerCodeHash));
        return id;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Completion complete(UUID requestId, String wecomUserId, String fingerprint) {
        apply();
        List<RequestIdentity> requests = jdbc.query("""
                select id, account_id, expires_at from wecom_user_binding_request
                where tenant_id = :tenantId and id = :id and status = 'AUTHORIZING'
                for update
                """, params().addValue("id", requestId), (rs, rowNum) -> new RequestIdentity(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("expires_at", OffsetDateTime.class)
        ));
        if (requests.size() != 1) throw new IllegalArgumentException("企业微信绑定申请状态已变化");
        RequestIdentity request = requests.getFirst();
        if (!request.expiresAt().isAfter(OffsetDateTime.now())) {
            expireRequest(requestId);
            systemAudit(requestId, request.accountId(), "WECOM_BINDING_INVITATION_EXPIRED", Map.of(
                    "accountId", request.accountId(), "failureCode", "INVITATION_EXPIRED"
            ));
            return new Completion(request.accountId(), "EXPIRED");
        }
        Integer identityReady = jdbc.queryForObject("""
                select count(*)
                from wecom_user_binding_request request
                join user_account account
                  on account.tenant_id = request.tenant_id and account.id = request.account_id
                join employee employee
                  on employee.tenant_id = account.tenant_id and employee.account_id = account.id
                join employee_position_assignment assignment
                  on assignment.tenant_id = request.tenant_id
                 and assignment.id = request.preferred_assignment_id
                 and assignment.employee_id = employee.id
                where request.tenant_id = :tenantId and request.id = :id
                  and account.status = 'ACTIVE' and employee.employment_status = 'ACTIVE'
                  and assignment.status = 'ACTIVE' and assignment.valid_from <= current_date
                  and (assignment.valid_to is null or assignment.valid_to >= current_date)
                """, params().addValue("id", requestId), Integer.class);
        if (identityReady == null || identityReady != 1) {
            jdbc.update("""
                    update wecom_user_binding_request
                    set status = 'FAILED', candidate_wecom_user_id = null,
                        candidate_fingerprint = null, oauth_state_hash = null,
                        browser_verifier_hash = null, failure_code = 'ACCOUNT_OR_ASSIGNMENT_INACTIVE',
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, params().addValue("id", requestId));
            notifyGovernance(requestId, "FAILED");
            systemAudit(requestId, request.accountId(), "WECOM_BINDING_IDENTITY_INACTIVE", Map.of(
                    "accountId", request.accountId(), "failureCode", "ACCOUNT_OR_ASSIGNMENT_INACTIVE"
            ));
            return new Completion(request.accountId(), "FAILED");
        }
        List<UUID> conflicts = jdbc.query("""
                select account_id from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId and wecom_user_id = :userId
                  and account_id <> :accountId and status in ('ACTIVE','SUSPENDED')
                for update
                """, params().addValue("userId", wecomUserId).addValue("accountId", request.accountId()),
                (rs, rowNum) -> rs.getObject("account_id", UUID.class));
        String status = conflicts.isEmpty() ? "PENDING_APPROVAL" : "CONFLICT";
        UUID conflictingAccount = conflicts.isEmpty() ? null : conflicts.getFirst();
        jdbc.update("""
                update wecom_user_binding_request
                set status = :status, candidate_wecom_user_id = :userId,
                    candidate_fingerprint = :fingerprint, conflicting_account_id = :conflictingAccount,
                    oauth_state_hash = null, browser_verifier_hash = null, failure_code = null,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", requestId).addValue("status", status)
                .addValue("userId", wecomUserId).addValue("fingerprint", fingerprint)
                .addValue("conflictingAccount", conflictingAccount));
        notifyGovernance(requestId, status);
        systemAudit(requestId, request.accountId(), "CONFLICT".equals(status)
                ? "WECOM_BINDING_IDENTITY_CONFLICT" : "WECOM_BINDING_SCANNED", Map.of(
                "accountId", request.accountId(), "fingerprint", WeComUserBindingAdministrationService.maskFingerprint(fingerprint),
                "status", status
        ));
        return new Completion(request.accountId(), status);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void fail(UUID requestId, String failureCode) {
        apply();
        jdbc.update("""
                update wecom_user_binding_request
                set status = 'FAILED', candidate_wecom_user_id = null,
                    oauth_state_hash = null, browser_verifier_hash = null,
                    failure_code = :failureCode, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and status = 'AUTHORIZING'
                """, params().addValue("id", requestId).addValue("failureCode", failureCode));
        systemAudit(requestId, null, "WECOM_BINDING_OAUTH_FAILED", Map.of("failureCode", failureCode));
    }

    private void notifyGovernance(UUID requestId, String status) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                select distinct :tenantId, assignment.account_id, 'WECOM_BINDING_PENDING',
                       case when :status in ('CONFLICT','FAILED') then '企业微信绑定异常待处理' else '企业微信绑定待确认' end,
                       case when :status = 'CONFLICT'
                            then '发现一项身份冲突，请在中台核对后审批。'
                            when :status = 'FAILED'
                            then '员工账号或任职已失效，请在中台核对后处理。'
                            else '员工已完成企业微信验证，请在中台确认启用。' end,
                       'WECOM_BINDING_REQUEST', :requestId,
                       'wecom-binding:' || cast(:requestId as text) || ':governance:' || assignment.account_id::text
                from role_assignment assignment
                join app_role role on role.tenant_id = assignment.tenant_id and role.id = assignment.role_id
                join user_account account
                  on account.tenant_id = assignment.tenant_id and account.id = assignment.account_id
                where assignment.tenant_id = :tenantId and role.code in ('CEO','PLATFORM_ADMIN','HR_KPI_ADMIN')
                  and assignment.valid_from <= now()
                  and (assignment.valid_to is null or assignment.valid_to >= now())
                  and account.status = 'ACTIVE'
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, params().addValue("requestId", requestId).addValue("status", status));
    }

    private void systemAudit(UUID requestId, UUID accountId, String action, Map<String, ?> fields) {
        Map<String, Object> data = new java.util.LinkedHashMap<>(fields);
        if (accountId != null) data.putIfAbsent("accountId", accountId);
        jdbc.update("""
                insert into audit_log
                    (tenant_id, actor_id, action, resource_type, resource_id,
                     correlation_id, trace_id, outcome, sensitivity_level, after_data)
                values
                    (:tenantId, null, :action, 'wecom_user_binding_request', :requestId,
                     :correlationId, :correlationId, 'SUCCESS', 'INTERNAL', cast(:afterData as jsonb))
                """, params().addValue("action", action).addValue("requestId", requestId)
                .addValue("correlationId", UUID.randomUUID()).addValue("afterData", json(data)));
    }

    private void expireByToken(String tokenHash) {
        jdbc.update("""
                update wecom_user_binding_request
                set status = 'EXPIRED', candidate_wecom_user_id = null,
                    oauth_state_hash = null, browser_verifier_hash = null,
                    failure_code = 'INVITATION_EXPIRED', row_version = row_version + 1
                where tenant_id = :tenantId and token_hash = :tokenHash
                  and status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                  and expires_at <= now()
                """, params().addValue("tokenHash", tokenHash));
    }

    private void expireRequest(UUID requestId) {
        jdbc.update("""
                update wecom_user_binding_request
                set status = 'EXPIRED', candidate_wecom_user_id = null,
                    oauth_state_hash = null, browser_verifier_hash = null,
                    failure_code = 'INVITATION_EXPIRED', row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                """, params().addValue("id", requestId));
    }

    private void apply() { databaseContext.apply(properties.tenantId()); }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenantId", properties.tenantId()).addValue("corpId", properties.corpId());
    }

    private String json(Map<String, ?> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("审计数据序列化失败", exception); }
    }

    record PreviewRecord(
            UUID requestId, String status, OffsetDateTime expiresAt, String employeeName,
            String hotelName, String departmentName, String positionName
    ) { }
    record Completion(UUID accountId, String status) { }
    private record RequestIdentity(UUID id, UUID accountId, OffsetDateTime expiresAt) { }
}
