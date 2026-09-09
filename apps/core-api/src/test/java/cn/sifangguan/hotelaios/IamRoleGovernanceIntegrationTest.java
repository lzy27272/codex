package cn.sifangguan.hotelaios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IamRoleGovernanceIntegrationTest {
    private static final String TENANT = "10000000-0000-0000-0000-000000000001";
    private static final String CEO = "19000000-0000-0000-0000-000000000001";
    private static final String FRONT_DESK = "19000000-0000-0000-0000-000000000003";
    private static final String CEO_ROLE = "19400000-0000-0000-0000-000000000001";
    private static final String ORG_READ = "19300000-0000-0000-0000-000000000001";
    private static final String DASHBOARD_HOTEL = "19300000-0000-0000-0000-000000000008";
    private static final String HANGZHOU_HOTEL = "12000000-0000-0000-0000-000000000003";

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final DataSource DATA_SOURCE = POSTGRES.getPostgresDatabase();
    private static final String JDBC_URL = jdbcUrl(DATA_SOURCE);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> JDBC_URL);
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("app.security.development-header-auth-enabled", () -> true);
        registry.add("app.database.rls-enabled", () -> true);
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        POSTGRES.close();
    }

    @Test
    void genericIamCreatesAndAuditsOnlyCanonicalCustomRoles() throws Exception {
        for (String roleType : List.of("SYSTEM", "system", " SYSTEM ", "UNKNOWN")) {
            String code = "INVALID_TYPE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            mockMvc.perform(post("/api/v1/iam/roles")
                            .header("X-Tenant-Id", TENANT)
                            .header("X-Actor-Id", CEO)
                            .contentType("application/json")
                            .content("""
                                    {"code":"%s","name":"Invalid role","roleType":"%s"}
                                    """.formatted(code, roleType)))
                    .andExpect(status().isBadRequest());
            assertThat(roleCount(code)).isZero();
        }

        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("""
                                {"code":"PLATFORM_ADMIN","name":"Impersonated platform role","roleType":"CUSTOM"}
                                """))
                .andExpect(status().isBadRequest());

        int customRolesBeforeUnauthorizedRequest = jdbc.queryForObject("""
                select count(*) from app_role
                where tenant_id = cast(? as uuid) and role_type = 'CUSTOM'
                """, Integer.class, TENANT);
        mockMvc.perform(post("/api/v1/iam/roles")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", FRONT_DESK)
                        .contentType("application/json")
                        .content("""
                                {"code":"UNAUTHORIZED_CUSTOM","name":"Unauthorized","roleType":"CUSTOM"}
                                """))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("""
                select count(*) from app_role
                where tenant_id = cast(? as uuid) and role_type = 'CUSTOM'
                """, Integer.class, TENANT)).isEqualTo(customRolesBeforeUnauthorizedRequest);

        for (String roleTypeProperty : List.of("", ",\"roleType\":null", ",\"roleType\":\" custom \"")) {
            String rawCode = " custom_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + " ";
            MvcResult created = mockMvc.perform(post("/api/v1/iam/roles")
                            .header("X-Tenant-Id", TENANT)
                            .header("X-Actor-Id", CEO)
                            .contentType("application/json")
                            .content("""
                                    {"code":"%s","name":" Custom role "%s}
                                    """.formatted(rawCode, roleTypeProperty)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(rawCode.trim().toUpperCase()))
                    .andExpect(jsonPath("$.name").value("Custom role"))
                    .andExpect(jsonPath("$.roleType").value("CUSTOM"))
                    .andReturn();
            UUID roleId = UUID.fromString(json(created).path("id").asText());
            assertThat(auditCount("IAM_CUSTOM_ROLE_CREATED", roleId)).isEqualTo(1);
        }
    }

    @Test
    void systemRolePermissionAndGrantMutationsAreRejectedWithoutSideEffects() throws Exception {
        Set<String> beforePermissions = rolePermissionCodes(UUID.fromString(CEO_ROLE));
        int permissionAuditBefore = auditCount(
                "IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED", UUID.fromString(CEO_ROLE));

        mockMvc.perform(put("/api/v1/iam/roles/{roleId}/permissions", CEO_ROLE)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("{\"permissionIds\":[\"" + ORG_READ + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("访问被拒绝"));

        assertThat(rolePermissionCodes(UUID.fromString(CEO_ROLE))).isEqualTo(beforePermissions);
        assertThat(auditCount("IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED", UUID.fromString(CEO_ROLE)))
                .isEqualTo(permissionAuditBefore);

        int assignmentCountBefore = jdbc.queryForObject("""
                select count(*) from role_assignment
                where tenant_id = cast(? as uuid) and account_id = cast(? as uuid)
                  and role_id = cast(? as uuid)
                """, Integer.class, TENANT, FRONT_DESK, CEO_ROLE);
        int grantAuditBefore = jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = cast(? as uuid) and action = 'IAM_CUSTOM_ROLE_GRANTED'
                """, Integer.class, TENANT);

        mockMvc.perform(post("/api/v1/iam/role-assignments")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId":"%s",
                                  "roleId":"%s",
                                  "scopeType":"SELF"
                                }
                                """.formatted(FRONT_DESK, CEO_ROLE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("访问被拒绝"));

        assertThat(jdbc.queryForObject("""
                select count(*) from role_assignment
                where tenant_id = cast(? as uuid) and account_id = cast(? as uuid)
                  and role_id = cast(? as uuid)
                """, Integer.class, TENANT, FRONT_DESK, CEO_ROLE)).isEqualTo(assignmentCountBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = cast(? as uuid) and action = 'IAM_CUSTOM_ROLE_GRANTED'
                """, Integer.class, TENANT)).isEqualTo(grantAuditBefore);
    }

    @Test
    void customRolePermissionReplacementIsAtomicAndAudited() throws Exception {
        UUID roleId = createCustomRole("PERMISSION_TEST");

        mockMvc.perform(put("/api/v1/iam/roles/{roleId}/permissions", roleId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("""
                                {"permissionIds":["%s","%s"]}
                                """.formatted(ORG_READ, DASHBOARD_HOTEL)))
                .andExpect(status().isNoContent());

        assertThat(rolePermissionCodes(roleId)).containsExactlyInAnyOrder("org.read", "dashboard.hotel");
        assertThat(auditCount("IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED", roleId)).isEqualTo(1);
        JsonNode audit = objectMapper.readTree(jdbc.queryForObject("""
                select after_data::text from audit_log
                where tenant_id = cast(? as uuid)
                  and action = 'IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED'
                  and resource_id = ?
                """, String.class, TENANT, roleId));
        assertThat(audit.path("roleType").asText()).isEqualTo("CUSTOM");
        assertThat(audit.path("beforePermissionCount").asInt()).isZero();
        assertThat(audit.path("afterPermissionCount").asInt()).isEqualTo(2);
        assertThat(audit.path("afterPermissionCodes").toString()).contains("org.read", "dashboard.hotel");

        mockMvc.perform(put("/api/v1/iam/roles/{roleId}/permissions", roleId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("{\"permissionIds\":[\"" + DASHBOARD_HOTEL + "\"]}"))
                .andExpect(status().isNoContent());
        assertThat(rolePermissionCodes(roleId)).containsExactly("dashboard.hotel");
        JsonNode latestAudit = objectMapper.readTree(jdbc.queryForObject("""
                select after_data::text from audit_log
                where tenant_id = cast(? as uuid)
                  and action = 'IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED'
                  and resource_id = ?
                order by created_at desc
                limit 1
                """, String.class, TENANT, roleId));
        assertThat(latestAudit.path("beforePermissionCodes").toString())
                .contains("org.read", "dashboard.hotel");
        assertThat(latestAudit.path("afterPermissionCodes").toString())
                .isEqualTo("[\"dashboard.hotel\"]");

        Set<String> beforeInvalidRequest = rolePermissionCodes(roleId);
        mockMvc.perform(put("/api/v1/iam/roles/{roleId}/permissions", roleId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("{\"permissionIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/iam/roles/{roleId}/permissions", roleId)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("{\"permissionIds\":[\"" + ORG_READ + "\",\"" + ORG_READ + "\"]}"))
                .andExpect(status().isBadRequest());

        assertThat(rolePermissionCodes(roleId)).isEqualTo(beforeInvalidRequest);
        assertThat(auditCount("IAM_CUSTOM_ROLE_PERMISSIONS_REPLACED", roleId)).isEqualTo(2);
    }

    @Test
    void customRoleGrantIsAuditedAndRoleListingMarksEditability() throws Exception {
        UUID roleId = createCustomRole("GRANT_TEST");

        MvcResult granted = mockMvc.perform(post("/api/v1/iam/role-assignments")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("""
                                {
                                  "accountId":"%s",
                                  "roleId":"%s",
                                  "scopeType":" self "
                                }
                                """.formatted(FRONT_DESK, roleId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID assignmentId = UUID.fromString(json(granted).path("id").asText());

        assertThat(jdbc.queryForObject("""
                select source_type is null and source_assignment_id is null
                from role_assignment where tenant_id = cast(? as uuid) and id = ?
                """, Boolean.class, TENANT, assignmentId)).isTrue();
        assertThat(auditCount("IAM_CUSTOM_ROLE_GRANTED", assignmentId)).isEqualTo(1);

        JsonNode roles = json(mockMvc.perform(get("/api/v1/iam/roles")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(findRole(roles, roleId).path("editable").asBoolean()).isTrue();
        assertThat(findRole(roles, UUID.fromString(CEO_ROLE)).path("editable").asBoolean()).isFalse();
    }

    @Test
    void crossTenantCustomRoleCannotBeModified() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherRole = UUID.randomUUID();
        jdbc.update("insert into tenant (id, code, name) values (?, ?, 'Other tenant')",
                otherTenant, "OTHER-" + otherTenant.toString().substring(0, 8));
        jdbc.update("""
                insert into app_role (id, tenant_id, code, name, role_type)
                values (?, ?, ?, 'Other role', 'CUSTOM')
                """, otherRole, otherTenant, "OTHER_ROLE_" + otherRole.toString().substring(0, 8));

        mockMvc.perform(put("/api/v1/iam/roles/{roleId}/permissions", otherRole)
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("{\"permissionIds\":[\"" + ORG_READ + "\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("""
                select count(*) from role_permission
                where tenant_id = ? and role_id = ?
                """, Integer.class, otherTenant, otherRole)).isZero();
    }

    @Test
    void invalidCustomGrantScopeAndValidityFailBeforeWrite() throws Exception {
        UUID roleId = createCustomRole("INVALID_GRANT_TEST");
        int assignmentsBefore = jdbc.queryForObject("""
                select count(*) from role_assignment
                where tenant_id = cast(? as uuid) and role_id = ?
                """, Integer.class, TENANT, roleId);
        int auditsBefore = jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = cast(? as uuid) and action = 'IAM_CUSTOM_ROLE_GRANTED'
                """, Integer.class, TENANT);

        List<String> invalidBodies = List.of(
                """
                {"accountId":"%s","roleId":"%s","scopeType":"ORG_TREE"}
                """.formatted(FRONT_DESK, roleId),
                """
                {"accountId":"%s","roleId":"%s","scopeOrgUnitId":"%s","scopeType":"SELF"}
                """.formatted(FRONT_DESK, roleId, HANGZHOU_HOTEL),
                """
                {
                  "accountId":"%s","roleId":"%s","scopeType":"TENANT",
                  "validFrom":"2026-09-10T00:00:00+08:00",
                  "validTo":"2026-09-09T00:00:00+08:00"
                }
                """.formatted(FRONT_DESK, roleId)
        );
        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/v1/iam/role-assignments")
                            .header("X-Tenant-Id", TENANT)
                            .header("X-Actor-Id", CEO)
                            .contentType("application/json")
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from role_assignment
                where tenant_id = cast(? as uuid) and role_id = ?
                """, Integer.class, TENANT, roleId)).isEqualTo(assignmentsBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = cast(? as uuid) and action = 'IAM_CUSTOM_ROLE_GRANTED'
                """, Integer.class, TENANT)).isEqualTo(auditsBefore);
    }

    private UUID createCustomRole(String prefix) throws Exception {
        String code = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MvcResult created = mockMvc.perform(post("/api/v1/iam/roles")
                        .header("X-Tenant-Id", TENANT)
                        .header("X-Actor-Id", CEO)
                        .contentType("application/json")
                        .content("""
                                {"code":"%s","name":"Test custom role","roleType":"CUSTOM"}
                                """.formatted(code)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(created).path("id").asText());
    }

    private int roleCount(String code) {
        return jdbc.queryForObject("""
                select count(*) from app_role
                where tenant_id = cast(? as uuid) and code = ?
                """, Integer.class, TENANT, code.trim().toUpperCase());
    }

    private Set<String> rolePermissionCodes(UUID roleId) {
        return Set.copyOf(jdbc.queryForList("""
                select permission.code
                from role_permission grant_item
                join permission on permission.id = grant_item.permission_id
                where grant_item.tenant_id = cast(? as uuid) and grant_item.role_id = ?
                order by permission.code
                """, String.class, TENANT, roleId));
    }

    private int auditCount(String action, UUID resourceId) {
        return jdbc.queryForObject("""
                select count(*) from audit_log
                where tenant_id = cast(? as uuid) and action = ? and resource_id = ?
                """, Integer.class, TENANT, action, resourceId);
    }

    private JsonNode findRole(JsonNode roles, UUID roleId) {
        for (JsonNode role : roles) {
            if (roleId.toString().equals(role.path("id").asText())) {
                return role;
            }
        }
        throw new AssertionError("Role not found: " + roleId);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String jdbcUrl(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
