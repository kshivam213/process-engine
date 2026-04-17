# System Evolution Design

A few scenarios came up as we thought through longer-term needs for the system — versioning, cross-process dependencies, and audit scale. This doc captures the thinking behind each one.
 
---

## Scenario A: Process Versioning

### The Problem

Customers want to update their process definitions without breaking anything that's already running. A change to a definition today shouldn't suddenly alter the behavior of an instance that started last week. New instances should pick up the latest definition; in-flight ones should stay on whatever they started with.

### Approach

Treat process definitions as immutable. Any update creates a brand new row in `process_definitions` rather than overwriting the existing one. We never edit definitions in place.

```sql
process_definitions
--------------------
id (UUID)
name
version (INT)
status (ACTIVE / INACTIVE)
created_at
```

```sql
process_instances
--------------------
id
process_definition_id  -- pinned to the version at start time
```

When starting a new instance, we fetch the latest `ACTIVE` version and store that reference on the instance. From that point on, all step execution is tied to that specific version — it never drifts.

### Migration Risks

**Partial updates during deployment** — if a definition gets partially written, things get inconsistent. We avoid this by treating definition publishing as an atomic operation.

**Backward compatibility** — old instances might reference steps that no longer exist in newer versions, or routing logic that has changed. The fix here is simple: never delete old definitions. Just mark them `INACTIVE` and leave them in place.

**Storage growth** — yes, multiple versions means more rows. That's a conscious trade-off for correctness and auditability. It's worth it.
 
---

## Scenario B: Cross-Process Dependencies

### The Problem

Some processes have hard dependencies on others. For example, a Document Review process might need to wait until a CAPA process has reached a certain step before it can proceed.

### Approach

We model this explicitly with a dependency table:

```sql
process_dependencies
----------------------
id
dependent_process_instance_id
blocking_process_instance_id
blocking_step_id
status (WAITING / SATISFIED / CANCELLED)
```

The execution flow is straightforward: when a dependent process hits a blocking step, the engine checks the dependency. If the blocking condition isn't met yet, the step just stays blocked. When the blocking process eventually reaches the required step, we flip the dependency status to `SATISFIED` and resume the dependent process.

### Preventing Circular Dependencies

The main risk here is cycles — Process A waits for B, B waits for C, C waits for A. We catch this at creation time by modeling dependencies as a directed graph and running cycle detection (DFS) before persisting anything.

```
A → B → C → A  ← reject this
```

### What Happens When a Blocking Process Gets Cancelled?

A few options here, with different trade-offs:

| Strategy | Behavior |
|----------|----------|
| Fail the dependent | Strict, predictable |
| Allow manual override | More flexible |
| Retry / keep waiting | Conservative default |

Our recommendation: mark the dependency as `CANCELLED`, fail the dependent step with an audit entry explaining why, and give admins the ability to manually override if needed. It keeps the system predictable while not painting operators into a corner.
 
---

## Scenario C: Audit Trail at Scale

### The Problem

The audit log is sitting at 500M rows. Recent queries are still fast, but anything that spans multiple instances — compliance queries, cross-process reporting — starts crawling. This needs to be addressed before it becomes a production issue.

### Solution 1: Partition the Table

The quickest win is range partitioning on `created_at`:

```sql
PARTITION BY RANGE (created_at)
```

This gives us monthly slices (`audit_log_2025_01`, `audit_log_2025_02`, etc.) and means most queries only touch the partitions they actually need. Writes also get faster, and archival becomes much cleaner.

### Solution 2: Hot/Cold Data Split

Not all audit data is accessed equally. Recent data gets queried constantly; older data is mostly for compliance and rarely touched. Splitting by age makes sense:

| Data | Storage |
|------|---------|
| Last 90 days | Postgres (OLTP) |
| Older | S3 / Data Warehouse |

Recent queries hit Postgres as usual. Historical queries route to the OLAP system. The query layer handles the routing.

### Solution 3: Move Compliance Queries to OLAP

For the cross-instance, full-scan, aggregation-heavy compliance queries, a purpose-built OLAP system (BigQuery, Snowflake, Redshift — pick one) is the right tool. Postgres isn't designed for this pattern at this volume.

### Trade-offs

| Approach | Pros | Cons |
|----------|------|------|
| Partitioning | Simple, fast, low overhead | Doesn't scale forever |
| Hot/cold archival | Cost-effective | Adds query routing complexity |
| OLAP layer | Actually scales | More infra to manage |

### Recommended Path

Do all three, in order:
1. Partition `audit_log` in Postgres now — immediate win
2. Set up archival to object storage for data older than 90 days
3. Introduce an OLAP system for compliance and analytics queries
   As a bonus, pre-computed compliance reports and materialized views can take a lot of pressure off both systems once they're in place.

---

## Summary

| Scenario | Approach |
|----------|----------|
| Process versioning | Immutable definitions, version pinned at instance start |
| Cross-process deps | Explicit dependency table with graph-based cycle detection |
| Audit scale | Partition + archive + OLAP |

The through-line across all three: prefer immutability, keep a full audit trail, and separate concerns as data grows. These aren't exciting decisions, but they're the ones that tend to hold up.
