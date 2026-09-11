package cn.sifangguan.hotelaios.tasks;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ExecutiveTaskModels {
    private ExecutiveTaskModels() {
    }

    public static final class CreateExecutiveTask {
        private final UUID clientCommandId;
        private final UUID targetAssignmentId;
        private final String title;
        private final String description;
        private final String priority;
        private final OffsetDateTime dueAt;
        private final List<OffsetDateTime> reminderTimes;
        private final Map<String, Object> unknownFields = new LinkedHashMap<>();

        @JsonCreator
        public CreateExecutiveTask(
                @JsonProperty("clientCommandId") @NotNull UUID clientCommandId,
                @JsonProperty("targetAssignmentId") @NotNull UUID targetAssignmentId,
                @JsonProperty("title") @NotBlank @Size(max = 240) String title,
                @JsonProperty("description") @NotBlank String description,
                @JsonProperty("priority") String priority,
                @JsonProperty("dueAt") @NotNull OffsetDateTime dueAt,
                @JsonProperty("reminderTimes") @Size(max = 8) List<@NotNull OffsetDateTime> reminderTimes
        ) {
            this.clientCommandId = clientCommandId;
            this.targetAssignmentId = targetAssignmentId;
            this.title = title;
            this.description = description;
            this.priority = priority;
            this.dueAt = dueAt;
            this.reminderTimes = reminderTimes == null ? List.of() : List.copyOf(reminderTimes);
        }

        @JsonAnySetter
        public void rejectableUnknownField(String name, Object value) {
            unknownFields.put(name, value);
        }

        public UUID clientCommandId() { return clientCommandId; }
        public UUID targetAssignmentId() { return targetAssignmentId; }
        public String title() { return title; }
        public String description() { return description; }
        public String priority() { return priority; }
        public OffsetDateTime dueAt() { return dueAt; }
        public List<OffsetDateTime> reminderTimes() { return reminderTimes; }
        public Map<String, Object> unknownFields() { return Map.copyOf(unknownFields); }
    }

    public static final class ApproveExecutiveTask {
        private final long expectedVersion;
        private final String comment;
        private final Map<String, Object> unknownFields = new LinkedHashMap<>();

        @JsonCreator
        public ApproveExecutiveTask(
                @JsonProperty("expectedVersion") @PositiveOrZero long expectedVersion,
                @JsonProperty("comment") String comment
        ) {
            this.expectedVersion = expectedVersion;
            this.comment = comment;
        }

        @JsonAnySetter
        public void rejectableUnknownField(String name, Object value) { unknownFields.put(name, value); }
        public long expectedVersion() { return expectedVersion; }
        public String comment() { return comment; }
        public Map<String, Object> unknownFields() { return Map.copyOf(unknownFields); }
    }

    public static final class ReworkExecutiveTask {
        private final long expectedVersion;
        private final String reason;
        private final Map<String, Object> unknownFields = new LinkedHashMap<>();

        @JsonCreator
        public ReworkExecutiveTask(
                @JsonProperty("expectedVersion") @PositiveOrZero long expectedVersion,
                @JsonProperty("reason") @NotBlank String reason
        ) {
            this.expectedVersion = expectedVersion;
            this.reason = reason;
        }

        @JsonAnySetter
        public void rejectableUnknownField(String name, Object value) { unknownFields.put(name, value); }
        public long expectedVersion() { return expectedVersion; }
        public String reason() { return reason; }
        public Map<String, Object> unknownFields() { return Map.copyOf(unknownFields); }
    }

    public record ExecutiveTaskTarget(
            UUID assignmentId,
            String employeeName,
            String positionCode,
            String positionName,
            UUID organizationId,
            String organizationName
    ) {
    }

    public record ExecutiveTaskSummary(
            UUID id,
            String taskNo,
            String title,
            String lifecycleStatus,
            String slaStatus,
            String priority,
            OffsetDateTime dueAt,
            long rowVersion,
            String creationSource,
            UUID orgUnitId,
            String orgUnitName,
            UUID assigneeAssignmentId,
            String assigneeName,
            String assigneePositionName,
            UUID reviewerAssignmentId,
            String reviewerName,
            int progress
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExecutiveTaskDetail(
            UUID id,
            String taskNo,
            String title,
            String lifecycleStatus,
            String slaStatus,
            String priority,
            OffsetDateTime dueAt,
            long rowVersion,
            String creationSource,
            UUID orgUnitId,
            String orgUnitName,
            UUID assigneeAssignmentId,
            String assigneeName,
            String assigneePositionName,
            UUID reviewerAssignmentId,
            String reviewerName,
            int progress,
            String description,
            String resultSummary,
            Integer evidenceCount,
            List<OffsetDateTime> reminderTimes
    ) {
        public ExecutiveTaskSummary summary() {
            return new ExecutiveTaskSummary(id, taskNo, title, lifecycleStatus, slaStatus, priority, dueAt,
                    rowVersion, creationSource, orgUnitId, orgUnitName, assigneeAssignmentId, assigneeName,
                    assigneePositionName, reviewerAssignmentId, reviewerName, progress);
        }
    }

    static List<OffsetDateTime> sortedDistinct(List<OffsetDateTime> values) {
        if (values == null || values.isEmpty()) return List.of();
        return new ArrayList<>(values.stream().distinct().sorted().toList());
    }
}
