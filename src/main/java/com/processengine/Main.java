package com.processengine;

import java.util.HashMap;
import java.util.List;

import com.processengine.audit.AuditEntry;
import com.processengine.domain.config.StepConfig;
import com.processengine.domain.enums.ActionType;
import com.processengine.domain.enums.StepType;
import com.processengine.domain.model.ProcessDefinition;
import com.processengine.domain.model.ProcessInstance;
import com.processengine.domain.model.StepDefinition;
import com.processengine.domain.model.User;
import com.processengine.engine.Engine;
import com.processengine.engine.InMemoryEngine;

public class Main {

    public static void main(String[] args) {
        Engine engine = new InMemoryEngine();
        StepDefinition step1 = new StepDefinition(
                "step-1",
                "Initiation",
                StepType.TASK,
                List.of("step-2a", "step-2b"),
                null,
                new StepConfig(0, List.of(), null)
        );

        StepDefinition step2a = new StepDefinition(
                "step-2a",
                "QA Approval",
                StepType.APPROVAL,
                List.of("step-3"),
                "group-1",
                new StepConfig(1, List.of(), "step-1")
        );

        StepDefinition step2b = new StepDefinition(
                "step-2b",
                "Dept Head Approval",
                StepType.APPROVAL,
                List.of("step-3"),
                "group-1",
                new StepConfig(1, List.of(), "step-1")
        );

        StepDefinition step3 = new StepDefinition(
                "step-3",
                "Final Task",
                StepType.TASK,
                List.of(),
                null,
                new StepConfig(0, List.of(), null)
        );

        ProcessDefinition def = new ProcessDefinition(
                "def-1",
                List.of(step1, step2a, step2b, step3)
        );

        engine.loadDefinition(def);

        User user1 = new User("user-1");

        ProcessInstance instance = engine.startProcess(
                "def-1",
                user1,
                new HashMap<>()
        );

        System.out.println("Process started: " + instance.id);

        engine.advanceStep(instance.id, "step-1", ActionType.COMPLETED, user1);

        System.out.println("Step 1 completed");
        User qa = new User("qa-manager");
        User deptHead = new User("dept-head");

        engine.advanceStep(instance.id, "step-2a", ActionType.APPROVED, qa);
        System.out.println("QA approved");

        engine.advanceStep(instance.id, "step-2b", ActionType.APPROVED, deptHead);
        System.out.println("Dept Head approved");


        engine.advanceStep(instance.id, "step-3", ActionType.COMPLETED, user1);
        System.out.println("Final step completed");


        List<AuditEntry> audit = engine.getAuditTrail(instance.id);

        System.out.println("\n===== AUDIT LOG =====");

        for (AuditEntry entry : audit) {
            System.out.println(
                    "Step: " + entry.stepId +
                            " | Action: " + entry.action +
                            " | From: " + entry.prevState +
                            " → To: " + entry.newState +
                            " | By: " + entry.actor.id
            );
        }
    }
}

