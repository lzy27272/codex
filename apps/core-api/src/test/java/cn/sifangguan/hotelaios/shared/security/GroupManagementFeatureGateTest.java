package cn.sifangguan.hotelaios.shared.security;

import cn.sifangguan.hotelaios.shared.features.GroupManagementFeatureGate;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupManagementFeatureGateTest {
    private static final UUID TENANT = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void allFeaturesAreOffByDefault() {
        GroupManagementFeatureGate gate = new GroupManagementFeatureGate(false, false, false, "");
        gate.validate();
        assertFalse(gate.workPlansEnabled(TENANT));
        assertFalse(gate.executiveTasksEnabled(TENANT));
        assertFalse(gate.reminderWorkerEnabled(TENANT));
    }

    @Test
    void enabledFeatureRequiresAValidTenantAllowlist() {
        assertThrows(IllegalStateException.class, () -> newGate(true, false, false, ""));
        assertThrows(IllegalStateException.class, () -> newGate(true, false, false, "not-a-uuid"));
    }

    @Test
    void enabledFeatureIsEffectiveOnlyForAnAllowlistedTenant() {
        GroupManagementFeatureGate gate = newGate(true, true, false, TENANT.toString());
        assertTrue(gate.workPlansEnabled(TENANT));
        assertTrue(gate.executiveTasksEnabled(TENANT));
        assertFalse(gate.workPlansEnabled(UUID.randomUUID()));
        assertFalse(gate.reminderWorkerEnabled(TENANT));
    }

    private GroupManagementFeatureGate newGate(
            boolean workPlans, boolean executiveTasks, boolean reminders, String tenants
    ) {
        GroupManagementFeatureGate gate = new GroupManagementFeatureGate(
                workPlans, executiveTasks, reminders, tenants);
        gate.validate();
        return gate;
    }
}
