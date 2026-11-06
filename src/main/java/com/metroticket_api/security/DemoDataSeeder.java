package com.metroticket_api.security;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.metroticket_api.auth.RefreshTokenService;
import com.metroticket_api.notification.NotificationService;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.rbac.Role;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

import tools.jackson.databind.ObjectMapper;

/**
 * Nạp tài khoản test từ file seed (mặc định seed/demo-users.json), chỉ khi SEED_DEMO_DATA=true và không phải profile prod.
 * File ghi mật khẩu dạng chữ thường để dễ quản lý, seeder băm trước khi lưu vào DB.
 *
 * <p>File là nguồn dữ liệu chuẩn cho các email có trong file: sửa file rồi khởi động lại thì DB cập nhật theo,
 * kể cả mật khẩu. Không đụng tới tài khoản không có trong file. Không đặt trong Flyway vì migration chạy trên
 * mọi môi trường và không sửa lại được.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    record SeedFile(List<SeedUser> users) {
    }

    record SeedUser(String email, String password, String fullName, Set<String> roles, UserStatus status) {
    }

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notifications;
    private final RefreshTokenService refreshTokens;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Resource seedFile;

    public DemoDataSeeder(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
            NotificationService notifications, RefreshTokenService refreshTokens, ObjectMapper objectMapper, Clock clock,
            @Value("${app.seed.file}") Resource seedFile) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.notifications = notifications;
        this.refreshTokens = refreshTokens;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.seedFile = seedFile;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedFile.exists()) {
            log.warn("Không tìm thấy file seed {}, bỏ qua. Sao chép seed/demo-users.example.json thành seed/demo-users.json", seedFile);
            return;
        }
        int created = 0;
        int updated = 0;
        List<SeedUser> seedUsers = read().users();
        for (SeedUser seed : seedUsers) {
            User existing = users.findWithRolesByEmail(normalize(seed.email())).orElse(null);
            if (existing == null) {
                create(seed);
                created++;
            } else if (sync(existing, seed)) {
                updated++;
            }
        }
        log.warn("Seed {}: tạo mới {}, cập nhật {}, giữ nguyên {} tài khoản. Không bật SEED_DEMO_DATA trên production.",
                seedFile.getFilename(), created, updated, seedUsers.size() - created - updated);
    }

    private SeedFile read() {
        try (InputStream in = seedFile.getInputStream()) {
            return objectMapper.readValue(in, SeedFile.class);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file seed " + seedFile, e);
        }
    }

    private void create(SeedUser seed) {
        User user = new User(normalize(seed.email()), passwordEncoder.encode(seed.password()), seed.fullName(),
                UserStatus.PENDING_VERIFICATION, clock.instant());
        user.replaceRoles(resolveRoles(seed));
        if (seed.status() != UserStatus.PENDING_VERIFICATION) {
            user.activate(clock.instant());
            user.changeStatus(seed.status());
        }
        users.saveAndFlush(user);
        if (seed.status() == UserStatus.ACTIVE) {
            seedNotifications(user);
        }
    }

    /** Trả về true nếu có thay đổi. Đổi mật khẩu, vai trò hoặc trạng thái thì đăng xuất các phiên cũ. */
    private boolean sync(User user, SeedUser seed) {
        boolean sessionChanged = false;
        boolean changed = false;
        if (!passwordEncoder.matches(seed.password(), user.getPasswordHash())) {
            user.changePassword(passwordEncoder.encode(seed.password()), clock.instant());
            sessionChanged = true;
        }
        Set<String> currentRoles = user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
        if (!currentRoles.equals(seed.roles())) {
            user.replaceRoles(resolveRoles(seed));
            sessionChanged = true;
        }
        if (user.getStatus() != seed.status()) {
            user.changeStatus(seed.status());
            sessionChanged = true;
        }
        if (!user.getFullName().equals(seed.fullName())) {
            user.changeFullName(seed.fullName());
            changed = true;
        }
        if (sessionChanged) {
            refreshTokens.revokeAll(user.getId());
        }
        return sessionChanged || changed;
    }

    private List<Role> resolveRoles(SeedUser seed) {
        List<Role> found = roles.findByCodeIn(seed.roles());
        if (found.size() != seed.roles().size()) {
            throw new IllegalStateException("File seed có vai trò không tồn tại: " + seed.roles() + " (" + seed.email() + ")");
        }
        return found;
    }

    private void seedNotifications(User user) {
        notifications.notify(user.getId(), NotificationType.SYSTEM, "Chào mừng đến với MetroTicket",
                "Đây là thông báo mẫu đã đọc.").markRead(clock.instant());
        notifications.notify(user.getId(), NotificationType.SECURITY, "Đăng nhập trên thiết bị mới",
                "Đây là thông báo mẫu về an toàn tài khoản.");
        notifications.notify(user.getId(), NotificationType.ANNOUNCEMENT, "Lịch bảo trì hệ thống",
                "Đây là thông báo mẫu từ quản trị viên.");
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
