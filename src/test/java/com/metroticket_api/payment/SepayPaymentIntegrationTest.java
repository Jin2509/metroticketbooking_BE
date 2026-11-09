package com.metroticket_api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.parking.MonthlyPassStatus;
import com.metroticket_api.parking.ParkingDtos.BuyMonthlyPassRequest;
import com.metroticket_api.parking.ParkingDtos.MonthlyPassResponse;
import com.metroticket_api.parking.ParkingLotRepository;
import com.metroticket_api.parking.ParkingMonthlyPassRepository;
import com.metroticket_api.parking.ParkingPassPaymentFulfiller;
import com.metroticket_api.parking.ParkingService;
import com.metroticket_api.parking.VehicleType;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.ticket.GateDirection;
import com.metroticket_api.ticket.GateResult;
import com.metroticket_api.ticket.GateService;
import com.metroticket_api.ticket.Ticket;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.ticket.TicketPaymentFulfiller;
import com.metroticket_api.ticket.TicketRepository;
import com.metroticket_api.ticket.TicketService;
import com.metroticket_api.ticket.TicketStatus;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/** Luồng mua vé / vé tháng bằng QR SePay end-to-end trên Postgres thật (webhook gọi thẳng service). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "app.payment.sepay.enabled=true",
        "app.payment.sepay.bank=MBBank",
        "app.payment.sepay.account-number=0123456789",
        "app.payment.sepay.account-holder=METRO TICKET",
        "app.payment.sepay.webhook-api-key=test-key",
        "app.payment.sepay.max-pending-orders-per-user=3" })
@Import({ SepayPaymentIntegrationTest.Config.class,
        com.metroticket_api.notification.NotificationDispatcher.class,
        com.metroticket_api.student.StudentVerificationService.class,
        com.metroticket_api.green.GreenJourneyService.class, MapService.class, TicketService.class,
        GateService.class, PaymentService.class, PaymentOrderService.class, TicketPaymentFulfiller.class,
        ParkingPassPaymentFulfiller.class, StaffStationService.class, RolePermissionCache.class, ParkingService.class,
        com.metroticket_api.system.SystemParamService.class, com.metroticket_api.audit.AuditLogService.class,
        com.metroticket_api.loyalty.LoyaltyService.class, com.metroticket_api.ticket.FareService.class })
class SepayPaymentIntegrationTest {

    /** 10:00 giờ Việt Nam. */
    static final Instant START = Instant.parse("2026-11-07T03:00:00Z");

    /** Đồng hồ tua được để thử hết hạn đơn. */
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
    @Autowired ParkingService parking;
    @Autowired StationRepository stations;
    @Autowired UserRepository users;
    @Autowired TicketRepository ticketRepository;
    @Autowired PaymentTransactionRepository payments;
    @Autowired SepayTransactionRepository sepayTransactions;
    @Autowired ParkingLotRepository lots;
    @Autowired ParkingMonthlyPassRepository passes;

    Map<String, Long> ids;
    UUID passenger;

    @BeforeEach
    void setUp() {
        clock.now.set(START);
        ids = stations.findAll().stream().collect(Collectors.toMap(Station::getCode, Station::getId));
        passenger = newUser();
        loginAs(passenger, "TICKET_BUY", "PARKING_BUY");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private UUID newUser() {
        return users.save(new User("u" + UUID.randomUUID() + "@test.vn", "{noop}x", "Khách", UserStatus.ACTIVE,
                clock.instant())).getId();
    }

    private void loginAs(UUID id, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(),
                null, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private TicketResponse buyDayPass() {
        return tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.SEPAY_QR));
    }

    private SepayResult pay(String content, long amount) {
        return pay(SEPAY_IDS.incrementAndGet(), content, amount, "in", "0123456789");
    }

    private SepayResult pay(long id, String content, long amount, String type, String account) {
        return orders.handleWebhook(new SepayWebhookPayload(id, "MBBank", "2026-11-07 10:01:00", account, "",
                null, content, type, amount, "FT" + id, content));
    }

    @Test
    void qrPurchaseIsPendingUntilWebhookConfirmsPayment() {
        TicketResponse bought = buyDayPass();
        assertThat(bought.status()).isEqualTo(TicketStatus.PENDING_PAYMENT);
        assertThat(bought.qrToken()).as("chưa trả tiền thì không có QR vé").isNull();
        assertThat(bought.payment().amountVnd()).isEqualTo(40000);
        assertThat(bought.payment().orderCode()).matches("MTQ[A-Z0-9]{8}");
        assertThat(bought.payment().qrImageUrl())
                .startsWith("https://qr.sepay.vn/img?acc=0123456789&bank=MBBank&amount=40000&des=MTQ");

        // Cổng soát vé chặn vé chưa thanh toán (kẻ gian lấy được token từ DB/log cũng không dùng được)
        Ticket stored = ticketRepository.findById(bought.id()).orElseThrow();
        loginAs(newUser(), "TICKET_VALIDATE", "STATION_MANAGE");
        assertThat(gates.validate(new GateValidationRequest(stored.getQrToken(), ids.get("ST001"), GateDirection.ENTRY))
                .result()).isEqualTo(GateResult.REJECTED);

        long sepayId = SEPAY_IDS.incrementAndGet();
        String content = "CT DEN:123 " + bought.payment().orderCode().toLowerCase() + " chuyen tien";
        assertThat(pay(sepayId, content, 40000, "in", "0123456789")).isEqualTo(SepayResult.MATCHED);
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.ACTIVE);

        // SePay gửi lại cùng giao dịch: không tạo thêm thanh toán
        assertThat(pay(sepayId, content, 40000, "in", "0123456789")).isEqualTo(SepayResult.MATCHED);
        assertThat(payments.findByPurposeAndReferenceIdAndStatus(PaymentPurpose.TICKET, bought.id().toString(),
                PaymentStatus.SUCCEEDED)).hasSize(1);

        // Chuyển khoản lần 2 cho đơn đã thanh toán → ghi nhận để Kế toán xử lý
        assertThat(pay(bought.payment().orderCode(), 40000)).isEqualTo(SepayResult.NEEDS_REFUND);

        loginAs(passenger, "TICKET_BUY");
        assertThat(tickets.get(bought.id()).qrToken()).as("đã thanh toán thì có QR").isNotNull();
        assertThat(orders.getMine(bought.payment().orderCode()).status()).isEqualTo(PaymentOrderStatus.PAID);
    }

    @Test
    void underpaymentKeepsOrderPending() {
        TicketResponse bought = buyDayPass();
        assertThat(pay(bought.payment().orderCode(), 39000)).isEqualTo(SepayResult.AMOUNT_MISMATCH);
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.PENDING_PAYMENT);
    }

    @Test
    void irrelevantTransactionsAreIgnoredOrUnmatched() {
        TicketResponse bought = buyDayPass();
        assertThat(pay(SEPAY_IDS.incrementAndGet(), bought.payment().orderCode(), 40000, "out", "0123456789"))
                .isEqualTo(SepayResult.IGNORED);
        assertThat(pay(SEPAY_IDS.incrementAndGet(), bought.payment().orderCode(), 40000, "in", "999999"))
                .as("khác tài khoản thụ hưởng").isEqualTo(SepayResult.IGNORED);
        assertThat(pay("tien an trua", 50000)).isEqualTo(SepayResult.UNMATCHED);
        assertThat(pay("MTQZZZZZZZZ", 40000)).isEqualTo(SepayResult.UNMATCHED);
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.PENDING_PAYMENT);
    }

    @Test
    void orderExpiresAfterQrTtlAndLatePaymentCreatesNoTicket() {
        TicketResponse bought = buyDayPass();
        assertThat(bought.payment().expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        clock.advance(Duration.ofMinutes(11));
        orders.expireOrders();
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.CANCELLED);

        assertThat(pay(bought.payment().orderCode(), 40000)).isEqualTo(SepayResult.NEEDS_REFUND);
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(payments.findByPurposeAndReferenceIdAndStatus(PaymentPurpose.TICKET, bought.id().toString(),
                PaymentStatus.SUCCEEDED)).isEmpty();
    }

    @Test
    void overpaymentCreatesNoTicket() {
        TicketResponse bought = buyDayPass();
        assertThat(pay(bought.payment().orderCode(), 41000)).isEqualTo(SepayResult.AMOUNT_MISMATCH);
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.PENDING_PAYMENT);
    }

    @Test
    void paymentForCancelledOrderNeedsRefund() {
        TicketResponse bought = buyDayPass();
        orders.cancelMine(bought.payment().orderCode());
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.CANCELLED);
        assertThat(pay(bought.payment().orderCode(), 40000)).isEqualTo(SepayResult.NEEDS_REFUND);
    }

    @Test
    void otherUsersCannotSeeOrCancelMyOrder() {
        TicketResponse bought = buyDayPass();
        loginAs(newUser(), "TICKET_BUY");
        assertThatThrownBy(() -> orders.getMine(bought.payment().orderCode()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> orders.cancelMine(bought.payment().orderCode()))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void pendingOrdersPerUserAreLimited() {
        buyDayPass();
        buyDayPass();
        buyDayPass();
        assertThatThrownBy(this::buyDayPass)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void mockPaymentMethodsAreRejectedWhenMockDisabled() {
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.CARD)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void multiMonthPassIsPaidInOneTransfer() {
        TicketResponse bought = tickets.purchase(new PurchaseTicketRequest("MONTH_1", 3, PaymentMethod.SEPAY_QR));
        assertThat(bought.periods()).isEqualTo(3);
        assertThat(bought.priceVnd()).isEqualTo(900000);
        assertThat(bought.payment().amountVnd()).isEqualTo(900000);
        assertThat(pay(bought.payment().orderCode(), 300000)).as("chỉ trả 1 tháng").isEqualTo(SepayResult.AMOUNT_MISMATCH);
        assertThat(pay(bought.payment().orderCode(), 900000)).isEqualTo(SepayResult.MATCHED);
        assertThat(ticketRepository.findById(bought.id()).orElseThrow().getStatus()).isEqualTo(TicketStatus.ACTIVE);
    }

    @Test
    void periodsAboveProductLimitAreRejected() {
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("MONTH_1", 13, PaymentMethod.SEPAY_QR)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("DAY_1", 2, PaymentMethod.SEPAY_QR)))
                .as("vé ngày chỉ 1 kỳ").isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void monthlyParkingPassStartsWhenPaid() {
        Long lotId = lots.findByCode("PK-ST011").orElseThrow().getId();
        MonthlyPassResponse pass = parking.buyMonthlyPass(new BuyMonthlyPassRequest(lotId, VehicleType.MOTORBIKE,
                "59-X1 555.55", PaymentMethod.SEPAY_QR));
        assertThat(pass.status()).isEqualTo(MonthlyPassStatus.PENDING_PAYMENT);
        assertThat(pass.validNow()).isFalse();
        assertThat(pass.payment().amountVnd()).isEqualTo(120000);

        clock.advance(Duration.ofMinutes(5));
        assertThat(pay(pass.payment().orderCode(), 120000)).isEqualTo(SepayResult.MATCHED);
        var stored = passes.findById(pass.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(MonthlyPassStatus.ACTIVE);
        assertThat(stored.getValidFrom()).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(stored.getValidUntil()).isEqualTo(stored.getValidFrom().plus(Duration.ofDays(30)));
    }
}
