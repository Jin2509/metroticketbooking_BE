package com.metroticket_api.metro.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;

import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.map.Network.LineInfo;
import com.metroticket_api.metro.map.Network.StationInfo;
import com.metroticket_api.metro.map.Network.Stop;
import com.metroticket_api.metro.map.Network.TransferInfo;

/**
 * Tìm đường giữa 2 ga trên toàn mạng (Dijkstra trên đồ thị nút = (tuyến, ga)).
 *
 * <ul>
 *   <li>Chỉ đi trên tuyến ACTIVE. Ga không ACTIVE (bảo trì/tạm đóng) tàu vẫn chạy qua nhưng không thể là ga đi,
 *       ga đến hay ga chuyển tuyến.</li>
 *   <li>Chỉ chuyển tuyến khi có bản ghi line_transfer ACTIVE đúng chiều (from → to) tại ga đó.</li>
 *   <li>FASTEST: tối ưu thời gian (chạy + dừng + chuyển tuyến). SHORTEST: tối ưu quãng đường (dùng tính giá vé).
 *       Hoà thì ưu tiên ít chuyển tuyến.</li>
 * </ul>
 */
public final class RoutePlanner {

    public enum Optimize { FASTEST, SHORTEST }

    public record StationRef(Long id, String code, String name, double latitude, double longitude) {
        static StationRef of(StationInfo s) {
            return new StationRef(s.id(), s.code(), s.name(), s.latitude(), s.longitude());
        }
    }

    public record Leg(Long lineId, String lineCode, String lineName, String colorHex, StationRef from, StationRef to,
            StationRef headsign, List<StationRef> stops, BigDecimal distanceKm, long durationSeconds) {
        public int numberOfStops() {
            return stops.size() - 1;
        }
    }

    public record Transfer(StationRef station, String fromLineCode, String toLineCode, int minutes) {
    }

    public record Journey(StationRef from, StationRef to, Optimize optimize, BigDecimal totalDistanceKm,
            long totalDurationSeconds, long rideDurationSeconds, long transferDurationSeconds,
            List<Leg> legs, List<Transfer> transfers) {
        public int transferCount() {
            return transfers.size();
        }
    }

    private final Network network;
    private final List<LineInfo> lines;
    /** offset[l] = chỉ số nút đầu tiên của tuyến l trong mảng phẳng. */
    private final int[] offset;
    private final int nodeCount;

    public RoutePlanner(Network network) {
        this.network = network;
        this.lines = network.lines().stream().filter(LineInfo::active).filter(l -> l.stops().size() >= 2).toList();
        this.offset = new int[lines.size() + 1];
        for (int l = 0; l < lines.size(); l++) {
            offset[l + 1] = offset[l] + lines.get(l).stops().size();
        }
        this.nodeCount = offset[lines.size()];
    }

    public Optional<Journey> plan(Long fromStationId, Long toStationId, Optimize optimize) {
        if (fromStationId == null || toStationId == null || fromStationId.equals(toStationId)) {
            return Optional.empty();
        }
        long[] primary = new long[nodeCount];
        long[] secondary = new long[nodeCount];
        int[] hops = new int[nodeCount];
        int[] prev = new int[nodeCount];
        Arrays.fill(primary, Long.MAX_VALUE);
        Arrays.fill(prev, -1);

        PriorityQueue<long[]> queue = new PriorityQueue<>((a, b) -> {
            int c = Long.compare(a[1], b[1]);
            if (c != 0) return c;
            c = Long.compare(a[3], b[3]);
            if (c != 0) return c;
            return Long.compare(a[2], b[2]);
        });
        for (int node = 0; node < nodeCount; node++) {
            if (stopOf(node).station().id().equals(fromStationId) && stopOf(node).station().active()) {
                primary[node] = 0;
                queue.add(new long[] { node, 0, 0, 0 });
            }
        }

        int target = -1;
        while (!queue.isEmpty()) {
            long[] entry = queue.poll();
            int node = (int) entry[0];
            if (entry[1] != primary[node] || entry[3] != hops[node] || entry[2] != secondary[node]) {
                continue;
            }
            Stop stop = stopOf(node);
            if (stop.station().id().equals(toStationId)) {
                target = node;
                break;
            }
            int line = lineOf(node);
            int index = node - offset[line];
            List<Stop> stops = lines.get(line).stops();
            // Đi tiếp trên cùng tuyến, 2 chiều
            for (int next : new int[] { index - 1, index + 1 }) {
                if (next < 0 || next >= stops.size()) {
                    continue;
                }
                Stop segmentEnd = stops.get(Math.max(index, next));
                long time = segmentEnd.travelFromPrevSeconds() + stops.get(next).dwellSeconds();
                long meters = segmentEnd.distanceFromPrevKm().movePointRight(3).longValue();
                relax(queue, primary, secondary, hops, prev, node, offset[line] + next, optimize, time, meters, 0);
            }
            // Chuyển tuyến tại ga ACTIVE
            if (stop.station().active()) {
                for (TransferInfo transfer : network.transfers()) {
                    if (transfer.status() != TransferStatus.ACTIVE
                            || !transfer.stationId().equals(stop.station().id())
                            || !transfer.fromLineId().equals(lines.get(line).id())) {
                        continue;
                    }
                    int targetNode = nodeOf(transfer.toLineId(), transfer.stationId());
                    if (targetNode >= 0) {
                        relax(queue, primary, secondary, hops, prev, node, targetNode, optimize,
                                transfer.minutes() * 60L, 0, 1);
                    }
                }
            }
        }
        if (target < 0 || !stopOf(target).station().active()) {
            return Optional.empty();
        }
        List<Integer> path = new ArrayList<>();
        for (int node = target; node >= 0; node = prev[node]) {
            path.add(0, node);
        }
        return Optional.of(buildJourney(path, optimize));
    }

