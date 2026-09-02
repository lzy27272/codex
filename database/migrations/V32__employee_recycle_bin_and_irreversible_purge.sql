-- Employee deletion is intentionally two-stage:
-- 1) recoverable recycle-bin deletion; 2) irreversible identity purge.
-- Historical assignments, tasks and audit references remain intact.

ALTER TABLE employee
    ADD COLUMN deleted_at TIMESTAMPTZ,
    ADD COLUMN deleted_by UUID,
    ADD COLUMN permanently_deleted_at TIMESTAMPTZ,
    ADD COLUMN permanently_deleted_by UUID,
    ADD CONSTRAINT fk_employee_deleted_by
        FOREIGN KEY (tenant_id, deleted_by) REFERENCES user_account (tenant_id, id),
    ADD CONSTRAINT fk_employee_permanently_deleted_by
        FOREIGN KEY (tenant_id, permanently_deleted_by) REFERENCES user_account (tenant_id, id),
    ADD CONSTRAINT ck_employee_deletion_lifecycle
        CHECK (permanently_deleted_at IS NULL OR deleted_at IS NOT NULL),
    ADD CONSTRAINT ck_employee_deletion_actor
        CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)),
    ADD CONSTRAINT ck_employee_permanent_deletion_actor
        CHECK ((permanently_deleted_at IS NULL) = (permanently_deleted_by IS NULL));

CREATE INDEX ix_employee_recycle_bin
    ON employee (tenant_id, deleted_at DESC, id)
    WHERE deleted_at IS NOT NULL AND permanently_deleted_at IS NULL;

CREATE INDEX ix_employee_visible
    ON employee (tenant_id, employment_status, name, id)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN employee.deleted_at IS
    'Recoverable deletion timestamp. Recycle-bin rows remain referentially intact.';
COMMENT ON COLUMN employee.permanently_deleted_at IS
    'Irreversible purge timestamp. Identity fields are anonymized; historical references remain.';
