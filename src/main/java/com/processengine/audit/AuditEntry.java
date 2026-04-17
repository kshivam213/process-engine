package com.processengine.audit;

import java.time.Instant;

import com.processengine.domain.enums.ActionType;
import com.processengine.domain.enums.Status;
import com.processengine.domain.model.User;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AuditEntry {
    public String instanceId;
    public String stepId;
    public User actor;
    public ActionType action;
    public Status prevState;
    public Status newState;
    public Instant time;
    public String reason;

    public AuditEntry(String instanceId, String stepId, User actor,
                      ActionType action, Status prevState, Status newState, String reason) {
        this.instanceId = instanceId;
        this.stepId = stepId;
        this.actor = actor;
        this.action = action;
        this.prevState = prevState;
        this.newState = newState;
        this.reason = reason;
        this.time = Instant.now();
    }
}
   