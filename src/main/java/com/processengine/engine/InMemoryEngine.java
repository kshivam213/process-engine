package com.processengine.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // -------------------------
    @Override
    public void loadDefinition(ProcessDefinition def) {
        definitions.put(def.id, def);
    }

    // -------------------------
    @Override
    public ProcessInstance startProcess(String definitionId, User initiator,
                                        Map<String, Object> fields) {

        ProcessDefinition def = definitions.get(definitionId);
        if (def == null) throw new RuntimeException("Definition not found");

        ProcessInstance instance = new ProcessInstance(UUID.randomUUID().toString(), def);

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

    // -------------------------
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

        // Record action
        step.actions.add(new Action(action, actor, ""));

        // Evaluate transition
        if (step.def.type == StepType.TASK && action == ActionType.COMPLETED) {
            step.status = Status.COMPLETED;
        }

        if (step.def.type == StepType.APPROVAL) {

            if (action == ActionType.REJECTED) {
                step.status = Status.REJECTED;

                // route back
                activateStep(inst, step.def.config.onRejectStepId);
            } else {
                long approved = step.actions.stream()
                        .filter(a -> a.type == ActionType.APPROVED)
                        .count();

                if (approved >= step.def.config.requiredApprovals) {
                    step.status = Status.COMPLETED;
                }
            }
        }

        // Handle parallel completion
        if (step.def.parallelGroupId != null && step.status == Status.COMPLETED) {

            List<StepInstance> groupSteps = getGroupSteps(inst, step.def.parallelGroupId);

            boolean allDone = groupSteps.stream()
                    .allMatch(s -> s.status == Status.COMPLETED);

            if (allDone) {
                activateNextSteps(inst, step.def.nextStepIds);
            }

        } else if (step.status == Status.COMPLETED) {
            activateNextSteps(inst, step.def.nextStepIds);
        }

        // Audit log
        auditLog.get(instanceId).add(
                new AuditEntry(instanceId, stepId, actor, action, prev, step.status, "")
        );
    }

    // -------------------------
    private void activateNextSteps(ProcessInstance inst, List<String> nextSteps) {
        if (nextSteps == null) return;

        for (String next : nextSteps) {
            StepInstance s = inst.steps.get(next);
            if (s != null && s.status == Status.PENDING) {
                s.status = Status.IN_PROGRESS;
            }
        }
    }

    private void activateStep(ProcessInstance inst, String stepId) {
        StepInstance s = inst.steps.get(stepId);
        if (s != null) {
            s.status = Status.IN_PROGRESS;
        }
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

    // -------------------------
    @Override
    public List<AuditEntry> getAuditTrail(String instanceId) {
        return auditLog.getOrDefault(instanceId, new ArrayList<>());
    }
}