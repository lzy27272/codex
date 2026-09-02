-- Sensitive OTA connector authorization is explicit and remains hotel-scoped in the API.

INSERT INTO permission (code, resource, action, description) VALUES
    ('ota-authorization.start', 'ota_connector_authorization', 'start', '发起授权范围内门店的OTA受控登录')
ON CONFLICT (code) DO NOTHING;

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
          ON permission_item.code = 'ota-authorization.start'
        WHERE role.tenant_id = tenant_record.id
          AND role.code IN ('CEO', 'OTA_OPERATION_MANAGER')
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;
