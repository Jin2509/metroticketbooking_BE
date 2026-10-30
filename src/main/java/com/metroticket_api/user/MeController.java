package com.metroticket_api.user;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.metroticket_api.auth.RefreshTokenService;
import com.metroticket_api.verification.VerificationCodeService;
import com.metroticket_api.verification.VerificationPurpose;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final RefreshTokenService refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final VerificationCodeService verification;

    public MeController(UserRepository users, RolePermissionCache rolePermissions,
            RefreshTokenService refreshTokens, PasswordEncoder passwordEncoder,
            VerificationCodeService verification) {
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.verification = verification;
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
@PutMapping("/profile")
    @Transactional
public MeView updateProfile(@Valid @RequestBody UserDtos.UpdateProfileRequest req) {
    User user = users.findById(CurrentUser.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    String requestedName = req.fullName() == null ? user.getFullName() : req.fullName().trim();
    if (requestedName.isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Họ tên không được để trống");
    }
    user.changeFullName(requestedName);

    String requestedEmail = req.email() == null ? null : req.email().trim().toLowerCase();
    if (requestedEmail != null && !requestedEmail.equalsIgnoreCase(user.getEmail())) {
        if (users.existsByEmail(requestedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được sử dụng");
        }
        verification.issue(requestedEmail, VerificationPurpose.CHANGE_EMAIL);
        return me();
    }

    return me();
}

@PostMapping("/profile/verify-email")
@Transactional
public MeView confirmEmailChange(@Valid @RequestBody UserDtos.VerifyEmailChangeRequest req) {
    User user = users.findById(CurrentUser.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    var result = verification.verify(req.email(), VerificationPurpose.CHANGE_EMAIL, req.code());
    if (result != com.metroticket_api.verification.VerificationResult.SUCCESS) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                switch (result) {
                    case EXPIRED -> "Mã đã hết hạn, vui lòng yêu cầu mã mới";
                    case TOO_MANY_ATTEMPTS -> "Nhập sai quá nhiều lần, vui lòng yêu cầu mã mới";
                    case INVALID_CODE, NOT_FOUND -> "Mã xác thực không đúng";
                    case SUCCESS -> "SUCCESS";
                });
    }

    user.changeEmail(req.email());
    return me();
}

// Người dùng đổi mật khẩu của chính mình.
@Transactional
@PutMapping("/password")
public void changePassword(@Valid @RequestBody UserDtos.ChangePasswordRequest req) {
    User user = users.findById(CurrentUser.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    user.changePassword(passwordEncoder.encode(req.newPassword()));
    refreshTokens.revokeAll(user.getId());
}

}
