package com.metroticket_api.user;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.metroticket_api.auth.AuthEvent;
import com.metroticket_api.auth.AuthEventType;
import com.metroticket_api.rbac.Role;
import com.metroticket_api.security.ValidPassword;

public final class UserDtos {

    private UserDtos() {
    }

    public record UserView(UUID id, String email, String fullName, UserStatus status, Set<String> roles, Instant createdAt,
            Instant emailVerifiedAt, Instant lastLoginAt, Instant passwordChangedAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getStatus(), roleCodes(u.getRoles()), u.getCreatedAt(),
                    u.getEmailVerifiedAt(), u.getLastLoginAt(), u.getPasswordChangedAt());
        }
    }

    /** Thông tin của chính mình, kèm danh sách quyền để frontend ẩn/hiện chức năng. */
    public record MeView(UUID id, String email, String fullName, UserStatus status,
        Set<String> roles, Set<String> permissions,
        Instant emailVerifiedAt, Instant lastLoginAt, Instant passwordChangedAt, boolean twoFactorEnabled) {
    }

    /** Lịch sử bảo mật của chính mình. */
    public record AuthEventView(AuthEventType type, String ipAddress, String userAgent, Instant createdAt) {
        public static AuthEventView of(AuthEvent e) {
            return new AuthEventView(e.getType(), e.getIpAddress(), e.getUserAgent(), e.getCreatedAt());
        }
    }

    /** Admin xem lịch sử của người khác, kèm người đã thực hiện thao tác (null nếu là chính chủ). */
    public record AdminAuthEventView(AuthEventType type, UUID actorId, String ipAddress, String userAgent, Instant createdAt) {
        public static AdminAuthEventView of(AuthEvent e) {
            return new AdminAuthEventView(e.getType(), e.getActorId(), e.getIpAddress(), e.getUserAgent(), e.getCreatedAt());
        }
    }

    /** Một dòng nhật ký toàn hệ thống. actor là null nếu chính chủ thực hiện, hoặc tài khoản admin đó đã bị xoá. */
    public record AuditLogView(UUID id, AuthEventType type, AccountRef user, AccountRef actor, String ipAddress,
            String userAgent, Instant createdAt) {
        public static AuditLogView of(AuthEvent e, User user, User actor) {
            return new AuditLogView(e.getId(), e.getType(), AccountRef.of(user), AccountRef.of(actor), e.getIpAddress(),
                    e.getUserAgent(), e.getCreatedAt());
        }
    }

    public record AccountRef(UUID id, String email, String fullName) {
        static AccountRef of(User u) {
            return u == null ? null : new AccountRef(u.getId(), u.getEmail(), u.getFullName());
        }
    }

    public record ChangeRolesRequest(@NotEmpty Set<String> roles) {
    }

    public record ChangeStatusRequest(@NotNull UserStatus status) {
    }

    /** Admin đặt lại mật khẩu cho người khác. */
    public record ResetUserPasswordRequest(@NotBlank @ValidPassword String newPassword) {
    }

    /** temporaryPassword bỏ trống (null) thì hệ thống tự sinh. */
    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotEmpty Set<String> roles,
            @ValidPassword String temporaryPassword) {
    }

    /** Người dùng tự đổi mật khẩu: phải biết mật khẩu hiện tại, phòng khi access token bị lộ. */
    public record ChangeMyPasswordRequest(@NotBlank String currentPassword, @NotBlank @ValidPassword String newPassword) {
    }

    /** Bật/tắt xác nhận 2 lớp, luôn cần mật khẩu hiện tại. */
    public record ChangeTwoFactorRequest(@NotNull Boolean enabled, @NotBlank String currentPassword) {
    }

    /** email bỏ trống là không đổi email; currentPassword chỉ bắt buộc khi đổi email. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 120) String fullName,
            @Email @Size(max = 320) String email,
            String currentPassword) {
    }

    public record VerifyEmailChangeRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Pattern(regexp = "\\d{6}", message = "Mã gồm 6 chữ số") String code) {
    }

    static Set<String> roleCodes(Collection<Role> roles) {
        return roles.stream().map(Role::getCode).collect(Collectors.toCollection(TreeSet::new));
    }
}
