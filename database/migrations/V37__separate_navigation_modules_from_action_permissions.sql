-- Top-level navigation is a presentation capability, not an API authorization.
-- Keep module grants in the existing versioned position profile so they are
-- draftable, publishable and auditable together with a position, but give them
-- an isolated namespace/category. No backend endpoint authorizes on ui.module.*.

INSERT INTO permission (
    code, resource, action, description, delegable_to_position, function_category
) VALUES
    ('ui.module.workbench', 'ui_module', 'view', '角色工作台', true, 'UI_MODULE'),
    ('ui.module.hotel-dashboard', 'ui_module', 'view', '门店驾驶舱', true, 'UI_MODULE'),
    ('ui.module.operations-dashboard', 'ui_module', 'view', '区域多门店', true, 'UI_MODULE'),
    ('ui.module.investments', 'ui_module', 'view', '投资测算', true, 'UI_MODULE'),
    ('ui.module.work-packages', 'ui_module', 'view', '工作包中心', true, 'UI_MODULE'),
    ('ui.module.my-work', 'ui_module', 'view', '我的工作', true, 'UI_MODULE'),
    ('ui.module.team-work', 'ui_module', 'view', '团队工作', true, 'UI_MODULE'),
    ('ui.module.daily-reports-my', 'ui_module', 'view', '日报中心', true, 'UI_MODULE'),
    ('ui.module.daily-report-templates', 'ui_module', 'view', '日报模板中心', true, 'UI_MODULE'),
    ('ui.module.daily-operations', 'ui_module', 'view', '日运营中心', true, 'UI_MODULE'),
    ('ui.module.kpi-center', 'ui_module', 'view', 'KPI绩效中心', true, 'UI_MODULE'),
    ('ui.module.rules', 'ui_module', 'view', '企业规则中心', true, 'UI_MODULE'),
    ('ui.module.tasks', 'ui_module', 'view', '任务中心', true, 'UI_MODULE'),
    ('ui.module.evaluations', 'ui_module', 'view', '标准评价', true, 'UI_MODULE'),
    ('ui.module.notifications', 'ui_module', 'view', '通知中心', true, 'UI_MODULE'),
    ('ui.module.templates', 'ui_module', 'view', '集团模板配置', true, 'UI_MODULE'),
    ('ui.module.organization', 'ui_module', 'view', '组织与权限', true, 'UI_MODULE'),
    ('ui.module.wecom-webhooks', 'ui_module', 'view', '企业微信 Webhook', true, 'UI_MODULE'),
    ('ui.module.wecom-onboarding', 'ui_module', 'view', '企业微信入职审核', true, 'UI_MODULE'),
    ('ui.module.wecom-bindings', 'ui_module', 'view', '企微绑定与异常', true, 'UI_MODULE'),
    ('ui.module.all-functions', 'ui_module', 'view', '全部功能与我的', true, 'UI_MODULE')
ON CONFLICT (code) DO UPDATE
SET resource = EXCLUDED.resource,
    action = EXCLUDED.action,
    description = EXCLUDED.description,
    delegable_to_position = true,
    function_category = 'UI_MODULE';

