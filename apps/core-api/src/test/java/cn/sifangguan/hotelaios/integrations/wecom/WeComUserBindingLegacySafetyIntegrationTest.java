package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.DecisionRequest;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.OperationResult;
import static cn.sifangguan.hotelaios.integrations.wecom.WeComUserBindingModels.VersionedReasonRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeComUserBindingLegacySafetyIntegrationTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CEO = UUID.fromString("19000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT = UUID.fromString("19000000-0000-0000-0000-000000000003");
    private static final UUID ASSIGNMENT = UUID.fromString("19200000-0000-0000-0000-000000000002");
    private static final UUID FRONT_DEPARTMENT = UUID.fromString("12000000-0000-0000-0000-000000000005");
    private static final UUID FRONT_POSITION = UUID.fromString("14000000-0000-0000-0000-000000000001");
    private static final String CORP_ID = "ww-legacy-binding-safety";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    static void migrateDatabase() {
        Flyway.configure().dataSource(DATA_SOURCE).locations("classpath:db/migration")
                .cleanDisabled(true).load().migrate();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(DATA_SOURCE);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE));
        jdbc.update("delete from notification where idempotency_key like 'wecom-binding:%'");
        jdbc.update("delete from wecom_person_onboarding where tenant_id = ? and corp_id = ?", TENANT, CORP_ID);
        jdbc.update("delete from wecom_user_binding_request where tenant_id = ? and account_id = ?", TENANT, ACCOUNT);
        jdbc.update("delete from wecom_user_binding where tenant_id = ? and corp_id = ?", TENANT, CORP_ID);
        jdbc.update("delete from wecom_directory_event_receipt where tenant_id = ? and corp_id = ?", TENANT, CORP_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DIRECTORY_MEMBER_DISABLED",
            "DIRECTORY_MEMBER_DELETED",
            "DIRECTORY_IDENTITY_REUSED"
    })
    void directorySafetySuspensionsCannotBeDirectlyResumed(String reason) {
        String userId = "resume-blocked-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        insertBinding(userId, fingerprint, "SUSPENDED", reason);
        insertReceipt("update_user", 50, fingerprint, null, "SUCCEEDED");

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "manual override"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须重新发起绑定并审核");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void pendingLatestDirectoryFactBlocksResume() {
        String userId = "resume-pending-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW");
        insertReceipt("update_user", 90, fingerprint, null, "PROCESSING");

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "reviewed"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未成功处理");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void missingDirectoryWatermarkBlocksResume() {
        String userId = "resume-no-watermark-" + UUID.randomUUID();
        insertBinding(userId, fingerprint(userId), "SUSPENDED", "MANUAL_REVIEW");

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "reviewed"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚无可验证的企业微信通讯录状态");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void latestDeleteFactBlocksResume() {
        String userId = "resume-deleted-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW");
        insertReceipt("delete_user", 90, fingerprint, null, "SUCCEEDED");

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "reviewed"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前不是有效状态");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void latestRenameAwayFactBlocksResume() {
        String userId = "resume-renamed-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW");
        insertReceipt("update_user", 50, fingerprint("replacement-" + UUID.randomUUID()),
                fingerprint, "SUCCEEDED");

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "reviewed"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前不是有效状态");

        assertBinding("SUSPENDED", 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"deleted", "renamed"})
    void reusedIdentityWithOpenConflictCandidateCannotResumeOldManualBinding(String departureKind) {
        String userId = "reused-open-conflict-" + departureKind + "-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        OffsetDateTime establishedAt = OffsetDateTime.now().minusMinutes(10);
        UUID identityReceipt = insertReceipt("create_user", 60, fingerprint, null,
                "SUCCEEDED", establishedAt);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW", identityReceipt);
        OffsetDateTime departureAt = establishedAt.plusMinutes(2);
        if ("deleted".equals(departureKind)) {
            insertReceipt("delete_user", 90, fingerprint, null, "SUCCEEDED", departureAt);
        } else {
            insertReceipt("update_user", 50, fingerprint("renamed-" + UUID.randomUUID()),
                    fingerprint, "SUCCEEDED", departureAt);
        }
        UUID reusedCreate = insertReceipt("create_user", 60, fingerprint, null,
                "SUCCEEDED", departureAt.plusMinutes(2));
        insertOpenConflictCandidate(fingerprint, reusedCreate);

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "manual override"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待处理的人员入职或冲突申请");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void postWatermarkDepartureBlocksResumeEvenWhenLatestFactIsAnActiveReuse() {
        String userId = "reused-after-watermark-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        OffsetDateTime establishedAt = OffsetDateTime.now().minusMinutes(10);
        UUID identityReceipt = insertReceipt("create_user", 60, fingerprint, null,
                "SUCCEEDED", establishedAt);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW", identityReceipt);
        insertReceipt("delete_user", 90, fingerprint, null, "SUCCEEDED",
                establishedAt.plusMinutes(2));
        insertReceipt("create_user", 60, fingerprint, null, "SUCCEEDED",
                establishedAt.plusMinutes(4));

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "manual override"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前绑定建立后发生过");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void legacyBindingWithoutIdentityWatermarkCannotResumeAfterAnyDeparture() {
        String userId = "legacy-departed-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        OffsetDateTime departureAt = OffsetDateTime.now().minusMinutes(2);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW");
        insertReceipt("delete_user", 90, fingerprint, null, "SUCCEEDED", departureAt);
        insertReceipt("create_user", 60, fingerprint, null, "SUCCEEDED", departureAt.plusMinutes(1));

        assertThatThrownBy(() -> inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "manual override"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前绑定建立后发生过");

        assertBinding("SUSPENDED", 0);
    }

    @Test
    void processedActiveDirectoryFactStillAllowsOrdinaryManualResume() {
        String userId = "resume-active-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        insertBinding(userId, fingerprint, "SUSPENDED", "MANUAL_REVIEW");
        insertReceipt("update_user", 50, fingerprint, null, "SUCCEEDED");

        OperationResult result = inTransaction(() -> directoryService().resume(
                ACCOUNT, new VersionedReasonRequest(0, "reviewed")));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertBinding("ACTIVE", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "deleted", "renamed"})
    void directoryModeNeverLetsLegacyApprovalCreateAnActiveBinding(String directoryCase) {
        String userId = "legacy-approve-" + directoryCase + "-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        UUID requestId = insertPendingRequest(userId, fingerprint);
        switch (directoryCase) {
            case "disabled" -> insertReceipt("update_user", 90, fingerprint, null, "PROCESSING");
            case "deleted" -> insertReceipt("delete_user", 90, fingerprint, null, "SUCCEEDED");
            case "renamed" -> insertReceipt("update_user", 50,
                    fingerprint("replacement-" + UUID.randomUUID()), fingerprint, "SUCCEEDED");
            default -> throw new IllegalArgumentException("unknown case");
        }

        assertThatThrownBy(() -> inTransaction(() -> directoryService().approve(
                requestId, new DecisionRequest(0, false, "approve"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("人员自动入职审核");

        assertThat(jdbc.queryForObject("select status from wecom_user_binding_request where id = ?",
                String.class, requestId)).isEqualTo("PENDING_APPROVAL");
        assertThat(jdbc.queryForObject("select count(*) from wecom_user_binding where tenant_id = ? and corp_id = ?",
                Integer.class, TENANT, CORP_ID)).isZero();
    }

    @Test
    void legacyApprovalRemainsAvailableWhenDirectorySyncIsDisabled() {
        String userId = "legacy-approved-" + UUID.randomUUID();
        String fingerprint = fingerprint(userId);
        UUID requestId = insertPendingRequest(userId, fingerprint);

        OperationResult result = inTransaction(() -> service(false).approve(
                requestId, new DecisionRequest(0, false, "approve")));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertBinding("ACTIVE", 0);
    }

    @Test
    void legacyResumeRemainsAvailableWithoutDirectoryWatermarkWhenSyncIsDisabled() {
        String userId = "legacy-resume-" + UUID.randomUUID();
        insertBinding(userId, fingerprint(userId), "SUSPENDED", "MANUAL_REVIEW");

        OperationResult result = inTransaction(() -> service(false).resume(
                ACCOUNT, new VersionedReasonRequest(0, "reviewed")));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertBinding("ACTIVE", 1);
    }

    private WeComUserBindingAdministrationService directoryService() {
        return service(true);
    }

    private WeComUserBindingAdministrationService service(boolean directorySyncEnabled) {
        TenantPrincipal principal = new TenantPrincipal(
                TENANT, CEO, "CEO", Set.of("CEO"),
                Set.of("wecom-binding.read", "wecom-binding.manage", "wecom-binding.approve"),
                Set.of(), Set.of(), true, UUID.randomUUID());
        AccessPolicy accessPolicy = mock(AccessPolicy.class);
        when(accessPolicy.principal()).thenReturn(principal);
        WeComProperties properties = mock(WeComProperties.class);
        when(properties.tenantId()).thenReturn(TENANT);
        when(properties.corpId()).thenReturn(CORP_ID);
        return new WeComUserBindingAdministrationService(
                new NamedParameterJdbcTemplate(DATA_SOURCE), mock(TenantDatabaseContext.class),
                accessPolicy, mock(AuditWriter.class), new ObjectMapper(), properties,
                directorySyncEnabled);
    }

    private void insertBinding(String userId, String fingerprint, String status, String reason) {
        insertBinding(userId, fingerprint, status, reason, null);
    }

    private void insertBinding(
            String userId, String fingerprint, String status, String reason, UUID identityReceiptId
    ) {
        jdbc.update("""
                insert into wecom_user_binding
                    (id, tenant_id, corp_id, wecom_user_id, user_id_fingerprint, account_id,
                     preferred_assignment_id, status, status_reason, last_verified_at,
                     identity_event_receipt_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), ?)
                """, UUID.randomUUID(), TENANT, CORP_ID, userId, fingerprint, ACCOUNT,
                ASSIGNMENT, status, reason, identityReceiptId);
    }

    private UUID insertPendingRequest(String userId, String fingerprint) {
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                insert into wecom_user_binding_request
                    (id, tenant_id, account_id, preferred_assignment_id, status, token_hash,
                     candidate_wecom_user_id, candidate_fingerprint, expires_at, retain_until, requested_by)
                values (?, ?, ?, ?, 'PENDING_APPROVAL', ?, ?, ?, now() + interval '1 hour',
                        now() + interval '180 days', ?)
                """, requestId, TENANT, ACCOUNT, ASSIGNMENT,
                WeComUserBindingAdministrationService.sha256("token:" + requestId),
                userId, fingerprint, CEO);
        return requestId;
    }

    private UUID insertReceipt(
            String changeType, int priority, String fingerprint,
            String previousFingerprint, String status
    ) {
        return insertReceipt(changeType, priority, fingerprint, previousFingerprint,
                status, OffsetDateTime.now());
    }

    private UUID insertReceipt(
            String changeType, int priority, String fingerprint,
            String previousFingerprint, String status, OffsetDateTime occurredAt
    ) {
        UUID receiptId = UUID.randomUUID();
        jdbc.update("""
                insert into wecom_directory_event_receipt
                    (id, tenant_id, corp_id, event_key_hash, payload_hash, payload_ciphertext,
                     event_type, change_type, event_priority, user_id_fingerprint,
                     previous_user_id_fingerprint, occurred_at, status, correlation_id,
                     attempt_count, processed_at)
                values (?, ?, ?, ?, ?, 'ciphertext', 'change_contact', ?, ?, ?, ?, ?, ?, ?, 1,
                        case when ? = 'PROCESSING' then null else now() end)
                """, receiptId, TENANT, CORP_ID,
                WeComUserBindingAdministrationService.sha256("event:" + receiptId),
                WeComUserBindingAdministrationService.sha256("payload:" + receiptId),
                changeType, priority, fingerprint, previousFingerprint, occurredAt, status,
                UUID.randomUUID(), status);
        return receiptId;
    }

    private void insertOpenConflictCandidate(String fingerprint, UUID receiptId) {
        UUID candidateId = UUID.randomUUID();
        jdbc.update("""
                insert into wecom_person_onboarding
                    (id, tenant_id, corp_id, user_id_fingerprint, display_name,
                     onboarding_kind, directory_status, status,
                     requested_org_unit_id, requested_position_id,
                     identity_verified_at, profile_submitted_at, conflicting_account_id,
                     source_event_hash, last_event_at, last_event_receipt_id)
                values (?, ?, ?, ?, 'Reused identity candidate', 'NEW_MEMBER', 'ACTIVE', 'CONFLICT',
                        ?, ?, now(), now(), ?, ?, now(), ?)
                """, candidateId, TENANT, CORP_ID, fingerprint, FRONT_DEPARTMENT, FRONT_POSITION,
                ACCOUNT, WeComUserBindingAdministrationService.sha256("candidate:" + candidateId),
                receiptId);
    }

    private void assertBinding(String expectedStatus, long expectedVersion) {
        assertThat(jdbc.queryForMap("""
                select status, row_version from wecom_user_binding
                where tenant_id = ? and corp_id = ? and account_id = ?
                """, TENANT, CORP_ID, ACCOUNT))
                .containsEntry("status", expectedStatus)
                .containsEntry("row_version", expectedVersion);
    }

    private String fingerprint(String userId) {
        return WeComUserBindingAdministrationService.sha256(CORP_ID + ":" + userId);
    }

    private <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        return transactions.execute(status -> {
            try {
                return action.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
