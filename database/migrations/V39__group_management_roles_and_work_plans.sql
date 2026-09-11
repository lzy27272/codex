-- PRODUCT-V1.4 / DESIGN-1.1 / TECH-DESIGN-1.0
-- Batch A only: governed roles/positions, work-plan storage and task provenance.
-- Runtime feature flags remain disabled by default; this migration assigns no people.

ALTER TABLE app_role DROP CONSTRAINT ck_app_role_custom_reserved_code;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM app_role
        WHERE role_type = 'CUSTOM'
          AND upper(btrim(code)) IN (
              'GROUP_CHAIRMAN', 'HR_ADMINISTRATION_SUPERVISOR', 'HR_ADMINISTRATION'
          )
    ) THEN
        RAISE EXCEPTION 'V39 blocked: a CUSTOM role uses a new reserved SYSTEM role code';
    END IF;
END
$migration$;

ALTER TABLE app_role ADD CONSTRAINT ck_app_role_custom_reserved_code CHECK (
    role_type <> 'CUSTOM' OR upper(btrim(code)) NOT IN (
        'PLATFORM_ADMIN', 'GROUP_ADMIN', 'CEO', 'GROUP_VICE_PRESIDENT',
        'GROUP_CHAIRMAN', 'HR_ADMINISTRATION_SUPERVISOR', 'HR_ADMINISTRATION',
        'GENERAL_MANAGER', 'ASSISTANT_GENERAL_MANAGER', 'OTA_OPERATION_MANAGER',
        'OTA_OPERATION_ASSISTANT', 'FRONT_OFFICE_SUPERVISOR',
        'HOUSEKEEPING_SUPERVISOR', 'HOUSEKEEPING_ATTENDANT', 'FRONT_DESK',
        'HR_KPI_ADMIN'
    )
);

INSERT INTO permission (code, resource, action, description, delegable_to_position, function_category) VALUES
    ('ui.module.work-plans', 'ui_module', 'work-plans', '显示工作计划模块', true, 'UI_MODULE'),
    ('work-plan.read', 'work_plan', 'read', '查看本人工作计划', true, 'WORK'),
    ('work-plan.submit', 'work_plan', 'submit', '提交本人工作计划', true, 'WORK'),
    ('work-plan.team-read', 'work_plan', 'team-read', '查看管理范围工作计划', true, 'WORK'),
    ('work-plan.review', 'work_plan', 'review', '审批直属下级工作计划', true, 'WORK'),
    ('work-record.team-read', 'work_record', 'team-read', '查看管理范围工作记录', true, 'WORK'),
    ('executive-task.read', 'executive_task', 'read', '查看董事长派发任务', true, 'TASK'),
    ('executive-task.assign', 'executive_task', 'assign', '董事长向总经理或副总经理派发任务', true, 'TASK')
ON CONFLICT (code) DO UPDATE SET
    resource = EXCLUDED.resource,
    action = EXCLUDED.action,
    description = EXCLUDED.description,
    delegable_to_position = true,
    function_category = EXCLUDED.function_category;

UPDATE permission
SET delegable_to_position = true, function_category = 'DASHBOARD'
WHERE code = 'dashboard.ceo';

ALTER TABLE management_task
    ADD COLUMN creation_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN created_by_assignment_id UUID,
    ADD CONSTRAINT ck_management_task_creation_source CHECK (creation_source IN (
        'LEGACY', 'MANUAL', 'RULE_ENGINE', 'TASK_CANDIDATE', 'WORK_PLAN', 'CHAIRMAN_DIRECTIVE'
    )),
    ADD CONSTRAINT fk_management_task_created_by_assignment
        FOREIGN KEY (tenant_id, created_by_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id);

WITH facts AS (
    SELECT task.id, task.tenant_id,
           (task.source_action_id IS NOT NULL OR EXISTS (
                 SELECT 1 FROM task_transition transition
                 WHERE transition.tenant_id = task.tenant_id AND transition.task_id = task.id
                   AND transition.command = 'CREATE'
                   AND transition.payload ->> 'source' = 'RULE_ENGINE'
             )) AS is_rule,
           (EXISTS (
                 SELECT 1 FROM task_transition transition
                 WHERE transition.tenant_id = task.tenant_id AND transition.task_id = task.id
                   AND transition.command = 'CREATE'
                   AND transition.payload ->> 'source' = 'TASK_CANDIDATE'
             ) OR EXISTS (
                 SELECT 1 FROM task_candidate candidate
                 WHERE candidate.tenant_id = task.tenant_id AND candidate.formal_task_id = task.id
             )) AS is_candidate,
           EXISTS (
                 SELECT 1 FROM task_transition transition
                 WHERE transition.tenant_id = task.tenant_id AND transition.task_id = task.id
                   AND transition.command = 'CREATE'
                   AND transition.payload ->> 'source' = 'MANUAL'
             ) AS is_manual
    FROM management_task task
), classified AS (
    SELECT id, tenant_id,
           CASE
             WHEN is_rule::integer + is_candidate::integer + is_manual::integer <> 1 THEN 'LEGACY'
             WHEN is_rule THEN 'RULE_ENGINE'
             WHEN is_candidate THEN 'TASK_CANDIDATE'
             WHEN is_manual THEN 'MANUAL'
             ELSE 'LEGACY'
           END AS source
    FROM facts
)
UPDATE management_task task SET creation_source = classified.source
FROM classified
WHERE task.tenant_id = classified.tenant_id AND task.id = classified.id;

