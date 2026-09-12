package cn.sifangguan.hotelaios.integrations.wecom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WeComDirectoryFeatureFlagTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    WeComDirectoryCallbackController.class,
                    WeComDirectoryOnboardingController.class
            )
            .withBean(WeComDirectoryCallbackService.class,
                    () -> mock(WeComDirectoryCallbackService.class))
            .withBean(WeComDirectoryOnboardingService.class,
                    () -> mock(WeComDirectoryOnboardingService.class))
            .withBean(WeComDirectoryOnboardingAdministrationService.class,
                    () -> mock(WeComDirectoryOnboardingAdministrationService.class))
            .withBean(WeComDirectoryProperties.class,
                    () -> mock(WeComDirectoryProperties.class));

    @Test
    void directoryEndpointsAreAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WeComDirectoryCallbackController.class);
            assertThat(context).doesNotHaveBean(WeComDirectoryOnboardingController.class);
        });
    }

    @Test
    void enablingWeComEnablesManualOnboardingButNotDirectoryCallbacks() {
        contextRunner.withPropertyValues("app.wecom.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(WeComDirectoryCallbackController.class);
                    assertThat(context).hasSingleBean(WeComDirectoryOnboardingController.class);
                });
    }

    @Test
    void directorySyncDoesNotDependOnBotOrGroupDeliveryFlags() {
        contextRunner.withPropertyValues(
                "app.wecom.enabled=true",
                "app.wecom.directory-sync-enabled=true",
                "app.wecom.bot.actions-enabled=false",
                "app.wecom.group-robot.delivery-enabled=false"
        ).run(context -> {
            assertThat(context).hasSingleBean(WeComDirectoryCallbackController.class);
            assertThat(context).hasSingleBean(WeComDirectoryOnboardingController.class);
        });
    }
}
