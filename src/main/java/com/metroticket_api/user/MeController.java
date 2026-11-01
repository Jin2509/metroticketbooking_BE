package com.metroticket_api.user;

import java.time.Clock;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.auth.AuthEventService;
import com.metroticket_api.auth.AuthEventType;
import com.metroticket_api.auth.AuthException;
import com.metroticket_api.auth.LoginAttemptLimiter;
import com.metroticket_api.auth.RefreshTokenService;
import com.metroticket_api.mail.MailRequested;
import com.metroticket_api.mail.MailTemplates;
import com.metroticket_api.notification.NotificationService;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.UserDtos.MeView;
import com.metroticket_api.verification.VerificationCodeService;
import com.metroticket_api.verification.VerificationPurpose;
import com.metroticket_api.verification.VerificationResult;

/** Bất kỳ người dùng đã đăng nhập nào cũng xem được thông tin của chính mình. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final UserRepository users;
    private final RolePermissionCache rolePermissions;
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verification;
    private final LoginAttemptLimiter loginAttempts;
    private final ApplicationEventPublisher events;
    private final MailTemplates mailTemplates;
    private final AuthEventService authEvents;
    private final NotificationService notifications;
    private final Clock clock;

    public MeController(UserRepository users, RolePermissionCache rolePermissions,
            RefreshTokenService refreshTokens, PasswordEncoder passwordEncoder,
            VerificationCodeService verification, LoginAttemptLimiter loginAttempts,
            ApplicationEventPublisher events, MailTemplates mailTemplates,
            AuthEventService authEvents, NotificationService notifications, Clock clock) {
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.verification = verification;
        this.loginAttempts = loginAttempts;
        this.events = events;
        this.mailTemplates = mailTemplates;
        this.authEvents = authEvents;
        this.notifications = notifications;
        this.clock = clock;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public MeView me() {
        User user = currentUser();
        Set<String> roles = UserDtos.roleCodes(user.getRoles());
        // Tra quyền từ cache trong bộ nhớ (không truy vấn thêm). Không đọc từ Authentication vì
        // Spring Security 7 còn thêm authority hệ thống như FACTOR_BEARER.
        Set<String> permissions = rolePermissions.authoritiesOf(roles).stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> !a.startsWith(RolePermissionCache.ROLE_PREFIX))
                .collect(Collectors.toCollection(TreeSet::new));
        return new MeView(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(), roles, permissions,
                user.getEmailVerifiedAt(), user.getLastLoginAt(), user.getPasswordChangedAt(), user.isTwoFactorEnabled());
    }

    /**
     * Đổi họ tên ngay. Đổi email thì cần mật khẩu hiện tại và chỉ gửi mã tới email mới,
     * email chỉ thật sự đổi sau bước /profile/verify-email.
     */
    @PutMapping("/profile")
    @Transactional
    public MeView updateProfile(@Valid @RequestBody UserDtos.UpdateProfileRequest req) {
        User user = currentUser();
        user.changeFullName(req.fullName().trim());

        if (req.email() != null && !req.email().isBlank()) {
            String requestedEmail = normalize(req.email());
            if (!requestedEmail.equals(user.getEmail())) {
                requireCurrentPassword(user, req.currentPassword());
                if (users.existsByEmail(requestedEmail)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được sử dụng");
                }
                verification.issueForUser(user.getId(), requestedEmail, VerificationPurpose.CHANGE_EMAIL);
            }
        }
        return me();
    }

    /**
     * noRollbackFor: mã sai vẫn phải commit failed_attempts, nếu rollback thì thử mã vô hạn lần.
     * Đổi email xong thì đăng xuất mọi thiết bị và báo cho email cũ.
     */
    @PostMapping("/profile/verify-email")
    @Transactional(noRollbackFor = AuthException.class)
    public MeView confirmEmailChange(@Valid @RequestBody UserDtos.VerifyEmailChangeRequest req) {
        User user = currentUser();
        String newEmail = normalize(req.email());
        if (users.existsByEmail(newEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được sử dụng");
        }

        VerificationResult result = verification.verifyForUser(
                user.getId(), newEmail, VerificationPurpose.CHANGE_EMAIL, req.code());
        if (result != VerificationResult.SUCCESS) {
            throw AuthException.of(result);
        }

        String oldEmail = user.getEmail();
        user.changeEmail(newEmail);
        users.flush(); // bắt vi phạm unique khi 2 người cùng xác nhận một email
        refreshTokens.revokeAll(user.getId());
        events.publishEvent(new MailRequested(mailTemplates.emailChangedNotice(oldEmail, newEmail)));
        authEvents.record(user.getId(), AuthEventType.EMAIL_CHANGED);
        notifications.notify(user.getId(), NotificationType.SECURITY, "Email đăng nhập đã được đổi",
                "Email đăng nhập của bạn vừa được đổi thành " + newEmail + ".");
        return me();
    }

    /** Người dùng đổi mật khẩu của chính mình; mọi thiết bị (cả phiên hiện tại) phải đăng nhập lại. */
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePassword(@Valid @RequestBody UserDtos.ChangeMyPasswordRequest req) {
        User user = currentUser();
        requireCurrentPassword(user, req.currentPassword());
        user.changePassword(passwordEncoder.encode(req.newPassword()), clock.instant());
        refreshTokens.revokeAll(user.getId());
        authEvents.record(user.getId(), AuthEventType.PASSWORD_CHANGED);
        notifications.notifyWithEmail(user, NotificationType.SECURITY, "Mật khẩu đã được đổi",
                "Mật khẩu của bạn vừa được đổi. Nếu không phải bạn, hãy liên hệ quản trị viên ngay.");
    }

    /** Bật/tắt xác nhận 2 lớp khi đăng nhập. Không đổi trạng thái thì không ghi lịch sử, không báo lại. */
    @PutMapping("/two-factor")
    @Transactional
    public MeView changeTwoFactor(@Valid @RequestBody UserDtos.ChangeTwoFactorRequest req) {
        User user = currentUser();
        requireCurrentPassword(user, req.currentPassword());
        if (user.isTwoFactorEnabled() != req.enabled()) {
            user.changeTwoFactor(req.enabled());
            authEvents.record(user.getId(), req.enabled() ? AuthEventType.TWO_FACTOR_ENABLED : AuthEventType.TWO_FACTOR_DISABLED);
            notifications.notifyWithEmail(user, NotificationType.SECURITY,
                    req.enabled() ? "Đã bật xác nhận 2 lớp" : "Đã tắt xác nhận 2 lớp",
                    (req.enabled()
                            ? "Từ giờ mỗi lần đăng nhập cần thêm mã gửi qua email."
                            : "Từ giờ đăng nhập chỉ cần mật khẩu.")
                            + " Nếu không phải bạn, hãy đổi mật khẩu và liên hệ quản trị viên ngay.");
        }
        return me();
    }

    /** Lịch sử bảo mật của chính mình, mới nhất trước. */
    @GetMapping("/auth-events")
    public Page<UserDtos.AuthEventView> authEvents(@PageableDefault(size = 20) Pageable pageable) {
        return authEvents.list(CurrentUser.id(), pageable).map(UserDtos.AuthEventView::of);
    }

    private User currentUser() {
        return users.findWithRolesById(CurrentUser.id())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Trả 400 thay vì 401 để frontend không hiểu nhầm là access token hết hạn. Dùng chung bộ đếm với đăng nhập. */
    private void requireCurrentPassword(User user, String currentPassword) {
        loginAttempts.ensureNotLocked(user.getEmail());
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            loginAttempts.recordFailure(user.getEmail());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng");
        }
        loginAttempts.reset(user.getEmail());
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
