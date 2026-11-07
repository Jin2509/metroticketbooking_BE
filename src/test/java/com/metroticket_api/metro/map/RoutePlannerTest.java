package com.metroticket_api.metro.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.map.Network.LineInfo;
import com.metroticket_api.metro.map.Network.StationInfo;
import com.metroticket_api.metro.map.Network.Stop;
import com.metroticket_api.metro.map.Network.TransferInfo;
import com.metroticket_api.metro.map.RoutePlanner.Journey;
import com.metroticket_api.metro.map.RoutePlanner.Optimize;

/**
 * Mạng thử:
 * <pre>
 * L1: A(1) —1km/60s— B(2) —1km/60s— C(3) —1km/60s— D(4)
 * L2: E(5) —2km/100s— C(3) —2km/100s— F(6)
 * L3: A(1) —10km/300s— F(6)        (đường thẳng xa nhưng nhanh)
 * Chuyển tuyến tại C giữa L1 ⇄ L2: 3 phút. Tại A L1 ⇄ L3, tại F L3 ⇄ L2: 1 phút.
 * dwell = 30s mọi ga.
 * </pre>
 */
class RoutePlannerTest {

    private static StationInfo st(long id, String code, StationStatus status) {
        return new StationInfo(id, code, "Ga " + code, 10 + id * 0.01, 106 + id * 0.01, status, false, null);
    }

    private static Network network(StationStatus statusOfC, MetroLineStatus statusOfL3, boolean withTransferAtC) {
        StationInfo a = st(1, "A", StationStatus.ACTIVE);
        StationInfo b = st(2, "B", StationStatus.ACTIVE);
        StationInfo c = st(3, "C", statusOfC);
        StationInfo d = st(4, "D", StationStatus.ACTIVE);
        StationInfo e = st(5, "E", StationStatus.ACTIVE);
        StationInfo f = st(6, "F", StationStatus.ACTIVE);
        LineInfo l1 = new LineInfo(1L, "L1", "Tuyến 1", "#111111", MetroLineStatus.ACTIVE, List.of(
                new Stop(a, 1, BigDecimal.ZERO, 0, 30),
                new Stop(b, 2, new BigDecimal("1.000"), 60, 30),
                new Stop(c, 3, new BigDecimal("1.000"), 60, 30),
                new Stop(d, 4, new BigDecimal("1.000"), 60, 30)));
        LineInfo l2 = new LineInfo(2L, "L2", "Tuyến 2", "#222222", MetroLineStatus.ACTIVE, List.of(
                new Stop(e, 1, BigDecimal.ZERO, 0, 30),
                new Stop(c, 2, new BigDecimal("2.000"), 100, 30),
                new Stop(f, 3, new BigDecimal("2.000"), 100, 30)));
        LineInfo l3 = new LineInfo(3L, "L3", "Tuyến 3", "#333333", statusOfL3, List.of(
                new Stop(a, 1, BigDecimal.ZERO, 0, 30),
                new Stop(f, 2, new BigDecimal("10.000"), 300, 30)));
        List<TransferInfo> transfers = new ArrayList<>(List.of(
                new TransferInfo(1L, 1L, 3L, 1, TransferStatus.ACTIVE),
                new TransferInfo(1L, 3L, 1L, 1, TransferStatus.ACTIVE),
                new TransferInfo(6L, 3L, 2L, 1, TransferStatus.ACTIVE),
                new TransferInfo(6L, 2L, 3L, 1, TransferStatus.ACTIVE)));
        if (withTransferAtC) {
            transfers.add(new TransferInfo(3L, 1L, 2L, 3, TransferStatus.ACTIVE));
            transfers.add(new TransferInfo(3L, 2L, 1L, 3, TransferStatus.ACTIVE));
        }
        Map<Long, StationInfo> stations = Stream.of(a, b, c, d, e, f)
                .collect(Collectors.toMap(StationInfo::id, Function.identity()));
        return new Network(List.of(l1, l2, l3), stations, transfers);
    }

