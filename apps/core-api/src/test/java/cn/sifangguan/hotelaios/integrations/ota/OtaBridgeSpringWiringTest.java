package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OtaBridgeSpringWiringTest {

    @Test
    void createsEveryOtaBridgeSpringComponentWithConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(NamedParameterJdbcTemplate.class,
                    () -> mock(NamedParameterJdbcTemplate.class));
            context.registerBean(TenantDatabaseContext.class,
                    () -> mock(TenantDatabaseContext.class));
            context.registerBean(AccessPolicy.class, () -> mock(AccessPolicy.class));
            context.registerBean(AuditWriter.class, () -> mock(AuditWriter.class));
            context.register(
                    CtripPilotBridgeConfiguration.class,
                    CtripPilotUnixSocketClient.class,
                    OtaAuthorizationIdempotencyGuard.class,
                    OtaHotelScopeRepository.class,
                    OtaConnectorAuthorizationService.class,
                    OtaConnectorAuthorizationController.class,
                    OtaAuthorizationExceptionHandler.class
            );

            context.refresh();

            assertThat(context.getBean(CtripPilotBridgeProperties.class)).isNotNull();
            assertThat(context.getBean(CtripPilotUnixSocketClient.class)).isNotNull();
            assertThat(context.getBean(OtaAuthorizationIdempotencyGuard.class)).isNotNull();
            assertThat(context.getBean(OtaHotelScopeRepository.class)).isNotNull();
            assertThat(context.getBean(OtaConnectorAuthorizationService.class)).isNotNull();
            assertThat(context.getBean(OtaConnectorAuthorizationController.class)).isNotNull();
            assertThat(context.getBean(OtaAuthorizationExceptionHandler.class)).isNotNull();
        }
    }

    @Test
    void selectsExactlyOnePublicProductionConstructorForMultiConstructorComponents() {
        assertPublicAutowiredConstructor(CtripPilotUnixSocketClient.class, 2);
        assertPublicAutowiredConstructor(OtaAuthorizationIdempotencyGuard.class, 0);
    }

    private static void assertPublicAutowiredConstructor(Class<?> type, int parameterCount) {
        Constructor<?>[] autowired = Arrays.stream(type.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toArray(Constructor<?>[]::new);

        assertThat(autowired).singleElement().satisfies(constructor -> {
            assertThat(Modifier.isPublic(constructor.getModifiers())).isTrue();
            assertThat(constructor.getParameterCount()).isEqualTo(parameterCount);
        });
    }
}
