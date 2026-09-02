package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class WeComDirectoryOnboardingServiceTest {
    @Test
    void invitationLinkUsesTheFrontEndTokenContract() {
        URI link = WeComDirectoryOnboardingService.invitationUri(
                URI.create("https://www.sfgzt.cn/"), "safe-once-token");

        assertThat(link.toString())
                .isEqualTo("https://www.sfgzt.cn/#/wecom-onboarding?token=safe-once-token")
                .doesNotContain("invitation_token");
    }
}
