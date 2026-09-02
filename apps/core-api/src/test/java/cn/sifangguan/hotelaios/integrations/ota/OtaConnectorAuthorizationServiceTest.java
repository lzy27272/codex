package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OtaConnectorAuthorizationServiceTest {
    private final TenantDatabaseContext databaseContext = mock(TenantDatabaseContext.class);
    private final AccessPolicy accessPolicy = mock(AccessPolicy.class);
    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final OtaHotelScopeRepository hotelScopeRepository = mock(OtaHotelScopeRepository.class);
    private final CtripPilotUnixSocketClient pilotClient = mock(CtripPilotUnixSocketClient.class);
    private final CtripPilotBridgeProperties bridgeProperties = new CtripPilotBridgeProperties();
    private final UUID tenantId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID actorId = UUID.fromString("19000000-0000-0000-0000-000000000001");
    private final UUID orgUnitId = UUID.fromString("12000000-0000-0000-0000-000000000004");
    private final TenantPrincipal principal = new TenantPrincipal(
            tenantId, actorId, "OTA_OPERATION_MANAGER", Set.of("OTA_OPERATION_MANAGER"),
            Set.of("ota-authorization.start"), Set.of(orgUnitId), Set.of(), false, UUID.randomUUID());
    private OtaConnectorAuthorizationService service;

    @BeforeEach
    void setUp() {
        when(accessPolicy.principal()).thenReturn(principal);
        when(hotelScopeRepository.requirePilotHotelOrgUnit(principal)).thenReturn(orgUnitId);
        bridgeProperties.setAuthorizationEnabled(false);
        service = new OtaConnectorAuthorizationService(
                databaseContext, accessPolicy, auditWriter, new ObjectMapper(), hotelScopeRepository,
                pilotClient, new OtaAuthorizationIdempotencyGuard(
                Clock.fixed(Instant.parse("2026-08-29T12:00:00Z"), ZoneOffset.UTC)), bridgeProperties);
    }

    @Test
    void readsBoundAuthorizedStatusOnlyAfterPermissionAndOrgScopeChecks() {
        OffsetDateTime expiresAt = OffsetDateTime.parse("2026-09-28T12:00:00Z");
        when(pilotClient.sessionStatus()).thenReturn(
                new CtripPilotUnixSocketClient.PilotSession("BOUND", "AUTHORIZED", expiresAt));

        OtaAuthorizationModels.StatusResponse response = service.status(
                OtaPilotScope.HOTEL_ID, "ctrip");

        assertThat(response.bindingStatus()).isEqualTo(OtaAuthorizationModels.BindingStatus.BOUND);
        assertThat(response.sessionStatus()).isEqualTo(OtaAuthorizationModels.SessionStatus.AUTHORIZED);
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
        verify(accessPolicy).requirePermission("ota-authorization.start");
        verify(databaseContext).apply(tenantId);
        verify(accessPolicy).requireOrgScope(orgUnitId);
    }

    @Test
    void startsDiscoveryOnlyWhenStableIdentityIsNotBoundAndAuditsNoSecret() {
        UUID challengeId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        String authorizationUrl = OtaPilotScope.AUTHORIZATION_URL_PREFIX
                + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        when(pilotClient.sessionStatus()).thenReturn(
                new CtripPilotUnixSocketClient.PilotSession("DISCOVERY_REQUIRED", "REAUTH_REQUIRED", null));
        when(pilotClient.startDiscovery()).thenReturn(new CtripPilotUnixSocketClient.PilotStart(
                challengeId, "WAITING_FOR_USER", true, authorizationUrl,
                OffsetDateTime.parse("2026-08-29T12:09:59Z"), null));
        OtaAuthorizationModels.StartRequest request = new OtaAuthorizationModels.StartRequest(
                OtaPilotScope.HOTEL_ID, "CTRIP", "DISCOVERY", "首次核验002携程身份", "discovery-002-0001");

        OtaAuthorizationModels.StartResponse response = service.start(request, request.idempotencyKey());

        assertThat(response.authorizationUrl()).isEqualTo(authorizationUrl);
        assertThat(response.bindingStatus()).isEqualTo(OtaAuthorizationModels.BindingStatus.DISCOVERY_REQUIRED);
        verify(accessPolicy).requireOrgScope(orgUnitId);
        verify(auditWriter).record(
                eq("OTA_CONNECTOR_AUTHORIZATION_STARTED"),
                eq("OTA_CONNECTOR_AUTHORIZATION_CHALLENGE"),
                eq(challengeId),
                argThat(json -> json.contains("\"hotelCode\":\"002\"")
                        && !json.contains("authorize#")
                        && !json.contains("discovery-002-0001")
                        && !json.matches(".*[a-f0-9]{64}.*")));
    }

    @Test
    void blocksAuthorizationBeforeBindingWithoutCallingStartEndpoint() {
        bridgeProperties.setAuthorizationEnabled(true);
        when(pilotClient.sessionStatus()).thenReturn(
                new CtripPilotUnixSocketClient.PilotSession("DISCOVERY_REQUIRED", "REAUTH_REQUIRED", null));
        OtaAuthorizationModels.StartRequest request = new OtaAuthorizationModels.StartRequest(
                OtaPilotScope.HOTEL_ID, "CTRIP", "AUTHORIZE", "重新授权", "authorize-002-0001");

        assertThatThrownBy(() -> service.start(request, request.idempotencyKey()))
                .isInstanceOfSatisfying(OtaAuthorizationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("OTA_CTRIP_CLOUD_HOTEL_IDENTITY_DISCOVERY_REQUIRED"));
        verify(pilotClient, never()).startAuthorization();
    }

    @Test
    void blocksAuthorizationWhenBoundButFeatureIsDisabledWithoutPilotIoOrAudit() {
        when(pilotClient.sessionStatus()).thenReturn(
                new CtripPilotUnixSocketClient.PilotSession("BOUND", "REAUTH_REQUIRED", null));
        OtaAuthorizationModels.StartRequest request = new OtaAuthorizationModels.StartRequest(
                OtaPilotScope.HOTEL_ID, "CTRIP", "AUTHORIZE", "重新授权", "authorize-002-disabled");

        assertThatThrownBy(() -> service.start(request, request.idempotencyKey()))
                .isInstanceOfSatisfying(OtaAuthorizationException.class,
                        exception -> {
                            assertThat(exception.status().value()).isEqualTo(409);
                            assertThat(exception.code())
                                    .isEqualTo("OTA_CTRIP_CLOUD_AUTHORIZATION_NOT_ENABLED");
                        });

        verify(pilotClient, never()).sessionStatus();
        verify(pilotClient, never()).startAuthorization();
        verify(pilotClient, never()).startDiscovery();
        verify(auditWriter, never()).record(any(), any(), any(), any());
    }

    @Test
    void replaysTheExactStartResponseAndCallsPilotStartOnlyOnce() {
        UUID challengeId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        String authorizationUrl = OtaPilotScope.AUTHORIZATION_URL_PREFIX
                + "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
        when(pilotClient.sessionStatus()).thenReturn(
                new CtripPilotUnixSocketClient.PilotSession("DISCOVERY_REQUIRED", "REAUTH_REQUIRED", null));
        when(pilotClient.startDiscovery()).thenReturn(new CtripPilotUnixSocketClient.PilotStart(
                challengeId, "WAITING_FOR_USER", true, authorizationUrl,
                OffsetDateTime.parse("2026-08-29T12:09:59Z"), null));
        OtaAuthorizationModels.StartRequest request = new OtaAuthorizationModels.StartRequest(
                OtaPilotScope.HOTEL_ID, "CTRIP", "DISCOVERY", "首次核验002携程身份", "discovery-002-replay");

        OtaAuthorizationModels.StartResponse first = service.start(request, request.idempotencyKey());
        OtaAuthorizationModels.StartResponse replay = service.start(request, request.idempotencyKey());

        assertThat(replay).isSameAs(first);
        verify(pilotClient, times(1)).sessionStatus();
        verify(pilotClient, times(1)).startDiscovery();
        verify(auditWriter, times(1)).record(
                eq("OTA_CONNECTOR_AUTHORIZATION_STARTED"),
                eq("OTA_CONNECTOR_AUTHORIZATION_CHALLENGE"), eq(challengeId), any(String.class));
    }

    @Test
    void rejectsAnyStoreOrPlatformOutsideFrozenPilotScopeBeforeDatabaseLookup() {
        assertThatThrownBy(() -> service.status(UUID.randomUUID(), "CTRIP"))
                .isInstanceOfSatisfying(OtaAuthorizationException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("OTA_AUTHORIZATION_SCOPE_UNSUPPORTED"));
        verify(hotelScopeRepository, never()).requirePilotHotelOrgUnit(principal);
    }
}
