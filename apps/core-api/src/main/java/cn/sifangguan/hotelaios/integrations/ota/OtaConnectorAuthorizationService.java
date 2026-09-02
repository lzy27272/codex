package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

import static cn.sifangguan.hotelaios.integrations.ota.OtaAuthorizationModels.BindingStatus.BOUND;
import static cn.sifangguan.hotelaios.integrations.ota.OtaAuthorizationModels.BindingStatus.DISCOVERY_REQUIRED;
import static cn.sifangguan.hotelaios.integrations.ota.OtaAuthorizationModels.SessionStatus.AUTHORIZED;
import static cn.sifangguan.hotelaios.integrations.ota.OtaAuthorizationModels.SessionStatus.REAUTH_REQUIRED;

@Service
public class OtaConnectorAuthorizationService {
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final OtaHotelScopeRepository hotelScopeRepository;
    private final CtripPilotUnixSocketClient pilotClient;
    private final OtaAuthorizationIdempotencyGuard idempotencyGuard;
    private final CtripPilotBridgeProperties bridgeProperties;

    public OtaConnectorAuthorizationService(
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            AuditWriter auditWriter,
            ObjectMapper objectMapper,
            OtaHotelScopeRepository hotelScopeRepository,
            CtripPilotUnixSocketClient pilotClient,
            OtaAuthorizationIdempotencyGuard idempotencyGuard,
            CtripPilotBridgeProperties bridgeProperties
    ) {
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.hotelScopeRepository = hotelScopeRepository;
        this.pilotClient = pilotClient;
        this.idempotencyGuard = idempotencyGuard;
        this.bridgeProperties = bridgeProperties;
    }

    @Transactional(readOnly = true)
    OtaAuthorizationModels.StatusResponse status(UUID hotelId, String platformCode) {
        TenantPrincipal principal = authorize(hotelId, platformCode);
        requireHotel(principal);
        CtripPilotUnixSocketClient.PilotSession session = pilotClient.sessionStatus();
        OtaAuthorizationModels.BindingStatus bindingStatus = "BOUND".equals(session.bindingStatus())
                ? BOUND : DISCOVERY_REQUIRED;
        OtaAuthorizationModels.SessionStatus sessionStatus = "AUTHORIZED".equals(session.status())
                ? AUTHORIZED : REAUTH_REQUIRED;
        return new OtaAuthorizationModels.StatusResponse(
                OtaPilotScope.HOTEL_ID, OtaPilotScope.HOTEL_CODE, OtaPilotScope.PLATFORM_CODE,
                bindingStatus, sessionStatus, sessionStatus == AUTHORIZED ? session.expiresAt() : null);
    }

    @Transactional(readOnly = true)
    OtaAuthorizationModels.CredentialStatusResponse credentialStatus(UUID hotelId, String platformCode) {
        TenantPrincipal principal = authorize(hotelId, platformCode);
        requireHotel(principal);
        return credentialStatusResponse(pilotClient.credentialStatus());
    }

    @Transactional
    OtaAuthorizationModels.CredentialStatusResponse saveCredentials(
            OtaAuthorizationModels.CredentialSaveRequest request,
            String idempotencyKey
    ) {
        requireIdempotency(idempotencyKey, request.idempotencyKey());
        TenantPrincipal principal = authorize(request.hotelId(), request.normalizedPlatformCode());
        requireHotel(principal);
        requireCredentialLoginEnabled();
        validateCredentialCharacters(request.username());
        validateCredentialCharacters(request.password());
        CtripPilotUnixSocketClient.PilotCredentialStatus saved =
                pilotClient.saveCredentials(request.username(), request.password());
        auditWriter.record(
                "OTA_CONNECTOR_CREDENTIAL_CONFIGURED",
                "OTA_CONNECTOR_CREDENTIAL",
                OtaPilotScope.HOTEL_ID,
                safeCredentialAuditJson("CREDENTIAL_SAVED", null));
        return credentialStatusResponse(saved);
    }

