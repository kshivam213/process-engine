package com.processengine.domain.model;

import java.util.HashMap;
import java.util.Map;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ProcessInstance {
    public String id;
    public ProcessDefinition definition;
    public Map<String, StepInstance> steps = new HashMap<>();
    public Map<String, Object> fields = new HashMap<>();

    public ProcessInstance(String id, ProcessDefinition def) {
        this.id = id;
        this.definition = def;
    }
}
