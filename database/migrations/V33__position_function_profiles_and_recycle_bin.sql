-- Position governance P1:
-- * user-facing positions have a name, hotel applicability and a function profile;
-- * internal legacy classification fields remain implementation details;
-- * deletion is recoverable once, then becomes an irreversible historical tombstone;
-- * hotel profiles can only reduce the current group-standard permission set.

ALTER TABLE position_definition
    ADD COLUMN applies_to_all_hotels BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID,
    ADD COLUMN permanently_deleted_at TIMESTAMPTZ,
    ADD COLUMN permanently_deleted_by UUID,
    ADD COLUMN deletion_batch_id UUID,
    ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_position_deleted_by
        FOREIGN KEY (tenant_id, deleted_by) REFERENCES user_account (tenant_id, id),
    ADD CONSTRAINT fk_position_permanently_deleted_by
        FOREIGN KEY (tenant_id, permanently_deleted_by) REFERENCES user_account (tenant_id, id),
    ADD CONSTRAINT ck_position_deletion_lifecycle
        CHECK (permanently_deleted_at IS NULL OR deleted_at IS NOT NULL),
    ADD CONSTRAINT ck_position_deletion_actor
        CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)),
    ADD CONSTRAINT ck_position_permanent_deletion_actor
        CHECK ((permanently_deleted_at IS NULL) = (permanently_deleted_by IS NULL)),
    ADD CONSTRAINT ck_position_deletion_batch
        CHECK ((deleted_at IS NULL) = (deletion_batch_id IS NULL));

ALTER TABLE permission
    ADD COLUMN delegable_to_position BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN function_category VARCHAR(64) NOT NULL DEFAULT 'GENERAL';

-- Fail closed: only this reviewed business-function allowlist is configurable.
-- A future permission stays non-delegable until a later migration explicitly opts it in.
UPDATE permission
SET delegable_to_position = true
WHERE code IN (
    'org.read', 'standard.read', 'work.submit', 'metric.record',
    'dashboard.hotel',
    'work-package.read', 'work-package.manage', 'work-package.publish', 'work-package.allocate',
    'work-record.read', 'work-record.submit', 'work-record.review', 'work-record.submit-for-other',
    'rule.read', 'rule.manage', 'rule.simulate', 'rule.publish',
    'task.read', 'task.create', 'task.dispatch', 'task.act', 'task.review', 'task.cancel',
    'evaluation.read', 'evaluation.manual-review', 'notification.read',
    'template.read', 'template.manage', 'template.publish',
    'daily-report.read', 'daily-report.submit', 'daily-report.team-read',
    'daily-report.review', 'daily-report.revision-review',
    'daily-report-template.read', 'daily-report-template.manage',
    'daily-report-template.review', 'daily-report-template.publish',
    'daily-report-template.store-supplement',
    'daily-operation.read', 'daily-operation.cross-hotel-read',
    'issue.confirm', 'issue.assign', 'issue.close', 'issue.reopen',
    'task-candidate.read', 'task-candidate.manage', 'task-candidate.confirm',
    'task-candidate.reject', 'task-candidate.retry',
    'operation-snapshot.read', 'operation-snapshot.retry', 'operation-snapshot.compare',
    'operation-export.create', 'operation-export.download',
    'ai-recommendation.read', 'ai-recommendation.feedback', 'ai-recommendation.adopt',
    'kpi.metric.read', 'kpi.metric.manage', 'kpi.template.read', 'kpi.template.manage',
    'kpi.template.review', 'kpi.template.publish', 'kpi.template.import',
    'kpi.relation.manage', 'kpi.scorecard.read-own', 'kpi.scorecard.read-team',
    'kpi.scorecard.generate', 'kpi.scorecard.manual-score', 'kpi.scorecard.review',
    'kpi.scorecard.dispute', 'kpi.scorecard.lock', 'kpi.settlement.read',
    'kpi.inspection.submit', 'kpi.inspection.read-team', 'kpi.inspection.verify'
);

-- Technical administration, tenant-wide executive access, sensitive evidence,
-- credentials and financial policy configuration remain protected.
UPDATE permission
SET delegable_to_position = false,
    function_category = 'PROTECTED'
WHERE delegable_to_position = false;

UPDATE permission
SET function_category = CASE
    WHEN code LIKE 'dashboard.%' THEN 'DASHBOARD'
    WHEN code LIKE 'task.%' OR code LIKE 'task-candidate.%' THEN 'TASK'
    WHEN code LIKE 'daily-report.%' OR code LIKE 'daily-operation.%' THEN 'DAILY'
    WHEN code LIKE 'kpi.%' THEN 'KPI'
    WHEN code LIKE 'investment.%' THEN 'INVESTMENT'
    WHEN code LIKE 'wecom-binding.%' THEN 'WECOM'
    WHEN code LIKE 'notification.%' THEN 'NOTIFICATION'
    WHEN code LIKE 'work-%' OR code LIKE 'work.%' THEN 'WORK'
    WHEN delegable_to_position = false THEN 'PROTECTED'
    ELSE function_category
