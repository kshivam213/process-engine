package com.processengine.domain.model;

import java.util.ArrayList;
import java.util.List;

import com.processengine.domain.enums.Status;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StepInstance {
    public String id;
    public StepDefinition def;
    public Status status;
    public List<Action> actions = new ArrayList<>();

    public StepInstance(String id, StepDefinition def) {
        this.id = id;
        this.def = def;
        this.status = Status.PENDING;
    }
}
