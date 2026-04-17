package com.processengine.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.processengine.audit.AuditEntry;
import com.processengine.domain.enums.ActionType;
import com.processengine.domain.enums.Status;
import com.processengine.domain.enums.StepType;
import com.processengine.domain.model.Action;
import com.processengine.domain.model.ProcessDefinition;
import com.processengine.domain.model.ProcessInstance;
import com.processengine.domain.model.StepDefinition;
import com.processengine.domain.model.StepInstance;
import com.processengine.domain.model.User;

public class InMemoryEngine implements Engine {

    Map<String, ProcessDefinition> definitions = new HashMap<>();
    Map<String, ProcessInstance> instances = new HashMap<>();
    Map<String, List<AuditEntry>> auditLog = new HashMap<>();

    private final StatusTransitionValidator transitionValidator = new StatusTransitionValidator();

    @Override
    public void loadDefinition(ProcessDefinition def) {
        definitions.put(def.id, def);
    }

    @Override
    public ProcessInstance startProcess(String definitionId, User initiator,
                                        Map<String, Object> fields) {

        ProcessDefinition def = definitions.get(definitionId);
        if (def == null) throw new RuntimeException("Definition not found");

        validateFields(def, fields);

        ProcessInstance instance = new ProcessInstance(UUID.randomUUID().toString(), def);
        if (fields != null) {
            instance.fields.putAll(fields);
        }

        for (StepDefinition sd : def.steps) {
            instance.steps.put(sd.id, new StepInstance(sd.id, sd));
        }

        // Activate first step
        StepDefinition first = def.steps.get(0);
        instance.steps.get(first.id).status = Status.IN_PROGRESS;

        instances.put(instance.id, instance);
        auditLog.put(instance.id, new ArrayList<>());

        return instance;
    }

    private void validateFields(ProcessDefinition def, Map<String, Object> fields) {
        Set<String> required = new LinkedHashSet<>();
        for (StepDefinition sd : def.steps) {
            if (sd.config != null && sd.config.requiredFields != null) {
                required.addAll(sd.config.requiredFields);
            }
        }
        if (required.isEmpty()) return;

        List<String> missing = new ArrayList<>();
        for (String key : required) {
            if (fields == null || !fields.containsKey(key) || fields.get(key) == null) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new RuntimeException("Missing required fields: " + missing);
        }
    }

    @Override
    public void advanceStep(String instanceId, String stepId,
                            ActionType action, User actor) {

        ProcessInstance inst = instances.get(instanceId);
        if (inst == null) throw new RuntimeException("Instance not found");

        StepInstance step = inst.steps.get(stepId);
        if (step == null) throw new RuntimeException("Step not found");

        if (step.status != Status.IN_PROGRESS) {
            throw new RuntimeException("Step not active");
        }

        Status prev = step.status;

        step.actions.add(new Action(action, actor, ""));

        if (step.def.type == StepType.TASK && action == ActionType.COMPLETED) {
            transitionTo(step, Status.COMPLETED);
        }

        if (step.def.type == StepType.APPROVAL) {

            if (action == ActionType.REJECTED) {
                transitionTo(step, Status.REJECTED);

                activateStep(inst, step.def.config.onRejectStepId);
            } else {
                long approved = step.actions.stream()
                        .filter(a -> a.type == ActionType.APPROVED)
                        .count();

                if (approved >= step.def.config.requiredApprovals) {
                    transitionTo(step, Status.COMPLETED);
                }
            }
        }

        boolean justCompleted = prev != Status.COMPLETED && step.status == Status.COMPLETED;
        if (justCompleted) {
            if (step.def.parallelGroupId != null) {
                if (isGroupCompleted(inst, step.def.parallelGroupId)) {
                    activateNextSteps(inst, collectGroupNextStepIds(inst, step.def.parallelGroupId));
                }
            } else {
                activateNextSteps(inst, step.def.nextStepIds);
            }
        }

        auditLog.get(instanceId).add(
                new AuditEntry(instanceId, stepId, actor, action, prev, step.status, "")
        );
    }

    private void activateNextSteps(ProcessInstance inst, List<String> nextSteps) {
        if (nextSteps == null) return;

        for (String next : nextSteps) {
            StepInstance s = inst.steps.get(next);
            if (s != null && s.status == Status.PENDING) {
                transitionTo(s, Status.IN_PROGRESS);
            }
        }
    }

    private void activateStep(ProcessInstance inst, String stepId) {
        StepInstance s = inst.steps.get(stepId);
        if (s != null) {
            transitionTo(s, Status.IN_PROGRESS);
        }
    }

    private void transitionTo(StepInstance step, Status nextStatus) {
        if (!transitionValidator.isValidTransition(step.status, nextStatus)) {
            throw new RuntimeException(
                    "Invalid status transition from " + step.status + " to " + nextStatus);
        }
        step.status = nextStatus;
    }

    private List<StepInstance> getGroupSteps(ProcessInstance inst, String groupId) {
        List<StepInstance> result = new ArrayList<>();

        for (StepInstance s : inst.steps.values()) {
            if (groupId.equals(s.def.parallelGroupId)) {
                result.add(s);
            }
        }
        return result;
    }

    private boolean isGroupCompleted(ProcessInstance inst, String groupId) {
        return getGroupSteps(inst, groupId).stream()
                .allMatch(s -> s.status == Status.COMPLETED);
    }

    private List<String> collectGroupNextStepIds(ProcessInstance inst, String groupId) {
        Set<String> merged = new LinkedHashSet<>();
        for (StepInstance s : getGroupSteps(inst, groupId)) {
            if (s.def.nextStepIds != null) {
                merged.addAll(s.def.nextStepIds);
            }
        }
        return new ArrayList<>(merged);
    }

    @Override
    public List<AuditEntry> getAuditTrail(String instanceId) {
        return auditLog.getOrDefault(instanceId, new ArrayList<>());
    }
}