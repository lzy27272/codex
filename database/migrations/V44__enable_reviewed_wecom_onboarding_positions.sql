-- Make the employee self-registration flow usable out of the box without
-- weakening the position-permission boundary.  Only the reviewed hotel
-- operating positions below are enabled.  A role that has any protected
-- (non-delegable) permission remains unavailable, and an explicit hotel-level
-- opt-out continues to override the group default.

ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    tenant_record RECORD;
    changed_count INTEGER;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        WITH reviewed_profile AS (
            SELECT version.id, profile.id AS profile_id, position_item.id AS position_id
            FROM position_function_profile_version version
            JOIN position_function_profile profile
              ON profile.tenant_id = version.tenant_id
             AND profile.id = version.profile_id
             AND profile.scope_type = 'GROUP'
            JOIN position_definition position_item
              ON position_item.tenant_id = profile.tenant_id
             AND position_item.id = profile.position_id
            JOIN app_role mapped_role
              ON mapped_role.tenant_id = profile.tenant_id
             AND mapped_role.id = profile.default_role_id
             AND mapped_role.code = position_item.code
             AND mapped_role.role_type = 'SYSTEM'
            WHERE version.tenant_id = tenant_record.id
              AND version.lifecycle_status = 'PUBLISHED'
              AND version.wecom_self_selectable = false
              AND position_item.status = 'ACTIVE'
              AND position_item.deleted_at IS NULL
              AND position_item.permanently_deleted_at IS NULL
              AND position_item.code IN (
                  'FRONT_DESK', 'HOUSEKEEPING_ATTENDANT', 'OTA_OPERATION_ASSISTANT',
                  'FRONT_OFFICE_SUPERVISOR', 'HOUSEKEEPING_SUPERVISOR',
                  'ASSISTANT_GENERAL_MANAGER', 'GENERAL_MANAGER'
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM role_permission role_grant
                  JOIN permission protected_permission
                    ON protected_permission.id = role_grant.permission_id
                  WHERE role_grant.tenant_id = mapped_role.tenant_id
                    AND role_grant.role_id = mapped_role.id
                    AND protected_permission.delegable_to_position = false
              )
        ), enabled_profile AS (
            UPDATE position_function_profile_version version
               SET wecom_self_selectable = true,
                   row_version = version.row_version + 1,
                   updated_at = now()
              FROM reviewed_profile reviewed
             WHERE version.tenant_id = tenant_record.id
               AND version.id = reviewed.id
            RETURNING reviewed.profile_id, reviewed.position_id, version.id
        ), aligned_draft AS (
            UPDATE position_function_profile_version draft
               SET wecom_self_selectable = true,
                   row_version = draft.row_version + 1,
                   updated_at = now()
              FROM enabled_profile published
             WHERE draft.tenant_id = tenant_record.id
               AND draft.profile_id = published.profile_id
               AND draft.lifecycle_status = 'DRAFT'
               AND draft.copied_from_version_id = published.id
               AND draft.row_version = 0
               AND draft.wecom_self_selectable = false
            RETURNING draft.id
        )
        SELECT count(*) INTO changed_count FROM enabled_profile;

        IF changed_count > 0 THEN
            INSERT INTO audit_log (
                tenant_id, actor_id, action, resource_type, resource_id,
                correlation_id, after_data
            ) VALUES (
                tenant_record.id,
                NULL,
                'SYSTEM_WECOM_ONBOARDING_DEFAULTS_ENABLED',
                'TENANT',
                tenant_record.id,
                gen_random_uuid(),
                jsonb_build_object(
                    'category', 'SYSTEM',
                    'source', 'V44',
                    'enabledProfileCount', changed_count
                )
            );
        END IF;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
