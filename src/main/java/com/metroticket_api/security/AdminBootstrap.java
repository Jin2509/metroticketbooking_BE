package com.metroticket_api.security;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.rbac.RoleCodes;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/**
 * Tạo tài khoản ADMIN đầu tiên khi hệ thống chưa có admin nào.
 * Đặt ADMIN_EMAIL / ADMIN_PASSWORD trong .env; để trống thì bỏ qua.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final String email;
    private final String password;

    public AdminBootstrap(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder, Clock clock,
                          @Value("${app.bootstrap.admin-email:}") String email,
                          @Value("${app.bootstrap.admin-password:}") String password) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.email = email;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank() || users.existsByRoles_Code(RoleCodes.ADMIN)) {
            return;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalized)) {
            log.warn("ADMIN_EMAIL {} đã tồn tại nhưng không có vai trò ADMIN, bỏ qua bootstrap", normalized);
            return;
        }
        User admin = new User(normalized, passwordEncoder.encode(password), "Quản trị viên",
                UserStatus.ACTIVE, clock.instant());
        admin.assignRole(roles.findByCode(RoleCodes.ADMIN)
                .orElseThrow(() -> new IllegalStateException("Thiếu vai trò ADMIN, kiểm tra migration")));
        users.save(admin);
        log.warn("Đã tạo tài khoản ADMIN đầu tiên: {}. Hãy đổi mật khẩu ngay.", normalized);
    }
}
