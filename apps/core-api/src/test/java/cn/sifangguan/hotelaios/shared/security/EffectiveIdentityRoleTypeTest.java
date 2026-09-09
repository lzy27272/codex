package cn.sifangguan.hotelaios.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveIdentityRoleTypeTest {

    @Test
    void reservedRoleCodesOnlyRetainTheirMeaningForSystemRoles() {
        for (String reservedCode : new String[]{
                "PLATFORM_ADMIN", "GROUP_ADMIN", "CEO", "GROUP_VICE_PRESIDENT",
                "GENERAL_MANAGER", "ASSISTANT_GENERAL_MANAGER", "OTA_OPERATION_MANAGER",
                "OTA_OPERATION_ASSISTANT", "FRONT_OFFICE_SUPERVISOR",
                "HOUSEKEEPING_SUPERVISOR", "HOUSEKEEPING_ATTENDANT", "FRONT_DESK",
                "HR_KPI_ADMIN"
        }) {
            assertThat(EffectiveIdentityService.identityRoleCode(reservedCode, "SYSTEM"))
                    .isEqualTo(reservedCode);
            assertThat(EffectiveIdentityService.identityRoleCode(reservedCode, "CUSTOM"))
                    .isEqualTo("CUSTOM");
        }
    }

    @Test
    void ordinaryCustomRoleCodesRemainVisible() {
        assertThat(EffectiveIdentityService.identityRoleCode("NIGHT_AUDITOR", "CUSTOM"))
                .isEqualTo("NIGHT_AUDITOR");
    }
}
