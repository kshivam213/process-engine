package com.processengine.domain.model;

import java.util.List;

import com.processengine.domain.config.StepConfig;
import com.processengine.domain.enums.StepType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StepDefinition {
    public String id;
    public String name;
    public StepType type;
    public List<String> nextStepIds;
    public String parallelGroupId;
    public StepConfig config;
}