END;

CREATE TABLE position_applicable_hotel (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    position_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, position_id, hotel_org_unit_id),
    FOREIGN KEY (tenant_id, position_id)
        REFERENCES position_definition (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, hotel_org_unit_id)
        REFERENCES org_unit (tenant_id, id)
);

CREATE TABLE position_function_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    position_id UUID NOT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GROUP'
        CHECK (scope_type IN ('GROUP', 'HOTEL')),
    hotel_org_unit_id UUID,
    default_role_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, position_id)
        REFERENCES position_definition (tenant_id, id),
    FOREIGN KEY (tenant_id, hotel_org_unit_id)
        REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, default_role_id)
        REFERENCES app_role (tenant_id, id),
    CHECK (
        (scope_type = 'GROUP' AND hotel_org_unit_id IS NULL AND default_role_id IS NOT NULL)
        OR (scope_type = 'HOTEL' AND hotel_org_unit_id IS NOT NULL AND default_role_id IS NULL)
    )
);

CREATE UNIQUE INDEX ux_position_profile_group
    ON position_function_profile (tenant_id, position_id)
    WHERE scope_type = 'GROUP';

CREATE UNIQUE INDEX ux_position_profile_group_role
    ON position_function_profile (tenant_id, default_role_id)
    WHERE scope_type = 'GROUP';

CREATE UNIQUE INDEX ux_position_profile_hotel
    ON position_function_profile (tenant_id, position_id, hotel_org_unit_id)
    WHERE scope_type = 'HOTEL';

CREATE TABLE position_function_profile_version (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    profile_id UUID NOT NULL,
    version_no INTEGER NOT NULL CHECK (version_no > 0),
    lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (lifecycle_status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    copied_from_version_id UUID,
    based_on_group_version_id UUID,
    authorization_scope_type VARCHAR(24) NOT NULL DEFAULT 'SELF'
        CHECK (authorization_scope_type IN ('SELF', 'ORG_UNIT', 'ORG_TREE', 'TENANT')),
    wecom_self_selectable BOOLEAN NOT NULL DEFAULT false,
    created_by UUID,
    published_by UUID,
    published_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, profile_id, version_no),
    FOREIGN KEY (tenant_id, profile_id)
        REFERENCES position_function_profile (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, copied_from_version_id)
        REFERENCES position_function_profile_version (tenant_id, id),
    FOREIGN KEY (tenant_id, based_on_group_version_id)
        REFERENCES position_function_profile_version (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, published_by) REFERENCES user_account (tenant_id, id),
    CHECK (
        (lifecycle_status = 'PUBLISHED' AND published_at IS NOT NULL AND published_by IS NOT NULL)
        OR lifecycle_status <> 'PUBLISHED'
    )
);

CREATE UNIQUE INDEX ux_position_profile_single_draft
    ON position_function_profile_version (tenant_id, profile_id)
    WHERE lifecycle_status = 'DRAFT';

CREATE UNIQUE INDEX ux_position_profile_single_published
    ON position_function_profile_version (tenant_id, profile_id)
    WHERE lifecycle_status = 'PUBLISHED';

CREATE INDEX ix_position_profile_version_history
    ON position_function_profile_version (tenant_id, profile_id, version_no DESC);

