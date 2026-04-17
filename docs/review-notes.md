# Code Review Notes

## 1. Missing Transition Validation (Critical Bug)

**Problem**
The current implementation only checks:

```
if (step.status != IN_PROGRESS) throw...
```

However, it does not validate whether a transition between two states is actually allowed. This can lead to invalid transitions such as:

* COMPLETED → IN_PROGRESS
* REJECTED → COMPLETED

**Fix**
Introduce an explicit transition map:

```
Map<Status, Set<Status>> validTransitions = Map.of(
    Status.PENDING, Set.of(Status.IN_PROGRESS),
    Status.IN_PROGRESS, Set.of(Status.COMPLETED, Status.REJECTED, Status.ESCALATED),
    Status.REJECTED, Set.of(Status.IN_PROGRESS)
);
```

**Review Note**
Added explicit state transition validation to prevent invalid transitions and ensure correctness of the state machine.

---

## 2. Parallel Logic is Incorrect (Subtle Bug)

**Problem**

```
if (allDone) activateNextSteps()
```

This can lead to duplicate activations because:

* Each step has `nextStepIds`
* Multiple steps completing may trigger the same next steps repeatedly

**Fix**
Trigger activation only once per group:

```
if (isGroupCompleted(step)) {
    activateNextSteps(...)
}
```

Also ensure activation happens only when:

* Status changes from a non-COMPLETED state to COMPLETED

**Review Note**
Fixed parallel execution logic to prevent duplicate activation of next steps when multiple steps in the same group complete.

---

## 3. No Field Validation (Requirement Missed)

**Problem**
Input fields passed to `StartProcess(... fields)` are not validated.

**Fix**
Add validation against required fields:

```
validateFields(step.Def.config.requiredFields, fields)
```

**Review Note**
Added field validation to enforce required input constraints defined in process configuration.

---

## 4. StepInstance Mutates Without Guard

**Problem**

```
step.status = COMPLETED
```

State mutation happens directly without encapsulation or validation.

**Fix**
Encapsulate transitions:

```
step.transitionTo(Status newStatus)
```

**Review Note**
Encapsulated state transitions within `StepInstance` to centralize validation and improve maintainability.

---

## 5. Audit Logging is Weak

**Problem**

```
new AuditEntry(... "")
```

Audit logs are missing:

* Reason
* Consistent event type
* Contextual information

**Fix**
Include meaningful metadata:

```
reason = "APPROVED by QA"
```

**Review Note**
Improved audit logging to include meaningful reason and context for each transition.

---

## 6. No Idempotency / Duplicate Action Handling

**Problem**
The same user can perform the same action multiple times:

* QA → APPROVED
* QA → APPROVED again

**Fix**
Add idempotency check:

```
if (alreadyActed(actor)) return;
```

**Review Note**
Added idempotency checks to prevent duplicate actions from the same actor.

---

## 7. Hardcoded Logic (Tightly Coupled)

**Problem**

```
if (step.def.type == TASK) ...
if (step.def.type == APPROVAL) ...
```

Logic is tightly coupled and hard to extend.

**Fix**
Extract logic into a separate method:

```
evaluateStep(step, action)
```

**Review Note**
Extracted step evaluation logic to improve readability and separation of concerns.

---

## 8. No Error Types (Poor Developer Experience)

**Problem**

```
throw new RuntimeException(...)
```

Using generic exceptions makes debugging and handling difficult.

**Fix**
Introduce domain-specific exceptions:

```
class InvalidTransitionException extends RuntimeException {}
```

**Review Note**
Replaced generic exceptions with domain-specific exceptions for better error handling and clarity.
