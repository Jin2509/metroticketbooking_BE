package com.metroticket_api.metro.station;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.StationDtos.CreateStationRequest;
import com.metroticket_api.metro.station.StationDtos.LineStationResponse;
import com.metroticket_api.metro.station.StationDtos.StationOrderItem;
import com.metroticket_api.metro.station.StationDtos.StationResponse;

/**
 * Chạy với Postgres thật (Flyway seed tuyến BTS-01, 14 ga) để kiểm tra các thao tác đổi station_order
 * không vi phạm UNIQUE(line_id, station_order) và không ghi đè số liệu đoạn đã cấu hình.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ StationService.class, LineStationIntegrationTest.PostgresConfig.class })
class LineStationIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresConfig {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired
    StationService service;

    @Autowired
    MetroLineRepository lines;

    @Autowired
    LineStationRepository lineStations;

    Long lineId;
    Map<String, Long> ids;

    @BeforeEach
    void loadSeed() {
        lineId = lines.findByCode("BTS-01").orElseThrow().getId();
        ids = route().stream().collect(Collectors.toMap(ls -> ls.getStation().getCode(), ls -> ls.getStation().getId()));
    }

    @Test
    void seedHasFourteenOrderedStations() {
        assertThat(codes()).containsExactly("ST001", "ST002", "ST003", "ST004", "ST005", "ST006", "ST007",
                "ST008", "ST009", "ST010", "ST011", "ST012", "ST013", "ST014");
        assertContiguousOrders();
    }

    @Test
    void insertInMiddleShiftsOrdersAndOnlyRecalculatesAdjacentSegments() {
        StationResponse extra = service.create(new CreateStationRequest(
                "TST-X", "Ga thử", new BigDecimal("10.7785000"), new BigDecimal("106.7050000"),
                null, null, null, false, StationStatus.ACTIVE));

        LineStationResponse added = service.addStationToLine(lineId, extra.id(), 3, null, null, null);

        assertThat(added.stationOrder()).isEqualTo(3);
        assertThat(codes()).startsWith("ST001", "ST002", "TST-X", "ST003").hasSize(15);
        assertContiguousOrders();

        assertThat(segment("ST002").getDistanceFromPrevKm()).isEqualByComparingTo("0.700");
        assertThat(segment("ST002").getTravelTimeFromPrevSeconds()).isEqualTo(42);
        assertThat(segment("ST004").getDistanceFromPrevKm()).isEqualByComparingTo("1.878");

        LineStation inserted = segment("TST-X");
        assertThat(inserted.getDistanceFromPrevKm()).isPositive();
        assertThat(inserted.getTravelTimeFromPrevSeconds()).isPositive();
        // ST003 giờ nối từ ga thử chứ không còn từ ST002 nên đoạn của nó được tính lại.
        assertThat(segment("ST003").getDistanceFromPrevKm()).isNotEqualByComparingTo("1.061");
        assertThat(segment("ST003").getTravelTimeFromPrevSeconds()).isPositive();
    }

    @Test
    void insertAtEndKeepsExplicitSegmentValues() {
        StationResponse extra = service.create(new CreateStationRequest(
                "TST-END", "Ga cuối thử", new BigDecimal("10.8900000"), new BigDecimal("106.8200000"),
                null, null, null, false, null));

        LineStationResponse added = service.addStationToLine(lineId, extra.id(), null,
                new BigDecimal("1.500"), 95, 40);

        assertThat(added.stationOrder()).isEqualTo(15);
        assertThat(added.distanceFromPrevKm()).isEqualByComparingTo("1.500");
        assertThat(added.travelTimeFromPrevSeconds()).isEqualTo(95);
        assertThat(added.dwellTimeSeconds()).isEqualTo(40);
    }

    @Test
    void removeMiddleStationCompactsOrders() {
        service.removeStationFromLine(lineId, ids.get("ST005"));

        assertThat(codes()).hasSize(13).doesNotContain("ST005");
        assertContiguousOrders();
        assertThat(segment("ST004").getDistanceFromPrevKm()).isEqualByComparingTo("1.878");
        // ST006 giờ nối thẳng từ ST004.
        assertThat(segment("ST006").getDistanceFromPrevKm()).isGreaterThan(new BigDecimal("1.231"));
    }

    @Test
    void reorderSwapDoesNotViolateUniqueOrder() {
        List<StationOrderItem> items = route().stream()
                .map(ls -> {
                    String code = ls.getStation().getCode();
                    int order = switch (code) {
                        case "ST001" -> 2;
                        case "ST002" -> 1;
                        default -> ls.getStationOrder();
                    };
                    return new StationOrderItem(ls.getStation().getId(), order);
                })
                .toList();

        List<LineStationResponse> result = service.updateStationOrder(lineId, items);

        assertThat(result).extracting(r -> r.station().code()).startsWith("ST002", "ST001", "ST003");
        assertContiguousOrders();
        assertThat(segment("ST002").getDistanceFromPrevKm()).isEqualByComparingTo("0");
        assertThat(segment("ST002").getTravelTimeFromPrevSeconds()).isZero();
        assertThat(segment("ST001").getTravelTimeFromPrevSeconds()).isPositive();
        assertThat(segment("ST004").getDistanceFromPrevKm()).isEqualByComparingTo("1.878");
    }

    @Test
    void cannotDeleteStationStillOnLine() {
        assertThatThrownBy(() -> service.delete(ids.get("ST001")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void nearestUsesKilometresAndSortsByDistance() {
        List<StationResponse> near = service.nearest(new BigDecimal("10.7709609"), new BigDecimal("106.6977577"), 1.0);

        assertThat(near).extracting(StationResponse::code).containsExactly("ST001", "ST002");
    }

    @Test
    void listByLineKeepsRouteOrderAndPaginates() {
        var page = service.list(null, lineId, null, PageRequest.of(1, 5));

        assertThat(page.getTotalElements()).isEqualTo(14);
        assertThat(page.getContent()).extracting(StationResponse::code)
                .containsExactly("ST006", "ST007", "ST008", "ST009", "ST010");
    }

    @Test
    void transferRequiresStationOnBothLines() {
        MetroLine other = lines.save(new MetroLine("TST-L2", "Tuyến thử", "#FF0000", null, MetroLineStatus.ACTIVE));

        assertThatThrownBy(() -> service.createLineTransfer(ids.get("ST001"), lineId, other.getId(), 5, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        service.addStationToLine(other.getId(), ids.get("ST001"), null, null, null, null);
        LineTransfer transfer = service.createLineTransfer(ids.get("ST001"), lineId, other.getId(), 5, null);
        assertThat(transfer.getId()).isNotNull();

        assertThatThrownBy(() -> service.createLineTransfer(ids.get("ST001"), lineId, other.getId(), 5, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void invalidSortIsBadRequestNotServerError() {
        assertThatThrownBy(() -> service.list("Bến", null, null, PageRequest.of(0, 5, Sort.by("foo"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(service.list(null, null, null, PageRequest.of(0, 5, Sort.by("nameStation"))).getContent()).hasSize(5);
    }

    private List<LineStation> route() {
        return lineStations.findByLineIdOrderByStationOrderAsc(lineId);
    }

    private List<String> codes() {
        return route().stream().map(ls -> ls.getStation().getCode()).toList();
    }

    private LineStation segment(String code) {
        return route().stream().filter(ls -> ls.getStation().getCode().equals(code)).findFirst().orElseThrow();
    }

    private void assertContiguousOrders() {
        List<Integer> orders = route().stream().map(LineStation::getStationOrder).toList();
        for (int i = 0; i < orders.size(); i++) {
            assertThat(orders.get(i)).isEqualTo(i + 1);
        }
    }
}