CREATE TABLE position_function_profile_permission (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    profile_version_id UUID NOT NULL,
    permission_id UUID NOT NULL REFERENCES permission(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, profile_version_id, permission_id),
    FOREIGN KEY (tenant_id, profile_version_id)
        REFERENCES position_function_profile_version (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_position_profile_permission_permission
    ON position_function_profile_permission (permission_id, tenant_id, profile_version_id);

CREATE TABLE position_assignment_recycle_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    position_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    deletion_batch_id UUID NOT NULL,
    previous_status VARCHAR(24) NOT NULL,
    previous_valid_to DATE,
    previous_is_primary BOOLEAN NOT NULL,
    restored_at TIMESTAMPTZ,
    restore_outcome VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, deletion_batch_id, assignment_id),
    FOREIGN KEY (tenant_id, position_id)
        REFERENCES position_definition (tenant_id, id),
    FOREIGN KEY (tenant_id, assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    CHECK ((restored_at IS NULL) = (restore_outcome IS NULL))
);

CREATE INDEX ix_position_assignment_recycle_batch
    ON position_assignment_recycle_snapshot
       (tenant_id, position_id, deletion_batch_id, restored_at);

CREATE INDEX ix_position_recycle_bin
    ON position_definition (tenant_id, deleted_at DESC, id)
    WHERE deleted_at IS NOT NULL AND permanently_deleted_at IS NULL;

CREATE INDEX ix_position_visible
    ON position_definition (tenant_id, status, name, id)
    WHERE deleted_at IS NULL AND permanently_deleted_at IS NULL;

CREATE INDEX ix_position_applicable_hotel_reverse
    ON position_applicable_hotel (tenant_id, hotel_org_unit_id, position_id);

CREATE OR REPLACE FUNCTION validate_position_profile_version_context()
RETURNS trigger AS $$
DECLARE
    current_scope VARCHAR(16);
    current_position UUID;
    base_scope VARCHAR(16);
    base_position UUID;
    base_lifecycle VARCHAR(16);
    base_authorization_scope VARCHAR(24);
    base_wecom_self_selectable BOOLEAN;
    mapped_role UUID;
BEGIN
    SELECT profile.scope_type, profile.position_id,
           group_profile.default_role_id
      INTO current_scope, current_position, mapped_role
      FROM position_function_profile profile
      LEFT JOIN position_function_profile group_profile
        ON group_profile.tenant_id = profile.tenant_id
       AND group_profile.position_id = profile.position_id
       AND group_profile.scope_type = 'GROUP'
     WHERE profile.tenant_id = NEW.tenant_id AND profile.id = NEW.profile_id;

    IF current_scope = 'GROUP' AND NEW.based_on_group_version_id IS NOT NULL THEN
        RAISE EXCEPTION 'Group position profile versions cannot reference a group baseline';
    END IF;

    IF current_scope = 'HOTEL' THEN
        IF NEW.based_on_group_version_id IS NULL THEN
            RAISE EXCEPTION 'Hotel position profile versions require a published group baseline';
        END IF;
        SELECT base_profile.scope_type, base_profile.position_id, base_version.lifecycle_status,
               base_version.authorization_scope_type, base_version.wecom_self_selectable
          INTO base_scope, base_position, base_lifecycle,
               base_authorization_scope, base_wecom_self_selectable
          FROM position_function_profile_version base_version
          JOIN position_function_profile base_profile
            ON base_profile.tenant_id = base_version.tenant_id
           AND base_profile.id = base_version.profile_id
         WHERE base_version.tenant_id = NEW.tenant_id
           AND base_version.id = NEW.based_on_group_version_id;
        IF base_scope IS DISTINCT FROM 'GROUP'
           OR base_position IS DISTINCT FROM current_position
           OR base_lifecycle IS DISTINCT FROM 'PUBLISHED' THEN
            RAISE EXCEPTION 'Hotel position profile baseline must be the published group profile for the same position';
        END IF;
        IF (CASE NEW.authorization_scope_type
               WHEN 'SELF' THEN 0 WHEN 'ORG_UNIT' THEN 1 WHEN 'ORG_TREE' THEN 2 ELSE 3
           END) > (CASE base_authorization_scope
               WHEN 'SELF' THEN 0 WHEN 'ORG_UNIT' THEN 1 WHEN 'ORG_TREE' THEN 2 ELSE 3
           END) THEN
            RAISE EXCEPTION 'Hotel position profile cannot expand the group authorization scope';
        END IF;
        IF NEW.wecom_self_selectable AND NOT base_wecom_self_selectable THEN
            RAISE EXCEPTION 'Hotel position profile cannot enable WeCom self-selection beyond the group baseline';
        END IF;
    END IF;

    IF NEW.wecom_self_selectable AND EXISTS (
        SELECT 1
          FROM role_permission role_grant
          JOIN permission protected_permission
            ON protected_permission.id = role_grant.permission_id
         WHERE role_grant.tenant_id = NEW.tenant_id
           AND role_grant.role_id = mapped_role
           AND protected_permission.delegable_to_position = false
    ) THEN
        RAISE EXCEPTION 'Protected positions cannot be selected through WeCom self-enrollment';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_position_profile_version_context
    BEFORE INSERT OR UPDATE OF
        profile_id, based_on_group_version_id,
        authorization_scope_type, wecom_self_selectable
    ON position_function_profile_version
    FOR EACH ROW EXECUTE FUNCTION validate_position_profile_version_context();

CREATE OR REPLACE FUNCTION enforce_position_profile_permission_boundary()
RETURNS trigger AS $$
DECLARE
    is_delegable BOOLEAN;
    current_scope VARCHAR(16);
    group_baseline UUID;
BEGIN
    SELECT delegable_to_position
      INTO is_delegable
      FROM permission
     WHERE id = NEW.permission_id;
    IF is_delegable IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Protected permission cannot be delegated through a position profile';
    END IF;

    SELECT profile.scope_type, version.based_on_group_version_id
      INTO current_scope, group_baseline
      FROM position_function_profile_version version
      JOIN position_function_profile profile
        ON profile.tenant_id = version.tenant_id AND profile.id = version.profile_id
     WHERE version.tenant_id = NEW.tenant_id
       AND version.id = NEW.profile_version_id;

    IF current_scope = 'HOTEL' AND NOT EXISTS (
        SELECT 1
          FROM position_function_profile_permission allowed
         WHERE allowed.tenant_id = NEW.tenant_id
           AND allowed.profile_version_id = group_baseline
           AND allowed.permission_id = NEW.permission_id
    ) THEN
        RAISE EXCEPTION 'Hotel position profile cannot exceed the group-standard permission set';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_position_profile_permission_boundary
    BEFORE INSERT OR UPDATE OF profile_version_id, permission_id
    ON position_function_profile_permission
    FOR EACH ROW EXECUTE FUNCTION enforce_position_profile_permission_boundary();

CREATE TRIGGER trg_position_function_profile_updated_at
    BEFORE UPDATE ON position_function_profile
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_position_function_profile_version_updated_at
    BEFORE UPDATE ON position_function_profile_version
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- Every position has an explicit role mapping. Never infer roles from a display name.
-- Existing exact code matches are retained; missing internal roles are created first.
-- Tenant-by-tenant execution remains valid when FORCE RLS is already enabled upstream.
DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        INSERT INTO app_role (tenant_id, code, name, role_type)
        SELECT position.tenant_id, position.code, position.name, 'CUSTOM'
        FROM position_definition position
        WHERE position.tenant_id = tenant_record.id
          AND NOT EXISTS (
              SELECT 1 FROM app_role role
              WHERE role.tenant_id = position.tenant_id AND role.code = position.code
          )
        ON CONFLICT (tenant_id, code) DO NOTHING;

        -- Every existing position receives a group profile with an empty basic draft.
        -- Existing RBAC remains authoritative until a profile is explicitly published.
        INSERT INTO position_function_profile
            (id, tenant_id, position_id, scope_type, default_role_id)
        SELECT gen_random_uuid(), position.tenant_id, position.id, 'GROUP', role.id
        FROM position_definition position
        JOIN app_role role
          ON role.tenant_id = position.tenant_id AND role.code = position.code
        WHERE position.tenant_id = tenant_record.id;

        INSERT INTO position_function_profile_version
            (id, tenant_id, profile_id, version_no, lifecycle_status)
        SELECT gen_random_uuid(), profile.tenant_id, profile.id, 1, 'DRAFT'
        FROM position_function_profile profile
        WHERE profile.tenant_id = tenant_record.id AND profile.scope_type = 'GROUP';
    END LOOP;
END $$;

DO $$
DECLARE
    table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'position_applicable_hotel', 'position_function_profile',
        'position_function_profile_version', 'position_function_profile_permission',
        'position_assignment_recycle_snapshot'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;

INSERT INTO permission (code, resource, action, description, delegable_to_position, function_category) VALUES
    ('position-profile.read', 'position_function_profile', 'read', '查看岗位功能方案与版本', false, 'PROTECTED'),
    ('position-profile.manage', 'position_function_profile', 'manage', '维护岗位、草稿、门店范围和回收站', false, 'PROTECTED'),
    ('position-profile.publish', 'position_function_profile', 'publish', '发布岗位集团标准或门店减权方案', false, 'PROTECTED')
ON CONFLICT (code) DO UPDATE
SET delegable_to_position = false,
    function_category = 'PROTECTED';

DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM app_role role
        JOIN permission permission_item
          ON permission_item.code IN (
              'position-profile.read', 'position-profile.manage', 'position-profile.publish'
          )
        WHERE role.tenant_id = tenant_record.id
          AND role.code = 'PLATFORM_ADMIN'
        ON CONFLICT DO NOTHING;

        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM app_role role
        JOIN permission permission_item
          ON permission_item.code IN (
              'position-profile.read', 'position-profile.manage', 'position-profile.publish'
          )
        WHERE role.tenant_id = tenant_record.id
          AND role.code = 'CEO'
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            position_applicable_hotel, position_function_profile,
            position_function_profile_version, position_function_profile_permission,
            position_assignment_recycle_snapshot
        TO hotel_ai_os_app;
        GRANT SELECT ON permission TO hotel_ai_os_app;
    END IF;
END $$;

COMMENT ON COLUMN position_definition.code IS
    'Internal identifier. It is generated server-side and must not be exposed in position-management APIs.';
COMMENT ON COLUMN position_definition.job_family IS
    'Legacy internal classification. It is not part of the user-facing position model.';
COMMENT ON COLUMN position_definition.level_code IS
    'Legacy internal classification. It is not part of the user-facing position model.';
COMMENT ON COLUMN position_definition.permanently_deleted_at IS
    'Irreversible management deletion. The row remains as a historical tombstone for task/report references.';
