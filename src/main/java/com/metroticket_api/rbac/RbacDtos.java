package com.metroticket_api.rbac;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RbacDtos {

    private RbacDtos() {
    }

    public record PermissionView(String code, String description) {
        public static PermissionView of(Permission p) {
            return new PermissionView(p.getCode(), p.getDescription());
        }
    }

    public record RoleView(String code, String name, boolean system, Set<String> permissions) {
        public static RoleView of(Role r) {
            return new RoleView(r.getCode(), r.getName(), r.isSystem(),
                    r.getPermissions().stream().map(Permission::getCode).collect(Collectors.toCollection(java.util.TreeSet::new)));
        }
    }

    public record CreateRoleRequest(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,49}", message = "Mã vai trò dạng CHU_IN_HOA") String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull Set<String> permissions) {
    }

    public record UpdateRolePermissionsRequest(@NotNull Set<String> permissions) {
    }
}
