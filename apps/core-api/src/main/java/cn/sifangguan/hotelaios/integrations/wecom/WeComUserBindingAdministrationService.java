package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.*;

@Service
@ConditionalOnProperty(name = "app.wecom.enabled", havingValue = "true")
public class WeComUserBindingAdministrationService {
    private static final Duration INVITATION_TTL = Duration.ofMinutes(120);
    private static final Duration EXPIRING_SOON = Duration.ofMinutes(30);
    private static final List<String> OPEN_REQUEST_STATES = List.of(
            "WAITING_SCAN", "AUTHORIZING", "PENDING_APPROVAL", "CONFLICT"
    );
    private static final Set<String> PUBLIC_REASON_CODES = Set.of(
            "INVITATION_EXPIRED",
            "ACCOUNT_OR_ASSIGNMENT_INACTIVE",
            "MULTIPLE_ACTIVE_ASSIGNMENTS",
            "PREFERRED_ASSIGNMENT_INACTIVE",
            "WECOM_OAUTH_TIMEOUT",
            "WECOM_OAUTH_INVALID_RESPONSE",
            "WECOM_OAUTH_TECHNICAL_FAILURE",
            "REBIND_REQUESTED",
            "IDENTITY_TRANSFERRED",
            "IDENTITY_TRANSFERRED_BY_ONBOARDING",
            "MANUALLY_REVOKED",
            "DEFAULT_ASSIGNMENT_SELECTED",
            "POSITION_APPLICABILITY_REMOVED",
            "POSITION_DELETED",
            "ACCOUNT_PERMANENTLY_DELETED",
            "DIRECTORY_MEMBER_DISABLED",
            "DIRECTORY_MEMBER_DELETED",
            "DIRECTORY_IDENTITY_REUSED",
            "DIRECTORY_USER_ID_CHANGED"
    );
    private static final Set<String> DIRECTORY_REBIND_REQUIRED_REASONS = Set.of(
            "DIRECTORY_MEMBER_DISABLED",
            "DIRECTORY_MEMBER_DELETED",
            "DIRECTORY_IDENTITY_REUSED"
    );
    private static final String RESTRICTED_REASON = "已记录受限审批说明";

    private final SecureRandom secureRandom = new SecureRandom();
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final WeComProperties properties;
    private final boolean directorySyncEnabled;

    public WeComUserBindingAdministrationService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            WeComProperties properties,
            @Value("${app.wecom.directory-sync-enabled:false}") boolean directorySyncEnabled
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.directorySyncEnabled = directorySyncEnabled;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        TenantPrincipal principal = prepare("wecom-binding.read");
        List<PersonBase> bases = jdbc.query("""
                select u.id as account_id, e.id as employee_id, e.name as employee_name,
                       u.login_name, chosen.id as assignment_id, chosen.position_name,
                       chosen.department_name, chosen.hotel_code, chosen.hotel_name,
                       b.id as binding_id, b.status as canonical_status,
                       b.preferred_assignment_id, b.user_id_fingerprint,
                       b.last_verified_at, b.status_reason as binding_reason,
                       b.row_version as binding_version, b.updated_at as binding_updated_at,
                       req.id as request_id, req.status as request_status,
                       req.expires_at, req.failure_code, req.decision_reason,
                       req.row_version as request_version, req.updated_at as request_updated_at,
                       coalesce(binding_actor.display_name, request_actor.display_name) as updated_by
                from employee e
                join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                left join lateral (
                    select a.id, p.name as position_name,
                           case when o.unit_type = 'DEPARTMENT' then o.name end as department_name,
                           hotel.code as hotel_code, hotel.name as hotel_name
                    from employee_position_assignment a
                    join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                    join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                    left join lateral (
                        select ancestor.code, ancestor.name
                        from org_unit_closure closure
                        join org_unit ancestor
                          on ancestor.tenant_id = closure.tenant_id
                         and ancestor.id = closure.ancestor_id
                        where closure.tenant_id = a.tenant_id
                          and closure.descendant_id = a.org_unit_id
                          and ancestor.unit_type = 'HOTEL'
                        order by closure.depth asc
                        limit 1
                    ) hotel on true
                    where a.tenant_id = e.tenant_id and a.employee_id = e.id
                      and a.status = 'ACTIVE' and a.valid_from <= current_date
                      and (a.valid_to is null or a.valid_to >= current_date)
                    order by a.is_primary desc, a.created_at, a.id
                    limit 1
                ) chosen on true
                left join wecom_user_binding b
                  on b.tenant_id = e.tenant_id and b.account_id = u.id and b.corp_id = :corpId
                left join lateral (
                    select r.* from wecom_user_binding_request r
                    where r.tenant_id = e.tenant_id and r.account_id = u.id
                    order by r.created_at desc limit 1
                ) req on true
                left join user_account binding_actor
                  on binding_actor.tenant_id = b.tenant_id and binding_actor.id = b.updated_by
                left join user_account request_actor
                  on request_actor.tenant_id = req.tenant_id and request_actor.id = req.requested_by
                where e.tenant_id = :tenantId
                order by chosen.hotel_code nulls last, e.name, u.login_name
                """, base(principal), (rs, rowNum) -> new PersonBase(
                rs.getObject("account_id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("employee_name"), rs.getString("login_name"),
                rs.getObject("assignment_id", UUID.class), rs.getString("position_name"),
                rs.getString("department_name"), rs.getString("hotel_code"), rs.getString("hotel_name"),
                rs.getObject("binding_id", UUID.class), rs.getString("canonical_status"),
                rs.getObject("preferred_assignment_id", UUID.class), rs.getString("user_id_fingerprint"),
                rs.getObject("last_verified_at", OffsetDateTime.class), rs.getString("binding_reason"),
                nullableLong(rs, "binding_version"), rs.getObject("binding_updated_at", OffsetDateTime.class),
                rs.getObject("request_id", UUID.class), rs.getString("request_status"),
                rs.getObject("expires_at", OffsetDateTime.class), rs.getString("failure_code"),
                rs.getString("decision_reason"), nullableLong(rs, "request_version"),
                rs.getObject("request_updated_at", OffsetDateTime.class), rs.getString("updated_by")
        ));

        Map<UUID, List<AssignmentOption>> assignments = loadAssignments(principal);
        OffsetDateTime now = OffsetDateTime.now();
        List<PersonRow> rows = bases.stream().map(base -> toRow(base, assignments.getOrDefault(
                base.accountId(), List.of()), now)).toList();
        Counters counters = new Counters(
                count(rows, "WAITING_SCAN"), count(rows, "WAITING_APPROVAL"),
                count(rows, "ACTIVE"), count(rows, "SUSPENDED"), count(rows, "ABNORMAL")
        );
        return new Dashboard(counters, new Capabilities(true,
                allowed(principal, "wecom-binding.manage"), allowed(principal, "wecom-binding.approve")), rows);
    }

