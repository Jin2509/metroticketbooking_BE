package com.metroticket_api.workflow;

import java.util.Set;
import java.util.stream.Collectors;

public record WorkflowStep(
        String fromStatus,
        String toStatus,
        Set<String> allowedRoles,
        String description
) {
    public WorkflowStep {
        if (fromStatus == null || fromStatus.isBlank()) {
            throw new IllegalArgumentException("fromStatus không được trống");
        }
        if (toStatus == null || toStatus.isBlank()) {
            throw new IllegalArgumentException("toStatus không được trống");
        }
        if (allowedRoles == null) {
            allowedRoles = Set.of();
        }
        allowedRoles = allowedRoles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> role.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }
}