CREATE INDEX ix_management_task_created_by_assignment
    ON management_task (tenant_id, created_by_assignment_id)
    WHERE created_by_assignment_id IS NOT NULL;

CREATE TABLE management_work_plan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    owner_assignment_id UUID NOT NULL,
    plan_type VARCHAR(16) NOT NULL CHECK (plan_type IN ('WEEKLY', 'MONTHLY')),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    current_revision_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, owner_assignment_id, plan_type, period_start),
    FOREIGN KEY (tenant_id, owner_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    CHECK (
      (plan_type = 'WEEKLY' AND extract(isodow FROM period_start) = 1
       AND period_end = period_start + 6)
      OR
      (plan_type = 'MONTHLY' AND period_start = date_trunc('month', period_start)::date
       AND period_end = (date_trunc('month', period_start) + interval '1 month - 1 day')::date)
    )
);

CREATE TABLE management_work_plan_revision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    work_plan_id UUID NOT NULL,
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED', 'REJECTED', 'WITHDRAWN')),
    summary TEXT,
    submitted_at TIMESTAMPTZ,
    decided_at TIMESTAMPTZ,
    created_by_assignment_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, work_plan_id, revision_no),
    FOREIGN KEY (tenant_id, work_plan_id) REFERENCES management_work_plan (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    CHECK ((status = 'DRAFT' AND submitted_at IS NULL) OR status <> 'DRAFT')
);

ALTER TABLE management_work_plan ADD CONSTRAINT fk_work_plan_current_revision
    FOREIGN KEY (tenant_id, current_revision_id)
    REFERENCES management_work_plan_revision (tenant_id, id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE management_work_plan_item (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    revision_id UUID NOT NULL,
    item_no INTEGER NOT NULL CHECK (item_no > 0),
    title VARCHAR(240) NOT NULL,
    description TEXT,
    target_assignment_id UUID,
    due_at TIMESTAMPTZ NOT NULL,
    priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL'
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, revision_id, item_no),
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES management_work_plan_revision (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, target_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id)
);

CREATE TABLE management_work_plan_item_reminder (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    item_id UUID NOT NULL,
    remind_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(24) NOT NULL DEFAULT 'IN_APP'
        CHECK (channel IN ('IN_APP', 'WECOM')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, item_id, remind_at, channel),
    FOREIGN KEY (tenant_id, item_id)
        REFERENCES management_work_plan_item (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE management_work_plan_review (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    revision_id UUID NOT NULL,
    reviewer_assignment_id UUID NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('APPROVE', 'RETURN', 'REJECT')),
    comment TEXT,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, revision_id),
    FOREIGN KEY (tenant_id, revision_id)
        REFERENCES management_work_plan_revision (tenant_id, id),
    FOREIGN KEY (tenant_id, reviewer_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id)
);

CREATE TABLE management_work_plan_item_decision (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    review_id UUID NOT NULL,
    item_id UUID NOT NULL,
    content_decision VARCHAR(24) NOT NULL CHECK (content_decision IN ('ACCEPT', 'CHANGE_REQUIRED')),
    due_decision VARCHAR(24) NOT NULL CHECK (due_decision IN ('ACCEPT', 'ADJUST', 'CHANGE_REQUIRED')),
    reminder_decision VARCHAR(24) NOT NULL CHECK (reminder_decision IN ('ACCEPT', 'ADJUST', 'CHANGE_REQUIRED')),
    approved_title VARCHAR(240),
    approved_description TEXT,
    approved_due_at TIMESTAMPTZ,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, review_id, item_id),
    FOREIGN KEY (tenant_id, review_id)
        REFERENCES management_work_plan_review (tenant_id, id),
    FOREIGN KEY (tenant_id, item_id)
        REFERENCES management_work_plan_item (tenant_id, id)
);