    @Transactional
    OtaAuthorizationModels.CredentialChallengeResponse startCredentialLogin(
            OtaAuthorizationModels.CredentialLoginRequest request,
            String idempotencyKey
    ) {
        requireIdempotency(idempotencyKey, request.idempotencyKey());
        TenantPrincipal principal = authorize(request.hotelId(), request.normalizedPlatformCode());
        requireHotel(principal);
        requireCredentialLoginEnabled();
        CtripPilotUnixSocketClient.PilotCredentialChallenge challenge =
                pilotClient.startCredentialLogin();
        auditCredentialAction("CREDENTIAL_LOGIN_STARTED", challenge);
        return credentialChallengeResponse(challenge);
    }

    @Transactional(readOnly = true)
    OtaAuthorizationModels.CredentialChallengeResponse credentialChallenge(
            UUID hotelId,
            String platformCode,
            UUID challengeId
    ) {
        TenantPrincipal principal = authorize(hotelId, platformCode);
        requireHotel(principal);
        requireCredentialLoginEnabled();
        return credentialChallengeResponse(pilotClient.credentialChallenge(challengeId));
    }

    @Transactional
    OtaAuthorizationModels.CredentialChallengeResponse sendCredentialCode(
            OtaAuthorizationModels.CredentialLoginRequest request,
            UUID challengeId,
            String idempotencyKey
    ) {
        requireIdempotency(idempotencyKey, request.idempotencyKey());
        TenantPrincipal principal = authorize(request.hotelId(), request.normalizedPlatformCode());
        requireHotel(principal);
        requireCredentialLoginEnabled();
        CtripPilotUnixSocketClient.PilotCredentialChallenge challenge =
                pilotClient.sendCredentialCode(challengeId);
        auditCredentialAction("VERIFICATION_CODE_REQUESTED", challenge);
        return credentialChallengeResponse(challenge);
    }

    @Transactional
    OtaAuthorizationModels.CredentialChallengeResponse submitCredentialCode(
            OtaAuthorizationModels.CredentialCodeRequest request,
            String idempotencyKey
    ) {
        requireIdempotency(idempotencyKey, request.idempotencyKey());
        TenantPrincipal principal = authorize(request.hotelId(), request.normalizedPlatformCode());
        requireHotel(principal);
        requireCredentialLoginEnabled();
        if (!validVerificationCode(request.code())) {
            throw OtaAuthorizationException.badRequest(
                    "OTA_AUTHORIZATION_VERIFICATION_CODE_INVALID", "验证码格式无效");
        }
        CtripPilotUnixSocketClient.PilotCredentialChallenge challenge =
                pilotClient.submitCredentialCode(request.challengeId(), request.code());
        auditCredentialAction("VERIFICATION_CODE_SUBMITTED", challenge);
        return credentialChallengeResponse(challenge);
    }

    @Transactional
    OtaAuthorizationModels.StartResponse start(
            OtaAuthorizationModels.StartRequest request,
            String idempotencyKey
    ) {
        if (idempotencyKey == null || !idempotencyKey.equals(request.idempotencyKey())) {
            throw OtaAuthorizationException.badRequest(
                    "OTA_AUTHORIZATION_IDEMPOTENCY_HEADER_MISMATCH",
                    "Idempotency-Key请求头必须与请求体完全一致");
        }
        TenantPrincipal principal = authorize(request.hotelId(), request.normalizedPlatformCode());
        requireHotel(principal);
        if (request.normalizedAction() == OtaAuthorizationModels.StartAction.AUTHORIZE
                && !bridgeProperties.isAuthorizationEnabled()) {
            throw OtaAuthorizationException.conflict(
                    "OTA_CTRIP_CLOUD_AUTHORIZATION_NOT_ENABLED",
                    "本期仅开放携程门店身份发现，正式授权尚未启用");
        }
        return idempotencyGuard.execute(principal, request, () -> startOnce(request));
    }

