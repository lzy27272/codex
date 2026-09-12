-- Employee-completed account registration for directory-driven WeCom onboarding.
-- No active login exists before HR approval; only the PBKDF2 hash is retained.

ALTER TABLE wecom_person_onboarding
    ADD COLUMN requested_display_name VARCHAR(120),
    ADD COLUMN requested_login_name VARCHAR(120),
    ADD COLUMN requested_password_hash VARCHAR(255),
    ADD COLUMN registration_completed_at TIMESTAMPTZ;

ALTER TABLE wecom_person_onboarding
    ADD CONSTRAINT ck_wecom_onboarding_registration_complete
        CHECK (
            (requested_display_name IS NULL
             AND requested_login_name IS NULL
             AND requested_password_hash IS NULL
             AND registration_completed_at IS NULL)
            OR
            (requested_display_name IS NOT NULL
             AND btrim(requested_display_name) <> ''
             AND requested_login_name IS NOT NULL
             AND btrim(requested_login_name) <> ''
             AND registration_completed_at IS NOT NULL
             AND (
                 requested_password_hash LIKE 'pbkdf2_sha256$%'
                 OR (requested_password_hash IS NULL
                     AND status IN ('APPROVED','REJECTED','CANCELLED','EXPIRED'))
             ))
        );

CREATE UNIQUE INDEX ux_wecom_onboarding_open_login
    ON wecom_person_onboarding (tenant_id, lower(requested_login_name))
    WHERE requested_login_name IS NOT NULL
      AND status IN ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT');

INSERT INTO permission (
    code, resource, action, description, delegable_to_position, function_category
) VALUES (
    'wecom-onboarding.review', 'wecom_person_onboarding', 'review',
    '查看并审核企业微信员工自助入职申请', true, 'WECOM'
)
ON CONFLICT (code) DO UPDATE SET
    resource = EXCLUDED.resource,
    action = EXCLUDED.action,
    description = EXCLUDED.description,
    delegable_to_position = true,
    function_category = 'WECOM';

-- Assignment sessions load their effective capabilities from the published
-- position profile. Add only the dedicated review and navigation permissions.
ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role_item.id, permission_item.id
        FROM app_role role_item
        JOIN permission permission_item
          ON permission_item.code IN (
              'org.read', 'wecom-binding.read', 'wecom-onboarding.review',
              'ui.module.organization', 'ui.module.wecom-bindings',
              'ui.module.wecom-onboarding'
          )
        WHERE role_item.tenant_id = tenant_record.id
          AND role_item.code IN (
              'HR_ADMINISTRATION', 'HR_ADMINISTRATION_SUPERVISOR'
          )
        ON CONFLICT DO NOTHING;

        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, permission_item.id
        FROM position_definition position_item
        JOIN position_function_profile profile
          ON profile.tenant_id = position_item.tenant_id
         AND profile.position_id = position_item.id
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id
         AND version.profile_id = profile.id
         AND version.lifecycle_status = 'PUBLISHED'
        JOIN permission permission_item
          ON permission_item.code IN (
              'wecom-onboarding.review', 'ui.module.organization',
              'ui.module.wecom-bindings', 'ui.module.wecom-onboarding'
          )
         AND permission_item.delegable_to_position = true
        WHERE position_item.tenant_id = tenant_record.id
          AND position_item.code IN (
              'HR_ADMINISTRATION', 'HR_ADMINISTRATION_SUPERVISOR'
          )
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
