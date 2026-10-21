package com.metroticket_api.user;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.UserDtos.MeView;

/** Bất kỳ người dùng đã đăng nhập nào cũng xem được thông tin của chính mình. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository users;
    private final RolePermissionCache rolePermissions;

    public MeController(UserRepository users, RolePermissionCache rolePermissions) {
        this.users = users;
        this.rolePermissions = rolePermissions;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public MeView me() {
        User user = users.findWithRolesById(CurrentUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Set<String> roles = UserDtos.roleCodes(user.getRoles());
        // Tra quyền từ cache trong bộ nhớ (không truy vấn thêm). Không đọc từ Authentication vì
        // Spring Security 7 còn thêm authority hệ thống như FACTOR_BEARER.
        Set<String> permissions = rolePermissions.authoritiesOf(roles).stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> !a.startsWith(RolePermissionCache.ROLE_PREFIX))
                .collect(Collectors.toCollection(TreeSet::new));
        return new MeView(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(), roles, permissions);
    }
}
