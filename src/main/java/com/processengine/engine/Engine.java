package com.processengine.engine;

import java.util.List;
import java.util.Map;

import com.processengine.audit.AuditEntry;
import com.processengine.domain.enums.ActionType;
import com.processengine.domain.model.ProcessDefinition;
import com.processengine.domain.model.ProcessInstance;
import com.processengine.domain.model.User;

public interface Engine {

    void loadDefinition(ProcessDefinition def);

    ProcessInstance startProcess(String definitionId, User initiator,
                                 Map<String, Object> fields);

    void advanceStep(String instanceId, String stepId,
                     ActionType action, User actor);

    List<AuditEntry> getAuditTrail(String instanceId);
}