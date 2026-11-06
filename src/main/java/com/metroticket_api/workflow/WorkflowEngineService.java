package com.metroticket_api.workflow;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.metroticket_api.metro.common.AnnouncementStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.rbac.RoleCodes;

@Service
public class WorkflowEngineService {

    private final Map<WorkflowEntityType, WorkflowDefinition> definitions = new EnumMap<>(WorkflowEntityType.class);

    public WorkflowEngineService() {
        registerAnnouncementWorkflow();
        registerOperationConfigWorkflow();
    }

    public Set<String> allowedTransitions(WorkflowEntityType entityType, String currentStatus) {
        WorkflowDefinition definition = definitions.get(entityType);
        if (definition == null) {
            throw new IllegalStateException("Chưa định nghĩa workflow cho entity: " + entityType);
        }
        return definition.allowedTransitions(currentStatus);
    }

    public Set<String> allowedRoles(WorkflowEntityType entityType, String currentStatus, String targetStatus) {
        WorkflowDefinition definition = definitions.get(entityType);
        if (definition == null) {
            throw new IllegalStateException("Chưa định nghĩa workflow cho entity: " + entityType);
        }
        return definition.allowedRoles(currentStatus, targetStatus);
    }

    public void ensureTransitionAllowed(WorkflowEntityType entityType, String currentStatus, String targetStatus) {
        Set<String> actorRoles = currentActorRoles();
        ensureTransitionAllowed(entityType, currentStatus, targetStatus, actorRoles);
    }

    public void ensureTransitionAllowed(WorkflowEntityType entityType, String currentStatus, String targetStatus,
            Collection<String> actorRoles) {
        if (entityType == null) {
            throw new IllegalArgumentException("entityType không được null");
        }
        if (currentStatus == null || currentStatus.isBlank()) {
            throw new IllegalArgumentException("currentStatus không được trống");
        }
        if (targetStatus == null || targetStatus.isBlank()) {
            throw new IllegalArgumentException("targetStatus không được trống");
        }
        if (currentStatus.equalsIgnoreCase(targetStatus)) {
            throw new IllegalStateException("Không thể chuyển sang trạng thái hiện tại");
        }

        WorkflowDefinition definition = definitions.get(entityType);
        if (definition == null) {
            throw new IllegalStateException("Chưa định nghĩa workflow cho entity: " + entityType);
        }

        Set<String> allowedTransitions = definition.allowedTransitions(currentStatus);
        if (!allowedTransitions.contains(targetStatus)) {
            throw new IllegalStateException(
                    "Không được phép chuyển từ " + currentStatus + " sang " + targetStatus + " cho " + entityType);
        }

        Set<String> normalizedRoles = normalizeRoles(actorRoles);
        if (normalizedRoles.contains(RoleCodes.ADMIN) || normalizedRoles.contains(RoleCodes.SYSTEM)) {
            return;
        }

        Set<String> transitionRoles = definition.allowedRoles(currentStatus, targetStatus);
        if (transitionRoles.isEmpty()) {
            return;
        }

        boolean allowed = transitionRoles.stream().anyMatch(normalizedRoles::contains);
        if (!allowed) {
            throw new AccessDeniedException(
                    "Vai trò hiện tại không đủ quyền để chuyển từ " + currentStatus + " sang " + targetStatus);
        }
    }

    private void registerAnnouncementWorkflow() {
        Map<String, List<WorkflowStep>> steps = new java.util.HashMap<>();
        steps.put(AnnouncementStatus.DRAFT.name(), List.of(
                new WorkflowStep(AnnouncementStatus.DRAFT.name(), AnnouncementStatus.PENDING_APPROVAL.name(),
                        Set.of(RoleCodes.STAFF, RoleCodes.ADMIN, RoleCodes.SYSTEM), "Gửi duyệt")));
        steps.put(AnnouncementStatus.PENDING_APPROVAL.name(), List.of(
                new WorkflowStep(AnnouncementStatus.PENDING_APPROVAL.name(), AnnouncementStatus.APPROVED.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Phê duyệt"),
                new WorkflowStep(AnnouncementStatus.PENDING_APPROVAL.name(), AnnouncementStatus.REJECTED.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Từ chối")));

        definitions.put(WorkflowEntityType.ANNOUNCEMENT,
                new WorkflowDefinition(WorkflowEntityType.ANNOUNCEMENT, "Announcement approval workflow", steps));
    }

    private void registerOperationConfigWorkflow() {
        Map<String, List<WorkflowStep>> steps = new java.util.HashMap<>();
        steps.put(OperationConfigStatus.DRAFT.name(), List.of(
                new WorkflowStep(OperationConfigStatus.DRAFT.name(), OperationConfigStatus.PENDING_APPROVAL.name(),
                        Set.of(RoleCodes.STAFF, RoleCodes.ADMIN, RoleCodes.SYSTEM), "Gửi duyệt")));
        steps.put(OperationConfigStatus.PENDING_APPROVAL.name(), List.of(
                new WorkflowStep(OperationConfigStatus.PENDING_APPROVAL.name(), OperationConfigStatus.APPROVED.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Phê duyệt"),
                new WorkflowStep(OperationConfigStatus.PENDING_APPROVAL.name(), OperationConfigStatus.REJECTED.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Từ chối")));
        steps.put(OperationConfigStatus.APPROVED.name(), List.of(
                new WorkflowStep(OperationConfigStatus.APPROVED.name(), OperationConfigStatus.ACTIVE.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Kích hoạt"),
                new WorkflowStep(OperationConfigStatus.APPROVED.name(), OperationConfigStatus.INACTIVE.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Đặt không hoạt động")));
        steps.put(OperationConfigStatus.ACTIVE.name(), List.of(
                new WorkflowStep(OperationConfigStatus.ACTIVE.name(), OperationConfigStatus.INACTIVE.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Vô hiệu hóa")));
        steps.put(OperationConfigStatus.INACTIVE.name(), List.of(
                new WorkflowStep(OperationConfigStatus.INACTIVE.name(), OperationConfigStatus.ACTIVE.name(),
                        Set.of(RoleCodes.ADMIN, RoleCodes.SYSTEM), "Kích hoạt lại")));

        definitions.put(WorkflowEntityType.OPERATION_CONFIG,
                new WorkflowDefinition(WorkflowEntityType.OPERATION_CONFIG, "Operation config approval workflow", steps));
    }

    private static Set<String> normalizeRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        return roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .map(role -> role.trim())
                .map(role -> role.startsWith("ROLE_") ? role.substring(5) : role)
                .map(String::toUpperCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> currentActorRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        return normalizeRoles(authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .collect(Collectors.toList()));
    }
}
