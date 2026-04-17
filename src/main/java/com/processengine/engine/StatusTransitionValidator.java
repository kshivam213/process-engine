package com.processengine.engine;

import java.util.Map;
import java.util.Set;

import com.processengine.domain.enums.Status;

public class StatusTransitionValidator {

    private static final Map<Status, Set<Status>> validTransitions = Map.of(
            Status.PENDING, Set.of(Status.IN_PROGRESS),
            Status.IN_PROGRESS, Set.of(Status.COMPLETED, Status.REJECTED, Status.ESCALATED),
            Status.REJECTED, Set.of(Status.IN_PROGRESS)
    );

    public boolean isValidTransition(Status currentStatus, Status nextStatus) {
        Set<Status> allowed = validTransitions.get(currentStatus);
        return allowed != null && allowed.contains(nextStatus);
    }
}

