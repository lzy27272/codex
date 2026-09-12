-- Manual employee invitations are reviewed before any account, employee,
-- assignment, role grant or WeCom binding invitation is activated.

CREATE TABLE employee_invitation_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    display_name VARCHAR(120) NOT NULL,
    mobile VARCHAR(32),
    login_name VARCHAR(120) NOT NULL,
    employee_no VARCHAR(64) NOT NULL,
    note VARCHAR(500),
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED','CANCELLED')),
    requested_by UUID NOT NULL,
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    decision_reason VARCHAR(500),
    target_account_id UUID,
    target_employee_id UUID,
    binding_request_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, reviewed_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, target_account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, target_employee_id) REFERENCES employee (tenant_id, id),
    FOREIGN KEY (tenant_id, binding_request_id) REFERENCES wecom_user_binding_request (tenant_id, id),
    CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
    CHECK (status <> 'APPROVED' OR (
        reviewed_by IS NOT NULL AND target_account_id IS NOT NULL AND target_employee_id IS NOT NULL
    )),
    CHECK (status <> 'REJECTED' OR (
        reviewed_by IS NOT NULL AND decision_reason IS NOT NULL AND btrim(decision_reason) <> ''
    )),
    CHECK (status IN ('APPROVED','REJECTED') OR (
        reviewed_by IS NULL AND target_account_id IS NULL AND target_employee_id IS NULL
        AND binding_request_id IS NULL
    ))
);

CREATE TABLE employee_invitation_assignment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    invitation_id UUID NOT NULL,
    org_unit_id UUID NOT NULL,
    position_id UUID NOT NULL,
    manager_assignment_id UUID,
    is_primary BOOLEAN NOT NULL DEFAULT false,
    assignment_type VARCHAR(24) NOT NULL DEFAULT 'PERMANENT'
        CHECK (assignment_type IN ('PERMANENT','TEMPORARY','ACTING')),
    resulting_assignment_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, invitation_id, org_unit_id, position_id),
    FOREIGN KEY (tenant_id, invitation_id) REFERENCES employee_invitation_request (tenant_id, id),
    FOREIGN KEY (tenant_id, org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, position_id) REFERENCES position_definition (tenant_id, id),
    FOREIGN KEY (tenant_id, manager_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, resulting_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id)
);

CREATE UNIQUE INDEX ux_employee_invitation_pending_login
    ON employee_invitation_request (tenant_id, lower(login_name))
    WHERE status = 'PENDING_REVIEW';
CREATE UNIQUE INDEX ux_employee_invitation_pending_number
    ON employee_invitation_request (tenant_id, lower(employee_no))
    WHERE status = 'PENDING_REVIEW';
CREATE INDEX ix_employee_invitation_status
    ON employee_invitation_request (tenant_id, status, created_at DESC);

CREATE TRIGGER trg_employee_invitation_request_updated_at
    BEFORE UPDATE ON employee_invitation_request
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE employee_invitation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_invitation_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_invitation_request
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE employee_invitation_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_invitation_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_invitation_assignment
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            employee_invitation_request, employee_invitation_assignment
        TO hotel_ai_os_app;
    END IF;
END $$;
