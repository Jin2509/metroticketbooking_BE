package com.metroticket_api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.metroticket_api.mail.MailMessage;
import com.metroticket_api.mail.MailSender;
import com.metroticket_api.mail.MailTemplates;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.notification.NotificationDelivery.Channel;
import com.metroticket_api.notification.NotificationDelivery.Status;
import com.metroticket_api.notification.NotificationDispatcher.Request;
import com.metroticket_api.notification.campaign.AudienceType;
import com.metroticket_api.notification.campaign.CampaignAudience;
import com.metroticket_api.notification.campaign.CampaignDtos.CampaignRequest;
import com.metroticket_api.notification.campaign.CampaignService;
import com.metroticket_api.notification.campaign.CampaignStatus;
import com.metroticket_api.notification.push.PushSender;
import com.metroticket_api.rbac.RoleRepository;
import com.metroticket_api.ticket.Ticket;
import com.metroticket_api.ticket.TicketProductRepository;
import com.metroticket_api.ticket.TicketRepository;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/** Postgres thật: bộ phát, chiến dịch (chèn hàng loạt), outbox push/email, nhắc nhở tự động, tuỳ chọn nhận. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ NotificationPlatformIntegrationTest.Config.class, NotificationDispatcher.class, CampaignService.class,
        CampaignAudience.class, DeliveryProcessor.class, DeliveryWorker.class, AutoReminderJob.class,
        MyNotificationSettingsService.class, NotificationStreamService.class })
class NotificationPlatformIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");

    /** Push giả lập: trả lần lượt các kết quả được xếp, mặc định SENT. */
    static final class FakePush implements PushSender {
        final Deque<Result> next = new ArrayDeque<>();
        final List<String> sentTo = new java.util.ArrayList<>();

        @Override public boolean available() { return true; }

        @Override
        public Result send(String token, Message message) {
            sentTo.add(token);
            return next.isEmpty() ? Result.sent() : next.poll();
        }
    }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean @ServiceConnection PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine"); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean FakePush pushSender() { return new FakePush(); }
        @Bean MailSender mailSender() { return mock(MailSender.class); }
        @Bean MailTemplates mailTemplates() {
            MailTemplates t = mock(MailTemplates.class);
            org.mockito.Mockito.when(t.accountNotice(any(), any(), any(), any()))
                    .thenAnswer(inv -> MailMessage.text(inv.getArgument(0), inv.getArgument(2), inv.getArgument(3)));
            return t;
        }
    }

    @Autowired MutableClock clock;
    @Autowired FakePush push;
    @Autowired MailSender mail;
    @Autowired NotificationDispatcher dispatcher;
    @Autowired CampaignService campaigns;
    @Autowired DeliveryProcessor deliveryProcessor;
    @Autowired DeliveryWorker deliveryWorker;
    @Autowired AutoReminderJob reminders;
    @Autowired MyNotificationSettingsService settings;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired UserDeviceRepository devices;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired TicketRepository tickets;
    @Autowired TicketProductRepository products;
    @Autowired StationRepository stations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    UUID alice;
    UUID bob;
    UUID staff;

    @BeforeEach
    void setUp() {
        clock.now.set(NOW);
        push.next.clear();
        push.sentTo.clear();
        alice = user("Alice", "PASSENGER");
        bob = user("Bob", "PASSENGER");
        staff = user("Nhân viên", "STAFF");
        users.save(new User("locked" + UUID.randomUUID() + "@t.vn", "{noop}x", "Locked", UserStatus.LOCKED, NOW));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user("Admin", "ADMIN").toString(), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID user(String name, String role) {
        User u = new User(name.toLowerCase() + UUID.randomUUID() + "@t.vn", "{noop}x", name, UserStatus.ACTIVE, NOW);
        u.assignRole(roles.findByCode(role).orElseThrow());
        return users.save(u).getId();
    }

    private void processAllDeliveries() {
        deliveryProcessor.claim(100).forEach(deliveryWorker::process);
    }

    @Test
    void dispatcherDedupesAndQueuesPushPerDevice() {
        settings.register(alice, "tok-a-phone", UserDevice.Platform.ANDROID, "1.0");
        settings.register(alice, "tok-a-tablet", UserDevice.Platform.IOS, "1.0");
        Request r = Request.of(alice, NotificationType.TICKET, "Vé sắp hết hạn", "...").dedupe("TICKET_EXPIRING:1");
        assertThat(dispatcher.dispatch(r)).isPresent();
        assertThat(dispatcher.dispatch(r)).as("cùng dedupe key → không gửi lại").isEmpty();
        assertThat(notifications.countByUserIdAndReadAtIsNull(alice)).isEqualTo(1);
        assertThat(deliveries.findAll()).filteredOn(d -> d.getChannel() == Channel.PUSH).hasSize(2);

        processAllDeliveries();
        assertThat(push.sentTo).containsExactlyInAnyOrder("tok-a-phone", "tok-a-tablet");
        assertThat(deliveries.findAll()).allMatch(d -> d.getStatus() == Status.SENT);
    }

    @Test
    void preferencesAreRespectedExceptSecurity() {
        settings.register(alice, "tok-a", UserDevice.Platform.ANDROID, null);
        settings.updatePreferences(alice, false, false, false, false);
        assertThat(dispatcher.dispatch(Request.of(alice, NotificationType.PROMOTION, "KM", "Giảm 20%"))).isEmpty();
        assertThat(dispatcher.dispatch(Request.of(alice, NotificationType.TICKET, "Nhắc", "..."))).isEmpty();
        assertThat(dispatcher.dispatch(Request.of(alice, NotificationType.ANNOUNCEMENT, "Tàu chậm", "..."))).isPresent();
        assertThat(deliveries.findAll()).as("đã tắt push").isEmpty();
        assertThat(dispatcher.dispatch(Request.of(alice, NotificationType.SECURITY, "Đổi mật khẩu", "...").withEmail()))
                .isPresent();
        assertThat(deliveries.findAll()).as("bảo mật luôn gửi push + email").hasSize(2);
    }

    @Test
    void invalidTokenDisablesDeviceAndTransientErrorsRetryWithBackoff() {
        settings.register(alice, "dead-token", UserDevice.Platform.ANDROID, null);
        settings.register(bob, "flaky-token", UserDevice.Platform.ANDROID, null);
        push.next.add(new PushSender.Result(PushSender.Outcome.INVALID_TOKEN, "UNREGISTERED"));
        dispatcher.dispatch(Request.of(alice, NotificationType.SYSTEM, "a", "b"));
        processAllDeliveries();
        assertThat(devices.findByToken("dead-token").orElseThrow().getDisabledAt()).isNotNull();

        push.next.add(new PushSender.Result(PushSender.Outcome.RETRY, "503"));
        dispatcher.dispatch(Request.of(bob, NotificationType.SYSTEM, "a", "b"));
        processAllDeliveries();
        NotificationDelivery d = deliveries.findAll().stream().filter(x -> x.getUserId().equals(bob)).findFirst().orElseThrow();
        assertThat(d.getStatus()).isEqualTo(Status.PENDING);
        assertThat(deliveryProcessor.claim(100)).as("chưa đến lần thử lại").isEmpty();
        clock.now.set(NOW.plusSeconds(31));
        processAllDeliveries();
        assertThat(deliveries.findById(d.getId()).orElseThrow().getStatus()).isEqualTo(Status.SENT);
    }

    @Test
    void campaignToAllActiveUsersWithPushAndEmail() {
        settings.register(alice, "tok-a", UserDevice.Platform.ANDROID, null);
        settings.register(bob, "tok-b", UserDevice.Platform.IOS, null);
        settings.updatePreferences(bob, null, false, null, null); // Bob tắt email
        var c = campaigns.create(new CampaignRequest("Bảo trì hệ thống", "Ứng dụng bảo trì 23:00–23:30", NotificationType.SYSTEM,
                null, "/announcements", AudienceType.ALL, null, null, true, true));
        assertThat(c.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(campaigns.preview(c.id()).recipients()).as("4 tài khoản ACTIVE, bỏ tài khoản bị khoá").isEqualTo(4);

        assertThatThrownBy(() -> campaigns.send(c.id(), c.version() + 1, null)).hasMessageContaining("tải lại");
        var scheduled = campaigns.send(c.id(), c.version(), null);
        assertThat(scheduled.status()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(campaigns.runDue()).isTrue();

        var sent = campaigns.get(c.id());
        assertThat(sent.status()).isEqualTo(CampaignStatus.SENT);
        assertThat(sent.recipientCount()).isEqualTo(4);
        assertThat(sent.pushQueued()).isEqualTo(2);
        assertThat(sent.emailQueued()).as("Bob tắt email").isEqualTo(3);

        processAllDeliveries();
        verify(mail, times(3)).send(any());
        var stats = campaigns.stats(c.id());
        assertThat(stats.recipients()).isEqualTo(4);
        assertThat(stats.deliveries().get("PUSH").get("SENT")).isEqualTo(2);
        assertThat(campaigns.runDue()).as("không gửi lại").isFalse();
    }

    @Test
    void promotionSkipsOptedOutUsersAndRoleAudienceTargetsRole() {
        settings.updatePreferences(alice, null, null, false, null);
        var promo = campaigns.create(new CampaignRequest("Giảm 20% vé tháng", "Chỉ trong tuần này", NotificationType.PROMOTION,
                null, "/tickets/buy", AudienceType.ROLE, "passenger", null, false, false));
        assertThat(campaigns.preview(promo.id()).recipients()).as("chỉ Bob: Alice tắt khuyến mãi, staff khác vai trò")
                .isEqualTo(1);

        var staffOnly = campaigns.create(new CampaignRequest("Họp ca", "8h sáng mai", NotificationType.SYSTEM, null, null,
                AudienceType.USERS, null, Set.of(staff), false, false));
        campaigns.send(staffOnly.id(), staffOnly.version(), null);
        campaigns.runDue();
        assertThat(notifications.countByUserIdAndReadAtIsNull(staff)).isEqualTo(1);
        assertThat(notifications.countByUserIdAndReadAtIsNull(alice)).isZero();
    }

    @Test
    void scheduleCancelDeleteAndValidationRules() {
        var c = campaigns.create(new CampaignRequest("Hẹn giờ", "...", NotificationType.ANNOUNCEMENT, null, null,
                AudienceType.ALL, null, null, true, false));
        var scheduled = campaigns.send(c.id(), c.version(), NOW.plus(Duration.ofHours(2)));
        assertThat(campaigns.runDue()).as("chưa tới giờ").isFalse();
        assertThatThrownBy(() -> campaigns.delete(c.id())).as("chỉ xoá bản nháp").hasMessageContaining("bản nháp");
        campaigns.cancel(c.id(), scheduled.version());
        clock.now.set(NOW.plus(Duration.ofHours(3)));
        assertThat(campaigns.runDue()).as("đã huỷ").isFalse();

        assertThatThrownBy(() -> campaigns.create(new CampaignRequest("x", "y", NotificationType.SECURITY, null, null,
                AudienceType.ALL, null, null, true, false))).hasMessageContaining("ANNOUNCEMENT");
        assertThatThrownBy(() -> campaigns.create(new CampaignRequest("x", "y", NotificationType.SYSTEM, null, null,
                AudienceType.ROLE, "HACKER", null, true, false))).hasMessageContaining("Vai trò");
        assertThatThrownBy(() -> campaigns.create(new CampaignRequest("x", "y", NotificationType.SYSTEM, null, null,
                AudienceType.STATION, "999999", null, true, false))).hasMessageContaining("Không có ga");

        var empty = campaigns.create(new CampaignRequest("x", "y", NotificationType.SYSTEM, null, null,
                AudienceType.STUDENTS, null, null, true, false));
        assertThatThrownBy(() -> campaigns.send(empty.id(), empty.version(), null)).hasMessageContaining("Không có người nhận");
        var past = campaigns.create(new CampaignRequest("x", "y", NotificationType.SYSTEM, null, null,
                AudienceType.ALL, null, null, true, false));
        assertThatThrownBy(() -> campaigns.send(past.id(), past.version(), NOW.minus(Duration.ofHours(1))))
                .hasMessageContaining("đã qua");
    }

    @Test
    void approvedAnnouncementCreatesOneHighPriorityCampaign() {
        // chiến dịch tự động từ thông báo vận hành CRITICAL cho toàn bộ người dùng
        Long announcementId = jdbc.queryForObject("insert into announcement (title, content, status) "
                + "values ('Tạm dừng chạy tàu', 'Sự cố tín hiệu', 'APPROVED') returning id", Long.class);
        campaigns.scheduleFromAnnouncement(announcementId, "Tạm dừng chạy tàu", "Sự cố tín hiệu", true, AudienceType.ALL, null, NOW);
        campaigns.scheduleFromAnnouncement(announcementId, "Trùng", "Trùng", true, AudienceType.ALL, null, NOW);
        assertThat(campaigns.runDue()).isTrue();
        assertThat(campaigns.runDue()).as("1 thông báo vận hành chỉ sinh 1 chiến dịch").isFalse();
        assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(alice)).singleElement().satisfies(n -> {
            assertThat(n.getPriority()).isEqualTo(NotificationPriority.HIGH);
            assertThat(n.getLink()).isEqualTo("/announcements/" + announcementId);
        });
    }

    @Test
    void remindersAreSentOnceAndRespectOptOut() {
        var product = products.findByCode("DAY_1").orElseThrow();
        var station = stations.findAll().get(0);
        Ticket activated = new Ticket(alice, product, 1, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(29)));
        activated.enter(station, NOW.minus(Duration.ofHours(23))); // hết hạn sau 1 giờ
        activated.exit(NOW.minus(Duration.ofHours(22)));
        tickets.save(activated);
        Ticket unused = new Ticket(bob, product, 1, NOW.minus(Duration.ofDays(28)), NOW.plus(Duration.ofDays(2)));
        tickets.save(unused);
        Ticket far = new Ticket(staff, product, 1, NOW, NOW.plus(Duration.ofDays(30)));
        tickets.save(far);
        tickets.flush();

        assertThat(reminders.run()).isEqualTo(2);
        assertThat(reminders.run()).as("chạy lại không gửi trùng").isZero();
        assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(alice)).singleElement()
                .satisfies(n -> assertThat(n.getTitle()).isEqualTo("Vé sắp hết hạn"));
        assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(bob)).singleElement()
                .satisfies(n -> assertThat(n.getTitle()).contains("kích hoạt"));

        Ticket another = new Ticket(bob, product, 1, NOW.minus(Duration.ofDays(28)), NOW.plus(Duration.ofDays(1)));
        tickets.saveAndFlush(another);
        settings.updatePreferences(bob, null, null, null, false);
        assertThat(reminders.run()).as("Bob tắt nhắc nhở").isZero();
    }

    @Test
    void deviceTokenMovesToNewAccountOnSameDevice() {
        settings.register(alice, "shared-token", UserDevice.Platform.ANDROID, null);
        settings.register(bob, "shared-token", UserDevice.Platform.ANDROID, null);
        assertThat(settings.myDevices(alice)).isEmpty();
        assertThat(settings.myDevices(bob)).hasSize(1);
        assertThatThrownBy(() -> settings.unregister(alice, "shared-token")).as("không gỡ được token người khác")
                .hasMessageContaining("Không tìm thấy");
    }
}
