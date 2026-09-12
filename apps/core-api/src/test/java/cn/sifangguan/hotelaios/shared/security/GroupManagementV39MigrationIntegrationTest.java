package cn.sifangguan.hotelaios.shared.security;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroupManagementV39MigrationIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final Set<String> TABLES = Set.of(
            "management_work_plan", "management_work_plan_revision", "management_work_plan_item",
            "management_work_plan_item_reminder", "management_work_plan_review",
            "management_work_plan_item_decision", "management_work_plan_item_decision_reminder",
            "management_work_plan_task_link", "task_reminder",
            "position_assignment_reporting_relation", "executive_task_directive"
    );

    @Test
    void migrationBuildsTheFrozenBatchAFoundation() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            int migratedToV38 = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("38")
                    .load()
                    .migrate()
                    .migrationsExecuted;
            assertEquals(38, migratedToV38);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO app_role (tenant_id, code, name, role_type)
                        VALUES ('10000000-0000-0000-0000-000000000001',
                                'GROUP_CHAIRMAN', '冲突自定义角色', 'CUSTOM')
                        """);
            }

            assertThrows(FlywayException.class, () -> Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate());

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                assertEquals(0, scalar(statement, """
                        SELECT count(*) FROM flyway_schema_history
                        WHERE version = '39' AND success
                        """));
                assertEquals(0, scalar(statement, """
                        SELECT count(*) FROM pg_class WHERE relname = 'management_work_plan'
                        """));
                statement.execute("""
                        DELETE FROM app_role
                        WHERE tenant_id = '10000000-0000-0000-0000-000000000001'
                          AND code = 'GROUP_CHAIRMAN' AND role_type = 'CUSTOM'
                        """);
            }

            int migratedToV39 = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .target("39")
                    .load()
                    .migrate()
                    .migrationsExecuted;
            assertEquals(1, migratedToV39);

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                for (String table : TABLES) {
                    assertEquals(1, scalar(statement, """
                            SELECT count(*) FROM pg_class
                            WHERE oid = '%s'::regclass AND relrowsecurity AND relforcerowsecurity
                            """.formatted(table)), table);
                }
                assertEquals(3, scalar(statement, """
                        SELECT count(*) FROM app_role
                        WHERE tenant_id = '%s'::uuid AND role_type = 'SYSTEM'
                          AND code IN ('GROUP_CHAIRMAN','HR_ADMINISTRATION_SUPERVISOR','HR_ADMINISTRATION')
                        """.formatted(TENANT)));
                assertEquals(4, scalar(statement, """
                        SELECT count(*) FROM position_definition
                        WHERE tenant_id = '%s'::uuid
                          AND code IN ('GROUP_CHAIRMAN','GROUP_GENERAL_MANAGER',
                                       'HR_ADMINISTRATION_SUPERVISOR','HR_ADMINISTRATION')
                        """.formatted(TENANT)));
                assertEquals(8, scalar(statement, """
                        SELECT count(*) FROM permission
                        WHERE code IN ('ui.module.work-plans','work-plan.read','work-plan.submit',
                                       'work-plan.team-read','work-plan.review','work-record.team-read',
                                       'executive-task.read','executive-task.assign')
                        """));
                assertEquals(0, scalar(statement, """
                        SELECT count(*) FROM position_definition
                        WHERE tenant_id = '%s'::uuid AND code = 'REGIONAL_MANAGER'
                        """.formatted(TENANT)));
                assertEquals(1, scalar(statement, """
                        SELECT count(*) FROM app_role
                        WHERE tenant_id = '%s'::uuid AND code = 'OTA_OPERATION_MANAGER'
                          AND role_type = 'SYSTEM'
                        """.formatted(TENANT)));
                assertEquals(3, scalar(statement, profilePermissionCount(
                        "GROUP_CHAIRMAN",
                        "'executive-task.read','executive-task.assign','task.review'")));
                assertEquals(0, scalar(statement, profilePermissionCount(
                        "GROUP_CHAIRMAN",
                        "'task.read','task.create','task.dispatch','task.act','task.cancel','org.manage'")));
                assertEquals(0, scalar(statement, profilePermissionCount(
                        "HR_ADMINISTRATION", "'work-plan.submit','work-plan.review'")));
                assertEquals(2, scalar(statement, profilePermissionCount(
                        "HR_ADMINISTRATION_SUPERVISOR", "'work-plan.submit','work-plan.review'")));
                assertTrue(text(statement, """
                        SELECT column_default FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'management_task'
                          AND column_name = 'creation_source'
                        """).contains("LEGACY"));
            }

            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                connection.setAutoCommit(false);
                statement.execute("""
                        INSERT INTO position_assignment_reporting_relation
                            (tenant_id, subordinate_assignment_id, leader_assignment_id,
                             relation_type, valid_from)
                        VALUES ('10000000-0000-0000-0000-000000000001',
                                '19200000-0000-0000-0000-000000000004',
                                '19200000-0000-0000-0000-000000000002',
                                'INDIRECT_LEADER', current_date)
                        """);
                assertThrows(SQLException.class, connection::commit);
                connection.rollback();
            }
        }
    }

    private static String profilePermissionCount(String positionCode, String permissionCodes) {
        return """
                SELECT count(*)
                FROM position_definition position_item
                JOIN position_function_profile profile
                  ON profile.tenant_id = position_item.tenant_id
                 AND profile.position_id = position_item.id AND profile.scope_type = 'GROUP'
                JOIN position_function_profile_version version
                  ON version.tenant_id = profile.tenant_id AND version.profile_id = profile.id
                 AND version.version_no = 1
                JOIN position_function_profile_permission profile_permission
                  ON profile_permission.tenant_id = version.tenant_id
                 AND profile_permission.profile_version_id = version.id
                JOIN permission permission_item ON permission_item.id = profile_permission.permission_id
                WHERE position_item.tenant_id = '%s'::uuid AND position_item.code = '%s'
                  AND permission_item.code IN (%s)
                """.formatted(TENANT, positionCode, permissionCodes);
    }

    private static int scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String text(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
