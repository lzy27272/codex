package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WeComUserBindingModels {
    private WeComUserBindingModels() { }

    public record Counters(long waitingScan, long waitingApproval, long active, long suspended, long abnormal) { }

    public record Capabilities(boolean canRead, boolean canManage, boolean canApprove) { }

    public record AssignmentOption(
            UUID id, UUID orgUnitId, String hotelCode, String hotelName,
            String departmentName, String positionName, boolean primary
    ) { }

    public record PersonRow(
            UUID accountId, UUID employeeId, String hotelCode, String hotelName,
            String departmentName, String employeeName, String loginName, String positionName,
            String bindingStatus, UUID requestId, String requestStatus, OffsetDateTime expiresAt,
            boolean expiringSoon, UUID preferredAssignmentId, String defaultAssignment,
            String userIdFingerprint, OffsetDateTime lastVerifiedAt, String updatedBy,
            OffsetDateTime updatedAt, long rowVersion, String reason, String recommendedAction,
            List<AssignmentOption> assignments
    ) { }

    public record Dashboard(Counters counters, Capabilities capabilities, List<PersonRow> people) { }

    public record InviteRequest(@NotNull UUID accountId, @NotNull UUID preferredAssignmentId) { }

    public record BulkInviteRequest(
            @NotNull @Size(min = 1, max = 50) List<@NotNull InviteRequest> invitations
    ) { }

    public record InviteResponse(
            UUID requestId, URI enrollmentUrl, OffsetDateTime expiresAt, String status
    ) { }

    public record BulkInviteResponse(List<InviteResponse> invitations) { }

    public record BulkSuspendItem(@NotNull UUID accountId, long expectedVersion) { }

    public record BulkSuspendRequest(
            @NotNull @Size(min = 1, max = 50) List<@NotNull BulkSuspendItem> bindings,
            boolean confirmed, @Size(max = 500) String reason
    ) { }

    public record BulkOperationResponse(List<OperationResult> results) { }

    public record DecisionRequest(
            long expectedVersion, boolean transferExistingBinding,
            @Size(max = 500) String reason
    ) { }

    public record VersionedReasonRequest(long expectedVersion, @Size(max = 500) String reason) { }

    public record PreferredAssignmentRequest(
            @NotNull UUID preferredAssignmentId, long expectedVersion,
            @Size(max = 500) String reason
    ) { }

    public record RebindRequest(
            @NotNull UUID preferredAssignmentId, long expectedVersion,
            boolean confirmed, @Size(max = 500) String reason
    ) { }

    public record EnrollmentTokenRequest(@NotBlank @Size(max = 512) String token) { }

    public record EnrollmentPreview(
            String employeeName, String hotelName, String departmentName, String positionName,
            OffsetDateTime expiresAt, String status, boolean expiringSoon,
            boolean canStart, String message
    ) { }

    public record EnrollmentAuthorization(URI authorizationUri) { }

    public record OperationResult(
            UUID accountId, UUID requestId, String status, long rowVersion, String message
    ) { }

    public record AuditEntry(
            String action, UUID actorId, String actorName, OffsetDateTime createdAt, String summary
    ) { }
}