    private OtaAuthorizationModels.StartResponse startOnce(OtaAuthorizationModels.StartRequest request) {
        CtripPilotUnixSocketClient.PilotSession current = pilotClient.sessionStatus();
        boolean bound = "BOUND".equals(current.bindingStatus());
        OtaAuthorizationModels.StartAction action = request.normalizedAction();
        if (action == OtaAuthorizationModels.StartAction.DISCOVERY && bound) {
            throw OtaAuthorizationException.conflict(
                    "OTA_CTRIP_CLOUD_HOTEL_IDENTITY_ALREADY_CONFIGURED",
                    "该门店已完成携程稳定身份绑定，请直接重新授权");
        }
        if (action == OtaAuthorizationModels.StartAction.AUTHORIZE && !bound) {
            throw OtaAuthorizationException.conflict(
                    "OTA_CTRIP_CLOUD_HOTEL_IDENTITY_DISCOVERY_REQUIRED",
                    "该门店尚未完成携程稳定身份绑定，请先执行身份发现");
        }
        CtripPilotUnixSocketClient.PilotStart pilot = action == OtaAuthorizationModels.StartAction.DISCOVERY
                ? pilotClient.startDiscovery() : pilotClient.startAuthorization();
        OtaAuthorizationModels.BindingStatus bindingStatus = bound ? BOUND : DISCOVERY_REQUIRED;
        OtaAuthorizationModels.SessionStatus sessionStatus = REAUTH_REQUIRED;
        if (action == OtaAuthorizationModels.StartAction.AUTHORIZE) {
            if (pilot.sessionStatus() == null) {
                throw OtaAuthorizationException.badGateway(
                        "OTA_AUTHORIZATION_PILOT_RESPONSE_INVALID", "携程云端授权服务返回了无效响应");
            }
            sessionStatus = "AUTHORIZED".equals(pilot.sessionStatus()) ? AUTHORIZED : REAUTH_REQUIRED;
        }
        OtaAuthorizationModels.StartResponse response = new OtaAuthorizationModels.StartResponse(
                pilot.challengeId(), pilot.status(), pilot.authorizationRequired(),
                pilot.authorizationUrl(), pilot.expiresAt(), bindingStatus, sessionStatus);
        auditWriter.record(
                "OTA_CONNECTOR_AUTHORIZATION_STARTED",
                "OTA_CONNECTOR_AUTHORIZATION_CHALLENGE",
                pilot.challengeId(),
                safeAuditJson(action, response));
        return response;
    }

    private TenantPrincipal authorize(UUID hotelId, String platformCode) {
        accessPolicy.requirePermission("ota-authorization.start");
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        if (!OtaPilotScope.HOTEL_ID.equals(hotelId)
                || platformCode == null
                || !OtaPilotScope.PLATFORM_CODE.equals(platformCode.trim().toUpperCase(Locale.ROOT))) {
            throw OtaAuthorizationException.badRequest(
                    "OTA_AUTHORIZATION_SCOPE_UNSUPPORTED", "当前试点仅支持002门店的携程授权");
        }
        return principal;
    }

    private void requireCredentialLoginEnabled() {
        if (!bridgeProperties.isAuthorizationEnabled()) {
            throw OtaAuthorizationException.conflict(
                    "OTA_CTRIP_CLOUD_AUTHORIZATION_NOT_ENABLED", "携程云端自动登录尚未启用");
        }
    }

    private static void requireIdempotency(String header, String body) {
        if (header == null || !header.equals(body)) {
            throw OtaAuthorizationException.badRequest(
                    "OTA_AUTHORIZATION_IDEMPOTENCY_HEADER_MISMATCH",
                    "Idempotency-Key请求头必须与请求体完全一致");
        }
    }

