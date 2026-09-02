package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtaHotelScopeRepositoryTest {
    @SuppressWarnings("unchecked")
    @Test
    void resolvesHotelProfileIdToItsRealOrganizationScope() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        UUID expectedOrgUnit = UUID.fromString("12000000-0000-0000-0000-000000000004");
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(invocation -> {
                    RowMapper<Object> mapper = invocation.getArgument(2);
                    ResultSet resultSet = mock(ResultSet.class);
                    when(resultSet.getObject("org_unit_id", UUID.class)).thenReturn(expectedOrgUnit);
                    when(resultSet.getString("property_code")).thenReturn("002");
                    return List.of(mapper.mapRow(resultSet, 0));
                });
        TenantPrincipal principal = new TenantPrincipal(
                UUID.randomUUID(), UUID.randomUUID(), "OTA_OPERATION_MANAGER",
                Set.of(expectedOrgUnit), UUID.randomUUID());

        UUID actual = new OtaHotelScopeRepository(jdbc).requirePilotHotelOrgUnit(principal);

        assertThat(actual).isEqualTo(expectedOrgUnit);
    }
}
