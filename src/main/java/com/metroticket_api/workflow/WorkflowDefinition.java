package com.metroticket_api.workflow;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record WorkflowDefinition(
        WorkflowEntityType entityType,
        String name,
        Map<String, List<WorkflowStep>> steps
) {
    public WorkflowDefinition {
        if (entityType == null) {
            throw new IllegalArgumentException("entityType không được null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name không được trống");
        }
        if (steps == null) {
            steps = Collections.emptyMap();
        }
        steps = steps.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
    }

    public Set<String> allowedTransitions(String currentStatus) {
        if (currentStatus == null || currentStatus.isBlank()) {
            return Set.of();
        }
        return steps.getOrDefault(currentStatus, List.of()).stream()
                .map(WorkflowStep::toStatus)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> allowedRoles(String currentStatus, String targetStatus) {
        if (currentStatus == null || currentStatus.isBlank() || targetStatus == null || targetStatus.isBlank()) {
            return Set.of();
        }
        return steps.getOrDefault(currentStatus, List.of()).stream()
                .filter(step -> step.toStatus().equalsIgnoreCase(targetStatus))
                .flatMap(step -> step.allowedRoles().stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
