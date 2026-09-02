package cn.sifangguan.hotelaios.integrations.wecom;

import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.IdentityAuthenticationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeComSessionBindingGuardTest {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private NamedParameterJdbcTemplate jdbc;
    private TenantDatabaseContext databaseContext;
    private WeComSessionBindingGuard guard;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        databaseContext = mock(TenantDatabaseContext.class);
        WeComProperties properties = mock(WeComProperties.class);
        when(properties.tenantId()).thenReturn(tenantId);
        when(properties.corpId()).thenReturn("corp-test");
        guard = new WeComSessionBindingGuard(jdbc, databaseContext, properties);
    }

    @Test
    void returnsTheActiveBindingVersion() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(7L));

        assertThat(guard.currentVersion(tenantId, accountId)).isEqualTo(7L);
        guard.requireActive(tenantId, accountId, 7L);

        verify(databaseContext, org.mockito.Mockito.times(2)).apply(tenantId);
    }

    @Test
    void rejectsPausedOrMissingBinding() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> guard.requireActive(tenantId, accountId, 7L))
                .isInstanceOf(IdentityAuthenticationException.class)
                .hasMessageContaining("暂停、解除或不存在");
    }

    @Test
    void rejectsAChangedBindingVersion() {
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(8L));

        assertThatThrownBy(() -> guard.requireActive(tenantId, accountId, 7L))
                .isInstanceOf(IdentityAuthenticationException.class)
                .hasMessageContaining("已变更");
    }
}
