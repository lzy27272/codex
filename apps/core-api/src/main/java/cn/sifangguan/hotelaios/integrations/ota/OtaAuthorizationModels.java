package cn.sifangguan.hotelaios.integrations.ota;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class OtaAuthorizationModels {
    private OtaAuthorizationModels() {
    }

    public enum BindingStatus {
        DISCOVERY_REQUIRED,
        BOUND
    }

    public enum SessionStatus {
        REAUTH_REQUIRED,
        AUTHORIZED
    }

    public enum StartAction {
        DISCOVERY,
        AUTHORIZE
    }

    public record StatusResponse(
            UUID hotelId,
            String hotelCode,
            String platformCode,
            BindingStatus bindingStatus,
            SessionStatus sessionStatus,
            OffsetDateTime expiresAt
    ) {
    }

    public record StartRequest(
            @NotNull UUID hotelId,
            @NotBlank @Pattern(regexp = "(?i)CTRIP") String platformCode,
            @NotBlank @Pattern(regexp = "(?i)(DISCOVERY|AUTHORIZE)") String action,
            @NotBlank @Size(max = 200) String reason,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey
    ) {
        public StartAction normalizedAction() {
            return StartAction.valueOf(action.trim().toUpperCase(Locale.ROOT));
        }

        public String normalizedPlatformCode() {
            return platformCode.trim().toUpperCase(Locale.ROOT);
        }
    }

    public record StartResponse(
            UUID challengeId,
            String status,
            boolean authorizationRequired,
            String authorizationUrl,
            OffsetDateTime expiresAt,
            BindingStatus bindingStatus,
            SessionStatus sessionStatus
    ) {
    }

    public record CredentialSaveRequest(
            @NotNull UUID hotelId,
            @NotBlank @Pattern(regexp = "(?i)CTRIP") String platformCode,
            @NotNull @Size(min = 1, max = 256) char[] username,
            @NotNull @Size(min = 1, max = 256) char[] password,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey
    ) {
        public String normalizedPlatformCode() {
            return platformCode.trim().toUpperCase(Locale.ROOT);
        }

        public void clearSecrets() {
            Arrays.fill(username, '\0');
            Arrays.fill(password, '\0');
        }
    }

    public record CredentialLoginRequest(
            @NotNull UUID hotelId,
            @NotBlank @Pattern(regexp = "(?i)CTRIP") String platformCode,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey
    ) {
        public String normalizedPlatformCode() {
            return platformCode.trim().toUpperCase(Locale.ROOT);
        }
    }

    public record CredentialCodeRequest(
            @NotNull UUID hotelId,
            @NotBlank @Pattern(regexp = "(?i)CTRIP") String platformCode,
            @NotNull UUID challengeId,
            @NotNull @Size(min = 4, max = 8) char[] code,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+") String idempotencyKey
    ) {
        public String normalizedPlatformCode() {
            return platformCode.trim().toUpperCase(Locale.ROOT);
        }

        public void clearSecret() {
            Arrays.fill(code, '\0');
        }
    }

    public record CredentialStatusResponse(
            UUID hotelId,
            String hotelCode,
            String platformCode,
            String loginUrl,
            boolean configured,
            OffsetDateTime updatedAt,
            String algorithm,
            SessionStatus sessionStatus,
            OffsetDateTime sessionExpiresAt
    ) {
    }

    public record CredentialChallengeResponse(
            UUID challengeId,
            String status,
            String verificationType,
            OffsetDateTime expiresAt,
            String reasonCode,
            boolean authenticated,
            String fallbackAuthorizationUrl,
            SessionStatus sessionStatus,
            OffsetDateTime sessionExpiresAt
    ) {
    }
}
