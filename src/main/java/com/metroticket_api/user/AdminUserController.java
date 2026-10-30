package com.metroticket_api.user;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import java.security.SecureRandom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.auth.RefreshTokenService;
import com.metroticket_api.mail.MailSender;
import com.metroticket_api.mail.MailTemplates;
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final MailTemplates mailTemplates;

    public AdminUserController(UserRepository users, RoleRepository roles, RefreshTokenService refreshTokens,
            PasswordEncoder passwordEncoder, MailSender mailSender, MailTemplates mailTemplates) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.mailTemplates = mailTemplates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Transactional(readOnly = true)
    public Page<UserView> list(@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        // roles được nạp theo lô nhờ hibernate.default_batch_fetch_size → không bị N+1
        return users.findAll(pageable).map(UserView::of);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserView createUser(@Valid @RequestBody UserDtos.CreateUserRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }

        List<Role> found = roles.findByCodeIn(req.roles());
        if (found.size() != req.roles().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Có vai trò không tồn tại trong " + req.roles());
        }

        String tempPassword = req.temporaryPassword() == null || req.temporaryPassword().isBlank()
                ? generateTemporaryPassword()
                : req.temporaryPassword();

        User user = new User(email, passwordEncoder.encode(tempPassword), req.fullName().trim(), UserStatus.ACTIVE,
                java.time.Clock.systemUTC().instant());
        user.replaceRoles(found);
        users.save(user);

        mailSender.send(mailTemplates.adminCreatedAccountNotice(user.getEmail(), tempPassword));
        return UserView.of(user);
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
        if (req.status() == UserStatus.LOCKED) {
            // Không cho refresh nữa; access token còn lại tự hết hạn sau app.jwt.access-token-ttl
            refreshTokens.revokeAll(user.getId());
        }
        return UserView.of(user);
    }

    /** Không cho admin tự đổi vai trò/trạng thái của chính mình (tránh tự khoá). */
    private User loadOther(UUID id) {
        if (id.equals(CurrentUser.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể tự thay đổi tài khoản của chính mình");
        }
        return users.findWithRolesById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

 private static String generateTemporaryPassword() {
     String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%";
     StringBuilder builder = new StringBuilder(12);
     for (int i = 0; i < 12; i++) {
         builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
     }
     return builder.toString();
 }

 /** Admin đặt lại mật khẩu cho người dùng khác. Mật khẩu mới gửi qua email và phải băm trước khi lưu. */    @PutMapping("/{id}/password")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public void changePassword(@PathVariable UUID id, @Valid @RequestBody UserDtos.ChangePasswordRequest req) {
        User user = loadOther(id);
     String rawPassword = req.newPassword();
     String newHash = passwordEncoder.encode(rawPassword);
     user.changePassword(newHash);
     refreshTokens.revokeAll(user.getId());

     mailSender.send(mailTemplates.adminPasswordResetNotice(user.getEmail(), rawPassword));
   }

}