CREATE TEMP TABLE v37_role_module_matrix (
    role_code VARCHAR(64) NOT NULL,
    module_id VARCHAR(120) NOT NULL,
    PRIMARY KEY (role_code, module_id)
) ON COMMIT DROP;

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT role_code, module_id
FROM unnest(ARRAY['FRONT_DESK', 'HOUSEKEEPING_ATTENDANT']) AS roles(role_code)
CROSS JOIN unnest(ARRAY[
    'workbench', 'my-work', 'tasks', 'daily-reports-my', 'evaluations',
    'kpi-center', 'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'OTA_OPERATION_ASSISTANT', module_id
FROM unnest(ARRAY[
    'workbench', 'my-work', 'tasks', 'daily-reports-my', 'kpi-center',
    'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT role_code, module_id
FROM unnest(ARRAY['FRONT_OFFICE_SUPERVISOR', 'HOUSEKEEPING_SUPERVISOR']) AS roles(role_code)
CROSS JOIN unnest(ARRAY[
    'workbench', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
    'daily-operations', 'evaluations', 'kpi-center', 'notifications',
    'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'ASSISTANT_GENERAL_MANAGER', module_id
FROM unnest(ARRAY[
    'workbench', 'hotel-dashboard', 'team-work', 'tasks', 'daily-reports-my',
    'daily-report-templates', 'daily-operations', 'evaluations', 'kpi-center',
    'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'GENERAL_MANAGER', module_id
FROM unnest(ARRAY[
    'workbench', 'hotel-dashboard', 'team-work', 'tasks', 'daily-reports-my',
    'daily-operations', 'kpi-center', 'rules', 'evaluations', 'notifications',
    'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'OTA_OPERATION_MANAGER', module_id
FROM unnest(ARRAY[
    'workbench', 'operations-dashboard', 'tasks', 'daily-reports-my',
    'daily-operations', 'kpi-center', 'rules', 'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'HR_KPI_ADMIN', module_id
FROM unnest(ARRAY[
    'workbench', 'tasks', 'organization', 'wecom-bindings', 'wecom-onboarding',
    'kpi-center', 'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'GROUP_VICE_PRESIDENT', module_id
FROM unnest(ARRAY[
    'workbench', 'operations-dashboard', 'tasks', 'daily-reports-my',
    'daily-operations', 'kpi-center', 'work-packages', 'rules',
    'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'CEO', module_id
FROM unnest(ARRAY[
    'workbench', 'hotel-dashboard', 'operations-dashboard', 'investments',
    'work-packages', 'team-work', 'tasks', 'daily-reports-my',
    'daily-report-templates', 'daily-operations', 'kpi-center', 'rules',
    'evaluations', 'templates', 'organization', 'wecom-bindings',
    'wecom-onboarding', 'notifications', 'all-functions'
]) AS modules(module_id);

INSERT INTO v37_role_module_matrix (role_code, module_id)
SELECT 'PLATFORM_ADMIN', module_id
FROM unnest(ARRAY[
    'workbench', 'hotel-dashboard', 'operations-dashboard', 'investments',
    'work-packages', 'my-work', 'team-work', 'tasks', 'daily-reports-my',
    'daily-report-templates', 'daily-operations', 'kpi-center', 'rules',
    'evaluations', 'templates', 'organization', 'wecom-webhooks',
    'wecom-bindings', 'wecom-onboarding', 'notifications', 'all-functions'
]) AS modules(module_id);

-- V35/V36 made a versioned position profile authoritative for assignment
-- identities. Their standard manager matrix accidentally omitted part of the
-- already-released V18 manager workflow, so buttons backed by those APIs began
-- returning 403 even though the legacy role grant still described the role as
-- a manager. Restore that frozen V18 subset only for system-published default
-- profiles below; human-managed profiles and hotel overrides remain untouched.
CREATE TEMP TABLE v37_role_action_repair_matrix (
    role_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (role_code, permission_code)
) ON COMMIT DROP;

INSERT INTO v37_role_action_repair_matrix (role_code, permission_code)
SELECT role_code, permission_code
FROM unnest(ARRAY[
    'HOUSEKEEPING_SUPERVISOR', 'FRONT_OFFICE_SUPERVISOR',
    'ASSISTANT_GENERAL_MANAGER', 'GENERAL_MANAGER', 'OTA_OPERATION_MANAGER'
]) AS roles(role_code)
CROSS JOIN unnest(ARRAY[
    'daily-report.read', 'daily-report.submit', 'daily-report.team-read',
    'daily-report.review', 'daily-report.revision-review',
    'daily-report-template.read', 'daily-report-template.store-supplement',
    'daily-operation.read', 'issue.confirm', 'issue.assign', 'issue.close',
    'issue.reopen', 'task-candidate.read', 'task-candidate.manage',
    'task-candidate.confirm', 'task-candidate.reject', 'task-candidate.retry',
    'operation-snapshot.read', 'operation-snapshot.retry',
    'operation-snapshot.compare', 'operation-export.create',
    'operation-export.download', 'ai-recommendation.read',
    'ai-recommendation.feedback', 'ai-recommendation.adopt'
]) AS permissions(permission_code);

-- One-time compatibility inference for custom positions. From this migration
-- onward administrators select module grants explicitly in the position editor.
CREATE TEMP TABLE v37_module_action_matrix (
    module_id VARCHAR(120) NOT NULL,
    permission_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (module_id, permission_code)
) ON COMMIT DROP;

INSERT INTO v37_module_action_matrix (module_id, permission_code) VALUES
    ('hotel-dashboard', 'dashboard.hotel'),
    ('operations-dashboard', 'dashboard.operations'),
    ('investments', 'investment.read'),
    ('work-packages', 'work-package.read'),
    ('work-packages', 'work-package.manage'),
    ('work-packages', 'standard.read'),
    ('my-work', 'work-record.read'),
    ('my-work', 'work-record.submit'),
    ('my-work', 'work.submit'),
    ('team-work', 'work-record.review'),
    ('daily-reports-my', 'daily-report.read'),
    ('daily-reports-my', 'daily-report.submit'),
    ('daily-reports-my', 'daily-report.team-read'),
    ('daily-report-templates', 'daily-report-template.read'),
    ('daily-report-templates', 'daily-report-template.manage'),
    ('daily-report-templates', 'daily-report-template.publish'),
    ('daily-operations', 'daily-operation.read'),
    ('daily-operations', 'daily-operation.cross-hotel-read'),
    ('kpi-center', 'kpi.scorecard.read-own'),
    ('kpi-center', 'kpi.scorecard.read-team'),
    ('kpi-center', 'kpi.scorecard.read-all'),
    ('kpi-center', 'kpi.template.read'),
    ('rules', 'rule.read'),
    ('rules', 'rule.manage'),
    ('tasks', 'task.read'),
    ('tasks', 'task.act'),
    ('tasks', 'task.review'),
    ('evaluations', 'evaluation.read'),
    ('evaluations', 'evaluation.manual-review'),
    ('notifications', 'notification.read'),
    ('templates', 'template.manage'),
    ('organization', 'org.manage'),
    ('organization', 'position-profile.read'),
    ('wecom-webhooks', 'org.manage'),
    ('wecom-onboarding', 'wecom-binding.read'),
    ('wecom-bindings', 'wecom-binding.read');

DO $$
DECLARE
    missing_modules TEXT;
BEGIN
    SELECT string_agg(DISTINCT matrix.module_id, ', ' ORDER BY matrix.module_id)
      INTO missing_modules
      FROM v37_role_module_matrix matrix
      LEFT JOIN permission module_permission
        ON module_permission.code = 'ui.module.' || matrix.module_id
       AND module_permission.function_category = 'UI_MODULE'
     WHERE module_permission.id IS NULL;
    IF missing_modules IS NOT NULL THEN
        RAISE EXCEPTION 'V37 role matrix contains unknown modules: %', missing_modules;
    END IF;
END $$;

DO $$
DECLARE
    invalid_permissions TEXT;
BEGIN
    SELECT string_agg(matrix.permission_code, ', ' ORDER BY matrix.permission_code)
      INTO invalid_permissions
      FROM (
          SELECT DISTINCT permission_code
          FROM v37_role_action_repair_matrix
      ) matrix
      LEFT JOIN permission permission_item
        ON permission_item.code = matrix.permission_code
       AND permission_item.delegable_to_position = true
     WHERE permission_item.id IS NULL;
    IF invalid_permissions IS NOT NULL THEN
        RAISE EXCEPTION 'V37 action repair contains unknown or protected permissions: %',
            invalid_permissions;
    END IF;
END $$;

-- Match V36's FORCE-RLS-safe tenant discovery. Transactional DDL restores FORCE
-- automatically if any tenant migration fails.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        -- Repair only the immutable V1 publication and untouched V2 draft
        -- created by the V35/V36 automatic default publication. The audit and
        -- version-shape predicates deliberately exclude every human-managed
        -- profile and every hotel override.
        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, permission_item.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
        JOIN v37_role_action_repair_matrix repair
          ON repair.role_code = position_item.code
        JOIN permission permission_item
          ON permission_item.code = repair.permission_code
         AND permission_item.delegable_to_position = true
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'GROUP'
          AND (
              (version.version_no = 1 AND version.lifecycle_status = 'PUBLISHED')
              OR (
                  version.version_no = 2
                  AND version.lifecycle_status = 'DRAFT'
                  AND version.row_version = 0
                  AND version.copied_from_version_id IS NOT NULL
              )
          )
          AND EXISTS (
              SELECT 1
              FROM audit_log audit
              WHERE audit.tenant_id = profile.tenant_id
                AND audit.action = 'SYSTEM_POSITION_PROFILE_DEFAULT_PUBLISHED'
                AND audit.resource_type = 'POSITION_FUNCTION_PROFILE'
                AND audit.resource_id = profile.id
                AND audit.after_data ->> 'category' = 'SYSTEM'
                AND audit.after_data ->> 'source' IN ('V35', 'V36')
                AND audit.after_data ->> 'profileId' = profile.id::text
                AND audit.after_data ->> 'positionId' = position_item.id::text
          )
        ON CONFLICT DO NOTHING;

        -- Frozen standard positions get the reviewed role-specific module set
        -- on both active draft and published versions, including hotel overrides.
        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, module_permission.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status IN ('DRAFT', 'PUBLISHED')
        JOIN v37_role_module_matrix matrix
          ON matrix.role_code = position_item.code
        JOIN permission module_permission
          ON module_permission.code = 'ui.module.' || matrix.module_id
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'GROUP'
        ON CONFLICT DO NOTHING;

        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, module_permission.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status IN ('DRAFT', 'PUBLISHED')
        JOIN v37_role_module_matrix matrix
          ON matrix.role_code = position_item.code
        JOIN permission module_permission
          ON module_permission.code = 'ui.module.' || matrix.module_id
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'HOTEL'
        ON CONFLICT DO NOTHING;

        -- Existing custom positions retain their previous effective navigation
        -- once, based on action permissions, then become explicitly configurable.
        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT DISTINCT tenant_record.id, version.id, module_permission.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status IN ('DRAFT', 'PUBLISHED')
        JOIN position_function_profile_permission existing_grant
          ON existing_grant.tenant_id = version.tenant_id
         AND existing_grant.profile_version_id = version.id
        JOIN permission action_permission
          ON action_permission.id = existing_grant.permission_id
        JOIN v37_module_action_matrix mapping
          ON mapping.permission_code = action_permission.code
        JOIN permission module_permission
          ON module_permission.code = 'ui.module.' || mapping.module_id
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'GROUP'
          AND NOT EXISTS (
              SELECT 1 FROM v37_role_module_matrix known
              WHERE known.role_code = position_item.code
          )
        ON CONFLICT DO NOTHING;

        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT DISTINCT tenant_record.id, version.id, module_permission.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status IN ('DRAFT', 'PUBLISHED')
        JOIN position_function_profile_permission existing_grant
          ON existing_grant.tenant_id = version.tenant_id
         AND existing_grant.profile_version_id = version.id
        JOIN permission action_permission
          ON action_permission.id = existing_grant.permission_id
        JOIN v37_module_action_matrix mapping
          ON mapping.permission_code = action_permission.code
        JOIN permission module_permission
          ON module_permission.code = 'ui.module.' || mapping.module_id
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'HOTEL'
          AND NOT EXISTS (
              SELECT 1 FROM v37_role_module_matrix known
              WHERE known.role_code = position_item.code
          )
        ON CONFLICT DO NOTHING;

        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, module_permission.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status IN ('DRAFT', 'PUBLISHED')
        CROSS JOIN permission module_permission
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'GROUP'
          AND module_permission.code IN ('ui.module.workbench', 'ui.module.all-functions')
          AND NOT EXISTS (
              SELECT 1 FROM v37_role_module_matrix known
              WHERE known.role_code = position_item.code
          )
        ON CONFLICT DO NOTHING;

        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, module_permission.id
        FROM position_function_profile profile
        JOIN position_definition position_item
          ON position_item.tenant_id = profile.tenant_id
         AND position_item.id = profile.position_id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status IN ('DRAFT', 'PUBLISHED')
        CROSS JOIN permission module_permission
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'HOTEL'
          AND module_permission.code IN ('ui.module.workbench', 'ui.module.all-functions')
          AND NOT EXISTS (
              SELECT 1 FROM v37_role_module_matrix known
              WHERE known.role_code = position_item.code
          )
        ON CONFLICT DO NOTHING;

        -- Account-level standard roles (CEO/platform/HR supplements) and legacy
        -- role-based readers receive the same presentation grants.
        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, module_permission.id
        FROM app_role role
        JOIN v37_role_module_matrix matrix ON matrix.role_code = role.code
        JOIN permission module_permission
          ON module_permission.code = 'ui.module.' || matrix.module_id
        WHERE role.tenant_id = tenant_record.id
        ON CONFLICT DO NOTHING;

        -- Restore the same released manager workflow for legacy/account-wide
        -- role resolution. Assignment identities still use the selected
        -- published position profile as their authoritative action grant.
        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM app_role role
        JOIN v37_role_action_repair_matrix repair ON repair.role_code = role.code
        JOIN permission permission_item
          ON permission_item.code = repair.permission_code
         AND permission_item.delegable_to_position = true
        WHERE role.tenant_id = tenant_record.id
        ON CONFLICT DO NOTHING;

        -- Keep custom group roles aligned with their current published profile.
        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, profile.default_role_id, profile_grant.permission_id
        FROM position_function_profile profile
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status = 'PUBLISHED'
        JOIN position_function_profile_permission profile_grant
          ON profile_grant.tenant_id = version.tenant_id
         AND profile_grant.profile_version_id = version.id
        JOIN permission module_permission
          ON module_permission.id = profile_grant.permission_id
         AND module_permission.function_category = 'UI_MODULE'
        WHERE profile.tenant_id = tenant_record.id
          AND profile.scope_type = 'GROUP'
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