    @Transactional
    public InviteResponse invite(InviteRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        expireRequests(principal.tenantId(), request.accountId());
        AssignmentIdentity identity = requireActiveAssignment(principal.tenantId(), request.accountId(),
                request.preferredAssignmentId());
        Integer open = jdbc.queryForObject("""
                select count(*) from wecom_user_binding_request
                where tenant_id = :tenantId and account_id = :accountId
                  and status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                """, base(principal).addValue("accountId", request.accountId()), Integer.class);
        if (open != null && open > 0) {
            throw new IllegalArgumentException("该员工已有进行中的绑定申请，请先处理或取消");
        }

        String token = randomSecret();
        UUID requestId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(INVITATION_TTL);
        jdbc.update("""
                insert into wecom_user_binding_request
                    (id, tenant_id, account_id, preferred_assignment_id, status, token_hash,
                     expires_at, retain_until, requested_by, created_at, updated_at)
                values
                    (:id, :tenantId, :accountId, :assignmentId, 'WAITING_SCAN', :tokenHash,
                     :expiresAt, :retainUntil, :actorId, :now, :now)
                """, base(principal)
                .addValue("id", requestId).addValue("accountId", request.accountId())
                .addValue("assignmentId", request.preferredAssignmentId())
                .addValue("tokenHash", sha256(token)).addValue("expiresAt", expiresAt)
                .addValue("retainUntil", now.plusDays(180)).addValue("actorId", principal.actorId())
                .addValue("now", now));
        auditWriter.record("WECOM_BINDING_INVITED", "wecom_user_binding_request", requestId, json(Map.of(
                "accountId", request.accountId(), "assignmentId", request.preferredAssignmentId(),
                "hotelCode", nullSafe(identity.hotelCode()), "expiresAt", expiresAt.toString()
        )));
        return new InviteResponse(requestId, invitationUri(token), expiresAt, "WAITING_SCAN");
    }

    @Transactional
    public BulkInviteResponse bulkInvite(BulkInviteRequest request) {
        prepare("wecom-binding.manage");
        List<InviteResponse> responses = new ArrayList<>();
        for (InviteRequest invitation : request.invitations()) responses.add(invite(invitation));
        return new BulkInviteResponse(List.copyOf(responses));
    }

    @Transactional
    public BulkOperationResponse bulkSuspend(BulkSuspendRequest request) {
        prepare("wecom-binding.manage");
        if (!request.confirmed()) throw new IllegalArgumentException("批量暂停属于高风险操作，必须二次确认");
        List<OperationResult> results = new ArrayList<>();
        for (BulkSuspendItem item : request.bindings()) {
            results.add(suspend(item.accountId(), new VersionedReasonRequest(
                    item.expectedVersion(), reasonOr(request.reason(), "BULK_SUSPENDED"))));
        }
        return new BulkOperationResponse(List.copyOf(results));
    }

    @Transactional
    public InviteResponse retry(UUID requestId, VersionedReasonRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        BindingRequest current = lockRequest(principal, requestId, request.expectedVersion());
        if (!"FAILED".equals(current.status())) throw new IllegalArgumentException("仅技术故障申请可以重试");
        requireActiveAssignment(principal.tenantId(), current.accountId(), current.preferredAssignmentId());
        String token = randomSecret();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = now.plus(INVITATION_TTL);
        int updated = jdbc.update("""
                update wecom_user_binding_request
                set status = 'WAITING_SCAN', token_hash = :tokenHash,
                    oauth_state_hash = null, browser_verifier_hash = null,
                    provider_code_hash = null, candidate_wecom_user_id = null,
                    candidate_fingerprint = null, conflicting_account_id = null,
                    failure_code = null, decision_reason = :reason,
                    expires_at = :expiresAt, retain_until = :retainUntil,
                    reviewed_by = null, reviewed_at = null, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and row_version = :version
                """, base(principal).addValue("id", requestId).addValue("version", request.expectedVersion())
                .addValue("tokenHash", sha256(token)).addValue("reason", trimToNull(request.reason()))
                .addValue("expiresAt", expiresAt).addValue("retainUntil", now.plusDays(180)));
        if (updated != 1) throw new IllegalArgumentException("申请已变化，请刷新后重试");
        auditWriter.record("WECOM_BINDING_RETRY_INVITED", "wecom_user_binding_request", requestId,
                json(Map.of("accountId", current.accountId(), "expiresAt", expiresAt.toString())));
        return new InviteResponse(requestId, invitationUri(token), expiresAt, "WAITING_SCAN");
    }

