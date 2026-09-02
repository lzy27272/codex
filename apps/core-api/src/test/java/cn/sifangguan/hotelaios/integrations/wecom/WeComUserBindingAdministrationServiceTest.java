package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeComUserBindingAdministrationServiceTest {
    @Test
    void dashboardReasonOnlyExposesAllowlistedMachineCodes() {
        assertThat(WeComUserBindingAdministrationService.publicReason(
                "ACCOUNT_OR_ASSIGNMENT_INACTIVE", "ignored restricted text"))
                .isEqualTo("ACCOUNT_OR_ASSIGNMENT_INACTIVE");
        assertThat(WeComUserBindingAdministrationService.publicReason(
                "sfglzy-sensitive-user-id", null))
                .isEqualTo("已记录受限审批说明");
        assertThat(WeComUserBindingAdministrationService.publicReason(
                null, "审批人粘贴的完整UserID"))
                .isEqualTo("已记录受限审批说明");
        assertThat(WeComUserBindingAdministrationService.publicReason(null, null)).isNull();
    }
}
