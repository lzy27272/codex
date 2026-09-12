package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class EmployeeInvitationModels {
    private EmployeeInvitationModels() { }

    public record Capabilities(boolean canCreate, boolean canApprove) { }

    public record RequestRow(
            UUID id, String displayName, String mobile, String loginName, String employeeNo,
            String note, String status, String requestedByName, OffsetDateTime createdAt,
            String reviewedByName, OffsetDateTime reviewedAt, String decisionReason,
            UUID targetAccountId, UUID targetEmployeeId, long rowVersion
    ) { }

    public record AccountOption(
            UUID id, String loginName, String displayName, UUID employeeId,
            String employeeName, boolean platformAdmin
    ) { }

    public record OrgUnitOption(UUID id, String name, String unitType) { }
    public record PositionOption(UUID id, String name) { }
    public record ManagerOption(UUID assignmentId, String employeeName, String positionName) { }

    public record Dashboard(
            Capabilities capabilities, List<RequestRow> items, List<AccountOption> accounts,
            List<OrgUnitOption> orgUnits, List<PositionOption> positions,
            List<ManagerOption> managers
    ) { }

    public record CreateRequest(
            @NotBlank @Size(max = 120) String displayName,
            @Size(max = 32) String mobile,
            @NotBlank @Size(max = 120) String loginName,
            @NotBlank @Size(max = 64) String employeeNo,
            @Size(max = 500) String note
    ) { }

    public record AssignmentSelection(
            @NotNull UUID orgUnitId,
            @NotNull UUID positionId,
            UUID managerAssignmentId,
            boolean primary,
            @Size(max = 24) String assignmentType
    ) { }

    public record ApproveRequest(
            long expectedVersion,
            UUID existingAccountId,
            @NotEmpty @Size(max = 8) List<@Valid AssignmentSelection> assignments,
            @Size(max = 500) String reason
    ) { }

    public record RejectRequest(long expectedVersion, @NotBlank @Size(max = 500) String reason) { }

    public record ApprovalResponse(
            UUID invitationId, UUID accountId, UUID employeeId, String status,
            UUID bindingRequestId, URI enrollmentUrl, OffsetDateTime expiresAt,
            String message
    ) { }
}
