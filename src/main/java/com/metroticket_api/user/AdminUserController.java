package com.metroticket_api.user;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.rbac.Role;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.UserDtos.ChangeRolesRequest;
import com.metroticket_api.user.UserDtos.ChangeStatusRequest;
import com.metroticket_api.user.UserDtos.UserView;

/** Phân quyền theo permission (không theo tên vai trò), nên vai trò mới tạo cũng dùng được. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final UserRepository users;
    private final RoleRepository roles;

    public AdminUserController(UserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Transactional(readOnly = true)
    public Page<UserView> list(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        // roles được nạp theo lô nhờ hibernate.default_batch_fetch_size → không bị N+1
        return users.findAll(pageable).map(UserView::of);
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public UserView changeRoles(@PathVariable UUID id, @Valid @RequestBody ChangeRolesRequest req) {
        User user = loadOther(id);
        List<Role> found = roles.findByCodeIn(req.roles());
        if (found.size() != req.roles().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Có vai trò không tồn tại trong " + req.roles());
        }
        user.replaceRoles(found);
        return UserView.of(user);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public UserView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest req) {
        User user = loadOther(id);
        user.changeStatus(req.status());
        return UserView.of(user);
    }

    /** Không cho admin tự đổi vai trò/trạng thái của chính mình (tránh tự khoá). */
    private User loadOther(UUID id) {
        if (id.equals(CurrentUser.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể tự thay đổi tài khoản của chính mình");
        }
        return users.findWithRolesById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
