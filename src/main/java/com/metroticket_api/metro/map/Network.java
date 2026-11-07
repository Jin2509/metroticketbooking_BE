package com.metroticket_api.metro.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineTransfer;
import com.metroticket_api.metro.station.Station;

/**
 * Ảnh chụp bất biến của mạng lưới Metro (tuyến, ga theo thứ tự, điểm chuyển tuyến).
 * Không phụ thuộc JPA để thuật toán tìm đường/tính giá test được bằng dữ liệu thuần.
 */
public record Network(List<LineInfo> lines, Map<Long, StationInfo> stations, List<TransferInfo> transfers) {

    /** Tốc độ ước lượng khi đoạn chưa có travel_time (khớp StationService: ~60 giây/km). */
    static final int FALLBACK_SECONDS_PER_KM = 60;

    public record StationInfo(Long id, String code, String name, double latitude, double longitude,
            StationStatus status, boolean interchange, String district) {
        public boolean active() {
            return status == StationStatus.ACTIVE;
        }
    }

    public record Stop(StationInfo station, int order, BigDecimal distanceFromPrevKm, int travelFromPrevSeconds,
            int dwellSeconds) {
    }

    public record LineInfo(Long id, String code, String name, String colorHex, MetroLineStatus status, List<Stop> stops) {
        public boolean active() {
            return status == MetroLineStatus.ACTIVE;
        }

        public BigDecimal totalDistanceKm() {
            return stops.stream().skip(1).map(Stop::distanceFromPrevKm).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    public record TransferInfo(Long stationId, Long fromLineId, Long toLineId, int minutes, TransferStatus status) {
    }

    public Optional<LineInfo> line(Long lineId) {
        return lines.stream().filter(l -> l.id().equals(lineId)).findFirst();
    }

    public static Network of(Collection<MetroLine> allLines, List<LineStation> lineStations, List<LineTransfer> lineTransfers) {
        Map<Long, StationInfo> stations = new LinkedHashMap<>();
        Map<Long, List<Stop>> stopsByLine = new LinkedHashMap<>();
        for (MetroLine line : allLines) {
            stopsByLine.put(line.getId(), new ArrayList<>());
        }
        for (LineStation ls : lineStations) {
            StationInfo station = stations.computeIfAbsent(ls.getStation().getId(), id -> toInfo(ls.getStation()));
            stopsByLine.computeIfAbsent(ls.getLine().getId(), id -> new ArrayList<>()).add(new Stop(station,
                    ls.getStationOrder(), ls.getDistanceFromPrevKm(), safe(ls.getTravelTimeFromPrevSeconds()),
                    safe(ls.getDwellTimeSeconds())));
        }
        List<LineInfo> lines = new ArrayList<>();
        for (MetroLine line : allLines) {
            List<Stop> raw = new ArrayList<>(stopsByLine.getOrDefault(line.getId(), List.of()));
            raw.sort(java.util.Comparator.comparingInt(Stop::order));
            lines.add(new LineInfo(line.getId(), line.getCode(), line.getNameLine(), line.getColorHex(), line.getStatus(),
                    normalize(raw)));
        }
        List<TransferInfo> transfers = lineTransfers.stream()
                .map(t -> new TransferInfo(t.getStation().getId(), t.getFromLine().getId(), t.getToLine().getId(),
                        t.getTransferTimeMinutes() == null ? 0 : t.getTransferTimeMinutes(), t.getStatus()))
                .toList();
        return new Network(List.copyOf(lines), Map.copyOf(stations), transfers);
    }

    /** Bổ sung số liệu thiếu: khoảng cách = đường chim bay, thời gian = 60 giây/km; ga đầu luôn 0. */
    static List<Stop> normalize(List<Stop> stops) {
        List<Stop> result = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            if (i == 0) {
                result.add(new Stop(stop.station(), stop.order(), BigDecimal.ZERO, 0, stop.dwellSeconds()));
                continue;
            }
            BigDecimal distance = stop.distanceFromPrevKm();
            if (distance == null) {
                distance = BigDecimal.valueOf(haversineKm(stops.get(i - 1).station(), stop.station()))
                        .setScale(3, java.math.RoundingMode.HALF_UP);
            }
            int travel = stop.travelFromPrevSeconds();
            if (travel <= 0) {
                travel = Math.max(1, (int) Math.round(distance.doubleValue() * FALLBACK_SECONDS_PER_KM));
            }
            result.add(new Stop(stop.station(), stop.order(), distance, travel, stop.dwellSeconds()));
        }
        return List.copyOf(result);
    }

    static double haversineKm(StationInfo a, StationInfo b) {
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLng = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * 6371.0 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }

    private static StationInfo toInfo(Station s) {
        return new StationInfo(s.getId(), s.getCode(), s.getNameStation(), s.getLatitude().doubleValue(),
                s.getLongitude().doubleValue(), s.getStatus(), Boolean.TRUE.equals(s.getIsInterchange()), s.getDistrict());
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
