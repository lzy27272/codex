package cn.sifangguan.hotelaios.integrations.wecom;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WeComDirectoryOnboardingControllerTest {
    @Test
    void oauthFailureRedirectsWithOnlyAnAllowlistedResultCode() throws Exception {
        WeComDirectoryOnboardingService employeeService = mock(WeComDirectoryOnboardingService.class);
        WeComDirectoryOnboardingAdministrationService administrationService =
                mock(WeComDirectoryOnboardingAdministrationService.class);
        WeComDirectoryProperties properties = mock(WeComDirectoryProperties.class);
        when(properties.frontendBaseUrl()).thenReturn(URI.create("https://www.sfgzt.cn"));
        when(employeeService.callback("provider-code", "state", "verifier"))
                .thenThrow(new WeComDirectoryOnboardingService.OAuthCallbackFailure(
                        "OAUTH_IDENTITY_MISMATCH",
                        new IllegalArgumentException("sensitive-full-user-id")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WeComDirectoryOnboardingController(
                employeeService, administrationService, properties)).build();

        mvc.perform(get("/api/v1/integrations/wecom/directory-onboarding/oauth/callback")
                        .param("code", "provider-code").param("state", "state")
                        .cookie(new Cookie(WeComDirectoryOnboardingController.VERIFIER_COOKIE, "verifier")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://www.sfgzt.cn/#/wecom-onboarding?error_code=OAUTH_IDENTITY_MISMATCH"))
                .andExpect(header().string("Location", not(containsString("sensitive-full-user-id"))))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")))
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void unknownRuntimeFailureFallsBackToTheGenericAllowlistedCode() {
        URI result = WeComDirectoryOnboardingService.oauthFailureRedirect(
                URI.create("https://www.sfgzt.cn/"),
                new RuntimeException("never expose pasted UserID"));

        assertThat(result.toString()).isEqualTo(
                "https://www.sfgzt.cn/#/wecom-onboarding?error_code=OAUTH_VERIFICATION_FAILED");
        assertThat(result.toString()).doesNotContain("UserID");
    }
}