    private static void validateCredentialCharacters(char[] value) {
        for (char character : value) {
            if (character == '\0' || character == '\r' || character == '\n') {
                throw OtaAuthorizationException.badRequest(
                        "OTA_AUTHORIZATION_CREDENTIAL_INVALID", "账号或密码格式无效");
            }
        }
    }

    private static boolean validVerificationCode(char[] value) {
        if (value == null || value.length < 4 || value.length > 8) return false;
        for (char character : value) {
            if (character < '0' || character > '9') return false;
        }
        return true;
    }

    private OtaAuthorizationModels.CredentialStatusResponse credentialStatusResponse(
            CtripPilotUnixSocketClient.PilotCredentialStatus status
    ) {
        CtripPilotUnixSocketClient.PilotSession session = status.session();
        OtaAuthorizationModels.SessionStatus sessionStatus = "AUTHORIZED".equals(session.status())
                ? AUTHORIZED : REAUTH_REQUIRED;
        return new OtaAuthorizationModels.CredentialStatusResponse(
                OtaPilotScope.HOTEL_ID,
                OtaPilotScope.HOTEL_CODE,
                OtaPilotScope.PLATFORM_CODE,
                "https://ebooking.ctrip.com/",
                status.configured(),
                status.updatedAt(),
                status.algorithm(),
                sessionStatus,
                sessionStatus == AUTHORIZED ? session.expiresAt() : null);
    }

    private OtaAuthorizationModels.CredentialChallengeResponse credentialChallengeResponse(
            CtripPilotUnixSocketClient.PilotCredentialChallenge challenge
    ) {
        OtaAuthorizationModels.SessionStatus sessionStatus =
                "AUTHORIZED".equals(challenge.session().status()) ? AUTHORIZED : REAUTH_REQUIRED;
        return new OtaAuthorizationModels.CredentialChallengeResponse(
                challenge.challengeId(),
                challenge.status(),
                challenge.verificationType(),
                challenge.expiresAt(),
                challenge.reasonCode(),
                challenge.authenticated(),
                challenge.fallbackAuthorizationUrl(),
                sessionStatus,
                sessionStatus == AUTHORIZED ? challenge.session().expiresAt() : null);
    }

    private void auditCredentialAction(
            String action,
            CtripPilotUnixSocketClient.PilotCredentialChallenge challenge
    ) {
        auditWriter.record(
                "OTA_CONNECTOR_CREDENTIAL_LOGIN_EVENT",
                "OTA_CONNECTOR_AUTHORIZATION_CHALLENGE",
                challenge.challengeId(),
                safeCredentialAuditJson(action, challenge));
    }

    private String safeCredentialAuditJson(
            String action,
            CtripPilotUnixSocketClient.PilotCredentialChallenge challenge
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("hotelCode", OtaPilotScope.HOTEL_CODE);
        value.put("platformCode", OtaPilotScope.PLATFORM_CODE);
        value.put("action", action);
        if (challenge != null) {
            value.put("status", challenge.status());
            value.put("authenticated", challenge.authenticated());
            if (challenge.verificationType() != null) {
                value.put("verificationType", challenge.verificationType());
            }
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成OTA授权审计记录");
        }
    }

    private void requireHotel(TenantPrincipal principal) {
        UUID orgUnitId = hotelScopeRepository.requirePilotHotelOrgUnit(principal);
        accessPolicy.requireOrgScope(orgUnitId);
    }

    private String safeAuditJson(
            OtaAuthorizationModels.StartAction action,
            OtaAuthorizationModels.StartResponse response
    ) {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("hotelCode", OtaPilotScope.HOTEL_CODE);
        value.put("platformCode", OtaPilotScope.PLATFORM_CODE);
        value.put("action", action.name());
        value.put("status", response.status());
        value.put("authorizationRequired", response.authorizationRequired());
        value.put("bindingStatus", response.bindingStatus().name());
        value.put("sessionStatus", response.sessionStatus().name());
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成OTA授权审计记录");
        }
    }
}
