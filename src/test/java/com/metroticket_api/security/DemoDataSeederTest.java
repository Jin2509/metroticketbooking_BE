package com.metroticket_api.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.metroticket_api.TestcontainersConfiguration;
import com.metroticket_api.notification.NotificationRepository;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

@SpringBootTest(properties = { "app.seed.demo-data=true", "app.seed.file=classpath:seed/test-users.json" })
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DemoDataSeederTest {

    private static final String STAFF = "staff.demo@metroticket.test";
    private static final String FILE_PASSWORD = "Demo@12345";

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    UserRepository users;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TransactionTemplate tx;

    /** Lỗi cũ: tài khoản đã tồn tại thì bỏ qua, nên sửa mật khẩu không có tác dụng và không ai biết mật khẩu thật. */
    @Test
    void fileIsSourceOfTruthAndHashesPassword() throws Exception {
        tx.executeWithoutResult(s -> {
            User staff = users.findByEmail(STAFF).orElseThrow();
            assertThat(staff.getPasswordHash()).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches(FILE_PASSWORD, staff.getPasswordHash())).isTrue();
            staff.changePassword(passwordEncoder.encode("MatKhauKhac@123"), Instant.now());
            staff.changeStatus(UserStatus.LOCKED);
        });
        long notificationsBefore = notifications.count();

        seeder.run(null);

        User staff = users.findByEmail(STAFF).orElseThrow();
        assertThat(passwordEncoder.matches(FILE_PASSWORD, staff.getPasswordHash())).isTrue();
        assertThat(staff.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // Chạy lại không tạo thêm tài khoản hay thông báo trùng
        assertThat(notifications.count()).isEqualTo(notificationsBefore);
    }

    @Test
    void accountsNotInFileAreUntouched() throws Exception {
        String hash = passwordEncoder.encode("KhongDung@123");
        users.save(new User("ngoai.file@example.com", hash, "Ngoài file", UserStatus.ACTIVE, Instant.now()));

        seeder.run(null);

        assertThat(users.findByEmail("ngoai.file@example.com").orElseThrow().getPasswordHash()).isEqualTo(hash);
    }
}