    @Test
    void sameLineJourneyCountsIntermediateDwellOnly() {
        Journey j = new RoutePlanner(network(StationStatus.ACTIVE, MetroLineStatus.ACTIVE, true))
                .plan(1L, 4L, Optimize.FASTEST).orElseThrow();
        assertThat(j.legs()).hasSize(1);
        assertThat(j.transferCount()).isZero();
        assertThat(j.totalDistanceKm()).isEqualByComparingTo("3.000");
        // 3 đoạn × 60s + dwell tại B và C (2 × 30s), không tính ga lên/xuống
        assertThat(j.totalDurationSeconds()).isEqualTo(240);
        assertThat(j.legs().get(0).headsign().code()).isEqualTo("D");
    }

    @Test
    void reverseDirectionUsesSegmentOfLaterStation() {
        Journey j = new RoutePlanner(network(StationStatus.ACTIVE, MetroLineStatus.ACTIVE, true))
                .plan(4L, 2L, Optimize.FASTEST).orElseThrow();
        assertThat(j.totalDistanceKm()).isEqualByComparingTo("2.000");
        assertThat(j.totalDurationSeconds()).isEqualTo(150);
        assertThat(j.legs().get(0).headsign().code()).isEqualTo("A");
        assertThat(j.legs().get(0).stops()).extracting(RoutePlanner.StationRef::code).containsExactly("D", "C", "B");
    }

    @Test
    void transferAddsTransferTimeAndSplitsLegs() {
        Journey j = new RoutePlanner(network(StationStatus.ACTIVE, MetroLineStatus.INACTIVE, true))
                .plan(2L, 5L, Optimize.FASTEST).orElseThrow();
        assertThat(j.legs()).extracting(RoutePlanner.Leg::lineCode).containsExactly("L1", "L2");
        assertThat(j.transfers()).singleElement().satisfies(t -> {
            assertThat(t.station().code()).isEqualTo("C");
            assertThat(t.minutes()).isEqualTo(3);
        });
        // B→C 60s + chuyển 180s + C→E 100s
        assertThat(j.totalDurationSeconds()).isEqualTo(340);
        assertThat(j.totalDistanceKm()).isEqualByComparingTo("3.000");
    }

    @Test
    void fastestAndShortestCanDiffer() {
        Network net = network(StationStatus.ACTIVE, MetroLineStatus.ACTIVE, true);
        // A→F: qua L1+L2 = 1+1+2 = 4km nhưng chậm; L3 thẳng = 10km nhưng 300s
        Journey shortest = new RoutePlanner(net).plan(1L, 6L, Optimize.SHORTEST).orElseThrow();
        Journey fastest = new RoutePlanner(net).plan(1L, 6L, Optimize.FASTEST).orElseThrow();
        assertThat(shortest.totalDistanceKm()).isEqualByComparingTo("4.000");
        assertThat(fastest.legs()).extracting(RoutePlanner.Leg::lineCode).containsExactly("L3");
        assertThat(fastest.totalDurationSeconds()).isEqualTo(300);
    }

    @Test
    void cannotTransferWithoutTransferRecord() {
        Network net = network(StationStatus.ACTIVE, MetroLineStatus.INACTIVE, false);
        assertThat(new RoutePlanner(net).plan(2L, 5L, Optimize.FASTEST)).isEmpty();
    }

    @Test
    void trainsPassThroughClosedStationButCannotTransferThere() {
        Network net = network(StationStatus.MAINTENANCE, MetroLineStatus.INACTIVE, true);
        // B→D vẫn đi qua C đang bảo trì
        assertThat(new RoutePlanner(net).plan(2L, 4L, Optimize.FASTEST)).isPresent();
        // B→E phải chuyển tại C (đang bảo trì) → không có đường
        assertThat(new RoutePlanner(net).plan(2L, 5L, Optimize.FASTEST)).isEmpty();
        // C không thể là ga đến
        assertThatThrownBy(() -> MapService.route(net, 2L, 3L, Optimize.FASTEST))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void inactiveLineIsNotUsed() {
        Network net = network(StationStatus.ACTIVE, MetroLineStatus.INACTIVE, true);
        Journey j = new RoutePlanner(net).plan(1L, 6L, Optimize.FASTEST).orElseThrow();
        assertThat(j.legs()).extracting(RoutePlanner.Leg::lineCode).containsExactly("L1", "L2");
    }

    @Test
    void sameStationIsRejected() {
        Network net = network(StationStatus.ACTIVE, MetroLineStatus.ACTIVE, true);
        assertThatThrownBy(() -> MapService.route(net, 1L, 1L, Optimize.FASTEST))
                .isInstanceOf(ResponseStatusException.class);
    }
}
