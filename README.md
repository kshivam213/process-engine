# Process Engine

## Data Model & Schema

### Design Principles

- **Separation of concerns**: Process *definitions* (blueprints) vs *instances* (runtime execution)
- **Immutability-first**: Definitions are versioned and never mutated; audit logs are append-only
- **State-machine driven execution**: Each step instance represents runtime state
- **Auditability**: Every transition is recorded as an append-only event with full context
- **Extensibility**: JSONB used for flexible constraints and configuration

---

## 1. Process Definition Layer (Blueprint)

### `process_definitions`

Stores immutable, versioned process templates. Any modification to a process definition creates a **new row** with an incremented version. Old versions are preserved so that in-flight instances continue executing against the definition they were started on.

```sql
CREATE TABLE process_definitions (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    version         INT NOT NULL DEFAULT 1,
    status          VARCHAR(50) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    created_by      UUID NOT NULL,

    UNIQUE (name, version)
);

CREATE INDEX idx_pd_name_version ON process_definitions(name, version);
CREATE INDEX idx_pd_status       ON process_definitions(status);
```

**Indexing rationale:**
- `idx_pd_name_version` — supports the common lookup "give me the latest active version of definition X"; composite uniqueness also prevents duplicate versions.
- `idx_pd_status` — supports filtering for only `ACTIVE` definitions when listing available process types in the UI or API.
- Write frequency is low (only when a customer create new version), so index overhead is negligible.

---

### `process_definition_steps`

Defines the steps within a process template. Each step belongs to exactly one `process_definition` version.

```sql
CREATE TABLE process_definition_steps (
    id                    UUID PRIMARY KEY,
    process_definition_id UUID NOT NULL REFERENCES process_definitions(id),
    name                  VARCHAR(255) NOT NULL,
    step_type             VARCHAR(50) NOT NULL
                              CHECK (step_type IN ('TASK', 'APPROVAL', 'CHECKLIST', 'SIGNATURE')),
    sequence_order        INT,           -- NULL for steps inside a parallel group
    parallel_group_id     UUID,          -- non-NULL groups steps that must all complete together
    config                JSONB NOT NULL, -- validations, escalation rules, routing rules
    created_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pds_definition_id ON process_definition_steps(process_definition_id);
```

**Why `parallel_group_id`?**
Parallel steps (e.g., "QA Manager AND Dept Head must both approve") share the same `parallel_group_id`. The engine uses this at runtime to identify which steps belong to the same parallel group and advances the process only when every step in the group reaches a terminal state. Steps without a `parallel_group_id` are sequential and ordered by `sequence_order`.

**Indexing rationale:**
- `idx_pds_definition_id` — every time a process instance starts we fetch all steps for its definition version. This ensures O(log n) lookup instead of a full table scan.

#### Example `config` (JSONB)

```json
{
  "required_fields": ["severity", "description"],
  "approval": {
    "required_approvals": 2,
    "approvers": ["QA_MANAGER", "DEPT_HEAD"]
  },
  "escalation": {
    "duration_hours": 48,
    "escalate_to_role": "QA_MANAGER"
  },
  "routing": {
    "on_reject": { "goto_step_id": "UUID" }
  }
}
```

---

## 2. Process Instance Layer (Runtime Execution)

### `process_instances`

Represents a single running execution of a specific process definition **version**. Pinning to `process_definition_id` (which includes the version) ensures that mid-flight instances are never affected when a customer publishes a new definition version.

```sql
CREATE TABLE process_instances (
    id                    UUID PRIMARY KEY,
    process_definition_id UUID NOT NULL REFERENCES process_definitions(id),
    status                VARCHAR(50) NOT NULL
                              CHECK (status IN ('RUNNING', 'COMPLETED', 'CANCELLED')),
    initiated_by          UUID NOT NULL,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pi_definition_id ON process_instances(process_definition_id);
CREATE INDEX idx_pi_status        ON process_instances(status);
```

**Indexing rationale:**
- `idx_pi_definition_id` — enables querying all instances for a given process type/version (e.g., "show me all open CAPAs").
- `idx_pi_status` — supports dashboard queries that filter by `RUNNING` instances.
- `updated_at` — lets workers and APIs efficiently poll for recently changed instances without a full table scan.

---

### `process_instance_steps` _(Core Runtime Table)_

Tracks the execution state of each step within a process instance.

```sql
CREATE TABLE process_instance_steps (
    id                  UUID PRIMARY KEY,
    process_instance_id UUID NOT NULL REFERENCES process_instances(id),
    step_definition_id  UUID NOT NULL REFERENCES process_definition_steps(id),
    status              VARCHAR(50) NOT NULL
                            CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED',
                                              'REJECTED', 'SKIPPED', 'ESCALATED')),
    assigned_to         UUID,
    assigned_role       VARCHAR(100),
    deadline_at         TIMESTAMP,
    escalated           BOOLEAN NOT NULL DEFAULT FALSE,
    started_at          TIMESTAMP,
    completed_at        TIMESTAMP,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX goto_step_id     ON process_instance_steps(process_instance_id);
CREATE INDEX idx_pis_status_deadline ON process_instance_steps(status, deadline_at)
    WHERE deadline_at IS NOT NULL;
```

**Why no `current_step` column on `process_instances`?**

We intentionally avoid storing a `current_step` pointer at the instance level. Active steps are derived dynamically:

```sql
SELECT * FROM process_instance_steps
WHERE process_instance_id = $1 AND status = 'IN_PROGRESS';
```

This naturally supports:
- **Parallel steps** — multiple steps can be `IN_PROGRESS` simultaneously.
- **Reopen flows** — a rejected step returns to `IN_PROGRESS` without any special-case logic.
- **Conditional routing** — routing just changes which step transitions to `IN_PROGRESS` next.

