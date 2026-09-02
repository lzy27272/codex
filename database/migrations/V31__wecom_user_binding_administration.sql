-- Audited, self-service WeCom identity enrollment managed from the AI OS.
-- Enrollment never enables outbound delivery; delivery feature flags remain independent.

ALTER TABLE wecom_user_binding
    ADD COLUMN user_id_fingerprint CHAR(64),
    ADD COLUMN last_verified_at TIMESTAMPTZ,
    ADD COLUMN status_reason VARCHAR(80),
    ADD COLUMN assignment_snapshot_hash CHAR(64),
    ADD COLUMN assignment_selection_required BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN updated_by UUID;

UPDATE wecom_user_binding
SET user_id_fingerprint = encode(digest(corp_id || ':' || wecom_user_id, 'sha256'), 'hex'),
    last_verified_at = coalesce(last_verified_at, updated_at);

ALTER TABLE wecom_user_binding
    ALTER COLUMN user_id_fingerprint SET NOT NULL,
    ADD CONSTRAINT fk_wecom_user_binding_updated_by
        FOREIGN KEY (tenant_id, updated_by) REFERENCES user_account (tenant_id, id),
    ADD CONSTRAINT ck_wecom_user_binding_fingerprint
        CHECK (user_id_fingerprint ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_wecom_user_binding_assignment_snapshot
        CHECK (assignment_snapshot_hash IS NULL OR assignment_snapshot_hash ~ '^[0-9a-f]{64}$');

CREATE TABLE wecom_user_binding_request (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    account_id UUID NOT NULL,
    preferred_assignment_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_SCAN'
        CHECK (status IN (
            'WAITING_SCAN', 'AUTHORIZING', 'PENDING_APPROVAL', 'CONFLICT',
            'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'FAILED'
        )),
    token_hash CHAR(64) NOT NULL,
    oauth_state_hash CHAR(64),
    browser_verifier_hash CHAR(64),
    provider_code_hash CHAR(64),
    candidate_wecom_user_id VARCHAR(128),
    candidate_fingerprint CHAR(64),
    conflicting_account_id UUID,
    failure_code VARCHAR(80),
    decision_reason VARCHAR(500),
    expires_at TIMESTAMPTZ NOT NULL,
    retain_until TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '180 days'),
    requested_by UUID NOT NULL,
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, token_hash),
    FOREIGN KEY (tenant_id, account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, preferred_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, conflicting_account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, reviewed_by) REFERENCES user_account (tenant_id, id),
    CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (oauth_state_hash IS NULL OR oauth_state_hash ~ '^[0-9a-f]{64}$'),
    CHECK (browser_verifier_hash IS NULL OR browser_verifier_hash ~ '^[0-9a-f]{64}$'),
    CHECK (provider_code_hash IS NULL OR provider_code_hash ~ '^[0-9a-f]{64}$'),
    CHECK (candidate_fingerprint IS NULL OR candidate_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (expires_at <= created_at + interval '120 minutes 5 seconds'),
    CHECK (retain_until >= created_at + interval '180 days' - interval '5 seconds'),
    CHECK (status NOT IN ('PENDING_APPROVAL', 'CONFLICT') OR candidate_wecom_user_id IS NOT NULL),
    CHECK (status <> 'CONFLICT' OR conflicting_account_id IS NOT NULL),
    CHECK (status NOT IN ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')
           OR candidate_wecom_user_id IS NULL),
    CHECK ((reviewed_at IS NULL) = (reviewed_by IS NULL))
);

CREATE UNIQUE INDEX ux_wecom_binding_request_open_account
    ON wecom_user_binding_request (tenant_id, account_id)
    WHERE status IN ('WAITING_SCAN', 'AUTHORIZING', 'PENDING_APPROVAL', 'CONFLICT');
CREATE UNIQUE INDEX ux_wecom_binding_request_oauth_state
    ON wecom_user_binding_request (tenant_id, oauth_state_hash)
    WHERE oauth_state_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_wecom_binding_request_provider_code
    ON wecom_user_binding_request (tenant_id, provider_code_hash)
    WHERE provider_code_hash IS NOT NULL;
CREATE INDEX ix_wecom_binding_request_status_expiry
    ON wecom_user_binding_request (tenant_id, status, expires_at);
CREATE INDEX ix_wecom_binding_request_assignment
    ON wecom_user_binding_request (tenant_id, preferred_assignment_id, created_at DESC);
CREATE INDEX ix_wecom_binding_request_conflict
    ON wecom_user_binding_request (tenant_id, conflicting_account_id)
    WHERE conflicting_account_id IS NOT NULL;
CREATE INDEX ix_wecom_user_binding_updated_by
    ON wecom_user_binding (tenant_id, updated_by);

CREATE TRIGGER trg_wecom_user_binding_request_updated_at
    BEFORE UPDATE ON wecom_user_binding_request FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE wecom_user_binding_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE wecom_user_binding_request FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wecom_user_binding_request
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON wecom_user_binding_request TO hotel_ai_os_app;
    END IF;
END $$;

INSERT INTO permission (code, resource, action, description) VALUES
    ('wecom-binding.read', 'wecom_user_binding', 'read', '查看企业微信人员绑定和异常状态'),
    ('wecom-binding.manage', 'wecom_user_binding', 'manage', '发起邀请、选择默认任职和暂停绑定'),
    ('wecom-binding.approve', 'wecom_user_binding', 'approve', '确认、恢复、转移或解除企业微信人员绑定')
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
          ON permission_item.code = ANY (ARRAY[
              'wecom-binding.read', 'wecom-binding.manage', 'wecom-binding.approve'
          ])
        WHERE role.tenant_id = tenant_record.id
          AND role.code IN ('CEO', 'PLATFORM_ADMIN')
        ON CONFLICT DO NOTHING;

        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM app_role role
        JOIN permission permission_item
          ON permission_item.code = ANY (ARRAY['wecom-binding.read', 'wecom-binding.manage'])
        WHERE role.tenant_id = tenant_record.id
          AND role.code = 'HR_KPI_ADMIN'
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;
