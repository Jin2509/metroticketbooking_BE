package com.metroticket_api.rbac;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.rbac.RbacDtos.PermissionView;
import com.metroticket_api.rbac.RbacDtos.RoleView;

@Service
public class RbacService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final RolePermissionCache cache;

    public RbacService(RoleRepository roles, PermissionRepository permissions, RolePermissionCache cache) {
        this.roles = roles;
        this.permissions = permissions;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        return roles.findAllWithPermissions().stream().map(RoleView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionView> listPermissions() {
        return permissions.findAllByOrderByCodeAsc().stream().map(PermissionView::of).toList();
    }

    @Transactional
    public RoleView createRole(String code, String name, Set<String> permissionCodes) {
        if (roles.existsByCode(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Vai trò " + code + " đã tồn tại");
        }
        Role role = new Role(code, name.trim());
        role.replacePermissions(resolvePermissions(permissionCodes));
        roles.save(role);
        reloadCacheAfterCommit();
        return RoleView.of(role);
    }

    @Transactional
    public RoleView updatePermissions(String code, Set<String> permissionCodes) {
        Role role = roles.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không có vai trò " + code));
        if (RoleCodes.ADMIN.equals(role.getCode())) {
            // Giữ ADMIN luôn đủ quyền, tránh tự khoá mình khỏi hệ thống
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không được sửa quyền của vai trò ADMIN");
        }
        role.replacePermissions(resolvePermissions(permissionCodes));
        reloadCacheAfterCommit();
        return RoleView.of(role);
    }

    private Set<Permission> resolvePermissions(Set<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        List<Permission> found = permissions.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            Set<String> known = found.stream().map(Permission::getCode).collect(Collectors.toSet());
            Set<String> unknown = codes.stream().filter(c -> !known.contains(c)).collect(Collectors.toSet());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quyền không tồn tại: " + unknown);
        }
        return Set.copyOf(found);
    }

    /** Chỉ nạp lại cache khi dữ liệu đã commit, tránh cache chứa thay đổi bị rollback. */
    private void reloadCacheAfterCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.reload();
            }
        });
    }
}
