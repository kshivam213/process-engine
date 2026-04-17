package com.processengine.domain.config;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StepConfig {
    public int requiredApprovals;
    public List<String> requiredFields;
    public String onRejectStepId;
}
