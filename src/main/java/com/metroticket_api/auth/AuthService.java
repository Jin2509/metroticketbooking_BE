package com.metroticket_api.auth;

import java.time.Clock;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.auth.AuthDtos.LoginResponse;
import com.metroticket_api.auth.AuthDtos.TokenResponse;
import com.metroticket_api.auth.AuthDtos.TwoFactorChallengeResponse;
import com.metroticket_api.notification.NotificationService;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.rbac.RoleCodes;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.security.AccessTokenDenylist;
import com.metroticket_api.security.TokenService;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;
import com.metroticket_api.verification.EmailVerificationChallenge;
import com.metroticket_api.verification.ResendTooSoonException;
import com.metroticket_api.verification.VerificationCodeService;
import com.metroticket_api.verification.VerificationPurpose;
import com.metroticket_api.verification.VerificationResult;

@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "Email hoặc mật khẩu không đúng";
    private static final String LOGIN_TICKET_EXPIRED = "Phiên xác nhận đã hết hạn, vui lòng đăng nhập lại";

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verification;
    private final TokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final AccessTokenDenylist denylist;
    private final LoginAttemptLimiter loginAttempts;
    private final AuthEventService authEvents;
    private final NotificationService notifications;
    private final JwtDecoder jwtDecoder;
    private final Clock clock;
    /** Hash giả để email không tồn tại vẫn tốn thời gian BCrypt như email có thật (chống dò tài khoản). */
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            VerificationCodeService verification, TokenService tokens, RefreshTokenService refreshTokens,
            AccessTokenDenylist denylist, LoginAttemptLimiter loginAttempts, AuthEventService authEvents,
            NotificationService notifications, JwtDecoder jwtDecoder, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.verification = verification;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.denylist = denylist;
        this.loginAttempts = loginAttempts;
        this.authEvents = authEvents;
        this.notifications = notifications;
        this.jwtDecoder = jwtDecoder;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("metroticket-dummy-password");
    }

    /**
     * Đăng ký: tạo user PENDING_VERIFICATION với vai trò mặc định và gửi mã qua email.
     * Nếu email đã đăng ký nhưng chưa xác thực và mã đã hết hạn thì cho đăng ký lại: ghi đè
     * mật khẩu/họ tên và gửi mã mới. Khi mã còn hiệu lực thì từ chối: nếu không, kẻ khác có thể
     * ghi đè mật khẩu của người đang chờ xác thực, rồi chính người đó kích hoạt tài khoản bằng
     * mã mới nhận được. Mất mail thì dùng /resend-code (giữ nguyên mật khẩu).
     */
    @Transactional
    public void register(String rawEmail, String password, String fullName) {
        String email = normalize(rawEmail);
        User existing = users.findByEmail(email).orElse(null);
        if (existing != null) {
            if (existing.getStatus() != UserStatus.PENDING_VERIFICATION) {
                throw new AuthException(HttpStatus.CONFLICT, "Email đã được sử dụng");
            }
            verification.activeCodeRemaining(email, VerificationPurpose.REGISTER).ifPresent(remaining -> {
                throw new ResendTooSoonException(remaining);
            });
            existing.updatePendingRegistration(passwordEncoder.encode(password), fullName.trim());
            verification.issue(email, VerificationPurpose.REGISTER);
            return;
        }
        User user = new User(email, passwordEncoder.encode(password), fullName.trim(),
                UserStatus.PENDING_VERIFICATION, clock.instant());
        user.assignRole(roles.findByCode(RoleCodes.DEFAULT)
                .orElseThrow(() -> new IllegalStateException("Thiếu vai trò mặc định " + RoleCodes.DEFAULT)));
        try {
            // flush ngay để bắt vi phạm unique khi 2 request đăng ký cùng email chạy song song
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(HttpStatus.CONFLICT, "Email đã được sử dụng");
        }
        verification.issue(email, VerificationPurpose.REGISTER);
    }

    @Transactional
    public void resendRegisterCode(String rawEmail) {
        String email = normalize(rawEmail);
        users.findByEmail(email)
                .filter(u -> u.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(u -> verification.issue(email, VerificationPurpose.REGISTER));
        // Không báo "email không tồn tại" để tránh dò tài khoản
    }

    /**
     * noRollbackFor: khi mã sai ta ném AuthException, nhưng vẫn phải commit
     * failed_attempts đã tăng; nếu rollback thì kẻ tấn công thử mã vô hạn lần.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public void verifyEmail(String rawEmail, String code) {
        String email = normalize(rawEmail);
        VerificationResult result = verification.verify(email, VerificationPurpose.REGISTER, code);
        if (result != VerificationResult.SUCCESS) {
            throw AuthException.of(result);
        }
        users.findByEmail(email)
                .filter(u -> u.getStatus() == UserStatus.PENDING_VERIFICATION)
                .ifPresent(u -> {
                    u.activate(clock.instant());
                    authEvents.record(u.getId(), AuthEventType.EMAIL_VERIFIED);
                    notifications.notifyWithEmail(u, NotificationType.SYSTEM, "Chào mừng đến với MetroTicket",
                            "Tài khoản của bạn đã được kích hoạt.");
                });
    }

    /** noRollbackFor: lần đăng nhập sai vẫn phải được ghi vào lịch sử dù ta ném AuthException. */
    @Transactional(noRollbackFor = AuthException.class)
    public LoginResponse login(String rawEmail, String password) {
        String email = normalize(rawEmail);
        loginAttempts.ensureNotLocked(email);
        User user = users.findWithRolesByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            loginAttempts.recordFailure(email);
            throw new AuthException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttempts.recordFailure(email);
            authEvents.record(user.getId(), AuthEventType.LOGIN_FAILED);
            throw new AuthException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        if (user.isTwoFactorEnabled() && user.getStatus() == UserStatus.ACTIVE) {
            // Chưa xoá bộ đếm: nếu xoá ở đây thì đăng nhập lại là thử mã 2 lớp được vô hạn lần
            VerificationCodeService.LoginChallenge challenge = verification.issueLogin(user.getId(), email);
            return new TwoFactorChallengeResponse(true, challenge.ticket(), challenge.expiresAt());
        }
        loginAttempts.reset(email);
        ensureCanSignIn(user);
        return completeLogin(user);
    }

    /** Bước 2 của đăng nhập. noRollbackFor: lần nhập sai mã vẫn phải được commit. */
    @Transactional(noRollbackFor = AuthException.class)
    public TokenResponse verifyLoginCode(String ticket, String code) {
        EmailVerificationChallenge challenge = loadLoginChallenge(ticket);
        String email = challenge.getEmail();
        loginAttempts.ensureNotLocked(email);
        User user = loadLoginUser(challenge);
        VerificationResult result = verification.verifyLogin(challenge, code);
        if (result == VerificationResult.INVALID_CODE || result == VerificationResult.TOO_MANY_ATTEMPTS) {
            loginAttempts.recordFailure(email);
            authEvents.record(user.getId(), AuthEventType.TWO_FACTOR_FAILED);
        }
        if (result != VerificationResult.SUCCESS) {
            throw AuthException.of(result);
        }
        loginAttempts.reset(email);
        ensureCanSignIn(user);
        return completeLogin(user);
    }

    @Transactional
    public void resendLoginCode(String ticket) {
        EmailVerificationChallenge challenge = loadLoginChallenge(ticket);
        loadLoginUser(challenge);
        verification.reissueLogin(challenge, ticket);
    }

    /** Đổi refresh token lấy cặp token mới (refresh token cũ bị thu hồi). */
    @Transactional(noRollbackFor = AuthException.class)
    public TokenResponse refresh(String refreshToken) {
        RefreshTokenService.Rotated rotated = refreshTokens.rotate(refreshToken);
        User user = users.findWithRolesById(rotated.userId())
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "Tài khoản không tồn tại"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            refreshTokens.revokeAll(user.getId());
            ensureCanSignIn(user);
        }
        return issueTokens(user, rotated.token());
    }

    /**
     * Đăng xuất: thu hồi refresh token (cả phiên) và, nếu client gửi kèm, access token hiện tại.
     * Luôn thành công (idempotent) kể cả khi token sai hoặc đã hết hạn.
     */
    @Transactional
    public void logout(String refreshToken, String accessToken) {
        refreshTokens.revoke(refreshToken).ifPresent(userId -> authEvents.record(userId, AuthEventType.LOGOUT));
        if (accessToken == null || accessToken.isBlank()) {
            return;
        }
        try {
            Jwt jwt = jwtDecoder.decode(accessToken);
            denylist.revoke(jwt.getId(), jwt.getExpiresAt());
        } catch (JwtException e) {
            // Token hết hạn/sai chữ ký/đã thu hồi → không cần làm gì thêm
        }
    }

    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalize(rawEmail);
        users.findByEmail(email)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .ifPresent(u -> verification.issue(email, VerificationPurpose.RESET_PASSWORD));
    }

    /** Đặt lại mật khẩu bằng mã OTP; đăng xuất mọi thiết bị đang đăng nhập. */
    @Transactional(noRollbackFor = AuthException.class)
    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalize(rawEmail);
        VerificationResult result = verification.verify(email, VerificationPurpose.RESET_PASSWORD, code);
        if (result != VerificationResult.SUCCESS) {
            throw AuthException.of(result);
        }
        users.findByEmail(email).ifPresent(u -> {
            u.changePassword(passwordEncoder.encode(newPassword), clock.instant());
            refreshTokens.revokeAll(u.getId());
            loginAttempts.reset(email);
            authEvents.record(u.getId(), AuthEventType.PASSWORD_RESET);
            notifications.notifyWithEmail(u, NotificationType.SECURITY, "Mật khẩu đã được đặt lại",
                    "Mật khẩu của bạn vừa được đặt lại. Nếu không phải bạn, hãy liên hệ quản trị viên ngay.");
        });
    }

    private EmailVerificationChallenge loadLoginChallenge(String ticket) {
        return verification.findLogin(ticket)
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST, LOGIN_TICKET_EXPIRED));
    }

    /** Mật khẩu hoặc email đã đổi sau bước 1 thì ticket không còn giá trị, phải đăng nhập lại. */
    private User loadLoginUser(EmailVerificationChallenge challenge) {
        User user = users.findWithRolesById(challenge.getRequestedBy())
                .orElseThrow(() -> new AuthException(HttpStatus.BAD_REQUEST, LOGIN_TICKET_EXPIRED));
        boolean passwordChanged = user.getPasswordChangedAt() != null
                && user.getPasswordChangedAt().isAfter(challenge.getCreatedAt());
        if (passwordChanged || !user.getEmail().equals(challenge.getEmail())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, LOGIN_TICKET_EXPIRED);
        }
        return user;
    }

    private TokenResponse completeLogin(User user) {
        user.recordLogin(clock.instant());
        authEvents.record(user.getId(), AuthEventType.LOGIN_SUCCESS);
        return issueTokens(user, refreshTokens.issue(user.getId()));
    }

    private TokenResponse issueTokens(User user, RefreshTokenService.IssuedRefreshToken refresh) {
        TokenService.IssuedToken access = tokens.issueAccessToken(user);
        return new TokenResponse(access.value(), "Bearer", access.expiresAt(), refresh.value(), refresh.expiresAt());
    }

    private static void ensureCanSignIn(User user) {
        switch (user.getStatus()) {
            case PENDING_VERIFICATION -> throw new AuthException(HttpStatus.FORBIDDEN, "Email chưa được xác thực");
            case LOCKED -> throw new AuthException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khoá");
            case ACTIVE -> { }
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