    private void relax(PriorityQueue<long[]> queue, long[] primary, long[] secondary, int[] hops, int[] prev,
            int from, int to, Optimize optimize, long time, long meters, int transferHop) {
        long p = primary[from] + (optimize == Optimize.FASTEST ? time : meters);
        long s = secondary[from] + (optimize == Optimize.FASTEST ? meters : time);
        int h = hops[from] + transferHop;
        boolean better = p < primary[to]
                || (p == primary[to] && h < hops[to])
                || (p == primary[to] && h == hops[to] && s < secondary[to]);
        if (better) {
            primary[to] = p;
            secondary[to] = s;
            hops[to] = h;
            prev[to] = from;
            queue.add(new long[] { to, p, s, h });
        }
    }

    private Journey buildJourney(List<Integer> path, Optimize optimize) {
        List<Leg> legs = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>();
        long transferSeconds = 0;
        int legStart = 0;
        for (int i = 1; i <= path.size(); i++) {
            boolean lineChanges = i == path.size() || lineOf(path.get(i)) != lineOf(path.get(i - 1));
            if (!lineChanges) {
                continue;
            }
            List<Integer> legNodes = path.subList(legStart, i);
            if (legNodes.size() >= 2) {
                legs.add(buildLeg(legNodes));
            }
            if (i < path.size()) {
                LineInfo from = lines.get(lineOf(path.get(i - 1)));
                LineInfo to = lines.get(lineOf(path.get(i)));
                StationInfo station = stopOf(path.get(i)).station();
                int minutes = network.transfers().stream()
                        .filter(t -> t.status() == TransferStatus.ACTIVE && t.stationId().equals(station.id())
                                && t.fromLineId().equals(from.id()) && t.toLineId().equals(to.id()))
                        .mapToInt(TransferInfo::minutes).findFirst().orElse(0);
                transfers.add(new Transfer(StationRef.of(station), from.code(), to.code(), minutes));
                transferSeconds += minutes * 60L;
            }
            legStart = i;
        }
        BigDecimal distance = legs.stream().map(Leg::distanceKm).reduce(BigDecimal.ZERO, BigDecimal::add);
        long ride = legs.stream().mapToLong(Leg::durationSeconds).sum();
        return new Journey(StationRef.of(stopOf(path.get(0)).station()),
                StationRef.of(stopOf(path.get(path.size() - 1)).station()), optimize, distance,
                ride + transferSeconds, ride, transferSeconds, List.copyOf(legs), List.copyOf(transfers));
    }

    /** Thời gian chặng = Σ travel các đoạn + Σ dwell các ga giữa (không tính ga lên/xuống). */
    private Leg buildLeg(List<Integer> nodes) {
        int line = lineOf(nodes.get(0));
        LineInfo info = lines.get(line);
        List<Stop> stops = info.stops();
        int first = nodes.get(0) - offset[line];
        int last = nodes.get(nodes.size() - 1) - offset[line];
        int step = last > first ? 1 : -1;
        BigDecimal distance = BigDecimal.ZERO;
        long duration = 0;
        List<StationRef> refs = new ArrayList<>();
        refs.add(StationRef.of(stops.get(first).station()));
        for (int i = first + step; i != last + step; i += step) {
            Stop segmentEnd = stops.get(Math.max(i, i - step));
            distance = distance.add(segmentEnd.distanceFromPrevKm());
            duration += segmentEnd.travelFromPrevSeconds();
            if (i != last) {
                duration += stops.get(i).dwellSeconds();
            }
            refs.add(StationRef.of(stops.get(i).station()));
        }
        StationInfo headsign = step > 0 ? stops.get(stops.size() - 1).station() : stops.get(0).station();
        return new Leg(info.id(), info.code(), info.name(), info.colorHex(), refs.get(0), refs.get(refs.size() - 1),
                StationRef.of(headsign), List.copyOf(refs), distance, duration);
    }

    private Stop stopOf(int node) {
        int line = lineOf(node);
        return lines.get(line).stops().get(node - offset[line]);
    }

    private int lineOf(int node) {
        int idx = Arrays.binarySearch(offset, node);
        if (idx >= 0) {
            // offset trùng nhau chỉ xảy ra với tuyến rỗng, đã bị lọc nên idx là tuyến bắt đầu tại node
            return Math.min(idx, lines.size() - 1);
        }
        return -idx - 2;
    }

    private int nodeOf(Long lineId, Long stationId) {
        for (int l = 0; l < lines.size(); l++) {
            if (!lines.get(l).id().equals(lineId)) {
                continue;
            }
            List<Stop> stops = lines.get(l).stops();
            for (int i = 0; i < stops.size(); i++) {
                if (stops.get(i).station().id().equals(stationId)) {
                    return offset[l] + i;
                }
            }
        }
        return -1;
    }
}