CREATE TABLE management_work_plan_item_decision_reminder (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    item_decision_id UUID NOT NULL,
    remind_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(24) NOT NULL DEFAULT 'IN_APP'
        CHECK (channel IN ('IN_APP', 'WECOM')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, item_decision_id, remind_at, channel),
    FOREIGN KEY (tenant_id, item_decision_id)
        REFERENCES management_work_plan_item_decision (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE management_work_plan_task_link (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    item_decision_id UUID NOT NULL,
    task_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, item_decision_id),
    UNIQUE (tenant_id, task_id),
    FOREIGN KEY (tenant_id, item_decision_id)
        REFERENCES management_work_plan_item_decision (tenant_id, id),
    FOREIGN KEY (tenant_id, task_id)
        REFERENCES management_task (tenant_id, id)
);

CREATE TABLE task_reminder (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    task_id UUID NOT NULL,
    remind_at TIMESTAMPTZ NOT NULL,
    channel VARCHAR(24) NOT NULL DEFAULT 'IN_APP' CHECK (channel IN ('IN_APP', 'WECOM')),
    status VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED'
        CHECK (status IN ('SCHEDULED', 'PROCESSING', 'SENT', 'FAILED', 'CANCELLED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, task_id, remind_at, channel),
    FOREIGN KEY (tenant_id, task_id)
        REFERENCES management_task (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE position_assignment_reporting_relation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    subordinate_assignment_id UUID NOT NULL,
    leader_assignment_id UUID NOT NULL,
    relation_type VARCHAR(24) NOT NULL DEFAULT 'INDIRECT_LEADER'
        CHECK (relation_type = 'INDIRECT_LEADER'),
    valid_from DATE NOT NULL,
    valid_to DATE,
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, subordinate_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, leader_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, created_by) REFERENCES user_account (tenant_id, id),
    CHECK (subordinate_assignment_id <> leader_assignment_id),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

CREATE UNIQUE INDEX ux_reporting_relation_current
    ON position_assignment_reporting_relation
       (tenant_id, subordinate_assignment_id, leader_assignment_id, relation_type)
    WHERE valid_to IS NULL;

CREATE TABLE executive_task_directive (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    task_id UUID NOT NULL,
    chairman_assignment_id UUID NOT NULL,
    target_assignment_id UUID NOT NULL,
    client_command_id VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, task_id),
    UNIQUE (tenant_id, chairman_assignment_id, client_command_id),
    FOREIGN KEY (tenant_id, task_id) REFERENCES management_task (tenant_id, id),
    FOREIGN KEY (tenant_id, chairman_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    FOREIGN KEY (tenant_id, target_assignment_id)
        REFERENCES employee_position_assignment (tenant_id, id),
    CHECK (chairman_assignment_id <> target_assignment_id)
);

CREATE INDEX ix_work_plan_owner_status
    ON management_work_plan (tenant_id, owner_assignment_id, status, period_start DESC);
CREATE INDEX ix_work_plan_revision_plan
    ON management_work_plan_revision (tenant_id, work_plan_id, revision_no DESC);
CREATE INDEX ix_work_plan_item_revision
    ON management_work_plan_item (tenant_id, revision_id, item_no);
CREATE INDEX ix_work_plan_item_target
    ON management_work_plan_item (tenant_id, target_assignment_id)
    WHERE target_assignment_id IS NOT NULL;
CREATE INDEX ix_work_plan_item_reminder_item
    ON management_work_plan_item_reminder (tenant_id, item_id);
CREATE INDEX ix_work_plan_review_reviewer
    ON management_work_plan_review (tenant_id, reviewer_assignment_id, reviewed_at DESC);
CREATE INDEX ix_work_plan_decision_item
    ON management_work_plan_item_decision (tenant_id, item_id);
CREATE INDEX ix_work_plan_decision_reminder
    ON management_work_plan_item_decision_reminder (tenant_id, item_decision_id);
CREATE INDEX ix_task_reminder_queue
    ON task_reminder (tenant_id, coalesce(next_attempt_at, remind_at), id)
    WHERE status IN ('SCHEDULED', 'FAILED');
CREATE INDEX ix_reporting_relation_subordinate
    ON position_assignment_reporting_relation
       (tenant_id, subordinate_assignment_id, valid_from, valid_to);
CREATE INDEX ix_reporting_relation_leader
    ON position_assignment_reporting_relation
       (tenant_id, leader_assignment_id, valid_from, valid_to);
CREATE INDEX ix_executive_directive_target
    ON executive_task_directive (tenant_id, target_assignment_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_non_draft_work_plan_content_change()
RETURNS trigger AS $$
DECLARE revision_status VARCHAR(20);
BEGIN
    SELECT status INTO revision_status FROM management_work_plan_revision
    WHERE tenant_id = coalesce(NEW.tenant_id, OLD.tenant_id)
      AND id = coalesce(NEW.revision_id, OLD.revision_id);
    IF revision_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Submitted work-plan revision content is immutable';
    END IF;
    RETURN coalesce(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_work_plan_item_draft_only
    BEFORE INSERT OR UPDATE OR DELETE ON management_work_plan_item
    FOR EACH ROW EXECUTE FUNCTION reject_non_draft_work_plan_content_change();

CREATE OR REPLACE FUNCTION reject_non_draft_work_plan_reminder_change()
RETURNS trigger AS $$
DECLARE revision_status VARCHAR(20);
BEGIN
    SELECT revision.status INTO revision_status
    FROM management_work_plan_item item
    JOIN management_work_plan_revision revision
      ON revision.tenant_id = item.tenant_id AND revision.id = item.revision_id
    WHERE item.tenant_id = coalesce(NEW.tenant_id, OLD.tenant_id)
      AND item.id = coalesce(NEW.item_id, OLD.item_id);
    IF revision_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Submitted work-plan revision reminders are immutable';
    END IF;
    RETURN coalesce(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_work_plan_item_reminder_draft_only
    BEFORE INSERT OR UPDATE OR DELETE ON management_work_plan_item_reminder
    FOR EACH ROW EXECUTE FUNCTION reject_non_draft_work_plan_reminder_change();

CREATE OR REPLACE FUNCTION enforce_work_plan_revision_immutability()
RETURNS trigger AS $$
BEGIN
    IF OLD.status <> 'DRAFT' AND ROW(
        NEW.tenant_id, NEW.work_plan_id, NEW.revision_no, NEW.summary,
        NEW.created_by_assignment_id, NEW.created_at
    ) IS DISTINCT FROM ROW(
        OLD.tenant_id, OLD.work_plan_id, OLD.revision_no, OLD.summary,
        OLD.created_by_assignment_id, OLD.created_at
    ) THEN
        RAISE EXCEPTION 'Submitted work-plan revision is immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_work_plan_revision_immutable
    BEFORE UPDATE ON management_work_plan_revision
    FOR EACH ROW EXECUTE FUNCTION enforce_work_plan_revision_immutability();

CREATE OR REPLACE FUNCTION reject_reporting_relation_delete()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Reporting relations must be closed with valid_to, not deleted';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reporting_relation_no_delete
    BEFORE DELETE ON position_assignment_reporting_relation
    FOR EACH ROW EXECUTE FUNCTION reject_reporting_relation_delete();

CREATE OR REPLACE FUNCTION validate_reporting_graph()
RETURNS trigger AS $$
DECLARE
    tenant_key UUID;
    subordinate_key UUID;
    leader_key UUID;
    relation_key UUID;
BEGIN
    IF TG_TABLE_NAME = 'employee_position_assignment' THEN
        IF NEW.manager_assignment_id IS NULL THEN RETURN NEW; END IF;
        tenant_key := NEW.tenant_id;
        subordinate_key := NEW.id;
        leader_key := NEW.manager_assignment_id;
        relation_key := NULL;
    ELSE
        IF NEW.valid_to IS NOT NULL AND NEW.valid_to < current_date THEN RETURN NEW; END IF;
        tenant_key := NEW.tenant_id;
        subordinate_key := NEW.subordinate_assignment_id;
        leader_key := NEW.leader_assignment_id;
        relation_key := NEW.id;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(tenant_key::text, 39));
    IF subordinate_key = leader_key THEN
        RAISE EXCEPTION 'Reporting relation cannot point to itself';
    END IF;

    IF EXISTS (
        WITH RECURSIVE edges(subordinate_id, leader_id) AS (
            SELECT id, manager_assignment_id
            FROM employee_position_assignment
            WHERE tenant_id = tenant_key AND manager_assignment_id IS NOT NULL
            UNION ALL
            SELECT subordinate_assignment_id, leader_assignment_id
            FROM position_assignment_reporting_relation
            WHERE tenant_id = tenant_key
              AND (valid_to IS NULL OR valid_to >= current_date)
              AND (relation_key IS NULL OR id <> relation_key)
        ), chain(assignment_id) AS (
            SELECT leader_key
            UNION
            SELECT edge.leader_id FROM edges edge
            JOIN chain ON edge.subordinate_id = chain.assignment_id
        )
        SELECT 1 FROM chain WHERE assignment_id = subordinate_key
    ) THEN
        RAISE EXCEPTION 'Reporting relation would create a cycle';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
BEGIN
    IF EXISTS (
        WITH RECURSIVE edges(tenant_id, subordinate_id, leader_id) AS (
            SELECT tenant_id, id, manager_assignment_id
            FROM employee_position_assignment WHERE manager_assignment_id IS NOT NULL
        ), walk(tenant_id, origin_id, assignment_id, path, cycle) AS (
            SELECT tenant_id, subordinate_id, leader_id,
                   ARRAY[subordinate_id, leader_id], subordinate_id = leader_id
            FROM edges
            UNION ALL
            SELECT walk.tenant_id, walk.origin_id, edge.leader_id,
                   walk.path || edge.leader_id, edge.leader_id = ANY(walk.path)
            FROM walk JOIN edges edge
              ON edge.tenant_id = walk.tenant_id AND edge.subordinate_id = walk.assignment_id
            WHERE NOT walk.cycle
        )
        SELECT 1 FROM walk WHERE cycle
    ) THEN
        RAISE EXCEPTION 'V39 blocked: existing direct reporting assignments contain a cycle';
    END IF;
END $$;

CREATE CONSTRAINT TRIGGER trg_assignment_reporting_graph
    AFTER INSERT OR UPDATE OF manager_assignment_id ON employee_position_assignment
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_reporting_graph();
CREATE CONSTRAINT TRIGGER trg_indirect_reporting_graph
    AFTER INSERT OR UPDATE ON position_assignment_reporting_relation
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_reporting_graph();

CREATE OR REPLACE FUNCTION validate_new_task_provenance()
RETURNS trigger AS $$
DECLARE
    task_tenant UUID;
    task_key UUID;
    source_code VARCHAR(32);
    actor_assignment UUID;
    plan_links INTEGER;
    directives INTEGER;
BEGIN
    IF TG_TABLE_NAME = 'management_task' THEN
        task_tenant := NEW.tenant_id; task_key := NEW.id;
    ELSE
        task_tenant := coalesce(NEW.tenant_id, OLD.tenant_id);
        task_key := coalesce(NEW.task_id, OLD.task_id);
    END IF;
    SELECT creation_source, created_by_assignment_id
      INTO source_code, actor_assignment
      FROM management_task WHERE tenant_id = task_tenant AND id = task_key;
    IF NOT FOUND THEN RETURN coalesce(NEW, OLD); END IF;
    SELECT count(*) INTO plan_links FROM management_work_plan_task_link
      WHERE tenant_id = task_tenant AND task_id = task_key;
    SELECT count(*) INTO directives FROM executive_task_directive
      WHERE tenant_id = task_tenant AND task_id = task_key;

    IF source_code IN ('WORK_PLAN', 'CHAIRMAN_DIRECTIVE') AND actor_assignment IS NULL THEN
        RAISE EXCEPTION 'New task source requires created_by_assignment_id';
    ELSIF source_code = 'WORK_PLAN' AND (plan_links <> 1 OR directives <> 0) THEN
        RAISE EXCEPTION 'WORK_PLAN task requires exactly one work-plan link';
    ELSIF source_code = 'CHAIRMAN_DIRECTIVE' AND (directives <> 1 OR plan_links <> 0) THEN
        RAISE EXCEPTION 'CHAIRMAN_DIRECTIVE task requires exactly one directive';
    ELSIF source_code NOT IN ('WORK_PLAN', 'CHAIRMAN_DIRECTIVE')
          AND (plan_links <> 0 OR directives <> 0) THEN
        RAISE EXCEPTION 'Legacy/manual/rule tasks cannot use V39 provenance links';
    END IF;
    IF source_code = 'CHAIRMAN_DIRECTIVE' AND NOT EXISTS (
        SELECT 1 FROM executive_task_directive directive
        WHERE directive.tenant_id = task_tenant AND directive.task_id = task_key
          AND directive.chairman_assignment_id = actor_assignment
    ) THEN
        RAISE EXCEPTION 'Chairman directive actor does not match task creator assignment';
    END IF;
    RETURN coalesce(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_management_task_provenance
    AFTER INSERT OR UPDATE OF creation_source, created_by_assignment_id ON management_task
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_new_task_provenance();
CREATE CONSTRAINT TRIGGER trg_work_plan_task_link_provenance
    AFTER INSERT OR UPDATE OR DELETE ON management_work_plan_task_link
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_new_task_provenance();
CREATE CONSTRAINT TRIGGER trg_executive_directive_provenance
    AFTER INSERT OR UPDATE OR DELETE ON executive_task_directive
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_new_task_provenance();

CREATE OR REPLACE FUNCTION enforce_position_profile_permission_boundary()
RETURNS trigger AS $$
DECLARE
    is_delegable BOOLEAN;
    permission_code VARCHAR(120);
    current_scope VARCHAR(16);
    group_baseline UUID;
    position_code VARCHAR(64);
    default_role_code VARCHAR(64);
BEGIN
    SELECT delegable_to_position, code INTO is_delegable, permission_code
    FROM permission WHERE id = NEW.permission_id;
    IF is_delegable IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Protected permission cannot be delegated through a position profile';
    END IF;
    SELECT profile.scope_type, version.based_on_group_version_id,
           position_item.code, default_role.code
      INTO current_scope, group_baseline, position_code, default_role_code
      FROM position_function_profile_version version
      JOIN position_function_profile profile
        ON profile.tenant_id = version.tenant_id AND profile.id = version.profile_id
      JOIN position_definition position_item
        ON position_item.tenant_id = profile.tenant_id AND position_item.id = profile.position_id
      JOIN position_function_profile group_profile
        ON group_profile.tenant_id = profile.tenant_id
       AND group_profile.position_id = profile.position_id
       AND group_profile.scope_type = 'GROUP'
      JOIN app_role default_role
        ON default_role.tenant_id = group_profile.tenant_id
       AND default_role.id = group_profile.default_role_id
     WHERE version.tenant_id = NEW.tenant_id AND version.id = NEW.profile_version_id;
    IF permission_code IN ('executive-task.read', 'executive-task.assign')
       AND NOT (position_code = 'GROUP_CHAIRMAN' AND default_role_code = 'GROUP_CHAIRMAN') THEN
        RAISE EXCEPTION 'Executive task permissions are reserved for the chairman position';
    END IF;
    IF permission_code = 'dashboard.ceo'
       AND default_role_code NOT IN ('CEO', 'GROUP_CHAIRMAN') THEN
        RAISE EXCEPTION 'CEO dashboard can only be delegated to CEO or chairman positions';
    END IF;
    IF current_scope = 'HOTEL' AND NOT EXISTS (
        SELECT 1 FROM position_function_profile_permission allowed
        WHERE allowed.tenant_id = NEW.tenant_id
          AND allowed.profile_version_id = group_baseline
          AND allowed.permission_id = NEW.permission_id
    ) THEN
        RAISE EXCEPTION 'Hotel position profile cannot exceed the group-standard permission set';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TEMP TABLE v39_new_position_matrix (
    position_code VARCHAR(64) PRIMARY KEY,
    position_name VARCHAR(120) NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(120) NOT NULL,
    authorization_scope_type VARCHAR(24) NOT NULL
) ON COMMIT DROP;

INSERT INTO v39_new_position_matrix VALUES
    ('GROUP_CHAIRMAN', '集团董事长', 'GROUP_CHAIRMAN', '集团董事长', 'TENANT'),
    ('GROUP_GENERAL_MANAGER', '集团总经理', 'CEO', '集团总经理', 'ORG_TREE'),
    ('HR_ADMINISTRATION_SUPERVISOR', '行政人事主管', 'HR_ADMINISTRATION_SUPERVISOR', '行政人事主管', 'ORG_UNIT'),
    ('HR_ADMINISTRATION', '行政人事', 'HR_ADMINISTRATION', '行政人事', 'SELF');

CREATE TEMP TABLE v39_position_permission_matrix (
    position_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (position_code, permission_code)
) ON COMMIT DROP;

INSERT INTO v39_position_permission_matrix VALUES
    ('GROUP_CHAIRMAN','org.read'), ('GROUP_CHAIRMAN','dashboard.ceo'),
    ('GROUP_CHAIRMAN','dashboard.hotel'), ('GROUP_CHAIRMAN','dashboard.operations'),
    ('GROUP_CHAIRMAN','work-record.team-read'), ('GROUP_CHAIRMAN','task.review'),
    ('GROUP_CHAIRMAN','daily-report.team-read'), ('GROUP_CHAIRMAN','daily-operation.read'),
    ('GROUP_CHAIRMAN','daily-operation.cross-hotel-read'),
    ('GROUP_CHAIRMAN','operation-snapshot.read'), ('GROUP_CHAIRMAN','operation-snapshot.compare'),
    ('GROUP_CHAIRMAN','evaluation.read'), ('GROUP_CHAIRMAN','notification.read'),
    ('GROUP_CHAIRMAN','work-plan.team-read'), ('GROUP_CHAIRMAN','executive-task.read'),
    ('GROUP_CHAIRMAN','executive-task.assign'), ('GROUP_CHAIRMAN','ui.module.workbench'),
    ('GROUP_CHAIRMAN','ui.module.hotel-dashboard'), ('GROUP_CHAIRMAN','ui.module.operations-dashboard'),
    ('GROUP_CHAIRMAN','ui.module.team-work'), ('GROUP_CHAIRMAN','ui.module.tasks'),
    ('GROUP_CHAIRMAN','ui.module.daily-reports-my'), ('GROUP_CHAIRMAN','ui.module.daily-operations'),
    ('GROUP_CHAIRMAN','ui.module.evaluations'), ('GROUP_CHAIRMAN','ui.module.notifications'),
    ('GROUP_CHAIRMAN','ui.module.work-plans'),
    ('GROUP_GENERAL_MANAGER','ui.module.work-plans'),
    ('GROUP_GENERAL_MANAGER','work-plan.team-read'), ('GROUP_GENERAL_MANAGER','work-plan.review'),
    ('GROUP_GENERAL_MANAGER','work-record.team-read'),
    ('HR_ADMINISTRATION_SUPERVISOR','org.read'),
    ('HR_ADMINISTRATION_SUPERVISOR','work-record.read'),
    ('HR_ADMINISTRATION_SUPERVISOR','work-record.team-read'),
    ('HR_ADMINISTRATION_SUPERVISOR','task.read'), ('HR_ADMINISTRATION_SUPERVISOR','task.act'),
    ('HR_ADMINISTRATION_SUPERVISOR','task.review'),
    ('HR_ADMINISTRATION_SUPERVISOR','notification.read'),
    ('HR_ADMINISTRATION_SUPERVISOR','work-plan.read'),
    ('HR_ADMINISTRATION_SUPERVISOR','work-plan.submit'),
    ('HR_ADMINISTRATION_SUPERVISOR','work-plan.team-read'),
    ('HR_ADMINISTRATION_SUPERVISOR','work-plan.review'),
    ('HR_ADMINISTRATION_SUPERVISOR','ui.module.workbench'),
    ('HR_ADMINISTRATION_SUPERVISOR','ui.module.team-work'),
    ('HR_ADMINISTRATION_SUPERVISOR','ui.module.tasks'),
    ('HR_ADMINISTRATION_SUPERVISOR','ui.module.notifications'),
    ('HR_ADMINISTRATION_SUPERVISOR','ui.module.work-plans'),
    ('HR_ADMINISTRATION','org.read'), ('HR_ADMINISTRATION','task.read'),
    ('HR_ADMINISTRATION','task.act'), ('HR_ADMINISTRATION','notification.read'),
    ('HR_ADMINISTRATION','ui.module.workbench'), ('HR_ADMINISTRATION','ui.module.tasks'),
    ('HR_ADMINISTRATION','ui.module.notifications');

CREATE TEMP TABLE v39_existing_position_additions (
    position_code VARCHAR(64) NOT NULL,
    permission_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (position_code, permission_code)
) ON COMMIT DROP;

INSERT INTO v39_existing_position_additions
SELECT role_code, permission_code
FROM unnest(ARRAY['GROUP_VICE_PRESIDENT']) role(role_code)
CROSS JOIN unnest(ARRAY[
    'ui.module.work-plans','work-plan.read','work-plan.submit','work-plan.team-read',
    'work-plan.review','work-record.team-read','task.review'
]) permission(permission_code);

INSERT INTO v39_existing_position_additions
SELECT role_code, permission_code
FROM unnest(ARRAY[
    'OTA_OPERATION_MANAGER','GENERAL_MANAGER','ASSISTANT_GENERAL_MANAGER',
    'FRONT_OFFICE_SUPERVISOR','HOUSEKEEPING_SUPERVISOR'
]) role(role_code)
CROSS JOIN unnest(ARRAY[
    'ui.module.work-plans','work-plan.read','work-plan.submit','work-plan.team-read',
    'work-plan.review','work-record.team-read'
]) permission(permission_code);

DO $$
DECLARE invalid_codes TEXT;
BEGIN
    SELECT string_agg(DISTINCT matrix.permission_code, ', ' ORDER BY matrix.permission_code)
    INTO invalid_codes
    FROM (
        SELECT permission_code FROM v39_position_permission_matrix
        UNION ALL SELECT permission_code FROM v39_existing_position_additions
    ) matrix
    LEFT JOIN permission item ON item.code = matrix.permission_code
      AND item.delegable_to_position = true
    WHERE item.id IS NULL;
    IF invalid_codes IS NOT NULL THEN
        RAISE EXCEPTION 'V39 contains unknown or protected position permissions: %', invalid_codes;
    END IF;
END $$;

ALTER TABLE tenant NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    tenant_record RECORD;
    actor_id UUID;
BEGIN
    FOR tenant_record IN SELECT id FROM tenant ORDER BY id LOOP
        PERFORM set_config('app.tenant_id', tenant_record.id::text, true);

        IF EXISTS (
            SELECT 1 FROM position_definition position_item
            JOIN v39_new_position_matrix matrix ON matrix.position_code = position_item.code
            WHERE position_item.tenant_id = tenant_record.id
              AND (position_item.deleted_at IS NOT NULL OR position_item.permanently_deleted_at IS NOT NULL)
        ) THEN
            RAISE EXCEPTION 'V39 blocked: a required position code is tombstoned for tenant %', tenant_record.id;
        END IF;

        INSERT INTO app_role (tenant_id, code, name, role_type)
        SELECT tenant_record.id, code, name, 'SYSTEM'
        FROM (VALUES
            ('GROUP_CHAIRMAN','集团董事长'),
            ('HR_ADMINISTRATION_SUPERVISOR','行政人事主管'),
            ('HR_ADMINISTRATION','行政人事')
        ) role_seed(code, name)
        ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, role_type = 'SYSTEM';

        IF EXISTS (
            SELECT 1 FROM v39_new_position_matrix matrix
            JOIN app_role role ON role.tenant_id = tenant_record.id AND role.code = matrix.role_code
            JOIN position_function_profile profile
              ON profile.tenant_id = role.tenant_id AND profile.default_role_id = role.id
            JOIN position_definition existing_position
              ON existing_position.tenant_id = profile.tenant_id AND existing_position.id = profile.position_id
            WHERE existing_position.code <> matrix.position_code
        ) THEN
            RAISE EXCEPTION 'V39 blocked: required role already maps to another position for tenant %', tenant_record.id;
        END IF;

        INSERT INTO position_definition
            (tenant_id, code, name, job_family, level_code, applies_to_all_hotels)
        SELECT tenant_record.id, matrix.position_code, matrix.position_name,
               'GROUP_MANAGEMENT',
               CASE matrix.position_code
                 WHEN 'GROUP_CHAIRMAN' THEN 'M6'
                 WHEN 'GROUP_GENERAL_MANAGER' THEN 'M5'
                 WHEN 'HR_ADMINISTRATION_SUPERVISOR' THEN 'M2'
                 ELSE 'P1'
               END,
               true
        FROM v39_new_position_matrix matrix
        ON CONFLICT (tenant_id, code) DO UPDATE SET name = EXCLUDED.name, status = 'ACTIVE';

        INSERT INTO position_function_profile
            (tenant_id, position_id, scope_type, default_role_id)
        SELECT tenant_record.id, position_item.id, 'GROUP', role.id
        FROM v39_new_position_matrix matrix
        JOIN position_definition position_item
          ON position_item.tenant_id = tenant_record.id AND position_item.code = matrix.position_code
        JOIN app_role role
          ON role.tenant_id = tenant_record.id AND role.code = matrix.role_code
        WHERE NOT EXISTS (
            SELECT 1 FROM position_function_profile existing
            WHERE existing.tenant_id = tenant_record.id
              AND existing.position_id = position_item.id AND existing.scope_type = 'GROUP'
        );

        SELECT assignment.account_id INTO actor_id
        FROM role_assignment assignment
        JOIN app_role role ON role.tenant_id = assignment.tenant_id AND role.id = assignment.role_id
        JOIN user_account account ON account.tenant_id = assignment.tenant_id AND account.id = assignment.account_id
        WHERE assignment.tenant_id = tenant_record.id
          AND role.role_type = 'SYSTEM' AND role.code IN ('PLATFORM_ADMIN','CEO')
          AND account.status = 'ACTIVE'
          AND assignment.valid_from <= now()
          AND (assignment.valid_to IS NULL OR assignment.valid_to > now())
        ORDER BY CASE role.code WHEN 'PLATFORM_ADMIN' THEN 0 ELSE 1 END, assignment.account_id
        LIMIT 1;

        INSERT INTO position_function_profile_version
            (tenant_id, profile_id, version_no, lifecycle_status,
             authorization_scope_type, created_by, published_by, published_at)
        SELECT tenant_record.id, profile.id, 1,
               CASE WHEN actor_id IS NULL THEN 'DRAFT' ELSE 'PUBLISHED' END,
               matrix.authorization_scope_type, actor_id, actor_id,
               CASE WHEN actor_id IS NULL THEN NULL ELSE now() END
        FROM v39_new_position_matrix matrix
        JOIN position_definition position_item
          ON position_item.tenant_id = tenant_record.id AND position_item.code = matrix.position_code
        JOIN position_function_profile profile
          ON profile.tenant_id = tenant_record.id AND profile.position_id = position_item.id
         AND profile.scope_type = 'GROUP'
        WHERE NOT EXISTS (
            SELECT 1 FROM position_function_profile_version version
            WHERE version.tenant_id = profile.tenant_id AND version.profile_id = profile.id
        );

        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, permission_item.id
        FROM v39_position_permission_matrix matrix
        JOIN position_definition position_item
          ON position_item.tenant_id = tenant_record.id AND position_item.code = matrix.position_code
        JOIN position_function_profile profile
          ON profile.tenant_id = tenant_record.id AND profile.position_id = position_item.id
         AND profile.scope_type = 'GROUP'
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id AND version.profile_id = profile.id
         AND version.version_no = 1
        JOIN permission permission_item ON permission_item.code = matrix.permission_code
        ON CONFLICT DO NOTHING;

        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM v39_new_position_matrix matrix
        JOIN app_role role ON role.tenant_id = tenant_record.id AND role.code = matrix.role_code
        JOIN v39_position_permission_matrix grant_item ON grant_item.position_code = matrix.position_code
        JOIN permission permission_item ON permission_item.code = grant_item.permission_code
        ON CONFLICT DO NOTHING;

        -- Add only to untouched V35/V36 publications and their untouched copied drafts.
        INSERT INTO position_function_profile_permission
            (tenant_id, profile_version_id, permission_id)
        SELECT tenant_record.id, version.id, permission_item.id
        FROM v39_existing_position_additions addition
        JOIN position_definition position_item
          ON position_item.tenant_id = tenant_record.id AND position_item.code = addition.position_code
        JOIN position_function_profile profile
          ON profile.tenant_id = tenant_record.id AND profile.position_id = position_item.id
         AND profile.scope_type = 'GROUP'
        JOIN position_function_profile_version version
          ON version.tenant_id = profile.tenant_id AND version.profile_id = profile.id
        JOIN permission permission_item ON permission_item.code = addition.permission_code
        WHERE (
            (version.version_no = 1 AND version.lifecycle_status = 'PUBLISHED')
            OR (version.version_no = 2 AND version.lifecycle_status = 'DRAFT'
                AND version.row_version = 0 AND version.copied_from_version_id IS NOT NULL)
        ) AND EXISTS (
            SELECT 1 FROM audit_log audit
            WHERE audit.tenant_id = profile.tenant_id
              AND audit.action = 'SYSTEM_POSITION_PROFILE_DEFAULT_PUBLISHED'
              AND audit.resource_type = 'POSITION_FUNCTION_PROFILE'
              AND audit.resource_id = profile.id
              AND audit.after_data ->> 'source' IN ('V35','V36')
        )
        ON CONFLICT DO NOTHING;

        INSERT INTO role_permission (tenant_id, role_id, permission_id)
        SELECT tenant_record.id, role.id, permission_item.id
        FROM v39_existing_position_additions addition
        JOIN app_role role ON role.tenant_id = tenant_record.id AND role.code = addition.position_code
        JOIN permission permission_item ON permission_item.code = addition.permission_code
        ON CONFLICT DO NOTHING;
    END LOOP;
END $$;

ALTER TABLE tenant FORCE ROW LEVEL SECURITY;

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'management_work_plan','management_work_plan_revision','management_work_plan_item',
        'management_work_plan_item_reminder','management_work_plan_review',
        'management_work_plan_item_decision','management_work_plan_item_decision_reminder',
        'management_work_plan_task_link','task_reminder',
        'position_assignment_reporting_relation','executive_task_directive'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
            table_name
        );
    END LOOP;
END $$;

CREATE TRIGGER trg_management_work_plan_updated_at BEFORE UPDATE ON management_work_plan
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_management_work_plan_revision_updated_at BEFORE UPDATE ON management_work_plan_revision
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_management_work_plan_item_updated_at BEFORE UPDATE ON management_work_plan_item
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_task_reminder_updated_at BEFORE UPDATE ON task_reminder
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_reporting_relation_updated_at BEFORE UPDATE ON position_assignment_reporting_relation
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'hotel_ai_os_app') THEN
        REVOKE ALL ON
            management_work_plan, management_work_plan_revision, management_work_plan_item,
            management_work_plan_item_reminder, management_work_plan_review,
            management_work_plan_item_decision, management_work_plan_item_decision_reminder,
            management_work_plan_task_link, task_reminder,
            position_assignment_reporting_relation, executive_task_directive
        FROM hotel_ai_os_app;
        GRANT SELECT, INSERT, UPDATE ON management_work_plan, management_work_plan_revision,
            task_reminder, position_assignment_reporting_relation TO hotel_ai_os_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON management_work_plan_item,
            management_work_plan_item_reminder TO hotel_ai_os_app;
        GRANT SELECT, INSERT ON management_work_plan_review,
            management_work_plan_item_decision, management_work_plan_item_decision_reminder,
            management_work_plan_task_link, executive_task_directive TO hotel_ai_os_app;
    END IF;
END $$;

COMMENT ON COLUMN management_task.creation_source IS
    'Explicit provenance. LEGACY default keeps TECH-V0.2-PILOT.7 inserts compatible during rollback.';
COMMENT ON TABLE position_assignment_reporting_relation IS
    'Additional indirect leader edges. Close with valid_to; physical deletion is forbidden.';
