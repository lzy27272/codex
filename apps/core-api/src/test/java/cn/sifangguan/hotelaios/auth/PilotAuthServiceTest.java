package cn.sifangguan.hotelaios.auth;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import cn.sifangguan.hotelaios.shared.security.PilotPasswordHasher;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PilotAuthServiceTest {
    private static final UUID TENANT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("19000000-0000-0000-0000-000000000001");

    @Test
    void expiredJdbcTimestampLockDoesNotBreakLogin() {
        Fixture fixture = fixture(Timestamp.from(Instant.now().minusSeconds(60)));

        PilotAuthModels.LoginResponse response = fixture.service.login(
                new PilotAuthModels.LoginRequest(TENANT_ID, "manager", "valid-password"));

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void activeJdbcTimestampLockStillRejectsLogin() {
        Fixture fixture = fixture(Timestamp.from(Instant.now().plusSeconds(60)));

        assertThatThrownBy(() -> fixture.service.login(
                new PilotAuthModels.LoginRequest(TENANT_ID, "manager", "valid-password")))
                .isInstanceOf(IdentityAuthenticationException.class)
                .hasMessage("账号暂时锁定，请15分钟后重试");
    }

    private static Fixture fixture(Timestamp lockedUntil) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        TenantDatabaseContext databaseContext = mock(TenantDatabaseContext.class);
        PilotPasswordHasher passwordHasher = mock(PilotPasswordHasher.class);
        JwtEncoder jwtEncoder = mock(JwtEncoder.class);
        AuditWriter auditWriter = mock(AuditWriter.class);

        Map<String, Object> account = new HashMap<>();
        account.put("id", ACCOUNT_ID);
        account.put("display_name", "Manager");
        account.put("password_hash", "encoded-password");
        account.put("failed_login_attempts", 0);
        account.put("locked_until", lockedUntil);
        when(jdbc.queryForMap(any(String.class), any(MapSqlParameterSource.class))).thenReturn(account);
        when(passwordHasher.matches("valid-password", "encoded-password")).thenReturn(true);

        Instant issuedAt = Instant.now();
        Jwt jwt = new Jwt("signed-token", issuedAt, issuedAt.plusSeconds(3600),
                Map.of("alg", "HS256"), Map.of("sub", ACCOUNT_ID.toString()));
        when(jwtEncoder.encode(any())).thenReturn(jwt);

        return new Fixture(new PilotAuthService(
                jdbc, databaseContext, passwordHasher, jwtEncoder, auditWriter,
                "hotel-ai-os-local-review", "hotel-ai-os-api", 2));
    }

    private record Fixture(PilotAuthService service) {
    }
}
