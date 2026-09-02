package cn.sifangguan.hotelaios.integrations.ota;

import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class OtaHotelScopeRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public OtaHotelScopeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UUID requirePilotHotelOrgUnit(TenantPrincipal principal) {
        List<HotelScope> rows = jdbc.query("""
                select h.org_unit_id, h.property_code
                from hotel_profile h
                join org_unit o
                  on o.tenant_id = h.tenant_id and o.id = h.org_unit_id
                where h.tenant_id = :tenantId
                  and h.id = :hotelId
                  and h.property_code = :hotelCode
                  and o.unit_type = 'HOTEL'
                  and o.status = 'ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", principal.tenantId())
                .addValue("hotelId", OtaPilotScope.HOTEL_ID)
                .addValue("hotelCode", OtaPilotScope.HOTEL_CODE),
                (resultSet, rowNumber) -> new HotelScope(
                        resultSet.getObject("org_unit_id", UUID.class),
                        resultSet.getString("property_code")));
        if (rows.size() != 1 || !OtaPilotScope.HOTEL_CODE.equals(rows.getFirst().hotelCode())) {
            throw OtaAuthorizationException.notFound(
                    "OTA_AUTHORIZATION_HOTEL_NOT_FOUND", "002门店不存在、未启用或不属于当前租户");
        }
        return rows.getFirst().orgUnitId();
    }

    private record HotelScope(UUID orgUnitId, String hotelCode) {
    }
}
