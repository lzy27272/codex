-- A position defines whether its data scope is selected per assignment, while
-- each employee assignment owns its own set of responsible hotels. This keeps
-- two employees in the same position isolated from each other's hotel data.

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'position_function_profile_version'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%authorization_scope_type%'
    LOOP
        EXECUTE format(
            'ALTER TABLE position_function_profile_version DROP CONSTRAINT %I',
            constraint_name
        );
    END LOOP;
END $$;

ALTER TABLE position_function_profile_version
    ADD CONSTRAINT ck_position_profile_authorization_scope
    CHECK (authorization_scope_type IN (
        'SELF', 'ORG_UNIT', 'ORG_TREE', 'ASSIGNED_HOTELS', 'TENANT'
    ));

ALTER TABLE role_assignment
    DROP CONSTRAINT IF EXISTS role_assignment_scope_type_check,
    ADD CONSTRAINT role_assignment_scope_type_check
        CHECK (scope_type IN (
            'SELF', 'ORG_UNIT', 'ORG_TREE', 'ASSIGNED_HOTELS', 'TENANT'
        ));

CREATE TABLE employee_assignment_hotel_scope (
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    assignment_id UUID NOT NULL,
    hotel_org_unit_id UUID NOT NULL,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, assignment_id, hotel_org_unit_id),
    FOREIGN KEY (tenant_id, assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, hotel_org_unit_id)
        REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by)
        REFERENCES user_account (tenant_id, id)
);

CREATE INDEX ix_assignment_hotel_scope_reverse
    ON employee_assignment_hotel_scope (tenant_id, hotel_org_unit_id, assignment_id);

ALTER TABLE employee_assignment_hotel_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_assignment_hotel_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_assignment_hotel_scope
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

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
               WHEN 'SELF' THEN 0
               WHEN 'ORG_UNIT' THEN 1
               WHEN 'ORG_TREE' THEN 2
               WHEN 'ASSIGNED_HOTELS' THEN 3
               ELSE 4
           END) > (CASE base_authorization_scope
               WHEN 'SELF' THEN 0
               WHEN 'ORG_UNIT' THEN 1
               WHEN 'ORG_TREE' THEN 2
               WHEN 'ASSIGNED_HOTELS' THEN 3
               ELSE 4
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
