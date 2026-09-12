package cn.sifangguan.hotelaios.organization;

import cn.sifangguan.hotelaios.shared.audit.AuditWriter;
import cn.sifangguan.hotelaios.shared.context.TenantPrincipal;
import cn.sifangguan.hotelaios.shared.db.TenantDatabaseContext;
import cn.sifangguan.hotelaios.shared.security.AccessPolicy;
import cn.sifangguan.hotelaios.shared.security.AccessDeniedException;
import cn.sifangguan.hotelaios.shared.security.PilotPasswordHasher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TenantDatabaseContext databaseContext;
    private final AccessPolicy accessPolicy;
    private final PilotPasswordHasher passwordHasher;
    private final AuditWriter auditWriter;

    public OrganizationService(
            NamedParameterJdbcTemplate jdbc,
            TenantDatabaseContext databaseContext,
            AccessPolicy accessPolicy,
            PilotPasswordHasher passwordHasher,
            AuditWriter auditWriter
    ) {
        this.jdbc = jdbc;
        this.databaseContext = databaseContext;
        this.accessPolicy = accessPolicy;
        this.passwordHasher = passwordHasher;
        this.auditWriter = auditWriter;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listOrgUnits(String unitType) {
        accessPolicy.requirePermission("org.read");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = base(principal).addValue("unitType", normalize(unitType));
        String visibility = visibility("o", principal, parameters);
        return jdbc.queryForList("""
                select o.id, o.parent_id, o.code, o.name, o.unit_type, o.status, o.sort_order,
                       h.property_code, h.city, h.room_count, h.opening_date
                from org_unit o
                left join hotel_profile h on h.tenant_id = o.tenant_id and h.org_unit_id = o.id
                where o.tenant_id = :tenantId
                  and (cast(:unitType as varchar) is null or o.unit_type = :unitType)
                """ + visibility + " order by o.sort_order, o.name", parameters);
    }

    @Transactional
    public Map<String, Object> createOrgUnit(OrganizationModels.CreateOrgUnit request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        UUID id = UUID.randomUUID();
        String unitType = normalize(request.unitType());
        if (!Set.of("GROUP", "REGION", "HOTEL", "DEPARTMENT").contains(unitType)) {
            throw new IllegalArgumentException("组织类型必须为集团、区域、门店或部门");
        }
        String parentType = null;
        if (request.parentId() != null) {
            parentType = requireOrgType(principal, request.parentId());
            accessPolicy.requireOrgScope(request.parentId());
        } else if (!principal.hasTenantScope()) {
            throw new AccessDeniedException("仅租户级管理员可以创建根组织");
        }
        validateHierarchy(unitType, request.parentId(), parentType);
        if ("HOTEL".equals(unitType) && isBlank(request.propertyCode())) {
            throw new IllegalArgumentException("创建门店时必须填写门店编码");
        }
        if (request.roomCount() != null && request.roomCount() < 0) {
            throw new IllegalArgumentException("房间数不能小于0");
        }
        requireUniqueCode("org_unit", principal, id, request.code(), "组织编码已存在");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("parentId", request.parentId())
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("unitType", unitType)
                .addValue("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                .addValue("propertyCode", trimToNull(request.propertyCode()))
                .addValue("city", trimToNull(request.city()))
                .addValue("roomCount", request.roomCount())
                .addValue("openingDate", request.openingDate());
        jdbc.update("""
                insert into org_unit (id, tenant_id, parent_id, code, name, unit_type, sort_order)
                values (:id, :tenantId, :parentId, :code, :name, :unitType, :sortOrder)
                """, parameters);
        jdbc.update("""
                insert into org_unit_closure (tenant_id, ancestor_id, descendant_id, depth)
                values (:tenantId, :id, :id, 0)
                """, parameters);
        if (request.parentId() != null) {
            jdbc.update("""
                    insert into org_unit_closure (tenant_id, ancestor_id, descendant_id, depth)
                    select tenant_id, ancestor_id, :id, depth + 1
                    from org_unit_closure
                    where tenant_id = :tenantId and descendant_id = :parentId
                    """, parameters);
        }
        if ("HOTEL".equals(unitType)) {
            jdbc.update("""
                    insert into hotel_profile
                        (id, tenant_id, org_unit_id, property_code, city, room_count, opening_date)
                    values
                        (:hotelId, :tenantId, :id, :propertyCode, :city, :roomCount, :openingDate)
                    """, parameters.addValue("hotelId", UUID.randomUUID()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("code", request.code());
        response.put("name", request.name());
        response.put("unitType", unitType);
        response.put("propertyCode", trimToNull(request.propertyCode()));
        return response;
    }

    @Transactional
    public Map<String, Object> updateOrgUnit(UUID orgUnitId, OrganizationModels.UpdateOrgUnit request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        Map<String, Object> current = requireOrg(principal, orgUnitId);
        accessPolicy.requireOrgScope(orgUnitId);
        String unitType = String.valueOf(current.get("unit_type"));
        String status = lifecycleStatus(request.status());
        if ("GROUP".equals(unitType) && "INACTIVE".equals(status)) {
            throw new IllegalArgumentException("集团根组织不能停用");
        }
        if (request.roomCount() != null && request.roomCount() < 0) {
            throw new IllegalArgumentException("房间数不能小于0");
        }
        if ("HOTEL".equals(unitType) && isBlank(request.propertyCode())) {
            throw new IllegalArgumentException("门店必须保留门店编码");
        }
        requireUniqueCode("org_unit", principal, orgUnitId, request.code(), "组织编码已存在");
        if ("ACTIVE".equals(status) && current.get("parent_id") != null) {
            Integer activeParent = jdbc.queryForObject("""
                    select count(*) from org_unit
                    where tenant_id = :tenantId and id = :parentId and status = 'ACTIVE'
                    """, base(principal).addValue("parentId", current.get("parent_id")), Integer.class);
            if (activeParent == null || activeParent != 1) {
                throw new IllegalArgumentException("上级组织未启用，不能启用当前组织");
            }
        }
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", orgUnitId)
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                .addValue("status", status)
                .addValue("propertyCode", trimToNull(request.propertyCode()))
                .addValue("city", trimToNull(request.city()))
                .addValue("roomCount", request.roomCount())
                .addValue("openingDate", request.openingDate());
        jdbc.update("""
                update org_unit
                set code = :code, name = :name, sort_order = :sortOrder, status = :status, updated_at = now()
                where tenant_id = :tenantId and id = :id
                """, parameters);
        if ("HOTEL".equals(unitType)) {
            jdbc.update("""
                    update hotel_profile
                    set property_code = :propertyCode, city = :city, room_count = :roomCount,
                        opening_date = :openingDate, updated_at = now()
                    where tenant_id = :tenantId and org_unit_id = :id
                    """, parameters);
        }
        if ("INACTIVE".equals(status)) {
            deactivateOrgTree(principal, orgUnitId);
        }
        auditWriter.record("ORG_UNIT_UPDATED", "ORG_UNIT", orgUnitId,
                "{\"status\":\"" + status + "\",\"code\":\"" + jsonEscape(request.code().trim()) + "\"}");
        return Map.of("id", orgUnitId, "code", request.code().trim(), "name", request.name().trim(),
                "unitType", unitType, "status", status);
    }

    @Transactional
    public void deleteOrgUnit(UUID orgUnitId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "组织删除只能由集团级管理员执行");
        Map<String, Object> current = requireOrg(principal, orgUnitId);
        if ("GROUP".equals(String.valueOf(current.get("unit_type")))) {
            throw new IllegalArgumentException("集团根组织不能删除");
        }
        if (!"INACTIVE".equals(String.valueOf(current.get("status")))) {
            throw new IllegalArgumentException("请先停用组织，再执行删除");
        }
        Integer children = jdbc.queryForObject("""
                select count(*) from org_unit
                where tenant_id = :tenantId and parent_id = :id
                """, base(principal).addValue("id", orgUnitId), Integer.class);
        if (children != null && children > 0) {
            throw new IllegalArgumentException("该组织仍有下级组织，只能停用，不能删除");
        }
        try {
            MapSqlParameterSource parameters = base(principal).addValue("id", orgUnitId);
            jdbc.update("delete from hotel_profile where tenant_id = :tenantId and org_unit_id = :id", parameters);
            int deleted = jdbc.update("delete from org_unit where tenant_id = :tenantId and id = :id", parameters);
            if (deleted != 1) {
                throw new IllegalArgumentException("组织不存在或不属于当前租户");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("该组织已有任职、权限、工作或经营数据，只能停用，不能删除", exception);
        }
        auditWriter.record("ORG_UNIT_DELETED", "ORG_UNIT", orgUnitId, "{\"status\":\"DELETED\"}");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPositions() {
        accessPolicy.requirePermission("org.read");
        TenantPrincipal principal = prepare();
        return jdbc.queryForList("""
                select position.id, position.code, position.name, position.job_family,
                       position.level_code, position.status,
                       position.applies_to_all_hotels,
                       published.authorization_scope_type,
                       applicable_scope.applicable_hotel_ids
                from position_definition position
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id
                 and profile.position_id = position.id
                 and profile.scope_type = 'GROUP'
                join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id
                 and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                left join lateral (
                    select string_agg(applicable.hotel_org_unit_id::text, ','
                                      order by applicable.hotel_org_unit_id) as applicable_hotel_ids
                    from position_applicable_hotel applicable
                    where applicable.tenant_id = position.tenant_id
                      and applicable.position_id = position.id
                ) applicable_scope on true
                where position.tenant_id = :tenantId
                  and position.deleted_at is null and position.permanently_deleted_at is null
                  and exists (
                    select 1
                    from position_function_profile visible_profile
                    join position_function_profile_version visible_published
                      on visible_published.tenant_id = visible_profile.tenant_id
                     and visible_published.profile_id = visible_profile.id
                     and visible_published.lifecycle_status = 'PUBLISHED'
                    where visible_profile.tenant_id = position.tenant_id
                      and visible_profile.position_id = position.id
                      and visible_profile.scope_type = 'GROUP'
                  )
                order by position.job_family, position.level_code, position.name
                """, base(principal));
    }

    @Transactional
    public Map<String, Object> createPosition(OrganizationModels.CreatePosition request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "岗位字典只能由集团级管理员维护");
        UUID id = UUID.randomUUID();
        requireUniqueCode("position_definition", principal, id, request.code(), "岗位编码已存在");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("jobFamily", request.jobFamily().trim())
                .addValue("levelCode", request.levelCode());
        jdbc.update("""
                insert into position_definition (id, tenant_id, code, name, job_family, level_code)
                values (:id, :tenantId, :code, :name, :jobFamily, :levelCode)
                """, parameters);
        return Map.of("id", id, "code", request.code(), "name", request.name());
    }

    @Transactional
    public Map<String, Object> updatePosition(UUID positionId, OrganizationModels.UpdatePosition request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "岗位字典只能由集团级管理员维护");
        requireEntity("position_definition", principal, positionId);
        requireUniqueCode("position_definition", principal, positionId, request.code(), "岗位编码已存在");
        String status = lifecycleStatus(request.status());
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", positionId)
                .addValue("code", request.code().trim())
                .addValue("name", request.name().trim())
                .addValue("jobFamily", request.jobFamily().trim())
                .addValue("levelCode", trimToNull(request.levelCode()))
                .addValue("status", status);
        jdbc.update("""
                update position_definition
                set code = :code, name = :name, job_family = :jobFamily,
                    level_code = :levelCode, status = :status, updated_at = now()
                where tenant_id = :tenantId and id = :id
                """, parameters);
        if ("INACTIVE".equals(status)) {
            jdbc.update("""
                    update employee_position_assignment
                    set status = 'INACTIVE', valid_to = case
                        when valid_to is null or valid_to > current_date then current_date else valid_to end,
                        updated_at = now()
                    where tenant_id = :tenantId and position_id = :id and status = 'ACTIVE'
                    """, parameters);
        }
        auditWriter.record("POSITION_UPDATED", "POSITION", positionId,
                "{\"status\":\"" + status + "\",\"code\":\"" + jsonEscape(request.code().trim()) + "\"}");
        return Map.of("id", positionId, "code", request.code().trim(), "name", request.name().trim(), "status", status);
    }

    @Transactional
    public void deletePosition(UUID positionId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "岗位删除只能由集团级管理员执行");
        String status = jdbc.queryForObject("""
                select status from position_definition
                where tenant_id = :tenantId and id = :id
                """, base(principal).addValue("id", positionId), String.class);
        if (!"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("请先停用岗位，再执行删除");
        }
        try {
            int deleted = jdbc.update("""
                    delete from position_definition where tenant_id = :tenantId and id = :id
                    """, base(principal).addValue("id", positionId));
            if (deleted != 1) {
                throw new IllegalArgumentException("岗位不存在或不属于当前租户");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("该岗位已有任职、标准、表单或工作包数据，只能停用，不能删除", exception);
        }
        auditWriter.record("POSITION_DELETED", "POSITION", positionId, "{\"status\":\"DELETED\"}");
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listEmployees() {
        accessPolicy.requirePermission("org.read");
        TenantPrincipal principal = prepare();
        if (!principal.hasTenantScope() && principal.orgScopes().isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = base(principal);
        String visibility = visibility("o", principal, parameters);
        return jdbc.queryForList("""
                select distinct e.id, e.account_id, u.login_name, u.status as account_status,
                       e.employee_no, e.name, e.mobile, e.employment_status, e.hired_on,
                       a.id as assignment_id, a.is_primary, a.valid_from, a.valid_to,
                       o.id as org_unit_id, o.name as org_unit_name,
                       p.id as position_id, p.name as position_name,
                       published.authorization_scope_type,
                       assigned_scope.responsible_hotel_ids,
                       assigned_scope.responsible_hotel_names
                from employee e
                left join user_account u on u.tenant_id = e.tenant_id and u.id = e.account_id
                left join employee_position_assignment a
                  on a.tenant_id = e.tenant_id and a.employee_id = e.id and a.status = 'ACTIVE'
                left join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                left join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                left join position_function_profile profile
                  on profile.tenant_id = p.tenant_id and profile.position_id = p.id
                 and profile.scope_type = 'GROUP'
                left join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                left join lateral (
                    select string_agg(scope.hotel_org_unit_id::text, ',' order by hotel.name) as responsible_hotel_ids,
                           string_agg(hotel.name, '、' order by hotel.name) as responsible_hotel_names
                    from employee_assignment_hotel_scope scope
                    join org_unit hotel
                      on hotel.tenant_id = scope.tenant_id and hotel.id = scope.hotel_org_unit_id
                    where scope.tenant_id = a.tenant_id and scope.assignment_id = a.id
                ) assigned_scope on true
                where e.tenant_id = :tenantId and e.deleted_at is null
                """ + visibility + " order by e.name, a.is_primary desc", parameters);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listDeletedEmployees() {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工回收站只能由集团级管理员查看");
        return jdbc.queryForList("""
                select e.id, e.account_id, u.login_name, e.employee_no, e.name, e.mobile,
                       e.employment_status, e.hired_on, e.deleted_at,
                       deleted_by.display_name as deleted_by_name,
                       latest.org_unit_id, latest.org_unit_name,
                       latest.position_id, latest.position_name
                from employee e
                left join user_account u
                  on u.tenant_id = e.tenant_id and u.id = e.account_id
                left join user_account deleted_by
                  on deleted_by.tenant_id = e.tenant_id and deleted_by.id = e.deleted_by
                left join lateral (
                    select a.org_unit_id, o.name as org_unit_name,
                           a.position_id, p.name as position_name
                    from employee_position_assignment a
                    join org_unit o on o.tenant_id = a.tenant_id and o.id = a.org_unit_id
                    join position_definition p on p.tenant_id = a.tenant_id and p.id = a.position_id
                    where a.tenant_id = e.tenant_id and a.employee_id = e.id
                    order by a.updated_at desc, a.created_at desc, a.id
                    limit 1
                ) latest on true
                where e.tenant_id = :tenantId
                  and e.deleted_at is not null and e.permanently_deleted_at is null
                order by e.deleted_at desc, e.id
                """, base(principal));
    }

    @Transactional
    public Map<String, Object> createEmployee(OrganizationModels.CreateEmployee request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工账号只能由集团级管理员创建");
        UUID id = UUID.randomUUID();
        requireUniqueCode("employee", principal, id, request.employeeNo(), "员工编号已存在");
        UUID accountId = null;
        String loginName = trimToNull(request.loginName());
        String temporaryPassword = request.temporaryPassword();
        if (loginName != null) {
            requireUniqueLogin(principal, null, loginName);
            passwordHasher.requirePassword(temporaryPassword);
            accountId = UUID.randomUUID();
        } else if (!isBlank(temporaryPassword)) {
            throw new IllegalArgumentException("设置初始密码时必须同时填写登录账号");
        }
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("accountId", accountId)
                .addValue("employeeNo", request.employeeNo().trim())
                .addValue("name", request.name().trim())
                .addValue("mobile", request.mobile())
                .addValue("hiredOn", request.hiredOn())
                .addValue("loginName", loginName == null ? null : loginName.toLowerCase())
                .addValue("passwordHash", accountId == null ? null : passwordHasher.hash(temporaryPassword));
        if (accountId != null) {
            jdbc.update("""
                    insert into user_account
                        (id, tenant_id, login_name, display_name, mobile, password_hash, password_changed_at)
                    values
                        (:accountId, :tenantId, :loginName, :name, :mobile, :passwordHash, now())
                    """, parameters);
        }
        jdbc.update("""
                insert into employee (id, tenant_id, account_id, employee_no, name, mobile, hired_on)
                values (:id, :tenantId, :accountId, :employeeNo, :name, :mobile, :hiredOn)
                """, parameters);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("accountId", accountId);
        response.put("employeeNo", request.employeeNo());
        response.put("name", request.name());
        response.put("loginName", loginName);
        return response;
    }

    @Transactional
    public Map<String, Object> updateEmployee(UUID employeeId, OrganizationModels.UpdateEmployee request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工账号只能由集团级管理员维护");
        Map<String, Object> current = requireEmployee(principal, employeeId);
        requireUniqueCode("employee", principal, employeeId, request.employeeNo(), "员工编号已存在");
        String employmentStatus = lifecycleStatus(request.employmentStatus());
        UUID accountId = (UUID) current.get("account_id");
        String loginName = trimToNull(request.loginName());
        String temporaryPassword = request.temporaryPassword();
        if (loginName != null) {
            requireUniqueLogin(principal, accountId, loginName);
        }
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", employeeId)
                .addValue("employeeNo", request.employeeNo().trim())
                .addValue("name", request.name().trim())
                .addValue("mobile", trimToNull(request.mobile()))
                .addValue("hiredOn", request.hiredOn())
                .addValue("employmentStatus", employmentStatus);
        if (accountId == null && loginName != null) {
            passwordHasher.requirePassword(temporaryPassword);
            accountId = UUID.randomUUID();
            parameters.addValue("accountId", accountId)
                    .addValue("loginName", loginName.toLowerCase())
                    .addValue("passwordHash", passwordHasher.hash(temporaryPassword));
            jdbc.update("""
                    insert into user_account
                        (id, tenant_id, login_name, display_name, mobile, status, password_hash, password_changed_at)
                    values
                        (:accountId, :tenantId, :loginName, :name, :mobile, :employmentStatus,
                         :passwordHash, now())
                    """, parameters);
        } else if (accountId != null) {
            if (loginName == null) {
                throw new IllegalArgumentException("已开通账号的员工必须保留登录账号");
            }
            parameters.addValue("accountId", accountId).addValue("loginName", loginName.toLowerCase());
            if (!isBlank(temporaryPassword)) {
                passwordHasher.requirePassword(temporaryPassword);
                parameters.addValue("passwordHash", passwordHasher.hash(temporaryPassword));
                jdbc.update("""
                        update user_account
                        set login_name = :loginName, display_name = :name, mobile = :mobile,
                            status = :employmentStatus, password_hash = :passwordHash,
                            password_changed_at = now(), updated_at = now()
                        where tenant_id = :tenantId and id = :accountId
                        """, parameters);
            } else {
                jdbc.update("""
                        update user_account
                        set login_name = :loginName, display_name = :name, mobile = :mobile,
                            status = :employmentStatus, updated_at = now()
                        where tenant_id = :tenantId and id = :accountId
                        """, parameters);
            }
        } else if (!isBlank(temporaryPassword)) {
            throw new IllegalArgumentException("设置初始密码时必须同时填写登录账号");
        }
        parameters.addValue("accountId", accountId);
        jdbc.update("""
                update employee
                set account_id = :accountId, employee_no = :employeeNo, name = :name,
                    mobile = :mobile, hired_on = :hiredOn, employment_status = :employmentStatus,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id
                """, parameters);
        if ("INACTIVE".equals(employmentStatus)) {
            jdbc.update("""
                    update employee_position_assignment
                    set status = 'INACTIVE', valid_to = case
                        when valid_to is null or valid_to > current_date then current_date else valid_to end,
                        updated_at = now()
                    where tenant_id = :tenantId and employee_id = :id and status = 'ACTIVE'
                    """, parameters);
            if (accountId != null) {
                jdbc.update("""
                        update role_assignment
                        set valid_to = now()
                        where tenant_id = :tenantId and account_id = :accountId
                          and (valid_to is null or valid_to > now())
                        """, parameters);
            }
        }
        auditWriter.record("EMPLOYEE_UPDATED", "EMPLOYEE", employeeId,
                "{\"status\":\"" + employmentStatus + "\",\"employeeNo\":\""
                        + jsonEscape(request.employeeNo().trim()) + "\"}");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", employeeId);
        response.put("accountId", accountId);
        response.put("employeeNo", request.employeeNo().trim());
        response.put("name", request.name().trim());
        response.put("loginName", loginName);
        response.put("employmentStatus", employmentStatus);
        return response;
    }

    @Transactional
    public void deleteEmployee(UUID employeeId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工删除只能由集团级管理员执行");
        Map<String, Object> current = requireEmployee(principal, employeeId);
        if (!"INACTIVE".equals(String.valueOf(current.get("employment_status")))) {
            throw new IllegalArgumentException("请先停用员工，再执行删除");
        }
        softDeleteEmployee(principal, current);
    }

    @Transactional
    public Map<String, Object> deleteAllInactiveEmployees() {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "批量删除员工只能由集团级管理员执行");
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, employee_no, name, employment_status
                from employee
                where tenant_id = :tenantId and employment_status = 'INACTIVE'
                  and deleted_at is null
                order by id
                for update
                """, base(principal));
        for (Map<String, Object> row : rows) {
            softDeleteEmployee(principal, row);
        }
        auditWriter.record("INACTIVE_EMPLOYEES_BULK_DELETED", "TENANT", principal.tenantId(),
                "{\"status\":\"RECYCLE_BIN\",\"count\":" + rows.size() + "}");
        return Map.of("deletedCount", rows.size());
    }

    @Transactional
    public Map<String, Object> restoreEmployee(UUID employeeId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工恢复只能由集团级管理员执行");
        Map<String, Object> current = requireDeletedEmployee(principal, employeeId);
        if (current.get("permanently_deleted_at") != null) {
            throw new IllegalArgumentException("该员工已永久删除，无法恢复");
        }
        int restored = jdbc.update("""
                update employee
                set deleted_at = null, deleted_by = null, updated_at = now()
                where tenant_id = :tenantId and id = :id
                  and deleted_at is not null and permanently_deleted_at is null
                """, base(principal).addValue("id", employeeId));
        if (restored != 1) {
            throw new IllegalArgumentException("员工不在回收站或已被永久删除");
        }
        auditWriter.record("EMPLOYEE_RESTORED", "EMPLOYEE", employeeId,
                "{\"status\":\"INACTIVE\"}");
        return Map.of(
                "id", employeeId,
                "employeeNo", String.valueOf(current.get("employee_no")),
                "name", String.valueOf(current.get("name")),
                "employmentStatus", "INACTIVE"
        );
    }

    @Transactional
    public void permanentlyDeleteEmployee(UUID employeeId) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        requireTenantScope(principal, "员工永久删除只能由集团级管理员执行");
        Map<String, Object> current = requireDeletedEmployee(principal, employeeId);
        if (current.get("permanently_deleted_at") != null) {
            throw new IllegalArgumentException("该员工已永久删除");
        }
        UUID accountId = (UUID) current.get("account_id");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", employeeId)
                .addValue("accountId", accountId)
                .addValue("actorId", principal.actorId())
                .addValue("employeeTombstone", "PURGED-" + employeeId)
                .addValue("accountTombstone", accountId == null ? null : "purged-" + accountId);
        jdbc.update("""
                update employee
                set employee_no = :employeeTombstone, name = '已永久删除员工', mobile = null,
                    hired_on = null, employment_status = 'INACTIVE',
                    permanently_deleted_at = now(), permanently_deleted_by = :actorId,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id
                  and deleted_at is not null and permanently_deleted_at is null
                """, parameters);
        if (accountId != null) {
            jdbc.update("""
                    update user_account
                    set login_name = :accountTombstone, display_name = '已永久删除账号',
                        mobile = null, status = 'INACTIVE', password_hash = null,
                        password_changed_at = now(), failed_login_attempts = 0,
                        locked_until = null, last_login_at = null, updated_at = now()
                    where tenant_id = :tenantId and id = :accountId
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding
                    set wecom_user_id = 'purged-' || cast(id as text), status = 'REVOKED',
                        user_id_fingerprint = encode(digest(
                            corp_id || ':' || 'purged-' || cast(id as text), 'sha256'), 'hex'),
                        preferred_assignment_id = null, last_verified_at = null,
                        status_reason = 'ACCOUNT_PERMANENTLY_DELETED',
                        assignment_snapshot_hash = null, assignment_selection_required = false,
                        updated_by = :actorId, row_version = row_version + 1,
                        updated_at = now()
                    where tenant_id = :tenantId and account_id = :accountId
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding_request
                    set status = case
                            when status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                            then 'CANCELLED' else status end,
                        oauth_state_hash = null, browser_verifier_hash = null,
                        provider_code_hash = null, candidate_wecom_user_id = null,
                        candidate_fingerprint = null, conflicting_account_id = null,
                        failure_code = 'ACCOUNT_PERMANENTLY_DELETED',
                        decision_reason = '员工账号已永久删除', row_version = row_version + 1
                    where tenant_id = :tenantId and account_id = :accountId
                    """, parameters);
        }
        auditWriter.record("EMPLOYEE_PERMANENTLY_DELETED", "EMPLOYEE", employeeId,
                "{\"status\":\"PURGED\",\"identityErased\":true}");
    }

    private void softDeleteEmployee(TenantPrincipal principal, Map<String, Object> current) {
        UUID employeeId = (UUID) current.get("id");
        UUID accountId = (UUID) current.get("account_id");
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", employeeId)
                .addValue("accountId", accountId)
                .addValue("actorId", principal.actorId());
        int deleted = jdbc.update("""
                update employee
                set employment_status = 'INACTIVE', deleted_at = now(), deleted_by = :actorId,
                    updated_at = now()
                where tenant_id = :tenantId and id = :id and deleted_at is null
                """, parameters);
        if (deleted != 1) {
            throw new IllegalArgumentException("员工不存在、已删除或不属于当前租户");
        }
        jdbc.update("""
                update employee_position_assignment
                set status = 'INACTIVE', valid_to = case
                    when valid_to is null or valid_to > greatest(valid_from, current_date)
                    then greatest(valid_from, current_date) else valid_to end,
                    updated_at = now()
                where tenant_id = :tenantId and employee_id = :id and status = 'ACTIVE'
                """, parameters);
        if (accountId != null) {
            jdbc.update("""
                    update user_account
                    set status = 'INACTIVE', updated_at = now()
                    where tenant_id = :tenantId and id = :accountId
                    """, parameters);
            jdbc.update("""
                    update role_assignment
                    set valid_to = now()
                    where tenant_id = :tenantId and account_id = :accountId
                      and (valid_to is null or valid_to > now())
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding
                    set status = 'SUSPENDED', status_reason = 'ACCOUNT_OR_ASSIGNMENT_INACTIVE',
                        assignment_selection_required = false, updated_by = :actorId,
                        row_version = row_version + 1, updated_at = now()
                    where tenant_id = :tenantId and account_id = :accountId
                      and status <> 'REVOKED'
                    """, parameters);
            jdbc.update("""
                    update wecom_user_binding_request
                    set status = 'CANCELLED', oauth_state_hash = null,
                        browser_verifier_hash = null, provider_code_hash = null,
                        candidate_wecom_user_id = null, candidate_fingerprint = null,
                        conflicting_account_id = null, failure_code = 'ACCOUNT_DELETED',
                        decision_reason = '员工账号已移入回收站', row_version = row_version + 1
                    where tenant_id = :tenantId and account_id = :accountId
                      and status in ('WAITING_SCAN','AUTHORIZING','PENDING_APPROVAL','CONFLICT')
                    """, parameters);
        }
        auditWriter.record("EMPLOYEE_SOFT_DELETED", "EMPLOYEE", employeeId,
                "{\"status\":\"RECYCLE_BIN\"}");
    }

    @Transactional
    public Map<String, Object> assignPosition(UUID employeeId, OrganizationModels.CreatePositionAssignment request) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        if (request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new IllegalArgumentException("任职结束日期不能早于开始日期");
        }
        if (request.validTo() != null && request.validTo().isBefore(java.time.LocalDate.now())) {
            throw new IllegalArgumentException("不能创建已经结束的有效任职");
        }
        requireOrgType(principal, request.orgUnitId());
        PositionRoleGrant positionGrant = lockActivePublishedPosition(principal, request.positionId());
        requirePositionApplicableToOrganization(principal, request.positionId(), request.orgUnitId());
        accessPolicy.requireOrgScope(request.orgUnitId());
        Set<UUID> responsibleHotelIds = validateResponsibleHotels(
                principal, request.positionId(), positionGrant.scopeType(), request.responsibleHotelIds(),
                request.orgUnitId());
        UUID accountId = lockActiveEmployeeAccount(principal, employeeId);

        int previousPrimaryCount = 0;
        if (Boolean.TRUE.equals(request.primary())) {
            previousPrimaryCount = jdbc.update("""
                    update employee_position_assignment
                    set is_primary = false, updated_at = now()
                    where tenant_id = :tenantId and employee_id = :employeeId
                      and is_primary = true and status = 'ACTIVE'
                    """, base(principal).addValue("employeeId", employeeId));
        }

        UUID id = UUID.randomUUID();
        UUID roleAssignmentId = UUID.randomUUID();
        MapSqlParameterSource parameters = base(principal)
                .addValue("id", id)
                .addValue("employeeId", employeeId)
                .addValue("orgUnitId", request.orgUnitId())
                .addValue("positionId", request.positionId())
                .addValue("accountId", accountId)
                .addValue("roleAssignmentId", roleAssignmentId)
                .addValue("roleId", positionGrant.roleId())
                .addValue("scopeType", positionGrant.scopeType())
                .addValue("scopeOrgUnitId", Set.of("ORG_UNIT", "ORG_TREE").contains(positionGrant.scopeType())
                        ? request.orgUnitId() : null)
                .addValue("actorId", principal.actorId())
                .addValue("managerAssignmentId", request.managerAssignmentId())
                .addValue("primary", Boolean.TRUE.equals(request.primary()))
                .addValue("assignmentType", request.assignmentType() == null ? "PERMANENT" : request.assignmentType().toUpperCase())
                .addValue("validFrom", request.validFrom())
                .addValue("validTo", request.validTo());
        jdbc.update("""
                insert into employee_position_assignment
                    (id, tenant_id, employee_id, org_unit_id, position_id, manager_assignment_id,
                     is_primary, assignment_type, valid_from, valid_to)
                values
                    (:id, :tenantId, :employeeId, :orgUnitId, :positionId, :managerAssignmentId,
                     :primary, :assignmentType, :validFrom, :validTo)
                """, parameters);
        jdbc.update("""
                insert into role_assignment
                    (id, tenant_id, account_id, role_id, scope_org_unit_id, scope_type,
                     valid_from, valid_to, granted_by, source_type, source_assignment_id)
                values
                    (:roleAssignmentId, :tenantId, :accountId, :roleId, :scopeOrgUnitId, :scopeType,
                     greatest(now(), cast(:validFrom as date)::timestamptz),
                     case when cast(:validTo as date) is null then null
                          else (cast(:validTo as date) + interval '1 day')::timestamptz end,
                     :actorId, 'POSITION_ASSIGNMENT', :id)
                """, parameters);
        replaceAssignmentHotelScope(principal, id, responsibleHotelIds);
        if (previousPrimaryCount > 0) {
            auditWriter.record("POSITION_PRIMARY_ASSIGNMENT_SWITCHED", "EMPLOYEE", employeeId,
                    "{\"previousPrimaryCount\":" + previousPrimaryCount
                            + ",\"newAssignmentId\":\"" + id + "\"}");
        }
        return Map.of("id", id, "employeeId", employeeId, "orgUnitId", request.orgUnitId(),
                "positionId", request.positionId(), "roleAssignmentId", roleAssignmentId,
                "responsibleHotelIds", List.copyOf(responsibleHotelIds));
    }

    @Transactional
    public Map<String, Object> updateAssignmentHotelScope(
            UUID assignmentId,
            OrganizationModels.UpdateAssignmentHotelScope request
    ) {
        accessPolicy.requirePermission("org.manage");
        TenantPrincipal principal = prepare();
        List<Map<String, Object>> assignments = jdbc.queryForList("""
                select assignment.position_id, assignment.org_unit_id,
                       published.authorization_scope_type
                from employee_position_assignment assignment
                join position_definition position
                  on position.tenant_id = assignment.tenant_id
                 and position.id = assignment.position_id
                 and position.status = 'ACTIVE'
                 and position.deleted_at is null and position.permanently_deleted_at is null
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id
                 and profile.position_id = position.id and profile.scope_type = 'GROUP'
                join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id
                 and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                where assignment.tenant_id = :tenantId and assignment.id = :assignmentId
                  and assignment.status = 'ACTIVE'
                for update of assignment
                """, base(principal).addValue("assignmentId", assignmentId));
        if (assignments.size() != 1) {
            throw new IllegalArgumentException("任职不存在、已停用或岗位方案尚未发布");
        }
        Map<String, Object> assignment = assignments.getFirst();
        UUID orgUnitId = (UUID) assignment.get("org_unit_id");
        UUID positionId = (UUID) assignment.get("position_id");
        accessPolicy.requireOrgScope(orgUnitId);
        String scopeType = String.valueOf(assignment.get("authorization_scope_type"));
        Set<UUID> responsibleHotelIds = validateResponsibleHotels(
                principal, positionId, scopeType, request.responsibleHotelIds(), null);
        replaceAssignmentHotelScope(principal, assignmentId, responsibleHotelIds);
        auditWriter.record("POSITION_ASSIGNMENT_HOTEL_SCOPE_UPDATED", "POSITION_ASSIGNMENT", assignmentId,
                "{\"responsibleHotelCount\":" + responsibleHotelIds.size() + "}");
        return Map.of("assignmentId", assignmentId,
                "responsibleHotelIds", List.copyOf(responsibleHotelIds));
    }

    private TenantPrincipal prepare() {
        TenantPrincipal principal = accessPolicy.principal();
        databaseContext.apply(principal.tenantId());
        return principal;
    }

    private MapSqlParameterSource base(TenantPrincipal principal) {
        return new MapSqlParameterSource("tenantId", principal.tenantId());
    }

    private String visibility(String orgAlias, TenantPrincipal principal, MapSqlParameterSource parameters) {
        if (principal.hasTenantScope()) {
            return "";
        }
        parameters.addValue("scopeIds", principal.orgScopes());
        return " and exists (select 1 from org_unit_closure vis where vis.tenant_id = " + orgAlias
                + ".tenant_id and vis.descendant_id = " + orgAlias + ".id and vis.ancestor_id in (:scopeIds))";
    }

    private String requireOrgType(TenantPrincipal principal, UUID orgUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select unit_type, status from org_unit where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", orgUnitId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("组织不存在或不属于当前租户");
        }
        if (!"ACTIVE".equals(String.valueOf(rows.getFirst().get("status")))) {
            throw new IllegalArgumentException("组织已停用，不能继续配置任职或下级组织");
        }
        return String.valueOf(rows.getFirst().get("unit_type"));
    }

    private Map<String, Object> requireOrg(TenantPrincipal principal, UUID orgUnitId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, parent_id, code, name, unit_type, status, sort_order
                from org_unit where tenant_id = :tenantId and id = :id
                """, base(principal).addValue("id", orgUnitId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("组织不存在或不属于当前租户");
        }
        return rows.getFirst();
    }

    private Map<String, Object> requireEmployee(TenantPrincipal principal, UUID employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, employee_no, name, employment_status,
                       deleted_at, permanently_deleted_at
                from employee
                where tenant_id = :tenantId and id = :id and deleted_at is null
                """, base(principal).addValue("id", employeeId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("员工不存在或不属于当前租户");
        }
        return rows.getFirst();
    }

    private Map<String, Object> requireDeletedEmployee(TenantPrincipal principal, UUID employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, employee_no, name, employment_status,
                       deleted_at, permanently_deleted_at
                from employee
                where tenant_id = :tenantId and id = :id and deleted_at is not null
                for update
                """, base(principal).addValue("id", employeeId));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("员工不在已删除列表或不属于当前租户");
        }
        return rows.getFirst();
    }

    private void deactivateOrgTree(TenantPrincipal principal, UUID orgUnitId) {
        MapSqlParameterSource parameters = base(principal).addValue("id", orgUnitId);
        jdbc.update("""
                update org_unit o
                set status = 'INACTIVE', updated_at = now()
                where o.tenant_id = :tenantId and exists (
                    select 1 from org_unit_closure c
                    where c.tenant_id = o.tenant_id and c.ancestor_id = :id and c.descendant_id = o.id
                )
                """, parameters);
        jdbc.update("""
                update employee_position_assignment a
                set status = 'INACTIVE', valid_to = case
                    when valid_to is null or valid_to > current_date then current_date else valid_to end,
                    updated_at = now()
                where a.tenant_id = :tenantId and a.status = 'ACTIVE' and exists (
                    select 1 from org_unit_closure c
                    where c.tenant_id = a.tenant_id and c.ancestor_id = :id and c.descendant_id = a.org_unit_id
                )
                """, parameters);
        jdbc.update("""
                update role_assignment ra
                set valid_to = now()
                where ra.tenant_id = :tenantId and (ra.valid_to is null or ra.valid_to > now())
                  and ra.scope_org_unit_id is not null and exists (
                    select 1 from org_unit_closure c
                    where c.tenant_id = ra.tenant_id and c.ancestor_id = :id
                      and c.descendant_id = ra.scope_org_unit_id
                  )
                """, parameters);
    }

    private void requireEntity(String table, TenantPrincipal principal, UUID id) {
        if (!Set.of("employee", "position_definition").contains(table)) {
            throw new IllegalArgumentException("不允许的实体类型");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id",
                base(principal).addValue("id", id),
                Integer.class
        );
        if (count == null || count == 0) {
            throw new IllegalArgumentException("实体不存在或不属于当前租户");
        }
    }

    private void requireActiveEntity(
            String table,
            String statusColumn,
            TenantPrincipal principal,
            UUID id,
            String message
    ) {
        if (!("employee".equals(table) && "employment_status".equals(statusColumn))
                && !("position_definition".equals(table) && "status".equals(statusColumn))) {
            throw new IllegalArgumentException("不允许的实体状态检查");
        }
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id = :id and "
                        + statusColumn + " = 'ACTIVE'"
                        + ("employee".equals(table) ? " and deleted_at is null" : ""),
                base(principal).addValue("id", id), Integer.class);
        if (count == null || count != 1) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requirePositionApplicableToOrganization(
            TenantPrincipal principal,
            UUID positionId,
            UUID orgUnitId
    ) {
        Boolean applicable = jdbc.queryForObject("""
                select exists (
                    select 1
                    from position_definition position
                    left join lateral (
                        select ancestor.id
                        from org_unit_closure closure
                        join org_unit ancestor
                          on ancestor.tenant_id = closure.tenant_id
                         and ancestor.id = closure.ancestor_id
                        where closure.tenant_id = position.tenant_id
                          and closure.descendant_id = :orgUnitId
                          and ancestor.unit_type = 'HOTEL'
                          and ancestor.status = 'ACTIVE'
                        order by closure.depth
                        limit 1
                    ) hotel_context on true
                    where position.tenant_id = :tenantId
                      and position.id = :positionId
                      and position.status = 'ACTIVE'
                      and position.deleted_at is null
                      and position.permanently_deleted_at is null
                      and (
                        position.applies_to_all_hotels = true
                        or (
                          hotel_context.id is not null
                          and exists (
                            select 1
                            from position_applicable_hotel applicable_hotel
                            where applicable_hotel.tenant_id = position.tenant_id
                              and applicable_hotel.position_id = position.id
                              and applicable_hotel.hotel_org_unit_id = hotel_context.id
                          )
                        )
                      )
                )
                """, base(principal)
                .addValue("positionId", positionId)
                .addValue("orgUnitId", orgUnitId), Boolean.class);
        if (!Boolean.TRUE.equals(applicable)) {
            throw new IllegalArgumentException("该岗位不适用于任职所在门店，请先调整岗位适用门店");
        }
    }

    /**
     * Serializes assignment creation with applicability updates.  The caller
     * must validate applicability only after acquiring this row lock, so an
     * assignment cannot be inserted against a scope snapshot that has just
     * been narrowed by another transaction.
     */
    private PositionRoleGrant lockActivePublishedPosition(TenantPrincipal principal, UUID positionId) {
        List<PositionRoleGrant> rows = jdbc.query("""
                select position.id, profile.default_role_id, published.authorization_scope_type
                from position_definition position
                join position_function_profile profile
                  on profile.tenant_id = position.tenant_id
                 and profile.position_id = position.id and profile.scope_type = 'GROUP'
                join position_function_profile_version published
                  on published.tenant_id = profile.tenant_id
                 and published.profile_id = profile.id
                 and published.lifecycle_status = 'PUBLISHED'
                join app_role role
                  on role.tenant_id = profile.tenant_id and role.id = profile.default_role_id
                where position.tenant_id = :tenantId and position.id = :positionId
                  and position.status = 'ACTIVE'
                  and position.deleted_at is null and position.permanently_deleted_at is null
                for update of position
                """, base(principal).addValue("positionId", positionId),
                (rs, rowNum) -> new PositionRoleGrant(
                        rs.getObject("default_role_id", UUID.class),
                        rs.getString("authorization_scope_type")));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("岗位已停用或功能方案尚未发布，不能分配任职");
        }
        return rows.getFirst();
    }

    private UUID lockActiveEmployeeAccount(TenantPrincipal principal, UUID employeeId) {
        List<UUID> rows = jdbc.query("""
                select account_id
                from employee
                where tenant_id = :tenantId and id = :employeeId
                  and employment_status = 'ACTIVE' and deleted_at is null
                  and account_id is not null
                for update
                """, base(principal).addValue("employeeId", employeeId),
                (rs, rowNum) -> rs.getObject("account_id", UUID.class));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("员工已停用或尚未开通中台账号，不能分配任职");
        }
        return rows.getFirst();
    }

    private record PositionRoleGrant(UUID roleId, String scopeType) { }

    private Set<UUID> validateResponsibleHotels(
            TenantPrincipal principal,
            UUID positionId,
            String authorizationScopeType,
            List<UUID> requestedHotelIds,
            UUID defaultOrgUnitId
    ) {
        Set<UUID> hotelIds = requestedHotelIds == null
                ? new java.util.LinkedHashSet<>()
                : new java.util.LinkedHashSet<>(requestedHotelIds);
        if (!"ASSIGNED_HOTELS".equals(authorizationScopeType)) {
            if (!hotelIds.isEmpty()) {
                throw new IllegalArgumentException("仅“指定负责门店”数据范围可以配置负责门店");
            }
            return hotelIds;
        }
        if (requestedHotelIds == null && defaultOrgUnitId != null) {
            List<UUID> containingHotels = jdbc.queryForList("""
                    select ancestor.id
                    from org_unit_closure closure
                    join org_unit ancestor
                      on ancestor.tenant_id = closure.tenant_id
                     and ancestor.id = closure.ancestor_id
                     and ancestor.unit_type = 'HOTEL'
                     and ancestor.status = 'ACTIVE'
                    where closure.tenant_id = :tenantId
                      and closure.descendant_id = :orgUnitId
                    order by closure.depth
                    limit 1
                    """, base(principal).addValue("orgUnitId", defaultOrgUnitId), UUID.class);
            hotelIds.addAll(containingHotels);
        }
        if (hotelIds.isEmpty()) {
            throw new IllegalArgumentException("该岗位必须至少选择一家负责门店");
        }
        for (UUID hotelId : hotelIds) {
            if (!"HOTEL".equals(requireOrgType(principal, hotelId))) {
                throw new IllegalArgumentException("负责范围只能选择启用中的门店");
            }
            accessPolicy.requireOrgScope(hotelId);
            requirePositionApplicableToOrganization(principal, positionId, hotelId);
        }
        return hotelIds;
    }

    private void replaceAssignmentHotelScope(
            TenantPrincipal principal,
            UUID assignmentId,
            Set<UUID> hotelIds
    ) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("assignmentId", assignmentId)
                .addValue("actorId", principal.actorId());
        jdbc.update("""
                delete from employee_assignment_hotel_scope
                where tenant_id = :tenantId and assignment_id = :assignmentId
                """, parameters);
        for (UUID hotelId : hotelIds) {
            jdbc.update("""
                    insert into employee_assignment_hotel_scope
                        (tenant_id, assignment_id, hotel_org_unit_id, created_by)
                    values (:tenantId, :assignmentId, :hotelId, :actorId)
                    """, parameters.addValue("hotelId", hotelId));
        }
    }

    private void requireUniqueCode(
            String table,
            TenantPrincipal principal,
            UUID id,
            String code,
            String message
    ) {
        String column = switch (table) {
            case "org_unit", "position_definition" -> "code";
            case "employee" -> "employee_no";
            default -> throw new IllegalArgumentException("不允许的唯一编码检查");
        };
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = :tenantId and id <> :id and lower("
                        + column + ") = lower(:code)",
                base(principal).addValue("id", id).addValue("code", code.trim()), Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private void requireUniqueLogin(TenantPrincipal principal, UUID accountId, String loginName) {
        MapSqlParameterSource parameters = base(principal)
                .addValue("loginName", loginName.trim().toLowerCase());
        String exclusion = "";
        if (accountId != null) {
            parameters.addValue("accountId", accountId);
            exclusion = " and id <> :accountId";
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from user_account
                where tenant_id = :tenantId and lower(login_name) = :loginName
                """ + exclusion, parameters, Integer.class);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("登录账号已存在");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String lifecycleStatus(String value) {
        String normalized = normalize(value);
        if (!Set.of("ACTIVE", "INACTIVE").contains(normalized)) {
            throw new IllegalArgumentException("状态必须为ACTIVE或INACTIVE");
        }
        return normalized;
    }

    private void validateHierarchy(String unitType, UUID parentId, String parentType) {
        if ("GROUP".equals(unitType)) {
            if (parentId != null) {
                throw new IllegalArgumentException("集团不能设置上级组织");
            }
            return;
        }
        if (parentId == null) {
            throw new IllegalArgumentException("区域、门店和部门必须选择上级组织");
        }
        boolean valid = switch (unitType) {
            case "REGION" -> "GROUP".equals(parentType);
            case "HOTEL" -> Set.of("GROUP", "REGION").contains(parentType);
            case "DEPARTMENT" -> "HOTEL".equals(parentType);
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("组织层级不符合集团→区域→门店→部门模型");
        }
    }

    private void requireTenantScope(TenantPrincipal principal, String message) {
        if (!principal.hasTenantScope()) {
            throw new AccessDeniedException(message);
        }
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
