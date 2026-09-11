package cn.sifangguan.hotelaios.shared.features;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Tenant-allowlisted release gate for the frozen group-management capabilities. */
@Component
public final class GroupManagementFeatureGate {
    private final boolean workPlansEnabled;
    private final boolean executiveTasksEnabled;
    private final boolean reminderWorkerEnabled;
    private final String configuredTenantIds;
    private Set<UUID> tenantIds = Set.of();

    public GroupManagementFeatureGate(
            @Value("${app.group-management.work-plans-enabled:false}") boolean workPlansEnabled,
            @Value("${app.group-management.executive-tasks-enabled:false}") boolean executiveTasksEnabled,
            @Value("${app.group-management.reminder-worker-enabled:false}") boolean reminderWorkerEnabled,
            @Value("${app.group-management.tenant-ids:}") String configuredTenantIds
    ) {
        this.workPlansEnabled = workPlansEnabled;
        this.executiveTasksEnabled = executiveTasksEnabled;
        this.reminderWorkerEnabled = reminderWorkerEnabled;
        this.configuredTenantIds = configuredTenantIds;
    }

    @PostConstruct
    public void validate() {
        if (!(workPlansEnabled || executiveTasksEnabled || reminderWorkerEnabled)) {
            tenantIds = Set.of();
            return;
        }
        if (configuredTenantIds == null || configuredTenantIds.isBlank()) {
            throw new IllegalStateException(
                    "GROUP_MANAGEMENT_TENANT_IDS is required when a group-management feature is enabled");
        }
        LinkedHashSet<UUID> parsed = new LinkedHashSet<>();
        try {
            Arrays.stream(configuredTenantIds.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(UUID::fromString)
                    .forEach(parsed::add);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("GROUP_MANAGEMENT_TENANT_IDS contains an invalid UUID", exception);
        }
        if (parsed.isEmpty()) {
            throw new IllegalStateException(
                    "GROUP_MANAGEMENT_TENANT_IDS must contain at least one tenant UUID");
        }
        tenantIds = Set.copyOf(parsed);
    }

    public boolean workPlansEnabled(UUID tenantId) {
        return workPlansEnabled && tenantIds.contains(tenantId);
    }

    public boolean executiveTasksEnabled(UUID tenantId) {
        return executiveTasksEnabled && tenantIds.contains(tenantId);
    }

    public boolean reminderWorkerEnabled(UUID tenantId) {
        return reminderWorkerEnabled && tenantIds.contains(tenantId);
    }
}
