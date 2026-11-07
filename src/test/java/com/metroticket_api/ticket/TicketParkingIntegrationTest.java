package com.metroticket_api.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.metroticket_api.metro.map.MapDtos.NetworkResponse;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.parking.ParkingDtos.CheckInRequest;
import com.metroticket_api.parking.ParkingDtos.CheckOutRequest;
import com.metroticket_api.parking.ParkingDtos.LotResponse;
import com.metroticket_api.parking.ParkingDtos.SessionResponse;
import com.metroticket_api.parking.ParkingDtos.UpsertRateRequest;
import com.metroticket_api.parking.ParkingLotRepository;
import com.metroticket_api.parking.ParkingService;
import com.metroticket_api.parking.VehicleType;
import com.metroticket_api.payment.PaymentMethod;
import com.metroticket_api.payment.PaymentPurpose;
import com.metroticket_api.payment.PaymentService;
import com.metroticket_api.payment.PaymentStatus;
import com.metroticket_api.payment.PaymentTransactionRepository;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.ticket.TicketDtos.GateValidationRequest;
import com.metroticket_api.ticket.TicketDtos.PurchaseTicketRequest;
import com.metroticket_api.ticket.TicketDtos.TicketResponse;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/**
 * Chạy với Postgres thật + Flyway (seed tuyến BTS-01, bảng giá, bãi xe V14/V15) để kiểm tra: mapping entity khớp
 * migration (ddl-auto=validate), các truy vấn JPQL/khoá dòng, CHECK/EXCLUDE/partial unique index, và luồng
 * mua vé → soát vé → gửi xe Park &amp; Ride end-to-end, ràng buộc thêm/sửa/xoá loại vé.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = "app.payment.mock-enabled=true")
@Import({ TicketParkingIntegrationTest.Config.class,
        com.metroticket_api.notification.NotificationDispatcher.class,
        com.metroticket_api.student.StudentVerificationService.class,
        com.metroticket_api.green.GreenJourneyService.class, MapService.class, TicketService.class, TicketProductService.class,
        GateService.class, PaymentService.class, StaffStationService.class, RolePermissionCache.class,
        ParkingService.class, com.metroticket_api.payment.PaymentOrderService.class, TicketPaymentFulfiller.class,
        com.metroticket_api.parking.ParkingPassPaymentFulfiller.class })
class TicketParkingIntegrationTest {

    /** 10:00 giờ Việt Nam: bãi xe (05:00–23:00) đang mở. */
    static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties({ com.metroticket_api.payment.PaymentProps.class,
            com.metroticket_api.ai.GeminiProps.class })
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
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired MapService map;
    @Autowired TicketProductService productService;
    @Autowired TicketProductRepository productRepository;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired TicketService tickets;
    @Autowired GateService gates;
    @Autowired ParkingService parking;
    @Autowired StationRepository stations;
    @Autowired UserRepository users;
    @Autowired TicketRepository ticketRepository;
    @Autowired PaymentTransactionRepository payments;
    @Autowired ParkingLotRepository lots;

    Map<String, Long> ids;
    UUID passenger;

    @BeforeEach
    void setUp() {
        ids = stations.findAll().stream().collect(Collectors.toMap(Station::getCode, Station::getId));
        passenger = users.save(new User("p" + UUID.randomUUID() + "@test.vn", "{noop}x", "Hành khách",
                UserStatus.ACTIVE, NOW)).getId();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(UUID id, String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(),
                null, java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private void loginAsAdmin() {
        UUID admin = users.save(new User("a" + UUID.randomUUID() + "@test.vn", "{noop}x", "Quản trị",
                UserStatus.ACTIVE, NOW)).getId();
        loginAs(admin, "STATION_MANAGE", "TICKET_VALIDATE", "PARKING_MANAGE", "PARKING_OPERATE");
    }

    @Test
    void networkMapContainsSeededLine() {
        NetworkResponse network = map.network();
        assertThat(network.lines()).anySatisfy(line -> {
            assertThat(line.code()).isEqualTo("BTS-01");
            assertThat(line.stationCount()).isEqualTo(14);
            assertThat(line.totalDistanceKm()).isEqualByComparingTo("19.694");
            assertThat(line.stops().get(0).stationCode()).isEqualTo("ST001");
        });
    }

    @Test
    void seededProductsAreTheFourTimeBasedPasses() {
        assertThat(productService.listActive()).extracting(TicketProductDtos.PublicProductResponse::code)
                .containsExactlyInAnyOrder("DAY_1", "DAY_3", "WEEK_1", "MONTH_1");
        assertThat(productService.listActive()).filteredOn(p -> p.code().equals("MONTH_1"))
                .singleElement().satisfies(p -> assertThat(p.maxPeriods()).isEqualTo(12));
    }

    @Test
    void productCrudRules() {
        loginAsAdmin();
        // trùng thời lượng với loại đang bán
        assertThatThrownBy(() -> productService.create(new TicketProductDtos.CreateProductRequest("DAY_1_PROMO", "KM",
                null, DurationUnit.DAY, 1, 30000, 1, 30, ProductStatus.ACTIVE)))
                .hasMessageContaining("cùng thời lượng");
        // mã trùng
        assertThatThrownBy(() -> productService.create(new TicketProductDtos.CreateProductRequest("day_1", "x",
                null, DurationUnit.DAY, 2, 30000, 1, 30, ProductStatus.INACTIVE)))
                .hasMessageContaining("đã tồn tại");

        var created = productService.create(new TicketProductDtos.CreateProductRequest("week_2", "Vé 2 tuần", null,
                DurationUnit.WEEK, 2, 280000, 1, 30, ProductStatus.ACTIVE));
        assertThat(created.code()).isEqualTo("WEEK_2");

        // sửa với version cũ → 409, đúng version → được, đổi giá ghi lịch sử
        assertThatThrownBy(() -> productService.update(created.id(), new TicketProductDtos.UpdateProductRequest(
                created.version() + 1, null, null, null, null, 250000, null, null)))
                .hasMessageContaining("tải lại");
        var updated = productService.update(created.id(), new TicketProductDtos.UpdateProductRequest(
                created.version(), null, null, null, null, 250000, null, null));
        assertThat(updated.priceVnd()).isEqualTo(250000);
        assertThat(productService.history(created.id())).extracting(TicketProductDtos.AuditResponse::action)
                .containsExactly(TicketProductAudit.Action.UPDATED, TicketProductAudit.Action.CREATED);

        // đã bán thì không đổi thời lượng, không xoá được; ngừng bán thì vé đã bán vẫn giữ
        loginAs(passenger, "TICKET_BUY");
        TicketResponse sold = tickets.purchase(new PurchaseTicketRequest("WEEK_2", 1, PaymentMethod.CARD));
        loginAsAdmin();
        var latest = productService.get(created.id());
        assertThat(latest.hasSoldTickets()).isTrue();
        assertThatThrownBy(() -> productService.update(created.id(), new TicketProductDtos.UpdateProductRequest(
                latest.version(), null, null, DurationUnit.WEEK, 3, null, null, null)))
                .hasMessageContaining("không thể đổi thời lượng");
        assertThatThrownBy(() -> productService.delete(created.id())).hasMessageContaining("không thể xoá");
        productService.deactivate(created.id());
        loginAs(passenger, "TICKET_BUY");
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("WEEK_2", 1, PaymentMethod.CARD)))
                .hasMessageContaining("ngừng bán");
        assertThat(tickets.get(sold.id()).status()).isEqualTo(TicketStatus.ACTIVE);
    }

    @Test
    void unsoldProductCanBeDeletedAndAuditIsKept() {
        loginAsAdmin();
        var created = productService.create(new TicketProductDtos.CreateProductRequest("DAY_2", "Vé 2 ngày", null,
                DurationUnit.DAY, 2, 70000, 1, 30, ProductStatus.INACTIVE));
        productService.delete(created.id());
        assertThat(productRepository.findById(created.id())).isEmpty();
        assertThat(productService.history(created.id())).extracting(TicketProductDtos.AuditResponse::action)
                .contains(TicketProductAudit.Action.DELETED);
    }

    @Test
    void databaseRejectsInvalidProductsEvenIfServiceIsBypassed() {
        assertThatThrownBy(() -> {
            em.createNativeQuery("insert into ticket_product (code, name, price_vnd, activation_window_days, status, "
                    + "duration_unit, duration_value, max_periods) values ('DAY_X', 'x', 1000, 30, 'INACTIVE', 'DAY', 1, 3)")
                    .executeUpdate();
        }).as("vé ngày nhiều kỳ").isInstanceOf(Exception.class);
    }

    @Test
    void databaseForbidsTwoActiveProductsWithSameDuration() {
        assertThatThrownBy(() -> {
            em.createNativeQuery("insert into ticket_product (code, name, price_vnd, activation_window_days, status, "
                    + "duration_unit, duration_value, max_periods) values ('DAY_DUP', 'x', 1000, 30, 'ACTIVE', 'DAY', 1, 1)")
                    .executeUpdate();
        }).isInstanceOf(Exception.class);
    }

    @Test
    void dayPassEndToEndWithParkAndRide() {
        loginAs(passenger, "TICKET_BUY");
        TicketResponse ticket = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.E_WALLET));
        assertThat(ticket.priceVnd()).isEqualTo(40000);
        assertThat(ticket.validUntil()).as("hạn kích hoạt 30 ngày").isEqualTo(NOW.plusSeconds(30L * 86400));
        assertThat(payments.findByPurposeAndReferenceIdAndStatus(PaymentPurpose.TICKET, ticket.id().toString(),
                PaymentStatus.SUCCEEDED)).hasSize(1);

        loginAsAdmin();
        Long lotId = lots.findByCode("PK-ST001").orElseThrow().getId();
        SessionResponse parked = parking.checkIn(new CheckInRequest(lotId, VehicleType.MOTORBIKE, "59-X1 123.45", null));
        assertThatThrownBy(() -> parking.checkIn(new CheckInRequest(lotId, VehicleType.MOTORBIKE, "59X112345", null)))
                .as("một biển số không gửi 2 lần").isInstanceOf(ResponseStatusException.class);

        assertThat(gates.validate(new GateValidationRequest(ticket.qrToken(), ids.get("ST001"), GateDirection.ENTRY))
                .result()).isEqualTo(GateResult.ACCEPTED);
        assertThat(gates.validate(new GateValidationRequest(ticket.qrToken(), ids.get("ST001"), GateDirection.ENTRY))
                .result()).as("anti-passback").isEqualTo(GateResult.REJECTED);
        assertThat(gates.validate(new GateValidationRequest(ticket.qrToken(), ids.get("ST011"), GateDirection.EXIT))
                .result()).isEqualTo(GateResult.ACCEPTED);

        Ticket stored = ticketRepository.findById(ticket.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        assertThat(stored.getValidUntil()).as("kích hoạt khi quẹt vào").isEqualTo(NOW.plusSeconds(86400));

        assertThatThrownBy(() -> parking.checkOut(new CheckOutRequest(parked.sessionCode(), "59X199999", PaymentMethod.CASH, null)))
                .as("biển số không khớp").isInstanceOf(ResponseStatusException.class);
        SessionResponse out = parking.checkOut(new CheckOutRequest(parked.sessionCode(), "59X1-12345", PaymentMethod.CASH,
                ticket.qrToken()));
        assertThat(out.feeVnd()).isEqualTo(5000);
        assertThat(out.discountVnd()).isEqualTo(2500);

        // cùng vé, cùng ngày, xe thứ hai không được ưu đãi nữa
        SessionResponse second = parking.checkIn(new CheckInRequest(lotId, VehicleType.MOTORBIKE, "59-X1 777.77", null));
        assertThatThrownBy(() -> parking.checkOut(new CheckOutRequest(second.sessionCode(), "59X177777",
                PaymentMethod.CASH, ticket.qrToken()))).hasMessageContaining("trong hôm nay");
    }

    @Test
    void parkingCapacityIsEnforced() {
        loginAsAdmin();
        Long lotId = lots.findByCode("PK-ST008").orElseThrow().getId();
        parking.upsertRate(lotId, VehicleType.CAR, new UpsertRateRequest(1, 25000, 50000, 1200000, 0));
        parking.checkIn(new CheckInRequest(lotId, VehicleType.CAR, "51A-123.45", null));
        assertThatThrownBy(() -> parking.checkIn(new CheckInRequest(lotId, VehicleType.CAR, "51A-678.90", null)))
                .hasMessageContaining("hết chỗ");
        LotResponse lot = parking.getLot(lotId);
        assertThat(lot.vehicles()).filteredOn(v -> v.vehicleType() == VehicleType.CAR)
                .singleElement().satisfies(v -> assertThat(v.available()).isZero());
        assertThatThrownBy(() -> parking.upsertRate(lotId, VehicleType.CAR, new UpsertRateRequest(0, 25000, 50000, 1200000, 0)))
                .as("không giảm sức chứa dưới số xe đang gửi").hasMessageContaining("nhỏ hơn số xe đang gửi");
    }

    @Test
    void staffMustBeAssignedToStation() {
        loginAs(UUID.randomUUID(), "TICKET_VALIDATE");
        assertThatThrownBy(() -> gates.validate(new GateValidationRequest("x", ids.get("ST001"), GateDirection.ENTRY)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void refundUnusedPassAndExpiryJob() {
        loginAs(passenger, "TICKET_BUY");
        TicketResponse pass = tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.CARD));
        assertThat(pass.refundable()).isTrue();
        var refund = tickets.refund(pass.id());
        assertThat(refund.refundedAmountVnd()).isEqualTo(40000);
        assertThat(refund.status()).isEqualTo(TicketStatus.REFUNDED);
        assertThatThrownBy(() -> tickets.refund(pass.id())).isInstanceOf(ResponseStatusException.class);

        TicketResponse week = tickets.purchase(new PurchaseTicketRequest("WEEK_1", 1, PaymentMethod.CARD));
        assertThat(ticketRepository.expireUnused(week.validUntil().plusSeconds(1))).as("quá hạn kích hoạt")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void onlyQrOrDevMockAllowedOnline() {
        loginAs(passenger, "TICKET_BUY");
        assertThatThrownBy(() -> tickets.purchase(new PurchaseTicketRequest("DAY_1", 1, PaymentMethod.CASH)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
