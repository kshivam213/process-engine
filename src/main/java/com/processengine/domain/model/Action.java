package com.processengine.domain.model;

import java.time.Instant;

import com.processengine.domain.enums.ActionType;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Action {
    public ActionType type;
    public User actor;
    public Instant time;
    public String reason;

    public Action(ActionType type, User actor, String reason) {
        this.type = type;
        this.actor = actor;
        this.reason = reason;
        this.time = Instant.now();
    }
}
