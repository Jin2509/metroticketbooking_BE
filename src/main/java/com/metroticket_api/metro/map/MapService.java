package com.metroticket_api.metro.map;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.map.MapDtos.MapLine;
import com.metroticket_api.metro.map.MapDtos.MapLineStop;
import com.metroticket_api.metro.map.MapDtos.MapStation;
import com.metroticket_api.metro.map.MapDtos.MapTransfer;
import com.metroticket_api.metro.map.MapDtos.NetworkResponse;
import com.metroticket_api.metro.map.Network.LineInfo;
import com.metroticket_api.metro.map.Network.StationInfo;
import com.metroticket_api.metro.map.Network.Stop;
import com.metroticket_api.metro.map.RoutePlanner.Journey;
import com.metroticket_api.metro.map.RoutePlanner.Optimize;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.LineTransferRepository;

/**
 * Bản đồ hệ thống: dựng ảnh chụp toàn mạng từ DB (3 câu truy vấn) và tìm đường giữa 2 ga.
 */
@Service
public class MapService {

    private final MetroLineRepository lines;
    private final LineStationRepository lineStations;
    private final LineTransferRepository transfers;

    public MapService(MetroLineRepository lines, LineStationRepository lineStations, LineTransferRepository transfers) {
        this.lines = lines;
        this.lineStations = lineStations;
        this.transfers = transfers;
    }

    @Transactional(readOnly = true)
    public Network loadNetwork() {
        return Network.of(lines.findAll(), lineStations.findAllWithLineAndStation(), transfers.findAllWithRefs());
    }

    @Transactional(readOnly = true)
    public NetworkResponse network() {
        Network network = loadNetwork();
        Map<Long, String> lineCodes = new LinkedHashMap<>();
        Map<Long, List<String>> linesByStation = new LinkedHashMap<>();
        List<MapLine> mapLines = new ArrayList<>();
        for (LineInfo line : network.lines()) {
            lineCodes.put(line.id(), line.code());
            List<MapLineStop> stops = new ArrayList<>();
            BigDecimal cumulativeKm = BigDecimal.ZERO;
            long cumulativeSec = 0;
            for (int i = 0; i < line.stops().size(); i++) {
                Stop stop = line.stops().get(i);
                if (i > 0) {
                    cumulativeKm = cumulativeKm.add(stop.distanceFromPrevKm());
                    cumulativeSec += stop.travelFromPrevSeconds() + line.stops().get(i - 1).dwellSeconds() * (i > 1 ? 1L : 0L);
                }
                StationInfo s = stop.station();
                stops.add(new MapLineStop(s.id(), s.code(), s.name(), stop.order(), s.latitude(), s.longitude(),
                        s.status(), stop.distanceFromPrevKm(), stop.travelFromPrevSeconds(), stop.dwellSeconds(),
                        cumulativeKm, cumulativeSec));
                linesByStation.computeIfAbsent(s.id(), id -> new ArrayList<>()).add(line.code());
            }
            mapLines.add(new MapLine(line.id(), line.code(), line.name(), line.colorHex(), line.status(),
                    stops.size(), cumulativeKm, cumulativeSec, List.copyOf(stops)));
        }
        List<MapStation> stations = network.stations().values().stream()
                .sorted(Comparator.comparing(StationInfo::code))
                .map(s -> new MapStation(s.id(), s.code(), s.name(), s.latitude(), s.longitude(), s.status(),
                        s.interchange() || linesByStation.getOrDefault(s.id(), List.of()).size() > 1, s.district(),
                        List.copyOf(linesByStation.getOrDefault(s.id(), List.of()))))
                .toList();
        List<MapTransfer> mapTransfers = network.transfers().stream()
                .map(t -> new MapTransfer(t.stationId(), network.stations().get(t.stationId()) == null ? null
                        : network.stations().get(t.stationId()).code(),
                        t.fromLineId(), lineCodes.get(t.fromLineId()), t.toLineId(), lineCodes.get(t.toLineId()),
                        t.minutes(), t.status()))
                .toList();
        return new NetworkResponse(List.copyOf(mapLines), stations, mapTransfers);
    }

    @Transactional(readOnly = true)
    public Journey route(Long fromStationId, Long toStationId, Optimize optimize) {
        return route(loadNetwork(), fromStationId, toStationId, optimize);
    }

    /** Dùng chung cho tính giá vé: lỗi trả 400/404/409 có thông điệp rõ ràng. */
    public static Journey route(Network network, Long fromStationId, Long toStationId, Optimize optimize) {
        if (fromStationId == null || toStationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phải chọn ga đi và ga đến");
        }
        if (fromStationId.equals(toStationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga đi và ga đến phải khác nhau");
        }
        StationInfo from = network.stations().get(fromStationId);
        StationInfo to = network.stations().get(toStationId);
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ga không tồn tại hoặc chưa thuộc tuyến nào");
        }
        if (!from.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ga " + from.name() + " đang " + from.status() + ", không phục vụ đón khách");
        }
        if (!to.active()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ga " + to.name() + " đang " + to.status() + ", không phục vụ trả khách");
        }
        return new RoutePlanner(network).plan(fromStationId, toStationId, optimize == null ? Optimize.FASTEST : optimize)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Không tìm được lộ trình giữa 2 ga trên các tuyến đang hoạt động"));
    }
}
