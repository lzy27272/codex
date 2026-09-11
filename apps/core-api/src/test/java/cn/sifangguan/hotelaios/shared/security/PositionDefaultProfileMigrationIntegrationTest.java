package cn.sifangguan.hotelaios.shared.security;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionDefaultProfileMigrationIntegrationTest {

    private static final String DEMO_TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String DEMO_CEO = "19000000-0000-0000-0000-000000000001";
    private static final String V35_RESOURCE =
            "db/migration/V35__publish_untouched_position_default_profiles.sql";
    private static final String MIGRATION_OWNER = "position_migration_owner";

    private static final Set<String> GENERAL_MANAGER_PERMISSIONS = Set.of(
            "org.read",
            "standard.read",
            "dashboard.hotel",
            "work.submit",
            "work-package.read",
            "work-record.read",
            "work-record.submit",
            "work-record.review",
            "task.read",
            "task.create",
            "task.dispatch",
            "task.act",
            "task.review",
            "task.cancel",
            "evaluation.read",
            "evaluation.manual-review",
            "notification.read",
            "daily-report.read",
            "daily-report.submit",
            "daily-report.team-read",
            "daily-report.review",
            "daily-report.revision-review",
            "daily-report-template.read",
            "daily-report-template.store-supplement",
            "daily-operation.read",
            "issue.confirm",
            "issue.assign",
            "issue.close",
            "issue.reopen",
            "task-candidate.read",
            "task-candidate.manage",
            "task-candidate.confirm",
            "task-candidate.reject",
            "task-candidate.retry",
            "rule.read",
            "operation-snapshot.read",
            "operation-snapshot.retry",
            "operation-snapshot.compare",
            "operation-export.create",
            "operation-export.download",
            "ai-recommendation.read",
            "ai-recommendation.feedback",
            "ai-recommendation.adopt",
            "kpi.metric.read",
            "kpi.template.read",
            "kpi.scorecard.read-own",
            "kpi.scorecard.read-team",
            "kpi.scorecard.manual-score",
            "kpi.scorecard.review",
            "kpi.scorecard.dispute"
    );
    private static final Set<String> GENERAL_MANAGER_MODULES = Set.of(
            "workbench", "hotel-dashboard", "team-work", "tasks",
            "daily-reports-my", "daily-operations", "kpi-center", "rules",
            "evaluations", "notifications", "all-functions"
    );
    private static final Set<String> RESTORED_MANAGER_WORKFLOW_PERMISSIONS = Set.of(
            "daily-report-template.store-supplement",
            "task-candidate.read", "task-candidate.manage", "task-candidate.confirm",
            "task-candidate.reject", "task-candidate.retry",
            "operation-snapshot.read", "operation-snapshot.retry", "operation-snapshot.compare",
            "operation-export.create", "operation-export.download",
            "ai-recommendation.read", "ai-recommendation.feedback", "ai-recommendation.adopt"
    );

    @Test
    void v35PublishesOnlyUntouchedV33DraftsAndIsSafeToReapply() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();

            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("34")
                    .load()
                    .migrate();

            prepareCustomPublishedAndHotelOverrideCases(dataSource);

            assertEquals(4, Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("38")
                    .load()
                    .migrate()
                    .migrationsExecuted);

            assertMigrationOutcomes(dataSource);

            // Flyway normally guarantees one application.  Execute the SQL again as
            // a stronger acceptance check that the migration's own guards are
            // idempotent and cannot create V3 drafts or duplicate permissions.
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute(loadMigrationSql());
                connection.commit();
            }

            assertMigrationOutcomes(dataSource);
        }
    }

    @Test
    void v36DiscoversTenantsForTheForcedRlsMigrationOwner() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource ownerDataSource = postgres.getPostgresDatabase();

            Flyway.configure()
                    .dataSource(ownerDataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("34")
                    .load()
                    .migrate();

            deleteGroupProfile(ownerDataSource, "GENERAL_MANAGER");
            try (Connection owner = ownerDataSource.getConnection();
                 Statement statement = owner.createStatement()) {
                statement.execute("CREATE ROLE " + MIGRATION_OWNER
                        + " LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOCREATEDB "
                        + "NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION_OWNER);
                statement.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "
                        + MIGRATION_OWNER);
                statement.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "
                        + MIGRATION_OWNER);
                statement.execute("ALTER TABLE tenant OWNER TO " + MIGRATION_OWNER);
                statement.execute("ALTER TABLE app_role OWNER TO " + MIGRATION_OWNER);
            }

            DataSource migrationDataSource = postgres.getDatabase(MIGRATION_OWNER, "postgres");
            try (Connection migration = migrationDataSource.getConnection();
                 Statement statement = migration.createStatement()) {
                assertEquals(0, scalarInt(statement, "SELECT count(*) FROM tenant"));
            }

            assertEquals(4, Flyway.configure()
                    .dataSource(migrationDataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("38")
                    .load()
                    .migrate()
                    .migrationsExecuted);

            try (Connection owner = ownerDataSource.getConnection();
                 Statement statement = owner.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '" + DEMO_TENANT + "', false)");
                assertEquals("PUBLISHED", versionValue(statement, "GENERAL_MANAGER", 1,
                        "lifecycle_status"));
                assertEquals("DRAFT", versionValue(statement, "GENERAL_MANAGER", 2,
                        "lifecycle_status"));
                assertEquals(GENERAL_MANAGER_PERMISSIONS,
                        permissionCodes(statement, "GENERAL_MANAGER", 1));
                assertEquals(GENERAL_MANAGER_MODULES,
                        moduleIds(statement, "GENERAL_MANAGER", 1));
                assertEquals(1, automaticPublicationAuditCount(
                        statement, "GENERAL_MANAGER", "V36"));
                assertEquals(1, scalarInt(statement, """
                        SELECT CASE WHEN relrowsecurity AND relforcerowsecurity THEN 1 ELSE 0 END
                        FROM pg_class
                        WHERE oid = 'tenant'::regclass
                        """));
            }
        }
    }

    @Test
    void v38RejectsReservedCustomRoleForForcedRlsMigrationOwner() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource ownerDataSource = postgres.getPostgresDatabase();

            Flyway.configure()
                    .dataSource(ownerDataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("37")
                    .load()
                    .migrate();

            try (Connection owner = ownerDataSource.getConnection();
                 Statement statement = owner.createStatement()) {
                statement.executeUpdate("""
                        INSERT INTO app_role (tenant_id, code, name, role_type)
                        VALUES ('%s'::uuid, ' PLATFORM_ADMIN ', 'Reserved custom role', 'CUSTOM')
                        """.formatted(DEMO_TENANT));
                statement.execute("CREATE ROLE " + MIGRATION_OWNER
                        + " LOGIN PASSWORD 'test-only-password' NOSUPERUSER NOCREATEDB "
                        + "NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + MIGRATION_OWNER);
                statement.execute("GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO "
                        + MIGRATION_OWNER);
                statement.execute("GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO "
                        + MIGRATION_OWNER);
                statement.execute("ALTER TABLE app_role OWNER TO " + MIGRATION_OWNER);
            }

            DataSource migrationDataSource = postgres.getDatabase(MIGRATION_OWNER, "postgres");
            try (Connection migration = migrationDataSource.getConnection();
                 Statement statement = migration.createStatement()) {
                assertEquals(0, scalarInt(statement, "SELECT count(*) FROM app_role"),
                        "FORCE RLS must hide tenant rows when the migration owner has no tenant GUC");
            }

            assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(migrationDataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("38")
                    .load()
                    .migrate());

            try (Connection owner = ownerDataSource.getConnection();
                 Statement statement = owner.createStatement()) {
                assertEquals(0, scalarInt(statement, """
                        SELECT count(*) FROM flyway_schema_history
                        WHERE version = '38' AND success
                        """));
            }
        }
    }

    private static void deleteGroupProfile(DataSource dataSource, String positionCode) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + DEMO_TENANT + "', false)");
            statement.executeUpdate("""
                    DELETE FROM position_function_profile_permission item
                    USING position_function_profile_version version,
                          position_function_profile profile,
                          position_definition position_item
                    WHERE item.tenant_id = '%s'::uuid
                      AND version.tenant_id = item.tenant_id
                      AND version.id = item.profile_version_id
                      AND profile.tenant_id = version.tenant_id
                      AND profile.id = version.profile_id
                      AND position_item.tenant_id = profile.tenant_id
                      AND position_item.id = profile.position_id
                      AND position_item.code = '%s'
                    """.formatted(DEMO_TENANT, positionCode));
            statement.executeUpdate("""
                    DELETE FROM position_function_profile_version version
                    USING position_function_profile profile,
                          position_definition position_item
                    WHERE version.tenant_id = '%s'::uuid
                      AND profile.tenant_id = version.tenant_id
                      AND profile.id = version.profile_id
                      AND position_item.tenant_id = profile.tenant_id
                      AND position_item.id = profile.position_id
                      AND position_item.code = '%s'
                    """.formatted(DEMO_TENANT, positionCode));
            assertEquals(1, statement.executeUpdate("""
                    DELETE FROM position_function_profile profile
                    USING position_definition position_item
                    WHERE profile.tenant_id = '%s'::uuid
                      AND position_item.tenant_id = profile.tenant_id
                      AND position_item.id = profile.position_id
                      AND position_item.code = '%s'
                      AND profile.scope_type = 'GROUP'
                    """.formatted(DEMO_TENANT, positionCode)));
        }
    }

    private static void prepareCustomPublishedAndHotelOverrideCases(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + DEMO_TENANT + "', false)");

            // Simulate a human-edited blank V1 draft.  V35 must preserve both the
            // selected permission and the changed scope.
            assertEquals(1, statement.executeUpdate("""
                    UPDATE position_function_profile_version draft
                       SET authorization_scope_type = 'ORG_UNIT',
                           row_version = draft.row_version + 1,
                           updated_at = now()
                      FROM position_function_profile profile,
                           position_definition position_item
                     WHERE draft.tenant_id = '%s'::uuid
                       AND profile.tenant_id = draft.tenant_id
                       AND profile.id = draft.profile_id
                       AND position_item.tenant_id = profile.tenant_id
                       AND position_item.id = profile.position_id
                       AND position_item.code = 'FRONT_DESK'
                       AND draft.version_no = 1
                    """.formatted(DEMO_TENANT)));
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO position_function_profile_permission
                        (tenant_id, profile_version_id, permission_id)
                    SELECT draft.tenant_id, draft.id, permission_item.id
                    FROM position_function_profile_version draft
                    JOIN position_function_profile profile
                      ON profile.tenant_id = draft.tenant_id
                     AND profile.id = draft.profile_id
                    JOIN position_definition position_item
                      ON position_item.tenant_id = profile.tenant_id
                     AND position_item.id = profile.position_id
                    JOIN permission permission_item
                      ON permission_item.code = 'notification.read'
                    WHERE draft.tenant_id = '%s'::uuid
                      AND position_item.code = 'FRONT_DESK'
                      AND draft.version_no = 1
                    """.formatted(DEMO_TENANT)));

            // Simulate an already-published profile.  V35 must not create a draft
            // or replace its deliberately empty permission set.
            assertEquals(1, statement.executeUpdate("""
                    UPDATE position_function_profile_version published
                       SET lifecycle_status = 'PUBLISHED',
                           published_by = '%s'::uuid,
                           published_at = now(),
                           row_version = published.row_version + 1,
                           updated_at = now()
                      FROM position_function_profile profile,
                           position_definition position_item
                     WHERE published.tenant_id = '%s'::uuid
                       AND profile.tenant_id = published.tenant_id
                       AND profile.id = published.profile_id
                       AND position_item.tenant_id = profile.tenant_id
                       AND position_item.id = profile.position_id
                       AND position_item.code = 'HOUSEKEEPING_SUPERVISOR'
                       AND published.version_no = 1
                    """.formatted(DEMO_CEO, DEMO_TENANT)));

            // The mere presence of a hotel-specific profile is a protected override
            // boundary.  V35 must leave the group draft untouched.
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO position_function_profile
                        (tenant_id, position_id, scope_type, hotel_org_unit_id)
                    SELECT position_item.tenant_id, position_item.id, 'HOTEL', hotel.id
                    FROM position_definition position_item
                    CROSS JOIN LATERAL (
                        SELECT org.id
                        FROM org_unit org
                        WHERE org.tenant_id = position_item.tenant_id
                          AND org.unit_type = 'HOTEL'
                        ORDER BY org.code
                        LIMIT 1
                    ) hotel
                    WHERE position_item.tenant_id = '%s'::uuid
                      AND position_item.code = 'ASSISTANT_GENERAL_MANAGER'
                    """.formatted(DEMO_TENANT)));

            // A protected account-level grant is deliberately attached to the
            // mapped role.  Delegable synchronization must preserve it.
            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO role_permission (tenant_id, role_id, permission_id)
                    SELECT role.tenant_id, role.id, permission_item.id
                    FROM app_role role
                    JOIN permission permission_item ON permission_item.code = 'iam.manage'
                    WHERE role.tenant_id = '%s'::uuid
                      AND role.code = 'GENERAL_MANAGER'
                    ON CONFLICT DO NOTHING
                    """.formatted(DEMO_TENANT)));

            // Reproduce a position created/restored after V33: the active
            // position and matching role exist but its group profile is
            // missing.  V35 must repair this safe canonical mapping so login
            // bootstrap does not reject the employee assignment.
            statement.executeUpdate("""
                    DELETE FROM position_function_profile_permission item
                    USING position_function_profile_version version,
                          position_function_profile profile,
                          position_definition position_item
                    WHERE item.tenant_id = '%s'::uuid
                      AND version.tenant_id = item.tenant_id
                      AND version.id = item.profile_version_id
                      AND profile.tenant_id = version.tenant_id
                      AND profile.id = version.profile_id
                      AND position_item.tenant_id = profile.tenant_id
                      AND position_item.id = profile.position_id
                      AND position_item.code = 'OTA_OPERATION_MANAGER'
                    """.formatted(DEMO_TENANT));
            statement.executeUpdate("""
                    DELETE FROM position_function_profile_version version
                    USING position_function_profile profile,
                          position_definition position_item
                    WHERE version.tenant_id = '%s'::uuid
                      AND profile.tenant_id = version.tenant_id
                      AND profile.id = version.profile_id
                      AND position_item.tenant_id = profile.tenant_id
                      AND position_item.id = profile.position_id
                      AND position_item.code = 'OTA_OPERATION_MANAGER'
                    """.formatted(DEMO_TENANT));
            assertEquals(1, statement.executeUpdate("""
                    DELETE FROM position_function_profile profile
                    USING position_definition position_item
                    WHERE profile.tenant_id = '%s'::uuid
                      AND position_item.tenant_id = profile.tenant_id
                      AND position_item.id = profile.position_id
                      AND position_item.code = 'OTA_OPERATION_MANAGER'
                      AND profile.scope_type = 'GROUP'
                    """.formatted(DEMO_TENANT)));
        }
    }

    private static void assertMigrationOutcomes(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT set_config('app.tenant_id', '" + DEMO_TENANT + "', false)");

            assertEquals("PUBLISHED", versionValue(statement, "GENERAL_MANAGER", 1,
                    "lifecycle_status"));
            assertEquals("ORG_TREE", versionValue(statement, "GENERAL_MANAGER", 1,
                    "authorization_scope_type"));
            assertEquals("DRAFT", versionValue(statement, "GENERAL_MANAGER", 2,
                    "lifecycle_status"));
            assertEquals("ORG_TREE", versionValue(statement, "GENERAL_MANAGER", 2,
                    "authorization_scope_type"));
            assertEquals(1, scalarInt(statement, profileQuery("GENERAL_MANAGER", """
                    AND EXISTS (
                        SELECT 1
                        FROM position_function_profile_version draft
                        WHERE draft.tenant_id = version.tenant_id
                          AND draft.profile_id = version.profile_id
                          AND draft.version_no = 2
                          AND draft.copied_from_version_id = version.id
                    )
                    """)));
            assertEquals(GENERAL_MANAGER_PERMISSIONS,
                    permissionCodes(statement, "GENERAL_MANAGER", 1));
            assertEquals(GENERAL_MANAGER_PERMISSIONS,
                    permissionCodes(statement, "GENERAL_MANAGER", 2));
            assertFalse(permissionCodes(statement, "GENERAL_MANAGER", 1)
                    .contains("dashboard.operations"));
            assertEquals(GENERAL_MANAGER_MODULES,
                    moduleIds(statement, "GENERAL_MANAGER", 1));
            assertFalse(moduleIds(statement, "GENERAL_MANAGER", 1)
                    .contains("operations-dashboard"));
            assertTrue(permissionCodes(statement, "OTA_OPERATION_MANAGER", 1)
                    .contains("dashboard.operations"));
            assertTrue(permissionCodes(statement, "OTA_OPERATION_MANAGER", 1)
                    .containsAll(RESTORED_MANAGER_WORKFLOW_PERMISSIONS));
            assertTrue(moduleIds(statement, "OTA_OPERATION_MANAGER", 1)
                    .contains("operations-dashboard"));
            assertFalse(moduleIds(statement, "OTA_OPERATION_MANAGER", 1)
                    .contains("hotel-dashboard"));
            assertEquals("PUBLISHED", versionValue(statement, "OTA_OPERATION_MANAGER", 1,
                    "lifecycle_status"));
            assertEquals("DRAFT", versionValue(statement, "OTA_OPERATION_MANAGER", 2,
                    "lifecycle_status"));
            assertEquals(1, automaticPublicationAuditCount(statement, "OTA_OPERATION_MANAGER"));
            assertTrue(permissionCodes(statement, "GROUP_VICE_PRESIDENT", 1)
                    .contains("dashboard.operations"));
            assertEquals(0, scalarInt(statement, profileQuery("GENERAL_MANAGER", """
                    AND EXISTS (
                        SELECT 1
                        FROM position_function_profile_permission item
                        JOIN permission permission_item ON permission_item.id = item.permission_id
                        WHERE item.tenant_id = version.tenant_id
                          AND item.profile_version_id = version.id
                          AND permission_item.delegable_to_position = false
                    )
                    """)));
            assertEquals(1, scalarInt(statement, """
                    SELECT count(*)
                    FROM role_permission grant_item
                    JOIN app_role role
                      ON role.tenant_id = grant_item.tenant_id
                     AND role.id = grant_item.role_id
                    JOIN permission permission_item
                      ON permission_item.id = grant_item.permission_id
                    WHERE grant_item.tenant_id = '%s'::uuid
                      AND role.code = 'GENERAL_MANAGER'
                      AND permission_item.code = 'iam.manage'
                      AND permission_item.delegable_to_position = false
                    """.formatted(DEMO_TENANT)));
            assertEquals(1, automaticPublicationAuditCount(statement, "GENERAL_MANAGER"));

            assertTrue(permissionCodes(statement, "FRONT_OFFICE_SUPERVISOR", 1)
                    .containsAll(RESTORED_MANAGER_WORKFLOW_PERMISSIONS));

            assertEquals("DRAFT", versionValue(statement, "FRONT_DESK", 1,
                    "lifecycle_status"));
            assertEquals("ORG_UNIT", versionValue(statement, "FRONT_DESK", 1,
                    "authorization_scope_type"));
            assertEquals(1, scalarInt(statement, versionCountQuery("FRONT_DESK")));
            assertEquals(Set.of("notification.read"), permissionCodes(statement, "FRONT_DESK", 1));
            assertEquals(0, automaticPublicationAuditCount(statement, "FRONT_DESK"));

            assertEquals("PUBLISHED", versionValue(statement, "HOUSEKEEPING_SUPERVISOR", 1,
                    "lifecycle_status"));
            assertEquals("SELF", versionValue(statement, "HOUSEKEEPING_SUPERVISOR", 1,
                    "authorization_scope_type"));
            assertEquals(1, scalarInt(statement, versionCountQuery("HOUSEKEEPING_SUPERVISOR")));
            assertTrue(permissionCodes(statement, "HOUSEKEEPING_SUPERVISOR", 1).isEmpty());
            assertEquals(0, automaticPublicationAuditCount(statement, "HOUSEKEEPING_SUPERVISOR"));

            assertEquals("DRAFT", versionValue(statement, "ASSISTANT_GENERAL_MANAGER", 1,
                    "lifecycle_status"));
            assertEquals(1, scalarInt(statement, versionCountQuery("ASSISTANT_GENERAL_MANAGER")));
            assertEquals(0, automaticPublicationAuditCount(statement, "ASSISTANT_GENERAL_MANAGER"));
            assertEquals(1, scalarInt(statement, """
                    SELECT count(*)
                    FROM position_function_profile hotel_profile
                    JOIN position_definition position_item
                      ON position_item.tenant_id = hotel_profile.tenant_id
                     AND position_item.id = hotel_profile.position_id
                    WHERE hotel_profile.tenant_id = '%s'::uuid
                      AND position_item.code = 'ASSISTANT_GENERAL_MANAGER'
                      AND hotel_profile.scope_type = 'HOTEL'
                    """.formatted(DEMO_TENANT)));
        }
    }

    private static String versionValue(
            Statement statement,
            String positionCode,
            int versionNo,
            String column
    ) throws Exception {
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT version.%s
                FROM position_function_profile_version version
                JOIN position_function_profile profile
                  ON profile.tenant_id = version.tenant_id
                 AND profile.id = version.profile_id
                 AND profile.scope_type = 'GROUP'
                JOIN position_definition position_item
                  ON position_item.tenant_id = profile.tenant_id
                 AND position_item.id = profile.position_id
                WHERE version.tenant_id = '%s'::uuid
                  AND position_item.code = '%s'
                  AND version.version_no = %d
                """.formatted(column, DEMO_TENANT, positionCode, versionNo))) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static Set<String> permissionCodes(
            Statement statement,
            String positionCode,
            int versionNo
    ) throws Exception {
        Set<String> codes = new HashSet<>();
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT permission_item.code
                FROM position_function_profile_permission item
                JOIN permission permission_item ON permission_item.id = item.permission_id
                JOIN position_function_profile_version version
                  ON version.tenant_id = item.tenant_id
                 AND version.id = item.profile_version_id
                JOIN position_function_profile profile
                  ON profile.tenant_id = version.tenant_id
                 AND profile.id = version.profile_id
                 AND profile.scope_type = 'GROUP'
                JOIN position_definition position_item
                  ON position_item.tenant_id = profile.tenant_id
                 AND position_item.id = profile.position_id
                WHERE item.tenant_id = '%s'::uuid
                  AND position_item.code = '%s'
                  AND version.version_no = %d
                  AND permission_item.code NOT LIKE 'ui.module.%%'
                """.formatted(DEMO_TENANT, positionCode, versionNo))) {
            while (resultSet.next()) codes.add(resultSet.getString(1));
        }
        return codes;
    }

    private static Set<String> moduleIds(
            Statement statement,
            String positionCode,
            int versionNo
    ) throws Exception {
        Set<String> modules = new HashSet<>();
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT substring(permission_item.code from length('ui.module.') + 1)
                FROM position_function_profile_permission item
                JOIN permission permission_item ON permission_item.id = item.permission_id
                JOIN position_function_profile_version version
                  ON version.tenant_id = item.tenant_id
                 AND version.id = item.profile_version_id
                JOIN position_function_profile profile
                  ON profile.tenant_id = version.tenant_id
                 AND profile.id = version.profile_id
                 AND profile.scope_type = 'GROUP'
                JOIN position_definition position_item
                  ON position_item.tenant_id = profile.tenant_id
                 AND position_item.id = profile.position_id
                WHERE item.tenant_id = '%s'::uuid
                  AND position_item.code = '%s'
                  AND version.version_no = %d
                  AND permission_item.code LIKE 'ui.module.%%'
                """.formatted(DEMO_TENANT, positionCode, versionNo))) {
            while (resultSet.next()) modules.add(resultSet.getString(1));
        }
        return modules;
    }

    private static String versionCountQuery(String positionCode) {
        return """
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
                  AND position_item.code = '%s'
                """.formatted(DEMO_TENANT, positionCode);
    }

    private static String profileQuery(String positionCode, String extraPredicate) {
        return """
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
                  AND position_item.code = '%s'
                  AND version.version_no = 1
                %s
                """.formatted(DEMO_TENANT, positionCode, extraPredicate);
    }

    private static int scalarInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static int automaticPublicationAuditCount(
            Statement statement,
            String positionCode
    ) throws Exception {
        return automaticPublicationAuditCount(statement, positionCode, "V35");
    }

    private static int automaticPublicationAuditCount(
            Statement statement,
            String positionCode,
            String source
    ) throws Exception {
        return scalarInt(statement, """
                SELECT count(*)
                FROM audit_log audit
                JOIN position_function_profile profile
                  ON profile.tenant_id = audit.tenant_id
                 AND profile.id = audit.resource_id
                JOIN position_definition position_item
                  ON position_item.tenant_id = profile.tenant_id
                 AND position_item.id = profile.position_id
                WHERE audit.tenant_id = '%s'::uuid
                  AND audit.action = 'SYSTEM_POSITION_PROFILE_DEFAULT_PUBLISHED'
                  AND audit.resource_type = 'POSITION_FUNCTION_PROFILE'
                  AND position_item.code = '%s'
                  AND audit.after_data ->> 'category' = 'SYSTEM'
                  AND audit.after_data ->> 'source' = '%s'
                  AND audit.after_data ->> 'profileId' = profile.id::text
                  AND audit.after_data ->> 'positionId' = position_item.id::text
                  AND audit.after_data ? 'authorizationScopeType'
                  AND (audit.after_data ->> 'permissionCount')::integer > 0
                """.formatted(DEMO_TENANT, positionCode, source));
    }

    private static String loadMigrationSql() throws Exception {
        try (InputStream stream = Objects.requireNonNull(
                PositionDefaultProfileMigrationIntegrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V35_RESOURCE),
                V35_RESOURCE + " must be available on the test classpath")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
