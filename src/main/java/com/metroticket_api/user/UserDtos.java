package com.metroticket_api.user;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.metroticket_api.rbac.Role;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserView(UUID id, String email, String fullName, UserStatus status, Set<String> roles) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getStatus(), roleCodes(u.getRoles()));
        }
    }

    /** Thông tin của chính mình, kèm danh sách quyền để frontend ẩn/hiện chức năng. */
    public record MeView(UUID id, String email, String fullName, UserStatus status,
                         Set<String> roles, Set<String> permissions) {
    }

    public record ChangeRolesRequest(@NotEmpty Set<String> roles) {
    }

    public record ChangeStatusRequest(@NotNull UserStatus status) {
    }

    static Set<String> roleCodes(Collection<Role> roles) {
        return roles.stream().map(Role::getCode).collect(Collectors.toCollection(TreeSet::new));
    }
}
