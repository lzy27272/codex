package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = {"app.wecom.enabled", "app.wecom.directory-sync-enabled"}, havingValue = "true"
)
class WeComDirectoryEventReceiptStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final WeComDirectoryProperties properties;
    private final WeComDirectorySecretCodec codec;
    private final ObjectMapper objectMapper;

    WeComDirectoryEventReceiptStore(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            WeComDirectoryProperties properties,
            WeComDirectorySecretCodec codec,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.properties = properties;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Reservation reserve(WeComDirectoryEvent event, UUID correlationId) {
        apply();
        String plaintext = json(event);
        String fingerprint = codec.fingerprint(event.effectiveUserId());
        String previousFingerprint = event.newUserId() != null
                && !event.newUserId().equals(event.userId())
                ? codec.fingerprint(event.userId()) : null;
        lockOnboardingIdentities(fingerprint, previousFingerprint);
        String payloadHash = WeComDirectorySecretCodec.sha256(plaintext);
        String eventKeyHash = WeComDirectorySecretCodec.sha256(String.join("|",
                event.eventType(), event.changeType(), codec.fingerprint(event.userId()),
                event.newUserId() == null ? "" : codec.fingerprint(event.newUserId()),
                event.occurredAt().toInstant().toString(),
                WeComDirectorySecretCodec.sha256(event.assignmentSnapshotMaterial()),
                payloadHash));
        UUID id = UUID.randomUUID();
        int inserted;
        try {
            inserted = jdbc.update("""
                    insert into wecom_directory_event_receipt
                        (id, tenant_id, corp_id, event_key_hash, payload_hash, payload_ciphertext,
                         event_type, change_type, event_priority, user_id_fingerprint,
                         previous_user_id_fingerprint, occurred_at,
                         status, correlation_id)
                    values
                        (:id, :tenantId, :corpId, :eventKeyHash, :payloadHash, :payloadCiphertext,
                         :eventType, :changeType, :eventPriority, :fingerprint,
                         :previousFingerprint, :occurredAt,
                         'PROCESSING', :correlationId)
                    on conflict (tenant_id, corp_id, event_key_hash) do nothing
                    """, params().addValue("id", id).addValue("eventKeyHash", eventKeyHash)
                    .addValue("payloadHash", payloadHash).addValue("payloadCiphertext", codec.encrypt(plaintext))
                    .addValue("eventType", event.eventType()).addValue("changeType", event.changeType())
                    .addValue("eventPriority", event.priority())
                    .addValue("fingerprint", fingerprint).addValue("previousFingerprint", previousFingerprint)
                    .addValue("occurredAt", event.occurredAt())
                    .addValue("correlationId", correlationId));
        } catch (DuplicateKeyException exception) {
            inserted = 0;
        }
        if (inserted == 1) return new Reservation(id, false);
        List<Existing> existing = jdbc.query("""
                select id, payload_hash from wecom_directory_event_receipt
                where tenant_id = :tenantId and corp_id = :corpId and event_key_hash = :eventKeyHash
                """, params().addValue("eventKeyHash", eventKeyHash), (rs, rowNum) -> new Existing(
                rs.getObject("id", UUID.class), rs.getString("payload_hash")));
        if (existing.size() != 1 || !existing.getFirst().payloadHash().equals(payloadHash)) {
            throw new IllegalArgumentException("WeCom directory event replay is inconsistent");
        }
        return new Reservation(existing.getFirst().id(), true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean begin(UUID receiptId) {
        apply();
        return jdbc.update("""
                update wecom_directory_event_receipt
                set status = 'PROCESSING', attempt_count = attempt_count + 1,
                    last_attempt_at = now(), processed_at = null, last_error_code = null,
                    row_version = row_version + 1
                where tenant_id = :tenantId and id = :id
                  and status = 'PROCESSING' and last_attempt_at is null
                """, params().addValue("id", receiptId)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    List<Recovery> claimRecoverable(int requestedLimit) {
        apply();
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbc.query("""
                with exhausted as (
                    select id from wecom_directory_event_receipt
                    where tenant_id = :tenantId and status = 'PROCESSING'
                      and attempt_count >= 8
                      and last_attempt_at < now() - interval '1 minute'
                    order by received_at, id
                    for update skip locked limit :limit
                ), dead_letters as (
                    update wecom_directory_event_receipt receipt
                    set status = 'DEAD_LETTER', processed_at = now(),
                        last_error_code = 'PROCESS_INTERRUPTED_AFTER_RETRY_LIMIT',
                        row_version = receipt.row_version + 1
                    from exhausted
                    where receipt.tenant_id = :tenantId and receipt.id = exhausted.id
                    returning receipt.id, receipt.correlation_id
                ), candidates as (
                    select id from wecom_directory_event_receipt
                    where tenant_id = :tenantId
                      and (
                        status = 'FAILED'
                        or (status = 'PROCESSING' and (
                            (last_attempt_at is null and received_at < now() - interval '1 minute')
                            or last_attempt_at < now() - interval '1 minute'
                        ))
                      )
                      and attempt_count < 8
                    order by received_at, id
                    for update skip locked limit :limit
                ), claimed as (
                    update wecom_directory_event_receipt receipt
                    set status = 'PROCESSING', attempt_count = receipt.attempt_count + 1,
                        last_attempt_at = now(), processed_at = null, last_error_code = null,
                        row_version = receipt.row_version + 1
                    from candidates
                    where receipt.tenant_id = :tenantId and receipt.id = candidates.id
                    returning receipt.id, receipt.correlation_id
                )
                select id, correlation_id, true as dead_letter from dead_letters
                union all
                select id, correlation_id, false as dead_letter from claimed
                """, params().addValue("limit", limit), (rs, rowNum) -> new Recovery(
                rs.getObject("id", UUID.class), rs.getObject("correlation_id", UUID.class),
                rs.getBoolean("dead_letter")));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    WeComDirectoryEvent load(UUID receiptId) {
        apply();
        String encrypted = jdbc.queryForObject("""
                select payload_ciphertext from wecom_directory_event_receipt
                where tenant_id = :tenantId and id = :id and status = 'PROCESSING'
                """, params().addValue("id", receiptId), String.class);
        if (encrypted == null) throw new IllegalArgumentException("Directory event receipt is unavailable");
        try {
            return objectMapper.readValue(codec.decrypt(encrypted), WeComDirectoryEvent.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Directory event payload cannot be recovered");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void complete(UUID receiptId, String status) {
        if (!List.of("SUCCEEDED", "IGNORED").contains(status)) {
            throw new IllegalArgumentException("Invalid directory event outcome");
        }
        apply();
        jdbc.update("""
                update wecom_directory_event_receipt
                set status = :status, processed_at = now(), payload_ciphertext = '',
                    last_error_code = null, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and status = 'PROCESSING'
                """, params().addValue("id", receiptId).addValue("status", status));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean fail(UUID receiptId, RuntimeException exception) {
        apply();
        String code = boundedError(exception);
        String status = jdbc.queryForObject("""
                update wecom_directory_event_receipt
                set status = case when attempt_count >= 8 then 'DEAD_LETTER' else 'FAILED' end,
                    processed_at = now(), last_error_code = :error, row_version = row_version + 1
                where tenant_id = :tenantId and id = :id and status = 'PROCESSING'
                returning status
                """, params().addValue("id", receiptId).addValue("error", code), String.class);
        return "DEAD_LETTER".equals(status);
    }

    private String json(WeComDirectoryEvent event) {
        try { return objectMapper.writeValueAsString(event); }
        catch (Exception exception) { throw new IllegalStateException("Directory event serialization failed"); }
    }

    private static String boundedError(RuntimeException exception) {
        String value = exception.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private void apply() { databaseContext.apply(properties.tenantId()); }
    private void lockOnboardingIdentities(String currentFingerprint, String previousFingerprint) {
        if (previousFingerprint == null || previousFingerprint.equals(currentFingerprint)) {
            lockOnboardingIdentity(currentFingerprint);
            return;
        }
        if (currentFingerprint.compareTo(previousFingerprint) <= 0) {
            lockOnboardingIdentity(currentFingerprint);
            lockOnboardingIdentity(previousFingerprint);
        } else {
            lockOnboardingIdentity(previousFingerprint);
            lockOnboardingIdentity(currentFingerprint);
        }
    }
    private void lockOnboardingIdentity(String fingerprint) {
        jdbc.queryForObject("""
                select pg_advisory_xact_lock(
                    hashtext(cast(:tenantId as text)),
                    hashtext(:corpId || ':' || :fingerprint))
                """, params().addValue("fingerprint", fingerprint), Object.class);
    }
    private MapSqlParameterSource params() {
        return new MapSqlParameterSource("tenantId", properties.tenantId())
                .addValue("corpId", properties.corpId());
    }

    record Reservation(UUID id, boolean duplicate) { }
    record Recovery(UUID id, UUID correlationId, boolean deadLetter) { }
    private record Existing(UUID id, String payloadHash) { }
}