**Indexing rationale:**
- `idx_pis_instance_id` — the most frequent query: fetch all steps of a given instance.
- `idx_pis_status_deadline` — partial composite index (only rows where `deadline_at IS NOT NULL`) used by the escalation worker. Avoids full table scan when polling for overdue steps.

---

## 3. Step Actions

### `step_actions`

Records every discrete action a user or system takes on a step (approve, reject, comment, etc.). This is the operational record that drives transition logic (e.g., counting approvals for parallel approval steps).

```sql
CREATE TABLE step_actions (
    id               UUID PRIMARY KEY,
    step_instance_id UUID NOT NULL REFERENCES process_instance_steps(id),

    actor_id         UUID NOT NULL,
    actor_type       VARCHAR(50) NOT NULL CHECK (actor_type IN ('USER', 'SYSTEM', 'SERVICE')),

    action           VARCHAR(50) NOT NULL
                         CHECK (action IN ('APPROVED', 'REJECTED', 'COMPLETED',
                                           'COMMENTED', 'ESCALATED', 'REASSIGNED')),
    comment          TEXT,
    metadata         JSONB,

    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sa_step_instance_id ON step_actions(step_instance_id);
```

**Why a separate table instead of storing actions on `process_instance_steps`?**
1. **Parallel approvals** — multiple actors approve the same step; each gets their own row.
2. **Actor accountability** — we can answer "who approved this?" or "how many have approved so far?" with a simple `COUNT`.
3. **Audit compliance** — regulators need the full chain of actions, not just the final outcome.

**Distinction from `audit_log`:**
`step_actions` captures *what a user intended to do* (the command). `audit_log` captures *what the system did as a result* (the state change). Both are kept because they serve different queries: actions answer "who did what", audit log answers "how did state evolve".

**Indexing rationale:**
- `idx_sa_step_instance_id` — required for counting approvals during parallel approval transitions, and for fetching the action history of a step. High read frequency during every step transition.

---

## 4. Audit Log

### `audit_log` _(Strictly append-only)_

The immutable ledger of every state change in the system. Regulators and auditors read from this table exclusively.

```sql
CREATE TABLE audit_log (
    id                  BIGSERIAL PRIMARY KEY,

    entity_type         VARCHAR(50) NOT NULL CHECK (entity_type IN ('PROCESS', 'STEP')),
    entity_id           UUID NOT NULL,

    process_instance_id UUID NOT NULL,

    actor_id            UUID NOT NULL,
    actor_type          VARCHAR(50) NOT NULL,

    action              VARCHAR(50) NOT NULL,

    previous_state      JSONB,
    new_state           JSONB,

    reason              TEXT,

    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_instance_time ON audit_log(process_instance_id, created_at);
CREATE INDEX idx_audit_entity        ON audit_log(entity_type, entity_id);
```

**Indexing rationale:**
- `idx_audit_instance_time` — primary query pattern: fetch the full audit trail for an instance ordered chronologically. The composite index satisfies both the `WHERE` clause and the `ORDER BY` in one scan.
- `idx_audit_entity` — supports targeted lookups like "show me all state changes for step X" without scanning the entire log.
- `BIGSERIAL` (not UUID) is used for the PK because audit records are always accessed in insertion order; a monotonically increasing integer makes range scans cheaper.

### Immutability Enforcement

Immutability is enforced at **two layers** for defence-in-depth:

**Database layer** — strip write privileges and block DML via rules:

```sql
REVOKE UPDATE, DELETE ON audit_log FROM PUBLIC;

CREATE RULE no_update_audit AS
    ON UPDATE TO audit_log DO INSTEAD NOTHING;

CREATE RULE no_delete_audit AS
    ON DELETE TO audit_log DO INSTEAD NOTHING;
```

**Application layer** — audit records are written only through a dedicated `AuditService` that never exposes an update or delete path. This is where business context (reason, transition metadata) is assembled before insertion.

**Why application-layer logging instead of DB triggers?**
- Triggers cannot capture *why* a transition happened (business reason, routing rule that fired).
- Multi-entity state changes (process + multiple steps) need to be batched into a single logical event.
- Application-layer code is easier to unit-test, version, and evolve.

---

## 5. Escalation Handling

Escalation is handled asynchronously by a background worker that polls on a configurable interval (e.g., every 60 seconds).

### Escalation Query

```sql
SELECT *
FROM process_instance_steps
WHERE status     = 'IN_PROGRESS'
  AND deadline_at IS NOT NULL
  AND deadline_at < NOW()
  AND escalated   = FALSE;
```

The partial composite index `idx_pis_status_deadline` on `(status, deadline_at) WHERE deadline_at IS NOT NULL` ensures this poll is efficient even as the table grows.

### Worker Responsibilities

1. Fetch all overdue, non-escalated steps (query above).
2. Resolve the escalation role from the step's `config` JSONB.
3. Reassign `assigned_role` on `process_instance_steps`.
4. Set `escalated = TRUE`.
5. Insert an `audit_log` entry capturing the previous assignee, new assignee, and reason (`"deadline exceeded"`).
6. Optionally insert a `step_actions` row with `action = 'ESCALATED'` for the operational record.

---

## ER Diagram (Logical)

```
process_definitions
    │
    ├──< process_definition_steps   (parallel_group_id groups parallel steps)
    │
    └──< process_instances
             │
             └──< process_instance_steps
                      │
                      ├──< step_actions      (per-actor operational actions)
                      │
                      └──< audit_log         (append-only state-change ledger)
```
