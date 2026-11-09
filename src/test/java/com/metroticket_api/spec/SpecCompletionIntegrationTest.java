package com.metroticket_api.spec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.metroticket_api.chatbot.ChatFaqRepository;
import com.metroticket_api.chatbot.ChatFaqService;
import com.metroticket_api.chatbot.ChatbotController.FaqRequest;
import com.metroticket_api.incident.Incident;
import com.metroticket_api.incident.IncidentService;
import com.metroticket_api.loyalty.LoyaltyService;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.parking.ParkingBookingController.BookingResponse;
import com.metroticket_api.parking.ParkingBookingController.CreateBookingRequest;
import com.metroticket_api.parking.ParkingBookingPaymentFulfiller;
import com.metroticket_api.parking.ParkingBookingService;
import com.metroticket_api.parking.ParkingBookingStatus;
import com.metroticket_api.parking.ParkingDtos.CreateLotRequest;
import com.metroticket_api.parking.ParkingDtos.UpsertRateRequest;
import com.metroticket_api.parking.ParkingPassPaymentFulfiller;
import com.metroticket_api.parking.ParkingService;
import com.metroticket_api.parking.VehicleType;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentOrderService;
import com.metroticket_api.payment.PaymentProps;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.payment.ReconciliationService;
import com.metroticket_api.payment.SepayResult;
import com.metroticket_api.payment.SepayWebhookPayload;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.report.ReportService;
import com.metroticket_api.report.ReportService.RevenueGroup;
import com.metroticket_api.report.ReportService.TrafficGroup;
import com.metroticket_api.system.SystemParamKey;
import com.metroticket_api.system.SystemParamService;
import com.metroticket_api.ticket.GateDirection;
import com.metroticket_api.ticket.GateService;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.ticket.TicketPaymentFulfiller;
import com.metroticket_api.ticket.TicketPrice;
import com.metroticket_api.ticket.TicketPriceService;
import com.metroticket_api.ticket.TicketProduct;
import com.metroticket_api.ticket.TicketProductRepository;
import com.metroticket_api.ticket.TicketService;
import com.metroticket_api.ticket.TicketStatus;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/** Các chức năng theo đặc tả báo cáo v2, chạy trên Postgres thật với migration Flyway. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "app.payment.sepay.enabled=true",
        "app.payment.sepay.bank=MBBank",
        "app.payment.sepay.account-number=0123456789",
        "app.payment.sepay.account-holder=METRO TICKET",
        "app.payment.sepay.webhook-api-key=test-key" })
@Import({ SpecCompletionIntegrationTest.Config.class,
        com.metroticket_api.notification.NotificationDispatcher.class,
        com.metroticket_api.student.StudentVerificationService.class,
        com.metroticket_api.green.GreenJourneyService.class, MapService.class, TicketService.class, GateService.class,
        PaymentService.class, PaymentOrderService.class, TicketPaymentFulfiller.class, ParkingPassPaymentFulfiller.class,
        ParkingBookingPaymentFulfiller.class, ParkingBookingService.class, ParkingService.class, StaffStationService.class,
        RolePermissionCache.class, SystemParamService.class, com.metroticket_api.audit.AuditLogService.class,
        LoyaltyService.class, com.metroticket_api.ticket.FareService.class, TicketPriceService.class, ReconciliationService.class, IncidentService.class,
        ReportService.class, ChatFaqService.class })
class SpecCompletionIntegrationTest {

    /** 10:00 giờ Việt Nam. */
    static final Instant START = Instant.parse("2026-11-07T03:00:00Z");

    static final class MutableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(START);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
        void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({ PaymentProps.class, com.metroticket_api.ai.GeminiProps.class })
    static class Config {
        @Bean
        com.metroticket_api.notification.push.PushSender pushSender() {
            return org.mockito.Mockito.mock(com.metroticket_api.notification.push.PushSender.class);
        }

        @Bean
        com.metroticket_api.ai.StudentCardExtractor studentCardExtractor() {
            return org.mockito.Mockito.mock(com.metroticket_api.ai.StudentCardExtractor.class);
        }

        @Bean
        com.metroticket_api.notification.NotificationService notificationService() {
            return org.mockito.Mockito.mock(com.metroticket_api.notification.NotificationService.class);
        }

        @Bean
        com.metroticket_api.content.ObjectStorage objectStorage() {
            return org.mockito.Mockito.mock(com.metroticket_api.content.ObjectStorage.class);
        }

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }

        @Bean
        MutableClock clock() {
            return new MutableClock();
        }
    }

    private static final AtomicLong SEPAY_IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired MutableClock clock;
    @Autowired TicketService tickets;
    @Autowired GateService gates;
    @Autowired PaymentOrderService orders;
    @Autowired LoyaltyService loyalty;
    @Autowired SystemParamService params;
    @Autowired TicketPriceService prices;
    @Autowired TicketProductRepository products;
    @Autowired ParkingService parking;
    @Autowired ParkingBookingService bookings;
    @Autowired ReconciliationService reconciliation;
    @Autowired IncidentService incidents;
    @Autowired ReportService reports;
    @Autowired ChatFaqService faqService;
    @Autowired ChatFaqRepository faqRepository;
    @Autowired StaffStationService staffStations;
    @Autowired UserRepository users;
    @Autowired StationRepository stations;
    @Autowired JdbcTemplate jdbc;

    Map<String, Long> stationIds;
    UUID passenger;

    @BeforeEach
    void setUp() {
        clock.now.set(START);
        stationIds = stations.findAll().stream().collect(Collectors.toMap(Station::getCode, Station::getId));
        passenger = newUser();
        loginAs(passenger, "TICKET_BUY", "PARKING_BUY");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rolesMatchTheRbacMatrix() {
        assertThat(permissionsOf("ACCOUNTANT")).contains("PAYMENT_MANAGE", "REPORT_VIEW", "REPORT_EXPORT")
                .doesNotContain("REPORT_TRAFFIC_VIEW", "USER_MANAGE");
        assertThat(permissionsOf("MANAGER")).contains("FARE_APPROVE", "INCIDENT_MANAGE", "REPORT_VIEW", "STUDENT_VERIFY")
                .doesNotContain("REPORT_EXPORT", "REPORT_TRAFFIC_VIEW");
        assertThat(permissionsOf("ADMIN")).contains("SYSTEM_PARAM_MANAGE", "REPORT_TRAFFIC_VIEW", "CHATBOT_MANAGE")
                .doesNotContain("FARE_APPROVE", "STUDENT_VERIFY");
        assertThat(permissionsOf("STAFF")).contains("INCIDENT_REPORT");
        assertThat(permissionsOf("PASSENGER")).contains("CHATBOT_USE");
    }

    @Test
    void systemParamKeepsHistoryAndAppliesFromEffectiveTime() {
        loginAs(newUser(), "SYSTEM_PARAM_MANAGE");
        assertThat(params.intValue(SystemParamKey.CPOINT_PER_CHECKIN)).isEqualTo(50);

        params.update("CPOINT_PER_CHECKIN", 70, START.plus(Duration.ofDays(1)));
        assertThat(params.intValue(SystemParamKey.CPOINT_PER_CHECKIN)).as("chưa tới thời gian áp dụng").isEqualTo(50);
        clock.advance(Duration.ofDays(2));
        assertThat(params.intValue(SystemParamKey.CPOINT_PER_CHECKIN)).isEqualTo(70);
        assertThat(params.history("CPOINT_PER_CHECKIN")).hasSize(2);

        assertThatThrownBy(() -> params.update("PAYMENT_QR_TTL_MINUTES", 60, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> params.update("KHONG_CO", 1, null)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void checkInEarnsFixedPointsOncePerValidationUpToDailyLimit() {
        for (long i = 0; i < 4; i++) {
            long id = insertValidation();
            loyalty.earnForCheckIn(passenger, id);
            loyalty.earnForCheckIn(passenger, id);
        }
        assertThat(loyalty.summary(passenger).pointsBalance()).as("mỗi lượt chỉ cộng một lần").isEqualTo(200);

        loyalty.earnForCheckIn(passenger, insertValidation());
        assertThat(loyalty.summary(passenger).pointsBalance()).as("quá 4 lượt mỗi ngày").isEqualTo(200);

        clock.advance(Duration.ofDays(1));
        loyalty.earnForCheckIn(passenger, insertValidation());
        assertThat(loyalty.summary(passenger).lifetimePoints()).isEqualTo(250);
    }

    @Test
    void spendingPointsDoesNotLowerTier() {
        grantPoints(passenger, 6000);
        assertThat(loyalty.summary(passenger).tier().code()).isEqualTo("SILVER");

        tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR, null, 5000));
        var summary = loyalty.summary(passenger);
        assertThat(summary.pointsBalance()).isEqualTo(1000);
        assertThat(summary.tier().code()).isEqualTo("SILVER");
    }

    @Test
    void pointsReduceOrderAndAreRestoredWhenOrderExpires() {
        grantPoints(passenger, 3000);
        TicketResponse bought = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR, null, 3000));
        assertThat(bought.priceVnd()).isEqualTo(37000);
        assertThat(bought.pointsUsed()).isEqualTo(3000);
        assertThat(bought.payment().amountVnd()).isEqualTo(37000);
        assertThat(loyalty.summary(passenger).pointsBalance()).isZero();

        clock.advance(Duration.ofMinutes(11));
        orders.expireOrders();
        assertThat(tickets.get(bought.id()).status()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(loyalty.summary(passenger).pointsBalance()).isEqualTo(3000);
        assertThat(loyalty.summary(passenger).lifetimePoints()).as("hoàn điểm không tính là tích thêm").isEqualTo(3000);
    }

    @Test
    void cannotUseMorePointsThanBalanceOrMaxDiscount() {
        grantPoints(passenger, 30000);
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("MONTH_1", 1, PaymentMethod.SEPAY_QR, null, 20001)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("tối đa 20000 điểm");

        UUID poor = newUser();
        loginAs(poor, "TICKET_BUY");
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR, null, 1)))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("tối đa 0 điểm");
    }

    @Test
    void priceNeedsApprovalFromAnotherPersonAndAppliesAtEffectiveTime() {
        TicketProduct day = products.findByCode("DAY_1").orElseThrow();
        int oldPrice = day.getPriceVnd();
        UUID admin = newUser();
        loginAs(admin, "FARE_MANAGE");
        TicketPrice price = prices.submit(day.getId(), oldPrice + 5000, START.plus(Duration.ofHours(1)));
        assertThat(price.getStatus()).isEqualTo(TicketPrice.Status.PENDING_APPROVAL);
        assertThatThrownBy(() -> prices.submit(day.getId(), oldPrice + 1000, START.plus(Duration.ofHours(2))))
                .as("mỗi loại vé chỉ có một bảng giá đang chờ").isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> prices.approve(price.getId())).as("người lập không tự duyệt").isInstanceOf(ResponseStatusException.class);

        loginAs(newUser(), "FARE_APPROVE");
        assertThat(prices.approve(price.getId()).getStatus()).isEqualTo(TicketPrice.Status.APPROVED);
        assertThat(products.findByCode("DAY_1").orElseThrow().getPriceVnd()).as("chưa tới giờ áp dụng").isEqualTo(oldPrice);

        clock.advance(Duration.ofHours(2));
        prices.applyDuePrices();
        assertThat(products.findByCode("DAY_1").orElseThrow().getPriceVnd()).isEqualTo(oldPrice + 5000);
    }

    @Test
    void rejectedPriceKeepsReason() {
        TicketProduct week = products.findByCode("WEEK_1").orElseThrow();
        loginAs(newUser(), "FARE_MANAGE");
        TicketPrice price = prices.submit(week.getId(), 150000, START.plus(Duration.ofHours(1)));
        loginAs(newUser(), "FARE_APPROVE");
        TicketPrice rejected = prices.reject(price.getId(), "Giá quá cao");
        assertThat(rejected.getStatus()).isEqualTo(TicketPrice.Status.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("Giá quá cao");
    }

    @Test
    void parkingBookingFlowWithOvertimeFee() {
        Long lotId = createLot(5);
        loginAs(passenger, "PARKING_BUY");
        BookingResponse booking = book(lotId, "59A1-12345");
        assertThat(booking.status()).isEqualTo(ParkingBookingStatus.PENDING_PAYMENT);
        assertThat(booking.qrToken()).as("chưa thanh toán thì chưa có QR").isNull();
        assertThat(booking.payment().amountVnd()).isEqualTo(5000);
        assertThatThrownBy(() -> book(lotId, "59A1-12345")).hasMessageContaining("vẫn còn hiệu lực");

        assertThat(pay(booking.payment().orderCode(), 5000)).isEqualTo(SepayResult.MATCHED);
        BookingResponse paid = bookings.mine(org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0);
        assertThat(paid.status()).isEqualTo(ParkingBookingStatus.PAID);
        assertThat(paid.qrToken()).isNotNull();

        loginAs(newUser(), "PARKING_MANAGE", "PARKING_OPERATE");
        assertThat(bookings.scan(paid.qrToken()).status()).isEqualTo(ParkingBookingStatus.CHECKED_IN);
        clock.advance(Duration.ofHours(9).plusMinutes(10)); // đăng ký 8 giờ, trễ 1 giờ 10 phút
        var out = bookings.scan(paid.qrToken());
        assertThat(out.status()).isEqualTo(ParkingBookingStatus.COMPLETED);
        assertThat(out.overtimeFeeVnd()).as("làm tròn lên 2 giờ").isEqualTo(10000);
        assertThatThrownBy(() -> bookings.scan(paid.qrToken())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void parkingBookingRespectsCapacityAndExpiry() {
        Long lotId = createLot(1);
        loginAs(passenger, "PARKING_BUY");
        BookingResponse first = book(lotId, "59A1-11111");
        loginAs(newUser(), "PARKING_BUY");
        assertThatThrownBy(() -> book(lotId, "59A1-22222")).hasMessageContaining("hết chỗ");

        assertThat(pay(first.payment().orderCode(), 5000)).isEqualTo(SepayResult.MATCHED);
        loginAs(passenger, "PARKING_BUY");
        String qr = bookings.mine(org.springframework.data.domain.PageRequest.of(0, 5)).getContent().get(0).qrToken();
        clock.advance(Duration.ofHours(9));
        loginAs(newUser(), "PARKING_MANAGE", "PARKING_OPERATE");
        assertThatThrownBy(() -> bookings.scan(qr)).hasMessageContaining("quá hạn");
    }

    @Test
    void reconciliationFindsMatchedAndMismatchedTransfers() {
        TicketResponse ok = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR));
        pay(ok.payment().orderCode(), 40000);
        TicketResponse wrong = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR));
        pay(wrong.payment().orderCode(), 50000);
        pay("chuyen nham", 12345);

        loginAs(newUser(), "PAYMENT_MANAGE");
        var result = reconciliation.reconcile(START.minusSeconds(60), START.plusSeconds(60));
        assertThat(result.gatewayCount()).isEqualTo(3);
        assertThat(result.matchedCount()).isEqualTo(1);
        assertThat(result.mismatchedCount()).isEqualTo(2);
        assertThat(result.mismatches()).extracting(ReconciliationService.Mismatch::result)
                .containsExactlyInAnyOrder(SepayResult.AMOUNT_MISMATCH, SepayResult.UNMATCHED);
        faqRepository.flush();
        assertThat(jdbc.queryForObject("select count(*) from sepay_transaction where reconciled", Long.class)).isEqualTo(3);

        Long sepayId = result.mismatches().get(0).sepayId();
        assertThat(reconciliation.note(sepayId, "Đã liên hệ khách").getReconcileNote()).isEqualTo("Đã liên hệ khách");
    }

    @Test
    void incidentGoesThroughItsStatusesAndStaffMustBeAssigned() {
        Long stationId = stationIds.get("ST001");
        UUID staff = newUser();
        jdbc.update("insert into staff_station (user_id, station_id, status, assigned_at) values (?, ?, 'ACTIVE', now())", staff, stationId);
        loginAs(staff, "INCIDENT_REPORT");
        Incident incident = incidents.report(stationId, Incident.Type.EQUIPMENT, Incident.Severity.HIGH, "Cổng 2 hỏng",
                new MockMultipartFile("image", "a.png", "image/png", new byte[] { 1 }));
        assertThat(incident.getStatus()).isEqualTo(Incident.Status.NEW);
        assertThat(incident.getImageKey()).startsWith("incidents/");
        assertThatThrownBy(() -> incidents.report(stationIds.get("ST002"), Incident.Type.OTHER, Incident.Severity.LOW, "x", null))
                .as("không phụ trách ga khác").isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        loginAs(newUser(), "INCIDENT_MANAGE");
        assertThatThrownBy(() -> incidents.resolve(incident.getId(), "xong")).isInstanceOf(ResponseStatusException.class);
        assertThat(incidents.accept(incident.getId()).getStatus()).isEqualTo(Incident.Status.IN_PROGRESS);
        Incident done = incidents.resolve(incident.getId(), "Đã thay thiết bị");
        assertThat(done.getStatus()).isEqualTo(Incident.Status.RESOLVED);
        assertThat(done.getResolution()).isEqualTo("Đã thay thiết bị");
    }

    @Test
    void reportsAggregateRevenueAndTraffic() {
        TicketResponse ok = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR));
        pay(ok.payment().orderCode(), 40000);
        LocalDate day = LocalDate.ofInstant(START, ZoneId.of("Asia/Ho_Chi_Minh"));

        var byProduct = reports.revenue(day, day, RevenueGroup.PRODUCT);
        assertThat(byProduct.totalAmountVnd()).isEqualTo(40000);
        assertThat(byProduct.rows()).hasSize(1);
        assertThat(reports.revenue(day, day, RevenueGroup.DAY).rows().get(0).label()).isEqualTo(day.toString());
        assertThat(reports.revenue(day, day, RevenueGroup.SERVICE).rows().get(0).label()).isEqualTo("TICKET");

        UUID staff = newUser();
        loginAs(staff, "TICKET_VALIDATE", "STATION_MANAGE");
        Long station = stationIds.get("ST001");
        loginAs(passenger, "TICKET_BUY");
        String qr = tickets.get(ok.id()).qrToken();
        loginAs(staff, "TICKET_VALIDATE", "STATION_MANAGE");
        gates.validate(new GateValidationRequest(qr, station, GateDirection.ENTRY));

        assertThat(reports.traffic(day, day, TrafficGroup.STATION).totalCount()).isEqualTo(1);
        assertThat(reports.traffic(day, day, TrafficGroup.LINE).totalCount()).isGreaterThanOrEqualTo(1);
        assertThat(reports.traffic(day, day, TrafficGroup.HOUR).rows().get(0).label()).isEqualTo("10");
        assertThatThrownBy(() -> reports.revenue(day, day.minusDays(1), RevenueGroup.DAY))
                .hasMessageContaining("Khoảng thời gian không hợp lệ");
    }

    private TicketResponse buySingle(String from, String to) {
        return tickets.purchase(new PurchaseTicketRequest("SINGLE", 1, PaymentMethod.SEPAY_QR, null, null,
                stationIds.get(from), stationIds.get(to)));
    }

    private com.metroticket_api.ticket.TicketDtos.GateValidationResponse gate(String qr, String station, GateDirection direction,
            PaymentMethod method) {
        loginAs(newUser(), "TICKET_VALIDATE", "STATION_MANAGE");
        var result = gates.validate(new GateValidationRequest(qr, stationIds.get(station), direction, method));
        loginAs(passenger, "TICKET_BUY", "PARKING_BUY");
        return result;
    }

    private String paidQr(TicketResponse bought) {
        pay(bought.payment().orderCode(), bought.priceVnd());
        return tickets.get(bought.id()).qrToken();
    }

    @Test
    void singleFareGrowsWithDistanceAndExpiresAtEndOfDay() {
        TicketResponse near = buySingle("ST001", "ST002");
        TicketResponse far = buySingle("ST001", "ST010");
        assertThat(near.priceVnd()).isEqualTo(7000);
        assertThat(far.priceVnd()).isGreaterThan(near.priceVnd());
        assertThat(far.distanceKm()).isGreaterThan(near.distanceKm());
        assertThat(near.validUntil()).isEqualTo(Instant.parse("2026-11-07T17:00:00Z"));
        assertThat(near.originStation().code()).isEqualTo("ST001");

        assertThatThrownBy(() -> buySingle("ST001", "ST001")).hasMessageContaining("khác nhau");
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("SINGLE", 1, PaymentMethod.SEPAY_QR)))
                .hasMessageContaining("ga đi và ga đến");
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR, null, null,
                stationIds.get("ST001"), stationIds.get("ST002")))).hasMessageContaining("Chỉ vé lượt");
    }

    @Test
    void singleTicketEntersOnlyAtOriginAndIsUsedOnce() {
        String qr = paidQr(buySingle("ST001", "ST002"));
        assertThat(gate(qr, "ST003", GateDirection.ENTRY, null).reason()).contains("chỉ được vào tại ga");
        assertThat(gate(qr, "ST001", GateDirection.ENTRY, null).result()).isEqualTo(com.metroticket_api.ticket.GateResult.ACCEPTED);
        var out = gate(qr, "ST002", GateDirection.EXIT, null);
        assertThat(out.result()).isEqualTo(com.metroticket_api.ticket.GateResult.ACCEPTED);
        assertThat(out.ticketStatus()).isEqualTo(TicketStatus.USED);
        assertThat(gate(qr, "ST001", GateDirection.ENTRY, null).reason()).contains("đã được sử dụng");
    }

    @Test
    void exitBeyondPurchasedStationRequiresFareDifference() {
        TicketResponse bought = buySingle("ST001", "ST002");
        String qr = paidQr(bought);
        gate(qr, "ST001", GateDirection.ENTRY, null);

        var blocked = gate(qr, "ST010", GateDirection.EXIT, null);
        assertThat(blocked.result()).isEqualTo(com.metroticket_api.ticket.GateResult.REJECTED);
        assertThat(blocked.fareDifferenceVnd()).isPositive();

        var paid = gate(qr, "ST010", GateDirection.EXIT, PaymentMethod.CASH);
        assertThat(paid.result()).isEqualTo(com.metroticket_api.ticket.GateResult.ACCEPTED);
        assertThat(paid.fareDifferenceVnd()).isEqualTo(blocked.fareDifferenceVnd());
        assertThat(paid.ticketStatus()).isEqualTo(TicketStatus.USED);
        faqRepository.flush();
        assertThat(jdbc.queryForObject("select amount_vnd from payment_transaction where purpose = 'TICKET_FARE_ADJUSTMENT' and reference_id = ?",
                Integer.class, bought.id().toString())).isEqualTo(blocked.fareDifferenceVnd());
    }

    @Test
    void exitingEarlierCostsNothingExtra() {
        String qr = paidQr(buySingle("ST001", "ST010"));
        gate(qr, "ST001", GateDirection.ENTRY, null);
        var out = gate(qr, "ST002", GateDirection.EXIT, null);
        assertThat(out.result()).isEqualTo(com.metroticket_api.ticket.GateResult.ACCEPTED);
        assertThat(out.fareDifferenceVnd()).isNull();
    }

    @Test
    void faqIsSearchableUniqueAndSoftDeleted() {
        loginAs(newUser(), "CHATBOT_MANAGE");
        var faq = faqService.create(new FaqRequest("fare", "Trẻ em có phải mua vé không?", "Trẻ dưới 6 tuổi được miễn vé.", "trẻ em miễn vé"));
        assertThatThrownBy(() -> faqService.create(new FaqRequest("fare", "trẻ em có phải mua vé không?", "x", null)))
                .isInstanceOf(ResponseStatusException.class);
        faqRepository.flush();
        assertThat(faqRepository.search("trẻ | miễn")).extracting(f -> f.getId()).contains(faq.getId());

        faqService.delete(faq.getId());
        faqRepository.flush();
        assertThat(faqRepository.search("trẻ | miễn")).isEmpty();
    }

    private List<String> permissionsOf(String role) {
        return jdbc.queryForList("""
                select p.code from permission p join role_permission rp on rp.permission_id = p.id
                join role r on r.id = rp.role_id where r.code = ?
                """, String.class, role);
    }

    private long insertValidation() {
        return jdbc.queryForObject("""
                insert into ticket_validation (station_id, direction, result, validated_at)
                values (?, 'ENTRY', 'ACCEPTED', now()) returning id
                """, Long.class, stationIds.get("ST001"));
    }

    private void grantPoints(UUID userId, int points) {
        jdbc.update("""
                insert into loyalty_account (user_id, points_balance, lifetime_points) values (?, ?, ?)
                on conflict (user_id) do update set points_balance = excluded.points_balance, lifetime_points = excluded.lifetime_points
                """, userId, points, points);
    }

    private Long createLot(int capacity) {
        loginAs(newUser(), "PARKING_MANAGE");
        String code = "L" + UUID.randomUUID().toString().substring(0, 8);
        var lot = parking.createLot(new CreateLotRequest(stationIds.get("ST001"), code, "Bãi " + code, null,
                LocalTime.of(0, 0), LocalTime.of(23, 59), null));
        parking.upsertRate(lot.id(), VehicleType.MOTORBIKE, new UpsertRateRequest(capacity, 5000, 3000, 100000, 0));
        return lot.id();
    }

    private BookingResponse book(Long lotId, String plate) {
        return bookings.create(new CreateBookingRequest(lotId, VehicleType.MOTORBIKE, plate, START, START.plus(Duration.ofHours(8)),
                PaymentMethod.SEPAY_QR));
    }

    private SepayResult pay(String content, long amount) {
        return orders.handleWebhook(new SepayWebhookPayload(SEPAY_IDS.incrementAndGet(), "MBBank", "2026-11-07 10:00:00", "0123456789",
                null, null, content, "in", amount, "REF", content));
    }

    private UUID newUser() {
        return users.saveAndFlush(new User("u" + UUID.randomUUID() + "@test.vn", "{noop}x", "Người dùng", UserStatus.ACTIVE, START)).getId();
    }

    private void loginAs(UUID id, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
