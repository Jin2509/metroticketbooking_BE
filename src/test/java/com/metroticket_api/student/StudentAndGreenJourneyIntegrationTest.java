package com.metroticket_api.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.metroticket_api.ai.GeminiProps;
import com.metroticket_api.ai.StudentCardExtraction;
import com.metroticket_api.ai.StudentCardExtractor;
import com.metroticket_api.green.GreenJourneyService;
import com.metroticket_api.green.GreenTripRepository;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.notification.NotificationService;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrderService;
import com.metroticket_api.payment.PaymentProps;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.ticket.GateDirection;
import com.metroticket_api.ticket.GateResult;
import com.metroticket_api.ticket.GateService;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.ticket.TicketPaymentFulfiller;
import com.metroticket_api.ticket.TicketService;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/**
 * Postgres thật + AI giả lập: nộp hồ sơ → worker → tự duyệt / chờ duyệt tay / từ chối, duyệt tay, ưu đãi khi mua vé,
 * Hành trình xanh ghi nhận khi quẹt ra.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "app.payment.mock-enabled=true")
@Import({ StudentAndGreenJourneyIntegrationTest.Config.class,
        com.metroticket_api.notification.NotificationDispatcher.class, StudentVerificationService.class,
        StudentPolicyService.class, StudentVerificationProcessor.class, TicketService.class, PaymentService.class,
        PaymentOrderService.class, TicketPaymentFulfiller.class, GateService.class, StaffStationService.class,
        RolePermissionCache.class, MapService.class, GreenJourneyService.class,
        com.metroticket_api.system.SystemParamService.class, com.metroticket_api.audit.AuditLogService.class,
        com.metroticket_api.loyalty.LoyaltyService.class, com.metroticket_api.ticket.FareService.class })
class StudentAndGreenJourneyIntegrationTest {

    /** 10:00 giờ VN ngày 08/11/2026. */
    static final Instant NOW = Instant.parse("2026-11-08T03:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    /** AI giả lập: trả kết quả do test đặt, hoặc ném lỗi. */
    static final class FakeExtractor implements StudentCardExtractor {
        final AtomicReference<Object> next = new AtomicReference<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public StudentCardExtraction extract(byte[] image, String mimeType) throws AiExtractionException {
            Object n = next.get();
            if (n instanceof AiExtractionException e) {
                throw e;
            }
            return (StudentCardExtraction) n;
        }
    }

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(NOW);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        @Override public Instant instant() { return now.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ PaymentProps.class, GeminiProps.class })
    static class Config {
        @Bean
        com.metroticket_api.notification.push.PushSender pushSender() {
            return org.mockito.Mockito.mock(com.metroticket_api.notification.push.PushSender.class);
        }

        @Bean @ServiceConnection PostgreSQLContainer postgres() { return new PostgreSQLContainer("postgres:17-alpine"); }
        @Bean MutableClock clock() { return new MutableClock(); }
        @Bean FakeExtractor extractor() { return new FakeExtractor(); }
        @Bean NotificationService notificationService() { return mock(NotificationService.class); }
    }

    @Autowired MutableClock clock;
    @Autowired FakeExtractor ai;
    @Autowired NotificationService notifications;
    @Autowired com.metroticket_api.notification.NotificationRepository notificationRepository;
    @Autowired com.metroticket_api.notification.NotificationDeliveryRepository deliveryRepository;
    @Autowired StudentVerificationService service;
    @Autowired StudentPolicyService policyService;
    @Autowired StudentVerificationProcessor processor;
    @Autowired StudentVerificationRepository verifications;
    @Autowired StudentVerificationImageRepository images;
    @Autowired TicketService tickets;
    @Autowired GateService gates;
    @Autowired GreenJourneyService green;
    @Autowired GreenTripRepository greenTrips;
    @Autowired UserRepository users;
    @Autowired StationRepository stations;

    StudentVerificationWorker worker;
    UUID student;
    Map<String, Long> ids;

    /** JPEG hợp lệ về magic byte, đủ 10 KB. */
    static final byte[] IMAGE = jpeg();

    static byte[] jpeg() {
        byte[] b = new byte[12_000];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF;
        return b;
    }

    @BeforeEach
    void setUp() {
        clock.now.set(NOW);
        worker = new StudentVerificationWorker(processor, ai, new ThreadPoolTaskExecutor());
        student = user("Nguyễn Văn An");
        loginAs(student, "TICKET_BUY");
        ids = stations.findAll().stream().collect(Collectors.toMap(Station::getCode, Station::getId));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID user(String name) {
        return users.save(new User("u" + UUID.randomUUID() + "@test.vn", "{noop}x", name, UserStatus.ACTIVE, NOW)).getId();
    }

    private void loginAs(UUID id, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(),
                null, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static StudentCardExtraction card(String name, String code, LocalDate until, String confidence) {
        return new StudentCardExtraction(true, name, code, "Đại học Bách khoa", until, new BigDecimal(confidence),
                false, null, "fake");
    }

    /** Nộp hồ sơ rồi cho worker chạy 1 vòng (đồng bộ). */
    private StudentVerification submitAndProcess(StudentCardExtraction result) {
        var submitted = service.submit("Nguyễn Văn An", "SV2022001", "Đại học Bách khoa", true, IMAGE);
        assertThat(submitted.status()).isEqualTo(StudentVerificationStatus.PENDING);
        ai.next.set(result);
        List<UUID> claimed = processor.claim(5);
        assertThat(claimed).contains(submitted.id());
        claimed.forEach(worker::process);
        return verifications.findById(submitted.id()).orElseThrow();
    }

    @Test
    void autoApprovedStudentGetsDiscountLimitedByValidity() {
        StudentVerification v = submitAndProcess(card("NGUYEN VAN AN", "SV2022001", LocalDate.of(2027, 2, 28), "0.95"));
        assertThat(v.getStatus()).isEqualTo(StudentVerificationStatus.APPROVED);
        assertThat(v.getDecisionSource()).isEqualTo(StudentVerification.DecisionSource.AI);
        assertThat(v.getApprovedUntil()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(notificationRepository.findAll()).anySatisfy(n -> {
            assertThat(n.getUserId()).isEqualTo(student);
            assertThat(n.getType()).isEqualTo(NotificationType.STUDENT);
            assertThat(n.getTitle()).isEqualTo("Xác thực sinh viên thành công");
            assertThat(n.getBody()).contains("50%");
        });
        assertThat(deliveryRepository.findAll()).as("kèm email")
                .anySatisfy(d -> assertThat(d.getChannel()).isEqualTo(com.metroticket_api.notification.NotificationDelivery.Channel.EMAIL));
        assertThat(service.myStatus().verified()).isTrue();

        TicketResponse monthly = tickets.purchase(new PurchaseTicketRequest("MONTH_1", 3, PaymentMethod.CARD));
        assertThat(monthly.discountCode()).isEqualTo("STUDENT");
        assertThat(monthly.listUnitPriceVnd()).isEqualTo(300_000);
        assertThat(monthly.unitPriceVnd()).isEqualTo(150_000);
        assertThat(monthly.priceVnd()).isEqualTo(450_000);

        // còn ~3,7 tháng ưu đãi → 6 tháng giá SV bị từ chối, giá thường thì được
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("MONTH_1", 6, PaymentMethod.CARD)))
                .hasMessageContaining("tối đa 3 kỳ");
        TicketResponse full = tickets.purchase(new PurchaseTicketRequest("MONTH_1", 6, PaymentMethod.CARD, false));
        assertThat(full.discountCode()).isNull();
        assertThat(full.priceVnd()).isEqualTo(1_800_000);

        // vé ngày không nằm trong danh sách ưu đãi mặc định
        assertThat(tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.CARD)).discountCode()).isNull();

        // đã xác thực còn dài hạn → không nộp lại được
        assertThatThrownBy(() -> service.submit("Nguyễn Văn An", "SV2022001", "BK", true, IMAGE))
                .hasMessageContaining("đã được xác thực");
    }

    @Test
    void mismatchGoesToManualReviewAndReviewerCannotBeApplicant() {
        StudentVerification v = submitAndProcess(card("LE THI BINH", "SV2022001", LocalDate.of(2027, 6, 30), "0.95"));
        assertThat(v.getStatus()).isEqualTo(StudentVerificationStatus.NEEDS_REVIEW);
        assertThat(v.getDecisionReasons()).contains("Họ tên");
        assertThat(service.myStatus().verified()).isFalse();
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("MONTH_1", 1, PaymentMethod.CARD, true)))
                .hasMessageContaining("chưa được xác thực");

        // tự duyệt hồ sơ của mình → 403
        loginAs(student, "STUDENT_VERIFY");
        assertThatThrownBy(() -> service.approve(v.getId(), null, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        UUID manager = user("Quản lý");
        loginAs(manager, "STUDENT_VERIFY");
        var approved = service.approve(v.getId(), null, "Tên trên thẻ là tên cũ, đã kiểm tra CCCD");
        assertThat(approved.status()).isEqualTo(StudentVerificationStatus.APPROVED);
        assertThat(approved.decisionSource()).isEqualTo(StudentVerification.DecisionSource.MANUAL);
        assertThat(approved.reviewedBy()).isEqualTo(manager);
        assertThat(approved.approvedUntil()).as("theo hạn thẻ AI đọc").isEqualTo(LocalDate.of(2027, 6, 30));

        var revoked = service.revoke(v.getId(), "Phát hiện thẻ giả");
        assertThat(revoked.status()).isEqualTo(StudentVerificationStatus.REVOKED);
        loginAs(student, "TICKET_BUY");
        assertThat(tickets.purchase(new PurchaseTicketRequest("MONTH_1", 1, PaymentMethod.CARD)).discountCode()).isNull();
    }

    @Test
    void expiredCardIsAutoRejectedAndManagerCanOverturnOnAppeal() {
        StudentVerification v = submitAndProcess(card("NGUYEN VAN AN", "SV2022001", LocalDate.of(2026, 9, 30), "0.95"));
        assertThat(v.getStatus()).isEqualTo(StudentVerificationStatus.REJECTED);
        assertThat(service.myStatus().latest().message()).contains("hết hạn");

        loginAs(user("Quản lý"), "STUDENT_VERIFY");
        var overturned = service.approve(v.getId(), TODAY.plusMonths(6), "Đã gia hạn thẻ, xem bản giấy");
        assertThat(overturned.status()).isEqualTo(StudentVerificationStatus.APPROVED);
        assertThat(overturned.decisionReasons()).contains("lật lại");
    }

    @Test
    void transientAiErrorsAreRetriedThenFallBackToManualReview() {
        var submitted = service.submit("Nguyễn Văn An", "SV2022001", "BK", true, IMAGE);
        ai.next.set(new StudentCardExtractor.AiExtractionException("Gemini HTTP 429", true, null));
        for (int attempt = 1; attempt <= 3; attempt++) {
            processor.claim(5).forEach(worker::process);
            StudentVerification v = verifications.findById(submitted.id()).orElseThrow();
            if (attempt < 3) {
                assertThat(v.getStatus()).isEqualTo(StudentVerificationStatus.PENDING);
                assertThat(v.getAiNextAttemptAt()).isAfter(clock.instant());
                clock.now.set(v.getAiNextAttemptAt()); // tua tới lần thử sau
            } else {
                assertThat(v.getStatus()).isEqualTo(StudentVerificationStatus.NEEDS_REVIEW);
                assertThat(v.getAiError()).contains("429");
            }
        }
    }

    @Test
    void submissionRules() {
        assertThatThrownBy(() -> service.submit("A", "B", "C", false, IMAGE)).hasMessageContaining("đồng ý");
        assertThatThrownBy(() -> service.submit("A", "B", "C", true, new byte[20_000])).hasMessageContaining("JPEG");
        service.submit("Nguyễn Văn An", "SV2022001", "BK", true, IMAGE);
        assertThatThrownBy(() -> service.submit("Nguyễn Văn An", "SV2022001", "BK", true, IMAGE))
                .as("chỉ 1 hồ sơ đang xử lý").hasMessageContaining("đang có hồ sơ");
    }

    @Test
    void policyUpdateRequiresCurrentVersionAndValidProducts() {
        var current = policyService.get();
        var stale = new StudentDtos.UpdatePolicyRequest(current.version() + 1, null, null, null, null, null, null,
                null, null, null, null, null, 40, null, null, null);
        assertThatThrownBy(() -> policyService.update(stale)).hasMessageContaining("tải lại");
        var badProduct = new StudentDtos.UpdatePolicyRequest(current.version(), null, null, null, null, null, null,
                null, null, null, null, null, null, List.of("NOPE"), null, null);
        assertThatThrownBy(() -> policyService.update(badProduct)).hasMessageContaining("NOPE");
        var ok = policyService.update(new StudentDtos.UpdatePolicyRequest(current.version(), null, false,
                new BigDecimal("0.90"), List.of("Bách khoa", "Kinh tế"), null, null, null, null, null, null, null, 40,
                List.of("MONTH_1", "WEEK_1"), null, null));
        assertThat(ok.autoApprove()).isFalse();
        assertThat(ok.acceptedSchools()).containsExactly("Bách khoa", "Kinh tế");
        assertThat(ok.eligibleProductCodes()).containsExactly("MONTH_1", "WEEK_1");
    }

    @Test
    void imagesArePurgedAfterRetention() {
        StudentVerification v = submitAndProcess(card("NGUYEN VAN AN", "SV2022001", LocalDate.of(2027, 2, 28), "0.95"));
        assertThat(images.existsById(v.getId())).isTrue();
        clock.now.set(NOW.plus(Duration.ofDays(31)));
        assertThat(service.purgeImages()).isGreaterThanOrEqualTo(1);
        assertThat(images.existsById(v.getId())).isFalse();
    }

    @Test
    void greenJourneyRecordedOnExit() {
        TicketResponse pass = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.CARD));
        loginAs(user("Nhân viên"), "TICKET_VALIDATE", "STATION_MANAGE");
        assertThat(gates.validate(new GateValidationRequest(pass.qrToken(), ids.get("ST001"), GateDirection.ENTRY))
                .result()).isEqualTo(GateResult.ACCEPTED);
        clock.now.set(NOW.plus(Duration.ofMinutes(25)));
        assertThat(gates.validate(new GateValidationRequest(pass.qrToken(), ids.get("ST011"), GateDirection.EXIT))
                .result()).isEqualTo(GateResult.ACCEPTED);
        // ra lại ngay ở cùng ga → không tính điểm
        assertThat(gates.validate(new GateValidationRequest(pass.qrToken(), ids.get("ST011"), GateDirection.ENTRY))
                .result()).isEqualTo(GateResult.ACCEPTED);
        clock.now.set(NOW.plus(Duration.ofMinutes(30)));
        gates.validate(new GateValidationRequest(pass.qrToken(), ids.get("ST011"), GateDirection.EXIT));

        loginAs(student, "TICKET_BUY");
        var summary = green.summary();
        assertThat(summary.allTime().trips()).as("chỉ chuyến hợp lệ được tính").isEqualTo(1);
        assertThat(summary.allTime().distanceKm()).isEqualByComparingTo("13.422");
        assertThat(summary.allTime().co2SavedKg()).isEqualByComparingTo("0.81");
        assertThat(summary.allTime().points()).isEqualTo(134);
        assertThat(summary.monthRank()).isEqualTo(1);
        assertThat(greenTrips.findAll()).filteredOn(t -> !t.isCounted()).singleElement()
                .satisfies(t -> assertThat(t.getNotCountedReason()).contains("cùng ga"));
        assertThat(green.leaderboard(null, 10).entries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.displayName()).isEqualTo("Nguyễn V. A.");
                    assertThat(e.me()).isTrue();
                });
    }
}