    @Transactional
    public InviteResponse rebind(UUID accountId, RebindRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        if (!request.confirmed()) throw new IllegalArgumentException("重新绑定会使原身份立即失效，必须二次确认");
        requireActiveAssignment(principal.tenantId(), accountId, request.preferredAssignmentId());
        BindingState current = lockBinding(principal, accountId, request.expectedVersion());
        jdbc.update("""
                update wecom_user_binding
                set status = 'REVOKED', wecom_user_id = 'revoked:' || id::text,
                    status_reason = 'REBIND_REQUESTED', updated_by = :actorId,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and row_version = :version
                """, base(principal).addValue("id", current.id()).addValue("version", request.expectedVersion())
                .addValue("actorId", principal.actorId()));
        auditWriter.record("WECOM_BINDING_REBIND_REQUESTED", "wecom_user_binding", current.id(), json(Map.of(
                "accountId", accountId, "fingerprint", maskFingerprint(current.fingerprint()),
                "reasonProvided", trimToNull(request.reason()) != null,
                "reasonCode", "REBIND_REQUESTED"
        )));
        return invite(new InviteRequest(accountId, request.preferredAssignmentId()));
    }

    @Transactional
    public OperationResult approve(UUID requestId, DecisionRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        BindingRequest bindingRequest = lockRequest(principal, requestId, request.expectedVersion());
        if (!List.of("PENDING_APPROVAL", "CONFLICT").contains(bindingRequest.status())) {
            throw new IllegalArgumentException("当前申请状态不可确认");
        }
        if (bindingRequest.candidateUserId() == null || bindingRequest.candidateUserId().isBlank()) {
            throw new IllegalArgumentException("申请缺少已验证的企业微信身份");
        }
        if (directorySyncEnabled) {
            throw new IllegalArgumentException("企业微信通讯录联动已启用，请在人员自动入职审核中完成确认");
        }
        requireActiveAssignment(principal.tenantId(), bindingRequest.accountId(),
                bindingRequest.preferredAssignmentId());

        List<CanonicalBinding> locked = jdbc.query("""
                select id, account_id, wecom_user_id, status
                from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId
                  and (account_id = :accountId or wecom_user_id = :userId)
                order by id for update
                """, base(principal).addValue("accountId", bindingRequest.accountId())
                .addValue("userId", bindingRequest.candidateUserId()), (rs, rowNum) -> new CanonicalBinding(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("wecom_user_id"), rs.getString("status")
        ));
        CanonicalBinding conflict = locked.stream()
                .filter(item -> item.wecomUserId().equals(bindingRequest.candidateUserId()))
                .filter(item -> !item.accountId().equals(bindingRequest.accountId())).findFirst().orElse(null);
        if (conflict != null && !request.transferExistingBinding()) {
            throw new IllegalArgumentException("该企业微信身份已绑定其他账号，须二次确认后执行原子转移");
        }
        if (conflict != null) {
            jdbc.update("""
                    update wecom_user_binding
                    set status = 'REVOKED', wecom_user_id = 'revoked:' || id::text,
                        status_reason = 'IDENTITY_TRANSFERRED', updated_by = :actorId,
                        row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, base(principal).addValue("id", conflict.id())
                    .addValue("actorId", principal.actorId()));
        }

        String fingerprint = fingerprint(bindingRequest.candidateUserId());
        String assignmentSnapshot = assignmentSnapshot(principal.tenantId(), bindingRequest.accountId());
        CanonicalBinding target = locked.stream()
                .filter(item -> item.accountId().equals(bindingRequest.accountId())).findFirst().orElse(null);
        UUID bindingId;
        if (target == null) {
            bindingId = UUID.randomUUID();
            jdbc.update("""
                    insert into wecom_user_binding
                        (id, tenant_id, corp_id, wecom_user_id, user_id_fingerprint, account_id,
                         preferred_assignment_id, status, last_verified_at, assignment_snapshot_hash,
                         assignment_selection_required, updated_by)
                    values
                        (:id, :tenantId, :corpId, :userId, :fingerprint, :accountId,
                         :assignmentId, 'ACTIVE', now(), :snapshot, false, :actorId)
                    """, base(principal).addValue("id", bindingId)
                    .addValue("userId", bindingRequest.candidateUserId()).addValue("fingerprint", fingerprint)
                    .addValue("accountId", bindingRequest.accountId())
                    .addValue("assignmentId", bindingRequest.preferredAssignmentId())
                    .addValue("snapshot", assignmentSnapshot).addValue("actorId", principal.actorId()));
        } else {
            bindingId = target.id();
            jdbc.update("""
                    update wecom_user_binding
                    set wecom_user_id = :userId, user_id_fingerprint = :fingerprint,
                        preferred_assignment_id = :assignmentId, status = 'ACTIVE',
                        last_verified_at = now(), status_reason = null,
                        assignment_snapshot_hash = :snapshot, assignment_selection_required = false,
                        updated_by = :actorId, row_version = row_version + 1
                    where tenant_id = :tenantId and id = :id
                    """, base(principal).addValue("id", bindingId)
                    .addValue("userId", bindingRequest.candidateUserId()).addValue("fingerprint", fingerprint)
                    .addValue("assignmentId", bindingRequest.preferredAssignmentId())
                    .addValue("snapshot", assignmentSnapshot).addValue("actorId", principal.actorId()));
        }
        completeRequest(principal, requestId, request.expectedVersion(), "APPROVED", request.reason());
        notifyAccount(principal.tenantId(), bindingRequest.accountId(), requestId, "WECOM_BINDING_ACTIVE",
                "企业微信绑定已启用", "您的企业微信身份绑定已通过确认。", "approved");
        auditWriter.record(conflict == null ? "WECOM_BINDING_APPROVED" : "WECOM_BINDING_TRANSFERRED",
                "wecom_user_binding", bindingId, json(Map.of(
                        "accountId", bindingRequest.accountId(), "fingerprint", maskFingerprint(fingerprint),
                        "requestId", requestId, "transferred", conflict != null
                )));
        return new OperationResult(bindingRequest.accountId(), requestId, "ACTIVE",
                request.expectedVersion() + 1, "绑定已启用；群推送开关未改变");
    }

    @Transactional
    public OperationResult reject(UUID requestId, VersionedReasonRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        BindingRequest current = lockRequest(principal, requestId, request.expectedVersion());
        if (!List.of("PENDING_APPROVAL", "CONFLICT").contains(current.status())) {
            throw new IllegalArgumentException("当前申请状态不可拒绝");
        }
        completeRequest(principal, requestId, request.expectedVersion(), "REJECTED", request.reason());
        notifyAccount(principal.tenantId(), current.accountId(), requestId, "WECOM_BINDING_REJECTED",
                "企业微信绑定未通过", "请联系管理员核对本人账号与企业微信身份。", "rejected");
        auditWriter.record("WECOM_BINDING_REJECTED", "wecom_user_binding_request", requestId,
                json(Map.of("accountId", current.accountId(),
                        "reasonProvided", trimToNull(request.reason()) != null,
                        "reasonCode", "REJECTED_BY_REVIEWER")));
        return new OperationResult(current.accountId(), requestId, "REJECTED",
                request.expectedVersion() + 1, "申请已拒绝");
    }

    @Transactional
    public OperationResult cancel(UUID requestId, VersionedReasonRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        BindingRequest current = lockRequest(principal, requestId, request.expectedVersion());
        if (!OPEN_REQUEST_STATES.contains(current.status())) throw new IllegalArgumentException("当前申请不可取消");
        completeRequest(principal, requestId, request.expectedVersion(), "CANCELLED", request.reason());
        auditWriter.record("WECOM_BINDING_CANCELLED", "wecom_user_binding_request", requestId,
                json(Map.of("accountId", current.accountId())));
        return new OperationResult(current.accountId(), requestId, "CANCELLED",
                request.expectedVersion() + 1, "邀请已取消");
    }

    @Transactional
    public OperationResult suspend(UUID accountId, VersionedReasonRequest request) {
        return updateBinding(accountId, request, "wecom-binding.manage", "SUSPENDED",
                "WECOM_BINDING_SUSPENDED", "绑定已暂停");
    }

    @Transactional
    public OperationResult resume(UUID accountId, VersionedReasonRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        BindingState state = lockBinding(principal, accountId, request.expectedVersion());
        if (!"SUSPENDED".equals(state.status())) throw new IllegalArgumentException("仅已暂停的绑定可以恢复");
        requireDirectoryResumeSafe(principal, state);
        requireAccountReady(principal.tenantId(), accountId);
        return updateLockedBindingPrepared(principal, accountId, request, state, "ACTIVE",
                "WECOM_BINDING_RESUMED", "绑定已恢复");
    }

    @Transactional
    public OperationResult revoke(UUID accountId, VersionedReasonRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.approve");
        BindingState state = lockBinding(principal, accountId, request.expectedVersion());
        jdbc.update("""
                update wecom_user_binding
                set status = 'REVOKED', wecom_user_id = 'revoked:' || id::text,
                    status_reason = :reason, updated_by = :actorId, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and row_version = :version
                """, base(principal).addValue("id", state.id()).addValue("version", request.expectedVersion())
                .addValue("reason", reasonOr(request.reason(), "MANUALLY_REVOKED"))
                .addValue("actorId", principal.actorId()));
        notifyAccount(principal.tenantId(), accountId, state.id(), "WECOM_BINDING_REVOKED",
                "企业微信绑定已解除", "如需再次使用，请由管理员重新发起绑定。", "revoked");
        auditWriter.record("WECOM_BINDING_REVOKED", "wecom_user_binding", state.id(),
                json(Map.of("accountId", accountId, "fingerprint", maskFingerprint(state.fingerprint()))));
        return new OperationResult(accountId, null, "REVOKED", request.expectedVersion() + 1, "绑定已解除");
    }

    @Transactional
    public OperationResult selectPreferred(UUID accountId, PreferredAssignmentRequest request) {
        TenantPrincipal principal = prepare("wecom-binding.manage");
        requireActiveAssignment(principal.tenantId(), accountId, request.preferredAssignmentId());
        BindingState state = lockBinding(principal, accountId, request.expectedVersion());
        jdbc.update("""
                update wecom_user_binding
                set preferred_assignment_id = :assignmentId, assignment_selection_required = false,
                    assignment_snapshot_hash = :snapshot, status_reason = :reason,
                    updated_by = :actorId, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and row_version = :version
                """, base(principal).addValue("id", state.id()).addValue("version", request.expectedVersion())
                .addValue("assignmentId", request.preferredAssignmentId())
                .addValue("snapshot", assignmentSnapshot(principal.tenantId(), accountId))
                .addValue("reason", reasonOr(request.reason(), "DEFAULT_ASSIGNMENT_SELECTED"))
                .addValue("actorId", principal.actorId()));
        auditWriter.record("WECOM_BINDING_DEFAULT_ASSIGNMENT_SELECTED", "wecom_user_binding", state.id(),
                json(Map.of("accountId", accountId, "assignmentId", request.preferredAssignmentId())));
        return new OperationResult(accountId, null, state.status(), request.expectedVersion() + 1,
                "默认企微任职已更新；如绑定处于暂停状态，仍需管理员恢复");
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> history(UUID accountId) {
        TenantPrincipal principal = prepare("wecom-binding.read");
        return jdbc.query("""
                select audit.action, audit.actor_id, actor.display_name, audit.created_at,
                       case audit.action
                         when 'WECOM_BINDING_REBIND_REQUESTED' then '管理员已发起重新绑定'
                         when 'WECOM_BINDING_REJECTED' then '管理员已拒绝绑定申请'
                         when 'WECOM_BINDING_SUSPENDED' then '管理员已暂停绑定'
                         when 'WECOM_BINDING_RESUMED' then '管理员已恢复绑定'
                         when 'WECOM_BINDING_REVOKED' then '管理员已解除绑定'
                         when 'WECOM_BINDING_APPROVED' then '管理员已确认启用绑定'
                         when 'WECOM_BINDING_TRANSFERRED' then '管理员已确认转移绑定'
                         else '企业微信绑定操作已记录'
                       end as summary
                from audit_log audit
                left join user_account actor
                  on actor.tenant_id = audit.tenant_id and actor.id = audit.actor_id
                where audit.tenant_id = :tenantId
                  and audit.resource_type in ('wecom_user_binding','wecom_user_binding_request')
                  and (audit.after_data ->> 'accountId') = :accountId
                order by audit.created_at desc limit 200
                """, base(principal).addValue("accountId", accountId.toString()), (rs, rowNum) -> new AuditEntry(
                rs.getString("action"), rs.getObject("actor_id", UUID.class), rs.getString("display_name"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getString("summary")
        ));
    }

    private OperationResult updateBinding(
            UUID accountId, VersionedReasonRequest request, String permission,
            String status, String action, String message
    ) {
        TenantPrincipal principal = prepare(permission);
        return updateBindingPrepared(principal, accountId, request, status, action, message);
    }

    private OperationResult updateBindingPrepared(
            TenantPrincipal principal, UUID accountId, VersionedReasonRequest request,
            String status, String action, String message
    ) {
        BindingState state = lockBinding(principal, accountId, request.expectedVersion());
        return updateLockedBindingPrepared(principal, accountId, request, state, status, action, message);
    }

    private OperationResult updateLockedBindingPrepared(
            TenantPrincipal principal, UUID accountId, VersionedReasonRequest request,
            BindingState state, String status, String action, String message
    ) {
        if ("REVOKED".equals(state.status())) throw new IllegalArgumentException("已解除的绑定必须重新发起绑定");
        int updated = jdbc.update("""
                update wecom_user_binding
                set status = :status, status_reason = :reason, updated_by = :actorId,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and row_version = :version
                """, base(principal).addValue("id", state.id()).addValue("version", request.expectedVersion())
                .addValue("status", status).addValue("reason", reasonOr(request.reason(), action))
                .addValue("actorId", principal.actorId()));
        if (updated != 1) throw new IllegalArgumentException("数据已变化，请刷新后重试");
        notifyAccount(principal.tenantId(), accountId, state.id(), action,
                message, "请进入中台查看当前绑定状态。", status.toLowerCase());
        auditWriter.record(action, "wecom_user_binding", state.id(),
                json(Map.of("accountId", accountId,
                        "reasonProvided", trimToNull(request.reason()) != null,
                        "reasonCode", action,
                        "fingerprint", maskFingerprint(state.fingerprint()))));
        return new OperationResult(accountId, null, status, request.expectedVersion() + 1, message);
    }

    private void requireDirectoryResumeSafe(TenantPrincipal principal, BindingState state) {
        if (!directorySyncEnabled) return;
        if (DIRECTORY_REBIND_REQUIRED_REASONS.contains(state.statusReason())) {
            throw new IllegalArgumentException("该绑定因企业微信人员停用、删除或身份复用而暂停，必须重新发起绑定并审核");
        }
        List<UUID> openCandidates = jdbc.queryForList("""
                select id from wecom_person_onboarding
                where tenant_id = :tenantId and corp_id = :corpId
                  and user_id_fingerprint = :fingerprint
                  and status in ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT')
                order by created_at, id for update
                """, base(principal).addValue("fingerprint", state.fingerprint()), UUID.class);
        if (!openCandidates.isEmpty()) {
            throw new IllegalArgumentException("该企业微信身份存在待处理的人员入职或冲突申请，不能直接恢复绑定");
        }
        List<DirectoryFact> facts = jdbc.query("""
                select id, status, change_type, event_priority,
                       user_id_fingerprint, previous_user_id_fingerprint
                from wecom_directory_event_receipt
                where tenant_id = :tenantId and corp_id = :corpId
                  and (user_id_fingerprint = :fingerprint
                       or previous_user_id_fingerprint = :fingerprint)
                order by occurred_at desc, event_priority desc, received_at desc, id desc
                limit 1 for update
                """, base(principal).addValue("fingerprint", state.fingerprint()), (rs, rowNum) -> new DirectoryFact(
                rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("change_type"),
                rs.getInt("event_priority"), rs.getString("user_id_fingerprint"),
                rs.getString("previous_user_id_fingerprint")
        ));
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("尚无可验证的企业微信通讯录状态，不能恢复绑定");
        }
        DirectoryFact latest = facts.getFirst();
        if (!"SUCCEEDED".equals(latest.status())) {
            throw new IllegalArgumentException("最新企业微信通讯录事件尚未成功处理，不能恢复绑定");
        }
        boolean renamedAway = state.fingerprint().equals(latest.previousFingerprint())
                && !state.fingerprint().equals(latest.fingerprint());
        if (latest.eventPriority() >= 90 || "delete_user".equals(latest.changeType()) || renamedAway) {
            throw new IllegalArgumentException("企业微信成员当前不是有效状态，不能恢复绑定");
        }
        List<UUID> departures = jdbc.queryForList("""
                select departure.id
                from wecom_directory_event_receipt departure
                left join wecom_directory_event_receipt watermark
                  on watermark.tenant_id = departure.tenant_id
                 and watermark.corp_id = departure.corp_id
                 and watermark.id = :identityReceiptId
                where departure.tenant_id = :tenantId and departure.corp_id = :corpId
                  and (
                    (departure.user_id_fingerprint = :fingerprint
                     and (departure.change_type = 'delete_user' or departure.event_priority >= 90))
                    or
                    (departure.change_type = 'update_user'
                     and departure.previous_user_id_fingerprint = :fingerprint
                     and departure.user_id_fingerprint <> :fingerprint)
                  )
                  and (
                    cast(:identityReceiptId as uuid) is null or watermark.id is null
                    or (departure.occurred_at, departure.event_priority,
                        departure.received_at, departure.id::text)
                       > (watermark.occurred_at, watermark.event_priority,
                          watermark.received_at, watermark.id::text)
                  )
                order by departure.occurred_at desc, departure.event_priority desc,
                         departure.received_at desc, departure.id desc
                limit 1 for update of departure
                """, base(principal).addValue("fingerprint", state.fingerprint())
                .addValue("identityReceiptId", state.identityEventReceiptId()), UUID.class);
        if (!departures.isEmpty()) {
            throw new IllegalArgumentException("该企业微信身份在当前绑定建立后发生过停用、删除或身份变更，必须重新绑定审核");
        }
    }

    private BindingRequest lockRequest(TenantPrincipal principal, UUID requestId, long expectedVersion) {
        List<BindingRequest> rows = jdbc.query("""
                select id, account_id, preferred_assignment_id, status, candidate_wecom_user_id,
                       candidate_fingerprint, conflicting_account_id, row_version
                from wecom_user_binding_request
                where tenant_id = :tenantId and id = :id for update
                """, base(principal).addValue("id", requestId), (rs, rowNum) -> new BindingRequest(
                rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("preferred_assignment_id", UUID.class), rs.getString("status"),
                rs.getString("candidate_wecom_user_id"), rs.getString("candidate_fingerprint"),
                rs.getObject("conflicting_account_id", UUID.class), rs.getLong("row_version")
        ));
        if (rows.isEmpty()) throw new IllegalArgumentException("绑定申请不存在");
        BindingRequest row = rows.getFirst();
        if (row.rowVersion() != expectedVersion) throw new IllegalArgumentException("申请已变化，请刷新后重试");
        return row;
    }

    private BindingState lockBinding(TenantPrincipal principal, UUID accountId, long expectedVersion) {
        List<BindingState> rows = jdbc.query("""
                select id, status, status_reason, user_id_fingerprint,
                       identity_event_receipt_id, row_version
                from wecom_user_binding
                where tenant_id = :tenantId and corp_id = :corpId and account_id = :accountId
                for update
                """, base(principal).addValue("accountId", accountId), (rs, rowNum) -> new BindingState(
                rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("status_reason"),
                rs.getString("user_id_fingerprint"),
                rs.getObject("identity_event_receipt_id", UUID.class), rs.getLong("row_version")
        ));
        if (rows.isEmpty()) throw new IllegalArgumentException("该账号尚无企业微信绑定");
        BindingState row = rows.getFirst();
        if (row.rowVersion() != expectedVersion) throw new IllegalArgumentException("绑定已变化，请刷新后重试");
        return row;
    }

    private void completeRequest(
            TenantPrincipal principal, UUID requestId, long expectedVersion, String status, String reason
    ) {
        int updated = jdbc.update("""
                update wecom_user_binding_request
                set status = :status, candidate_wecom_user_id = null,
                    browser_verifier_hash = null, oauth_state_hash = null,
                    decision_reason = :reason, reviewed_by = :actorId, reviewed_at = now(),
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and row_version = :version
                """, base(principal).addValue("id", requestId).addValue("version", expectedVersion)
                .addValue("status", status).addValue("reason", trimToNull(reason))
                .addValue("actorId", principal.actorId()));
        if (updated != 1) throw new IllegalArgumentException("申请已变化，请刷新后重试");
    }

    private void expireRequests(UUID tenantId, UUID accountId) {
        jdbc.update("""
                update wecom_user_binding_request
                set status = 'EXPIRED', candidate_wecom_user_id = null,
                    browser_verifier_hash = null, oauth_state_hash = null,
                    failure_code = 'INVITATION_EXPIRED', row_version = row_version + 1
                where tenant_id = :tenantId and account_id = :accountId
                  and status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                  and expires_at <= now()
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("accountId", accountId));
    }

    private AssignmentIdentity requireActiveAssignment(UUID tenantId, UUID accountId, UUID assignmentId) {
        List<AssignmentIdentity> rows = jdbc.query("""
                select a.id, hotel.code as hotel_code
                from employee e
                join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                join employee_position_assignment a
                  on a.tenant_id = e.tenant_id and a.employee_id = e.id
                left join lateral (
                    select ancestor.code
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = a.tenant_id and closure.descendant_id = a.org_unit_id
                      and ancestor.unit_type = 'HOTEL'
                    order by closure.depth asc limit 1
                ) hotel on true
                where e.tenant_id = :tenantId and u.id = :accountId and a.id = :assignmentId
                  and u.status = 'ACTIVE' and e.employment_status = 'ACTIVE'
                  and a.status = 'ACTIVE' and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date)
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("accountId", accountId)
                .addValue("assignmentId", assignmentId), (rs, rowNum) -> new AssignmentIdentity(
                rs.getObject("id", UUID.class), rs.getString("hotel_code")
        ));
        if (rows.size() != 1) throw new IllegalArgumentException("账号、员工或所选任职已失效");
        return rows.getFirst();
    }

    private void requireAccountReady(UUID tenantId, UUID accountId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from employee e
                join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                join employee_position_assignment a on a.tenant_id = e.tenant_id and a.employee_id = e.id
                where e.tenant_id = :tenantId and u.id = :accountId
                  and u.status = 'ACTIVE' and e.employment_status = 'ACTIVE'
                  and a.status = 'ACTIVE' and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date)
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("accountId", accountId), Integer.class);
        if (count == null || count == 0) throw new IllegalArgumentException("账号、员工或任职已失效，不能恢复绑定");
    }

    private Map<UUID, List<AssignmentOption>> loadAssignments(TenantPrincipal principal) {
        Map<UUID, List<AssignmentOption>> result = new HashMap<>();
        jdbc.query("""
                select u.id as account_id, a.id, a.org_unit_id, p.name as position_name,
                       case when o.unit_type = 'DEPARTMENT' then o.name end as department_name,
                       hotel.code as hotel_code, hotel.name as hotel_name, a.is_primary
                from employee e
                join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                join employee_position_assignment a on a.tenant_id = e.tenant_id and a.employee_id = e.id
                join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                left join lateral (
                    select ancestor.code, ancestor.name
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id and ancestor.id = closure.ancestor_id
                    where closure.tenant_id = a.tenant_id and closure.descendant_id = a.org_unit_id
                      and ancestor.unit_type = 'HOTEL'
                    order by closure.depth asc limit 1
                ) hotel on true
                where e.tenant_id = :tenantId and a.status = 'ACTIVE'
                  and a.valid_from <= current_date and (a.valid_to is null or a.valid_to >= current_date)
                order by a.is_primary desc, hotel.code, p.name
                """, base(principal), rs -> {
            UUID accountId = rs.getObject("account_id", UUID.class);
            result.computeIfAbsent(accountId, ignored -> new ArrayList<>()).add(new AssignmentOption(
                    rs.getObject("id", UUID.class), rs.getObject("org_unit_id", UUID.class),
                    rs.getString("hotel_code"), rs.getString("hotel_name"),
                    rs.getString("department_name"), rs.getString("position_name"), rs.getBoolean("is_primary")
            ));
        });
        result.replaceAll((ignored, value) -> List.copyOf(value));
        return result;
    }

    private PersonRow toRow(PersonBase base, List<AssignmentOption> assignments, OffsetDateTime now) {
        String requestStatus = base.requestStatus();
        boolean currentRequest = requestStatus != null && OPEN_REQUEST_STATES.contains(requestStatus)
                && base.expiresAt() != null && base.expiresAt().isAfter(now);
        String status;
        if (currentRequest && "WAITING_SCAN".equals(requestStatus)) status = "WAITING_SCAN";
        else if (currentRequest && List.of("AUTHORIZING", "PENDING_APPROVAL").contains(requestStatus)) {
            status = "WAITING_APPROVAL";
        } else if (currentRequest && "CONFLICT".equals(requestStatus)) status = "ABNORMAL";
        else if ("FAILED".equals(requestStatus)) status = "ABNORMAL";
        else if (base.canonicalStatus() != null) status = base.canonicalStatus();
        else if ("EXPIRED".equals(requestStatus)) status = "EXPIRED";
        else status = "UNBOUND";
        boolean failedRequest = "FAILED".equals(requestStatus);
        String reason = currentRequest || failedRequest
                ? publicReason(base.failureCode(), base.decisionReason())
                : publicReason(base.bindingReason(), null);
        String recommended = switch (status) {
            case "WAITING_SCAN" -> "请员工在有效期内使用企业微信打开邀请";
            case "WAITING_APPROVAL" -> "请集团CEO或平台管理员确认启用";
            case "ABNORMAL" -> "查看异常原因；技术故障可重试，身份冲突需审批";
            case "SUSPENDED" -> "核对账号和任职后，由有审批权限的管理员恢复";
            case "EXPIRED" -> "已过期链接不可恢复，请重新生成";
            case "UNBOUND" -> "发起绑定邀请";
            default -> "无需处理";
        };
        AssignmentOption selected = assignments.stream()
                .filter(item -> item.id().equals(base.preferredAssignmentId())).findFirst()
                .orElse(assignments.isEmpty() ? null : assignments.getFirst());
        String selectedLabel = selected == null ? null : String.join(" · ",
                nonBlank(selected.hotelName()), nonBlank(selected.departmentName()), nonBlank(selected.positionName()));
        long version = currentRequest || failedRequest ? base.requestVersion() : base.bindingVersion();
        OffsetDateTime updatedAt = currentRequest || failedRequest
                ? base.requestUpdatedAt() : base.bindingUpdatedAt();
        return new PersonRow(base.accountId(), base.employeeId(), base.hotelCode(), base.hotelName(),
                base.departmentName(), base.employeeName(), base.loginName(), base.positionName(), status,
                currentRequest || failedRequest ? base.requestId() : null,
                currentRequest || failedRequest ? requestStatus : null,
                currentRequest ? base.expiresAt() : null,
                currentRequest && base.expiresAt().isBefore(now.plus(EXPIRING_SOON)),
                base.preferredAssignmentId(), selectedLabel, maskFingerprint(base.fingerprint()),
                base.lastVerifiedAt(), base.updatedBy(), updatedAt, version, reason, recommended, assignments);
    }

    static String publicReason(String fixedCode, String restrictedText) {
        if (fixedCode != null && PUBLIC_REASON_CODES.contains(fixedCode)) return fixedCode;
        if ((fixedCode != null && !fixedCode.isBlank())
                || (restrictedText != null && !restrictedText.isBlank())) {
            return RESTRICTED_REASON;
        }
        return null;
    }

    private String assignmentSnapshot(UUID tenantId, UUID accountId) {
        List<String> ids = jdbc.queryForList("""
                select a.id::text from employee e
                join employee_position_assignment a on a.tenant_id = e.tenant_id and a.employee_id = e.id
                where e.tenant_id = :tenantId and e.account_id = :accountId
                  and a.status = 'ACTIVE' and a.valid_from <= current_date
                  and (a.valid_to is null or a.valid_to >= current_date)
                order by a.id
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("accountId", accountId), String.class);
        return sha256(String.join(",", ids));
    }

    private void notifyAccount(
            UUID tenantId, UUID accountId, UUID sourceId, String type,
            String title, String content, String suffix
    ) {
        jdbc.update("""
                insert into notification
                    (tenant_id, recipient_account_id, notification_type, title, content,
                     source_type, source_id, idempotency_key)
                values
                    (:tenantId, :accountId, :type, :title, :content,
                     'WECOM_BINDING_REQUEST', :sourceId, :key)
                on conflict (tenant_id, recipient_account_id, idempotency_key) do nothing
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("accountId", accountId)
                .addValue("type", type).addValue("title", title).addValue("content", content)
                .addValue("sourceId", sourceId).addValue("key", "wecom-binding:" + sourceId + ":" + suffix));
    }

    private TenantPrincipal prepare(String permission) {
        accessPolicy.requirePermission(permission);
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        if (!principal.tenantId().equals(properties.tenantId())) {
            throw new IllegalArgumentException("当前租户与企业微信配置不一致");
        }
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId()).addValue("corpId", properties.corpId());
    }

    private URI invitationUri(String token) {
        String base = properties.frontendBaseUrl().toString().replaceAll("/+$", "");
        return URI.create(base + "/#/wecom-bind?token=" + token);
    }

    private String randomSecret() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    private String fingerprint(String userId) { return sha256(properties.corpId() + ":" + userId); }

    static String maskFingerprint(String value) {
        if (value == null || value.length() < 8) return null;
        String upper = value.toUpperCase();
        return upper.substring(0, 4) + "••••" + upper.substring(upper.length() - 4);
    }

    private boolean allowed(TenantPrincipal principal, String permission) {
        return principal.hasPermission(permission) || principal.hasPermission("*");
    }

    private String json(Map<String, ?> value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("审计数据序列化失败", exception); }
    }

    private static long count(List<PersonRow> rows, String status) {
        return rows.stream().filter(row -> status.equals(row.bindingStatus())).count();
    }

    private static long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? 0 : value;
    }

    private static String nullSafe(String value) { return value == null ? "" : value; }
    private static String trimToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String reasonOr(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
    private static String nonBlank(String value) { return value == null ? "" : value; }

    private record AssignmentIdentity(UUID id, String hotelCode) { }
    private record CanonicalBinding(UUID id, UUID accountId, String wecomUserId, String status) { }
    private record BindingState(
            UUID id, String status, String statusReason, String fingerprint,
            UUID identityEventReceiptId, long rowVersion
    ) { }
    private record DirectoryFact(
            UUID id, String status, String changeType, int eventPriority,
            String fingerprint, String previousFingerprint
    ) { }
    private record BindingRequest(
            UUID id, UUID accountId, UUID preferredAssignmentId, String status,
            String candidateUserId, String candidateFingerprint, UUID conflictingAccountId, long rowVersion
    ) { }
    private record PersonBase(
            UUID accountId, UUID employeeId, String employeeName, String loginName,
            UUID assignmentId, String positionName, String departmentName, String hotelCode, String hotelName,
            UUID bindingId, String canonicalStatus, UUID preferredAssignmentId, String fingerprint,
            OffsetDateTime lastVerifiedAt, String bindingReason, long bindingVersion,
            OffsetDateTime bindingUpdatedAt, UUID requestId, String requestStatus, OffsetDateTime expiresAt,
            String failureCode, String decisionReason, long requestVersion,
            OffsetDateTime requestUpdatedAt, String updatedBy
    ) { }
}
