-- Enterprise WeCom directory events start a candidate onboarding flow only.
-- No account, assignment, role or binding becomes active until an authorized
-- administrator approves the candidate in one database transaction.

-- Position-derived grants must be independently revocable without touching an
-- equal role that was granted manually or by another assignment.  Legacy and
-- human-managed grants deliberately keep both source columns NULL.
ALTER TABLE role_assignment
    ADD COLUMN source_type VARCHAR(32),
    ADD COLUMN source_assignment_id UUID,
    ADD CONSTRAINT ck_role_assignment_source CHECK (
        (source_type IS NULL AND source_assignment_id IS NULL)
        OR (source_type = 'POSITION_ASSIGNMENT' AND source_assignment_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_role_assignment_source_assignment
        FOREIGN KEY (tenant_id, source_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id);

CREATE UNIQUE INDEX ux_role_assignment_position_source
    ON role_assignment (tenant_id, source_assignment_id, role_id)
    WHERE source_type = 'POSITION_ASSIGNMENT' AND source_assignment_id IS NOT NULL;
CREATE INDEX ix_role_assignment_source_assignment
    ON role_assignment (tenant_id, source_assignment_id)
    WHERE source_assignment_id IS NOT NULL;

CREATE TABLE wecom_directory_event_receipt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    corp_id VARCHAR(128) NOT NULL,
    event_key_hash CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    payload_ciphertext TEXT NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    change_type VARCHAR(48) NOT NULL,
    event_priority SMALLINT NOT NULL CHECK (event_priority BETWEEN 0 AND 100),
    user_id_fingerprint CHAR(64) NOT NULL,
    previous_user_id_fingerprint CHAR(64),
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING','SUCCEEDED','FAILED','IGNORED','DEAD_LETTER')),
    correlation_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_at TIMESTAMPTZ,
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(80),
    row_version BIGINT NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, corp_id, event_key_hash),
    CHECK (event_key_hash ~ '^[0-9a-f]{64}$'),
    CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CHECK (user_id_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (previous_user_id_fingerprint IS NULL
           OR previous_user_id_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (row_version >= 0),
    CHECK (status IN ('PROCESSING') OR processed_at IS NOT NULL),
    CHECK (status NOT IN ('FAILED','DEAD_LETTER') OR last_error_code IS NOT NULL)
);

-- Records the directory fact that established the current canonical identity.
-- It lets an out-of-order older rename avoid suspending a newer, already
-- approved member that legitimately reused the provider identifier.
ALTER TABLE wecom_user_binding
    ADD COLUMN identity_event_receipt_id UUID,
    ADD COLUMN directory_assignment_snapshot_hash CHAR(64),
    ADD CONSTRAINT fk_wecom_binding_identity_event_receipt
        FOREIGN KEY (tenant_id, identity_event_receipt_id)
        REFERENCES wecom_directory_event_receipt (tenant_id, id),
    ADD CONSTRAINT ck_wecom_binding_directory_assignment_snapshot_hash CHECK (
        directory_assignment_snapshot_hash IS NULL
        OR directory_assignment_snapshot_hash ~ '^[0-9a-f]{64}$'
    );
CREATE INDEX ix_wecom_binding_identity_event_receipt
    ON wecom_user_binding (tenant_id, identity_event_receipt_id)
    WHERE identity_event_receipt_id IS NOT NULL;

CREATE TABLE wecom_person_onboarding (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    corp_id VARCHAR(128) NOT NULL,
    user_id_fingerprint CHAR(64) NOT NULL,
    user_id_ciphertext TEXT,
    display_name VARCHAR(120) NOT NULL,
    onboarding_kind VARCHAR(32) NOT NULL DEFAULT 'NEW_MEMBER'
        CHECK (onboarding_kind IN ('NEW_MEMBER','ASSIGNMENT_CHANGE','USER_ID_CHANGE')),
    source_binding_id UUID,
    source_account_id UUID,
    directory_status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (directory_status IN ('ACTIVE','UNACTIVATED','DISABLED','DELETED')),
    status VARCHAR(32) NOT NULL DEFAULT 'WAITING_PROFILE'
        CHECK (status IN (
            'WAITING_PROFILE','PENDING_APPROVAL','CONFLICT',
            'APPROVED','REJECTED','CANCELLED','EXPIRED'
        )),
    invitation_token_hash CHAR(64),
    invitation_issued_at TIMESTAMPTZ,
    invitation_expires_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    oauth_state_hash CHAR(64),
    browser_verifier_hash CHAR(64),
    provider_code_hash CHAR(64),
    identity_verified_at TIMESTAMPTZ,
    exchange_code_hash CHAR(64),
    exchange_expires_at TIMESTAMPTZ,
    session_token_hash CHAR(64),
    session_expires_at TIMESTAMPTZ,
    requested_org_unit_id UUID,
    requested_position_id UUID,
    profile_submitted_at TIMESTAMPTZ,
    conflicting_account_id UUID,
    failure_code VARCHAR(80),
    decision_reason VARCHAR(500),
    source_event_hash CHAR(64) NOT NULL,
    directory_assignment_snapshot_hash CHAR(64),
    last_event_at TIMESTAMPTZ NOT NULL,
    last_event_receipt_id UUID,
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    account_id UUID,
    employee_id UUID,
    assignment_id UUID,
    role_assignment_id UUID,
    binding_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_org_unit_id) REFERENCES org_unit (tenant_id, id),
    FOREIGN KEY (tenant_id, requested_position_id) REFERENCES position_definition (tenant_id, id),
    FOREIGN KEY (tenant_id, source_binding_id) REFERENCES wecom_user_binding (tenant_id, id),
    FOREIGN KEY (tenant_id, source_account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, conflicting_account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, reviewed_by) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES user_account (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee (tenant_id, id),
    FOREIGN KEY (tenant_id, assignment_id) REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, role_assignment_id) REFERENCES role_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, binding_id) REFERENCES wecom_user_binding (tenant_id, id),
    FOREIGN KEY (tenant_id, last_event_receipt_id)
        REFERENCES wecom_directory_event_receipt (tenant_id, id),
    CHECK (user_id_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK (source_event_hash ~ '^[0-9a-f]{64}$'),
    CHECK (directory_assignment_snapshot_hash IS NULL
           OR directory_assignment_snapshot_hash ~ '^[0-9a-f]{64}$'),
    CHECK (invitation_token_hash IS NULL OR invitation_token_hash ~ '^[0-9a-f]{64}$'),
    CHECK (oauth_state_hash IS NULL OR oauth_state_hash ~ '^[0-9a-f]{64}$'),
    CHECK (browser_verifier_hash IS NULL OR browser_verifier_hash ~ '^[0-9a-f]{64}$'),
    CHECK (provider_code_hash IS NULL OR provider_code_hash ~ '^[0-9a-f]{64}$'),
    CHECK (exchange_code_hash IS NULL OR exchange_code_hash ~ '^[0-9a-f]{64}$'),
    CHECK (session_token_hash IS NULL OR session_token_hash ~ '^[0-9a-f]{64}$'),
    CHECK ((invitation_token_hash IS NULL) = (invitation_issued_at IS NULL)),
    CHECK ((invitation_token_hash IS NULL) = (invitation_expires_at IS NULL)),
    CHECK (invitation_expires_at IS NULL
           OR invitation_expires_at <= invitation_issued_at + interval '120 minutes 5 seconds'),
    CHECK ((exchange_code_hash IS NULL) = (exchange_expires_at IS NULL)),
    CHECK ((session_token_hash IS NULL) = (session_expires_at IS NULL)),
    CHECK (row_version >= 0),
    CHECK (onboarding_kind = 'NEW_MEMBER'
           OR (source_binding_id IS NOT NULL AND source_account_id IS NOT NULL)),
    CHECK (status NOT IN ('PENDING_APPROVAL','CONFLICT','APPROVED')
           OR (requested_org_unit_id IS NOT NULL AND requested_position_id IS NOT NULL
               AND identity_verified_at IS NOT NULL AND profile_submitted_at IS NOT NULL
               AND directory_status = 'ACTIVE')),
    CHECK (status <> 'CONFLICT' OR conflicting_account_id IS NOT NULL),
    CHECK (status <> 'APPROVED'
           OR (account_id IS NOT NULL AND employee_id IS NOT NULL
               AND assignment_id IS NOT NULL AND role_assignment_id IS NOT NULL
               AND binding_id IS NOT NULL AND reviewed_by IS NOT NULL)),
    CHECK (status <> 'REJECTED'
           OR (reviewed_by IS NOT NULL AND decision_reason IS NOT NULL
               AND btrim(decision_reason) <> '')),
    CHECK (status = 'APPROVED'
           OR (account_id IS NULL AND employee_id IS NULL AND assignment_id IS NULL
               AND role_assignment_id IS NULL AND binding_id IS NULL)),
    CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
    CHECK (status <> 'EXPIRED' OR expired_at IS NOT NULL),
    CHECK (status = 'EXPIRED' OR expired_at IS NULL),
    CHECK (status NOT IN ('APPROVED','REJECTED','CANCELLED')
           OR (user_id_ciphertext IS NULL
               AND invitation_token_hash IS NULL AND invitation_issued_at IS NULL
               AND invitation_expires_at IS NULL AND oauth_state_hash IS NULL
               AND browser_verifier_hash IS NULL AND provider_code_hash IS NULL
               AND exchange_code_hash IS NULL AND exchange_expires_at IS NULL
               AND session_token_hash IS NULL AND session_expires_at IS NULL)),
    CHECK (status <> 'EXPIRED'
           OR (invitation_token_hash IS NULL AND invitation_issued_at IS NULL
               AND invitation_expires_at IS NULL AND oauth_state_hash IS NULL
               AND browser_verifier_hash IS NULL AND provider_code_hash IS NULL
               AND exchange_code_hash IS NULL AND exchange_expires_at IS NULL
               AND session_token_hash IS NULL AND session_expires_at IS NULL))
);

CREATE UNIQUE INDEX ux_wecom_directory_onboarding_open_member
    ON wecom_person_onboarding (tenant_id, corp_id, user_id_fingerprint)
    WHERE status IN ('WAITING_PROFILE','PENDING_APPROVAL','CONFLICT');
CREATE UNIQUE INDEX ux_wecom_directory_onboarding_invitation
    ON wecom_person_onboarding (tenant_id, invitation_token_hash)
    WHERE invitation_token_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_wecom_directory_onboarding_oauth_state
    ON wecom_person_onboarding (tenant_id, oauth_state_hash)
    WHERE oauth_state_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_wecom_directory_onboarding_provider_code
    ON wecom_person_onboarding (tenant_id, provider_code_hash)
    WHERE provider_code_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_wecom_directory_onboarding_exchange
    ON wecom_person_onboarding (tenant_id, exchange_code_hash)
    WHERE exchange_code_hash IS NOT NULL;
CREATE UNIQUE INDEX ux_wecom_directory_onboarding_session
    ON wecom_person_onboarding (tenant_id, session_token_hash)
    WHERE session_token_hash IS NOT NULL;
CREATE INDEX ix_wecom_directory_event_recovery
    ON wecom_directory_event_receipt (tenant_id, status, received_at, id);
CREATE INDEX ix_wecom_directory_event_identity
    ON wecom_directory_event_receipt
       (tenant_id, corp_id, user_id_fingerprint, occurred_at DESC, received_at DESC);
CREATE INDEX ix_wecom_directory_event_previous_identity
    ON wecom_directory_event_receipt
       (tenant_id, corp_id, previous_user_id_fingerprint, occurred_at DESC, received_at DESC)
    WHERE previous_user_id_fingerprint IS NOT NULL;
CREATE INDEX ix_wecom_directory_onboarding_status
    ON wecom_person_onboarding (tenant_id, status, updated_at DESC, id);
CREATE INDEX ix_wecom_directory_onboarding_requested_org
    ON wecom_person_onboarding (tenant_id, requested_org_unit_id, status)
    WHERE requested_org_unit_id IS NOT NULL;
CREATE INDEX ix_wecom_directory_onboarding_requested_position
    ON wecom_person_onboarding (tenant_id, requested_position_id, status)
    WHERE requested_position_id IS NOT NULL;
CREATE INDEX ix_wecom_directory_onboarding_conflict
    ON wecom_person_onboarding (tenant_id, conflicting_account_id, status)
    WHERE conflicting_account_id IS NOT NULL;

CREATE TRIGGER trg_wecom_person_onboarding_updated_at
    BEFORE UPDATE ON wecom_person_onboarding
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE wecom_directory_event_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE wecom_directory_event_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wecom_directory_event_receipt
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE wecom_person_onboarding ENABLE ROW LEVEL SECURITY;
ALTER TABLE wecom_person_onboarding FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wecom_person_onboarding
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON
            wecom_directory_event_receipt, wecom_person_onboarding
        TO hotel_ai_os_app;
    END IF;
END $$;

-- HR participates in the same scoped approval workflow as CEO and platform
-- administrators.  This does not enable any delivery channel.
DO $$
DECLARE
    tenant_record RECORD;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);
        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM app_role role
        JOIN permission permission_item ON permission_item.code = 'wecom-binding.approve'
        WHERE role.tenant_id = tenant_record.id AND role.code = 'HR_KPI_ADMIN'
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;
