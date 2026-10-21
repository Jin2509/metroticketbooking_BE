package com.metroticket_api.auth;

import java.time.Clock;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.auth.AuthDtos.TokenResponse;
import com.metroticket_api.rbac.RoleCodes;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.security.TokenService;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;
import com.metroticket_api.verification.VerificationCodeService;
import com.metroticket_api.verification.VerificationPurpose;
import com.metroticket_api.verification.VerificationResult;

@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "Email hoặc mật khẩu không đúng";

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verification;
    private final TokenService tokens;
    private final Clock clock;
    /** Hash giả để email không tồn tại vẫn tốn thời gian BCrypt như email có thật (chống dò tài khoản). */
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            VerificationCodeService verification, TokenService tokens, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.verification = verification;
        this.tokens = tokens;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("metroticket-dummy-password");
    }

    /** Đăng ký: tạo user PENDING_VERIFICATION với vai trò mặc định và gửi mã qua email. */
    @Transactional
    public void register(String rawEmail, String password, String fullName) {
        String email = normalize(rawEmail);
        if (users.existsByEmail(email)) {
            throw new AuthException(HttpStatus.CONFLICT, "Email đã được sử dụng");
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
            throw toException(result);
        }
        users.findByEmail(email).ifPresent(User::activate);
    }

    @Transactional(readOnly = true)
    public TokenResponse login(String rawEmail, String password) {
        User user = users.findWithRolesByEmail(normalize(rawEmail)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw new AuthException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        switch (user.getStatus()) {
            case PENDING_VERIFICATION -> throw new AuthException(HttpStatus.FORBIDDEN, "Email chưa được xác thực");
            case LOCKED -> throw new AuthException(HttpStatus.FORBIDDEN, "Tài khoản đã bị khoá");
            case ACTIVE -> { }
        }
        TokenService.IssuedToken token = tokens.issueAccessToken(user);
        return new TokenResponse(token.value(), "Bearer", token.expiresAt());
    }

    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = normalize(rawEmail);
        users.findByEmail(email)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .ifPresent(u -> verification.issue(email, VerificationPurpose.RESET_PASSWORD));
    }

    @Transactional(noRollbackFor = AuthException.class)
    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalize(rawEmail);
        VerificationResult result = verification.verify(email, VerificationPurpose.RESET_PASSWORD, code);
        if (result != VerificationResult.SUCCESS) {
            throw toException(result);
        }
        users.findByEmail(email).ifPresent(u -> u.changePassword(passwordEncoder.encode(newPassword)));
    }

    private static AuthException toException(VerificationResult result) {
        return switch (result) {
            case EXPIRED -> new AuthException(HttpStatus.BAD_REQUEST, "Mã đã hết hạn, vui lòng yêu cầu mã mới");
            case TOO_MANY_ATTEMPTS -> new AuthException(HttpStatus.TOO_MANY_REQUESTS, "Nhập sai quá nhiều lần, vui lòng yêu cầu mã mới");
            case INVALID_CODE, NOT_FOUND -> new AuthException(HttpStatus.BAD_REQUEST, "Mã xác thực không đúng");
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS không phải lỗi");
        };
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
