package com.metroticket_api.user;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.auth.AuthEventService;
import com.metroticket_api.auth.AuthEventType;
import com.metroticket_api.auth.LoginAttemptLimiter;
import com.metroticket_api.auth.RefreshTokenService;
import com.metroticket_api.mail.MailRequested;
import com.metroticket_api.mail.MailTemplates;
import com.metroticket_api.notification.NotificationService;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.rbac.Role;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.security.AccessTokenDenylist;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.UserDtos.ChangeRolesRequest;
import com.metroticket_api.user.UserDtos.ChangeStatusRequest;
import com.metroticket_api.user.UserDtos.UserView;

/**
 * Phân quyền theo permission (không theo tên vai trò), nên vai trò mới tạo cũng dùng được.
 *
 * <p>Chống leo thang đặc quyền: vai trò có USER_MANAGE hoặc RBAC_MANAGE là vai trò đặc quyền, chỉ người
 * có RBAC_MANAGE mới được gán vai trò đó hoặc tác động lên tài khoản đang giữ nó. Nếu không, người chỉ
 * có USER_MANAGE có thể tự tạo tài khoản ADMIN hoặc đặt lại mật khẩu của ADMIN.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> PRIVILEGED_PERMISSIONS = Set.of(PermissionCodes.USER_MANAGE, PermissionCodes.RBAC_MANAGE);
    /** Whitelist: sort theo cột bất kỳ sẽ lỗi 500 với cột sai, và sort theo passwordHash làm lộ thông tin. */
    private static final Set<String> SORTABLE = Set.of("email", "fullName", "status", "createdAt");

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenDenylist denylist;
    private final LoginAttemptLimiter loginAttempts;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final MailTemplates mailTemplates;
    private final AuthEventService authEvents;
    private final NotificationService notifications;
    private final Clock clock;

    public AdminUserController(UserRepository users, RoleRepository roles, RefreshTokenService refreshTokens,
            AccessTokenDenylist denylist, LoginAttemptLimiter loginAttempts, PasswordEncoder passwordEncoder,
            ApplicationEventPublisher events, MailTemplates mailTemplates, AuthEventService authEvents,
            NotificationService notifications, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.denylist = denylist;
        this.loginAttempts = loginAttempts;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
        this.mailTemplates = mailTemplates;
        this.authEvents = authEvents;
        this.notifications = notifications;
        this.clock = clock;
    }

    /** q: tìm trong email hoặc họ tên; status: lọc theo trạng thái. */
    @GetMapping
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Transactional(readOnly = true)
    public Page<UserView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) UserStatus status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không sắp xếp được theo " + order.getProperty());
            }
        }
        // roles được nạp theo lô nhờ hibernate.default_batch_fetch_size → không bị N+1
        return users.findAll(matching(q, status), pageable).map(UserView::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    @Transactional(readOnly = true)
    public UserView get(@PathVariable UUID id) {
        return UserView.of(users.findWithRolesById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    /** Lịch sử bảo mật của một tài khoản, mới nhất trước. */
    @GetMapping("/{id}/auth-events")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public Page<UserDtos.AdminAuthEventView> authEvents(@PathVariable UUID id, @PageableDefault(size = 20) Pageable pageable) {
        if (!users.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return authEvents.list(id, pageable).map(UserDtos.AdminAuthEventView::of);
    }

    /** Tài khoản do admin tạo được kích hoạt ngay, mật khẩu tạm gửi qua email sau khi commit. */
    @PostMapping
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public UserView createUser(@Valid @RequestBody UserDtos.CreateUserRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }
        List<Role> found = resolveAssignableRoles(req.roles());

        String tempPassword = req.temporaryPassword() == null ? generateTemporaryPassword() : req.temporaryPassword();
        User user = new User(email, passwordEncoder.encode(tempPassword), req.fullName().trim(), UserStatus.ACTIVE,
                clock.instant());
        user.replaceRoles(found);
        users.saveAndFlush(user); // flush để 2 request tạo cùng email bị bắt ngay (409), trước khi phát mail

        events.publishEvent(new MailRequested(mailTemplates.adminCreatedAccountNotice(user.getEmail(), tempPassword)));
        authEvents.recordByAdmin(user.getId(), AuthEventType.ACCOUNT_CREATED_BY_ADMIN);
        notifications.notify(user.getId(), NotificationType.SYSTEM, "Chào mừng đến với MetroTicket",
                "Tài khoản của bạn đã được tạo. Hãy đổi mật khẩu tạm sau khi đăng nhập.");
        return UserView.of(user);
    }

    /** Thu hồi access token để vai trò cũ hết tác dụng ngay; refresh token vẫn dùng được và nhận vai trò mới. */
    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public UserView changeRoles(@PathVariable UUID id, @Valid @RequestBody ChangeRolesRequest req) {
        User user = loadManageable(id);
        user.replaceRoles(resolveAssignableRoles(req.roles()));
        denylist.revokeAllOfUserAfterCommit(user.getId());
        authEvents.recordByAdmin(user.getId(), AuthEventType.ROLES_CHANGED);
        notifications.notify(user.getId(), NotificationType.SYSTEM, "Vai trò đã được cập nhật",
                "Quản trị viên vừa thay đổi vai trò của bạn.");
        return UserView.of(user);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public UserView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest req) {
        if (req.status() == UserStatus.PENDING_VERIFICATION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Chỉ được đặt trạng thái ACTIVE hoặc LOCKED");
        }
        User user = loadManageable(id);
        if (user.getStatus() == req.status()) {
            return UserView.of(user); // không đổi gì thì không ghi lịch sử, không báo lại
        }
        user.changeStatus(req.status());
        if (req.status() == UserStatus.LOCKED) {
            refreshTokens.revokeAll(user.getId());
            authEvents.recordByAdmin(user.getId(), AuthEventType.ACCOUNT_LOCKED);
            // Chỉ gửi email: tài khoản đã khoá thì không vào được app để đọc thông báo
            events.publishEvent(new MailRequested(mailTemplates.accountNotice(user.getEmail(), user.getFullName(),
                    "Tài khoản đã bị khoá", "Quản trị viên đã khoá tài khoản của bạn. Vui lòng liên hệ quầy hỗ trợ tại ga.")));
        } else {
            authEvents.recordByAdmin(user.getId(), AuthEventType.ACCOUNT_ACTIVATED);
            notifications.notifyWithEmail(user, NotificationType.SECURITY, "Tài khoản đã được mở",
                    "Quản trị viên đã mở lại tài khoản của bạn.");
        }
        return UserView.of(user);
    }

    /** Admin đặt lại mật khẩu cho người dùng khác; mật khẩu mới gửi qua email sau khi commit. */
    @PutMapping("/{id}/password")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@PathVariable UUID id, @Valid @RequestBody UserDtos.ResetUserPasswordRequest req) {
        User user = loadManageable(id);
        user.changePassword(passwordEncoder.encode(req.newPassword()), clock.instant());
        refreshTokens.revokeAll(user.getId());
        loginAttempts.reset(user.getEmail());
        events.publishEvent(new MailRequested(mailTemplates.adminPasswordResetNotice(user.getEmail(), req.newPassword())));
        authEvents.recordByAdmin(user.getId(), AuthEventType.PASSWORD_RESET_BY_ADMIN);
        notifications.notify(user.getId(), NotificationType.SECURITY, "Mật khẩu đã được đặt lại",
                "Quản trị viên vừa đặt lại mật khẩu của bạn. Mật khẩu mới được gửi qua email.");
    }

    /** Không cho admin tự đổi vai trò/trạng thái của chính mình (tránh tự khoá). */
    private User loadManageable(UUID id) {
        if (id.equals(CurrentUser.id())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không thể tự thay đổi tài khoản của chính mình");
        }
        User user = users.findWithRolesById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requirePrivilegeFor(user.getRoles());
        return user;
    }

    private List<Role> resolveAssignableRoles(Set<String> codes) {
        List<Role> found = roles.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Có vai trò không tồn tại trong " + codes);
        }
        requirePrivilegeFor(found);
        return found;
    }

    private static void requirePrivilegeFor(Collection<Role> targetRoles) {
        boolean privileged = targetRoles.stream()
                .flatMap(r -> r.getPermissions().stream())
                .anyMatch(p -> PRIVILEGED_PERMISSIONS.contains(p.getCode()));
        if (privileged && !CurrentUser.hasAuthority(PermissionCodes.RBAC_MANAGE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không đủ quyền thao tác trên vai trò quản trị");
        }
    }

    private static Specification<User> matching(String q, UserStatus status) {
        return (root, query, cb) -> {
            List<Predicate> conditions = new ArrayList<>();
            if (status != null) {
                conditions.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String pattern = likePattern(q);
                conditions.add(cb.or(
                        cb.like(cb.lower(root.get("email")), pattern, '\\'),
                        cb.like(cb.lower(root.get("fullName")), pattern, '\\')));
            }
            return cb.and(conditions.toArray(Predicate[]::new));
        };
    }

    /** Mẫu LIKE không phân biệt hoa thường; ký tự đại diện trong q được escape bằng dấu \\. */
    static String likePattern(String q) {
        return "%" + q.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String generateTemporaryPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%";
        StringBuilder builder = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            builder.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return builder.toString();
    }
}
