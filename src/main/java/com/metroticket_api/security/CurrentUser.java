package com.metroticket_api.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Tiện ích lấy id người dùng hiện tại (claim "sub" của JWT). */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new IllegalStateException("Chưa đăng nhập");
        }
        return UUID.fromString(auth.getName());
    }

    /** Id người dùng hiện tại nếu đang có JWT hợp lệ; rỗng khi chạy ngoài request (job, test unit). */
    public static Optional<UUID> optionalId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(auth.getName()));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    public static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
