package cn.sifangguan.hotelaios.shared.security;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComOnboardingDefaultsV44MigrationIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";

    @Test
    void migrationEnablesReviewedHotelPositionsWithoutOpeningProtectedRolesOrOverrides()
            throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            int migratedToV43 = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("43")
                    .load()
                    .migrate()
                    .migrationsExecuted;
            assertEquals(43, migratedToV43);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', false)");
                assertEquals(0, selectableCount(statement, reviewedCodes()));
                assertEquals(0, selectableCount(statement, protectedCodes()));

                // Preserve a tenant's explicit hotel-level opt-out and prove the
                // group default cannot silently overwrite it.
                statement.executeUpdate("""
                        INSERT INTO position_function_profile
                            (tenant_id, position_id, scope_type, hotel_org_unit_id)
                        SELECT profile.tenant_id, profile.position_id, 'HOTEL',
                               '12000000-0000-0000-0000-000000000003'::uuid
                        FROM position_function_profile profile
                        JOIN position_definition position_item
                          ON position_item.tenant_id = profile.tenant_id
                         AND position_item.id = profile.position_id
                        WHERE profile.tenant_id = '%s'::uuid
                          AND profile.scope_type = 'GROUP'
                          AND position_item.code = 'FRONT_DESK'
                        """.formatted(TENANT));
                statement.executeUpdate("""
                        INSERT INTO position_function_profile_version
                            (tenant_id, profile_id, version_no, lifecycle_status,
                             based_on_group_version_id, authorization_scope_type,
                             wecom_self_selectable, created_by, published_by, published_at)
                        SELECT hotel_profile.tenant_id, hotel_profile.id, 1, 'PUBLISHED',
                               group_version.id, group_version.authorization_scope_type,
                               false, group_version.published_by, group_version.published_by, now()
                        FROM position_function_profile hotel_profile
                        JOIN position_function_profile group_profile
                          ON group_profile.tenant_id = hotel_profile.tenant_id
                         AND group_profile.position_id = hotel_profile.position_id
                         AND group_profile.scope_type = 'GROUP'
                        JOIN position_function_profile_version group_version
                          ON group_version.tenant_id = group_profile.tenant_id
                         AND group_version.profile_id = group_profile.id
                         AND group_version.lifecycle_status = 'PUBLISHED'
                        WHERE hotel_profile.tenant_id = '%s'::uuid
                          AND hotel_profile.scope_type = 'HOTEL'
                        """.formatted(TENANT));
            }

            assertEquals(1, Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("44")
                    .load()
                    .migrate()
                    .migrationsExecuted);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '" + TENANT + "', false)");
                assertEquals(Set.of(
                                "FRONT_DESK", "HOUSEKEEPING_ATTENDANT", "OTA_OPERATION_ASSISTANT",
                                "FRONT_OFFICE_SUPERVISOR", "HOUSEKEEPING_SUPERVISOR",
                                "ASSISTANT_GENERAL_MANAGER", "GENERAL_MANAGER"),
                        selectableCodes(statement, reviewedCodes()));
                assertEquals(7, scalar(statement, """
                        SELECT count(*)
                        FROM position_function_profile_version draft
                        JOIN position_function_profile profile
                          ON profile.tenant_id = draft.tenant_id
                         AND profile.id = draft.profile_id
                         AND profile.scope_type = 'GROUP'
                        JOIN position_definition position_item
                          ON position_item.tenant_id = profile.tenant_id
                         AND position_item.id = profile.position_id
                        WHERE draft.tenant_id = '%s'::uuid
                          AND draft.lifecycle_status = 'DRAFT'
                          AND draft.wecom_self_selectable = true
                          AND position_item.code IN (%s)
                        """.formatted(TENANT, reviewedCodes())));
                assertEquals(0, selectableCount(statement, protectedCodes()));
                assertEquals(1, scalar(statement, """
                        SELECT count(*)
                        FROM position_function_profile_version version
                        JOIN position_function_profile profile
                          ON profile.tenant_id = version.tenant_id
                         AND profile.id = version.profile_id
                        JOIN position_definition position_item
                          ON position_item.tenant_id = profile.tenant_id
                         AND position_item.id = profile.position_id
                        WHERE version.tenant_id = '%s'::uuid
                          AND profile.scope_type = 'HOTEL'
                          AND position_item.code = 'FRONT_DESK'
                          AND version.lifecycle_status = 'PUBLISHED'
                          AND version.wecom_self_selectable = false
                        """.formatted(TENANT)));
                assertEquals(1, scalar(statement, """
                        SELECT count(*) FROM audit_log
                        WHERE tenant_id = '%s'::uuid
                          AND action = 'SYSTEM_WECOM_ONBOARDING_DEFAULTS_ENABLED'
                          AND after_data ->> 'source' = 'V44'
                          AND (after_data ->> 'enabledProfileCount')::integer = 7
                        """.formatted(TENANT)));
                assertEquals(1, scalar(statement, """
                        SELECT CASE WHEN relrowsecurity AND relforcerowsecurity THEN 1 ELSE 0 END
                        FROM pg_class WHERE oid = 'tenant'::regclass
                        """));
            }
        }
    }

    private static String reviewedCodes() {
        return "'FRONT_DESK','HOUSEKEEPING_ATTENDANT','OTA_OPERATION_ASSISTANT',"
                + "'FRONT_OFFICE_SUPERVISOR','HOUSEKEEPING_SUPERVISOR',"
                + "'ASSISTANT_GENERAL_MANAGER','GENERAL_MANAGER'";
    }

    private static String protectedCodes() {
        return "'GROUP_CHAIRMAN','GROUP_GENERAL_MANAGER','GROUP_VICE_PRESIDENT',"
                + "'HR_ADMINISTRATION_SUPERVISOR','HR_ADMINISTRATION','HR_KPI_ADMIN',"
                + "'OTA_OPERATION_MANAGER'";
    }

    private static int selectableCount(Statement statement, String codes) throws Exception {
        return scalar(statement, """
                SELECT count(*)
                FROM position_function_profile_version version
                JOIN position_function_profile profile
                  ON profile.tenant_id = version.tenant_id
                 AND profile.id = version.profile_id
                 AND profile.scope_type = 'GROUP'
                JOIN position_definition position_item
                  ON position_item.tenant_id = profile.tenant_id
                 AND position_item.id = profile.position_id
                WHERE version.tenant_id = '%s'::uuid
                  AND version.lifecycle_status = 'PUBLISHED'
                  AND version.wecom_self_selectable = true
                  AND position_item.code IN (%s)
                """.formatted(TENANT, codes));
    }

    private static Set<String> selectableCodes(Statement statement, String codes) throws Exception {
        Set<String> result = new HashSet<>();
        try (ResultSet rows = statement.executeQuery("""
                SELECT position_item.code
                FROM position_function_profile_version version
                JOIN position_function_profile profile
                  ON profile.tenant_id = version.tenant_id
                 AND profile.id = version.profile_id
                 AND profile.scope_type = 'GROUP'
                JOIN position_definition position_item
                  ON position_item.tenant_id = profile.tenant_id
                 AND position_item.id = profile.position_id
                WHERE version.tenant_id = '%s'::uuid
                  AND version.lifecycle_status = 'PUBLISHED'
                  AND version.wecom_self_selectable = true
                  AND position_item.code IN (%s)
                """.formatted(TENANT, codes))) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }
}
