-- Protect the split between migration/profile-governed SYSTEM roles and
-- tenant-managed CUSTOM roles. This migration intentionally changes no role,
-- role_permission, or position profile data.

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM app_role
        WHERE role_type NOT IN ('SYSTEM', 'CUSTOM')
    ) THEN
        RAISE EXCEPTION
            'V38 blocked: app_role contains a non-canonical role_type';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM app_role
        WHERE role_type = 'CUSTOM'
          AND upper(btrim(code)) IN (
              'PLATFORM_ADMIN', 'GROUP_ADMIN', 'CEO', 'GROUP_VICE_PRESIDENT',
              'GENERAL_MANAGER', 'ASSISTANT_GENERAL_MANAGER',
              'OTA_OPERATION_MANAGER', 'OTA_OPERATION_ASSISTANT',
              'FRONT_OFFICE_SUPERVISOR', 'HOUSEKEEPING_SUPERVISOR',
              'HOUSEKEEPING_ATTENDANT', 'FRONT_DESK', 'HR_KPI_ADMIN'
          )
    ) THEN
        RAISE EXCEPTION
            'V38 blocked: a CUSTOM role uses a reserved SYSTEM role code';
    END IF;
END
$migration$;

ALTER TABLE app_role
    ADD CONSTRAINT ck_app_role_type_governance
        CHECK (role_type IN ('SYSTEM', 'CUSTOM')),
    ADD CONSTRAINT ck_app_role_custom_reserved_code
        CHECK (
            role_type <> 'CUSTOM'
            OR upper(btrim(code)) NOT IN (
                'PLATFORM_ADMIN', 'GROUP_ADMIN', 'CEO', 'GROUP_VICE_PRESIDENT',
                'GENERAL_MANAGER', 'ASSISTANT_GENERAL_MANAGER',
                'OTA_OPERATION_MANAGER', 'OTA_OPERATION_ASSISTANT',
                'FRONT_OFFICE_SUPERVISOR', 'HOUSEKEEPING_SUPERVISOR',
                'HOUSEKEEPING_ATTENDANT', 'FRONT_DESK', 'HR_KPI_ADMIN'
            )
        );

COMMENT ON CONSTRAINT ck_app_role_type_governance ON app_role IS
    'SYSTEM roles are release/profile governed; generic IAM creates CUSTOM roles only.';

COMMENT ON CONSTRAINT ck_app_role_custom_reserved_code ON app_role IS
    'CUSTOM roles cannot impersonate a reserved SYSTEM role code.';
