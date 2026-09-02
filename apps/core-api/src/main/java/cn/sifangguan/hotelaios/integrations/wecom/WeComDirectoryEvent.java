package cn.sifangguan.hotelaios.integrations.wecom;

import java.time.OffsetDateTime;

/** Minimal directory payload. toString deliberately excludes provider identifiers. */
public record WeComDirectoryEvent(
        String eventType,
        String changeType,
        String userId,
        String newUserId,
        String displayName,
        String statusCode,
        String departmentSnapshot,
        String positionSnapshot,
        OffsetDateTime occurredAt
) {
    public String effectiveUserId() {
        return newUserId != null && !newUserId.isBlank() ? newUserId : userId;
    }

    public boolean isCreate() { return "create_user".equals(changeType); }
    public boolean isUpdate() { return "update_user".equals(changeType); }
    public boolean isDelete() { return "delete_user".equals(changeType); }
    public boolean isDisabled() {
        return "2".equals(statusCode) || "5".equals(statusCode);
    }

    public boolean hasAssignmentSnapshot() {
        return (departmentSnapshot != null && !departmentSnapshot.isBlank())
                || (positionSnapshot != null && !positionSnapshot.isBlank());
    }

    /** Same-second safety ordering: removal/disable always wins over activation and profile updates. */
    public int priority() {
        if (isDelete() || isDisabled()) return 90;
        if (isCreate()) return 60;
        if ("1".equals(statusCode)) return 50;
        return 40;
    }

    public String assignmentSnapshotMaterial() {
        return (departmentSnapshot == null ? "" : departmentSnapshot.trim()) + "|"
                + (positionSnapshot == null ? "" : positionSnapshot.trim());
    }

    @Override
    public String toString() {
        return "WeComDirectoryEvent[eventType=" + eventType + ", changeType=" + changeType
                + ", occurredAt=" + occurredAt + "]";
    }
}
