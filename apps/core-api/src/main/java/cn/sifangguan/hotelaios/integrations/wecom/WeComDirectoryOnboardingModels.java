package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WeComDirectoryOnboardingModels {
    private WeComDirectoryOnboardingModels() { }

    public record InvitationStartRequest(
            @NotBlank @Size(max = 512) String invitationToken
    ) {
        @Override public String toString() { return "InvitationStartRequest[redacted]"; }
    }

    public record AuthorizationResponse(URI authorizationUri) { }

    public record ExchangeRequest(
            @NotBlank @Size(max = 512) String exchangeCode
    ) {
        @Override public String toString() { return "ExchangeRequest[redacted]"; }
    }

    public record ExchangeResponse(
            String sessionToken,
            OffsetDateTime expiresAt,
            String status
    ) {
        @Override public String toString() {
            return "ExchangeResponse[sessionToken=redacted, expiresAt=" + expiresAt + ", status=" + status + "]";
        }
    }

    public record SessionRequest(
            @NotBlank @Size(max = 512) String sessionToken
    ) {
        @Override public String toString() { return "SessionRequest[redacted]"; }
    }

    public record SubmitRequest(
            @NotBlank @Size(max = 512) String sessionToken,
            @Size(max = 120) String displayName,
            @Size(max = 120) String loginName,
            @Size(max = 128) String password,
            @Size(max = 128) String passwordConfirmation,
            @NotNull UUID orgUnitId,
            @NotNull UUID positionId,
            long expectedVersion
    ) {
        @Override public String toString() {
            return "SubmitRequest[sessionToken=redacted, displayName=" + displayName
                    + ", loginName=redacted, password=redacted, passwordConfirmation=redacted, orgUnitId=" + orgUnitId
                    + ", positionId=" + positionId + ", expectedVersion=" + expectedVersion + "]";
        }
    }

    public record OnboardingContext(
            UUID candidateId,
            String status,
            String displayName,
            String loginName,
            boolean requiresAccountRegistration,
            List<HotelOption> hotels,
            long rowVersion
    ) { }

    public record HotelOption(UUID id, String name, List<DepartmentOption> departments) { }
    public record DepartmentOption(UUID id, String name, List<PositionOption> positions) { }
    public record PositionOption(UUID id, String name) { }

    public record SubmitResponse(
            UUID candidateId,
            String status,
            long rowVersion,
            String message
    ) { }

    public record CandidateRow(
            UUID id,
            String maskedFingerprint,
            String displayName,
            String requestedLoginName,
            String onboardingKind,
            UUID requestedOrgUnitId,
            String requestedHotelName,
            String requestedDepartmentName,
            UUID requestedPositionId,
            String requestedPositionName,
            String status,
            String failureCode,
            OffsetDateTime invitationExpiresAt,
            boolean expiringSoon,
            boolean retryable,
            boolean canRegenerate,
            String suggestedAction,
            OffsetDateTime updatedAt,
            long rowVersion
    ) { }

    public record CandidateList(List<CandidateRow> items) { }

    public record DecisionRequest(
            long expectedVersion,
            @Size(max = 500) String reason,
            boolean transferExistingBinding
    ) {
        public DecisionRequest(long expectedVersion, String reason) {
            this(expectedVersion, reason, false);
        }
    }

    public record ApprovalResponse(
            UUID candidateId,
            UUID accountId,
            String status,
            String bindingStatus,
            long rowVersion,
            String message
    ) { }

    public record RetryRequest(
            long expectedVersion,
            @Size(max = 500) String reason
    ) { }

    public record InvitationActionResponse(
            UUID candidateId,
            String status,
            OffsetDateTime invitationExpiresAt,
            long rowVersion,
            String message
    ) { }

    public record DirectoryEventRetryResponse(
            UUID receiptId,
            String status,
            long rowVersion,
            String message
    ) { }

    public record DirectoryEventRow(
            UUID id,
            String changeType,
            String status,
            String lastErrorCode,
            String errorMessage,
            OffsetDateTime receivedAt,
            long rowVersion,
            String suggestedAction
    ) { }

    public record DirectoryEventList(List<DirectoryEventRow> items) { }
}
