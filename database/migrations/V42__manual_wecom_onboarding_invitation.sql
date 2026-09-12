-- A manager may issue a one-time onboarding link before the employee has
-- submitted any personal or assignment data. The employee's WeCom identity
-- is adopted only after OAuth verification; all profile fields remain
-- employee-entered and continue through the existing reviewed onboarding flow.

ALTER TABLE wecom_person_onboarding
    ADD COLUMN invitation_source VARCHAR(24) NOT NULL DEFAULT 'DIRECTORY_EVENT'
        CHECK (invitation_source IN ('DIRECTORY_EVENT', 'MANUAL_LINK')),
    ADD COLUMN invitation_created_by UUID,
    ADD CONSTRAINT fk_wecom_onboarding_invitation_creator
        FOREIGN KEY (tenant_id, invitation_created_by)
        REFERENCES user_account (tenant_id, id),
    ADD CONSTRAINT ck_wecom_onboarding_manual_invitation_creator
        CHECK (invitation_source <> 'MANUAL_LINK' OR invitation_created_by IS NOT NULL);

CREATE INDEX ix_wecom_onboarding_manual_invitation
    ON wecom_person_onboarding (tenant_id, status, invitation_expires_at DESC)
    WHERE invitation_source = 'MANUAL_LINK';
