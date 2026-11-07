package com.metroticket_api.metro.train;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfig;
import com.metroticket_api.metro.operation.OperationConfigRepository;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;

/**
 * Mô phỏng vị trí tàu theo cấu hình vận hành ACTIVE của tuyến.
 *
 * <p>Một vòng (cycle) = chiều đi + quay đầu ở ga cuối + chiều về + quay đầu ở ga đầu. Tàu thứ i lệch pha
 * i × headway so với tàu đầu. Thời gian chạy từng đoạn được co giãn theo tỉ lệ travel_time để tổng chiều đi
 * đúng bằng running_time_minutes.</p>
 *
 * <ul>
 *   <li>Tuyến không ACTIVE hoặc ngoài giờ vận hành → không có tàu.</li>
 *   <li>Số tàu sẵn sàng ít hơn cấu hình (tàu hỏng/bảo trì đột xuất) → chạy với số tàu còn lại, giữ đúng
 *       slot pha của các tàu còn lại (xuất hiện khoảng trống trong lịch chạy, đúng như thực tế).</li>
 * </ul>
 */
@Service
public class TrainPositionService {

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final long DAY_SECONDS = 24L * 60 * 60;

    private final OperationConfigRepository configs;
    private final MetroLineRepository lines;
    private final TrainRepository trains;
    private final LineStationRepository lineStations;
    private final Clock clock;

    public TrainPositionService(OperationConfigRepository configs, MetroLineRepository lines, TrainRepository trains,
            LineStationRepository lineStations, Clock clock) {
        this.configs = configs;
        this.lines = lines;
        this.trains = trains;
        this.lineStations = lineStations;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TrainPositionResponse> getPositions(Long lineId) {
        MetroLine line = lines.findById(lineId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
        Optional<Simulation> simulation = prepare(line, clock.instant());
        if (simulation.isEmpty()) {
            return List.of();
        }
        Simulation sim = simulation.get();
        List<TrainPositionResponse> positions = new ArrayList<>(sim.trains().size());
        for (int i = 0; i < sim.trains().size(); i++) {
            positions.add(simulatePosition(sim.trains().get(i), line, sim.route(), sim.cycle(), sim.phaseOf(i)));
        }
        return positions;
    }

    /**
     * Các chuyến sắp rời ga theo từng hướng, tính từ cùng mô hình với {@link #getPositions(Long)} nên khớp với
     * vị trí tàu hiển thị trên bản đồ.
     */
    @Transactional(readOnly = true)
    public StationArrivalsResponse getArrivals(String lineCode, String stationCode, int limitPerDirection) {
        MetroLine line = lines.findByCode(lineCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy tuyến"));
        int limit = Math.clamp(limitPerDirection, 1, 20);
        Instant now = clock.instant();
        List<LineStation> route = lineStations.findByLineIdOrderByStationOrderAsc(line.getId());
        LineStation target = route.stream()
                .filter(ls -> ls.getStation().getCode().equalsIgnoreCase(stationCode))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ga không thuộc tuyến " + line.getCode()));

        Optional<Simulation> simulation = prepare(line, now);
        if (simulation.isEmpty()) {
            return new StationArrivalsResponse(line.getCode(), target.getStation().getCode(),
                    target.getStation().getNameStation(), now.toString(), false, List.of());
        }
        Simulation sim = simulation.get();

        // Các thời điểm (offset trong vòng) tàu rời ga target, kèm thời điểm tàu đã vào ga trước đó
        List<long[]> events = new ArrayList<>();      // {departureOffset, arrivalOffset, outbound(1/0)}
        long offset = 0;
        List<MotionStep> cycle = sim.cycle();
        for (int s = 0; s < cycle.size(); s++) {
            MotionStep step = cycle.get(s);
            if (step.movement() && sameStation(step.from(), target)) {
                MotionStep previous = cycle.get(Math.floorMod(s - 1, cycle.size()));
                long arrival = !previous.movement() && sameStation(previous.from(), target)
                        ? offset - previous.durationSeconds()
                        : offset;
                events.add(new long[] { offset, arrival, step.outbound() ? 1 : 0 });
            }
            offset += step.durationSeconds();
        }

        List<StationArrival> outbound = new ArrayList<>();
        List<StationArrival> inbound = new ArrayList<>();
        LineStation first = sim.route().get(0);
        LineStation last = sim.route().get(sim.route().size() - 1);
        for (int i = 0; i < sim.trains().size(); i++) {
            long phase = sim.phaseOf(i);
            for (long[] event : events) {
                long departureIn = Math.floorMod(event[0] - phase, sim.cycleSeconds());
                long dwell = event[0] - event[1];
                for (long k = departureIn; k <= sim.remainingOperatingSeconds(); k += sim.cycleSeconds()) {
                    boolean isOutbound = event[2] == 1;
                    LineStation headsign = isOutbound ? last : first;
                    StationArrival arrival = new StationArrival(
                            sim.trains().get(i).getCode(),
                            isOutbound ? "OUTBOUND" : "INBOUND",
                            headsign.getStation().getCode(),
                            headsign.getStation().getNameStation(),
                            Math.max(0, k - dwell),
                            k,
                            now.plusSeconds(k).toString());
                    (isOutbound ? outbound : inbound).add(arrival);
                }
            }
        }
        Comparator<StationArrival> byDeparture = Comparator.comparingLong(StationArrival::departureInSec);
        List<StationArrival> result = new ArrayList<>();
        outbound.stream().sorted(byDeparture).limit(limit).forEach(result::add);
        inbound.stream().sorted(byDeparture).limit(limit).forEach(result::add);
        return new StationArrivalsResponse(line.getCode(), target.getStation().getCode(),
                target.getStation().getNameStation(), now.toString(), true, result);
    }

    /** Rỗng khi tuyến không chạy (không ACTIVE, ngoài giờ, không còn tàu sẵn sàng). */
    private Optional<Simulation> prepare(MetroLine line, Instant now) {
        if (line.getStatus() != MetroLineStatus.ACTIVE) {
            return Optional.empty();
        }
        Long lineId = line.getId();
        OperationConfig config = configs.findByLineIdAndStatus(lineId, OperationConfigStatus.ACTIVE).stream()
                .filter(candidate -> !candidate.getEffectiveFrom().isAfter(now))
                .filter(candidate -> candidate.getEffectiveTo() == null || candidate.getEffectiveTo().isAfter(now))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Tuyến chưa có cấu hình vận hành ACTIVE đang hiệu lực"));

        LocalTime localNow = LocalTime.ofInstant(now, VIETNAM);
        long operatingElapsedSeconds = getOperatingElapsedSeconds(config, localNow);
        if (operatingElapsedSeconds < 0) {
            return Optional.empty();
        }
        if (config.getNumberOfTrains() == null || config.getNumberOfTrains() <= 0
                || config.getHeadwayMinutes() == null || config.getHeadwayMinutes() <= 0
                || config.getRunningTimeMinutes() == null || config.getRunningTimeMinutes() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cấu hình vận hành không hợp lệ để mô phỏng");
        }

        List<LineStation> route = lineStations.findByLineIdOrderByStationOrderAsc(lineId);
        List<MotionStep> cycle = buildCycle(route, config);
        long cycleSeconds = cycle.stream().mapToLong(MotionStep::durationSeconds).sum();
        if (cycleSeconds <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể mô phỏng lịch chạy của tuyến");
        }

        List<Train> availableTrains = trains.findByLineId(lineId).stream()
                .filter(train -> train.getStatus() != null && train.getStatus().isAvailableForService())
                .sorted(Comparator.comparing(Train::getCode))
                .limit(config.getNumberOfTrains())
                .toList();
        if (availableTrains.isEmpty()) {
            return Optional.empty();
        }

        long remaining = Math.floorMod(config.getEndTime().toSecondOfDay() - localNow.toSecondOfDay(), DAY_SECONDS);
        return Optional.of(new Simulation(route, cycle, cycleSeconds, availableTrains, operatingElapsedSeconds,
                config.getHeadwayMinutes() * 60L, remaining));
    }

    private long getOperatingElapsedSeconds(OperationConfig config, LocalTime now) {
        LocalTime start = config.getStartTime();
        LocalTime end = config.getEndTime();
        if (start.equals(end)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "start_time và end_time của cấu hình không tạo thành khung giờ vận hành hợp lệ");
        }

        boolean overnight = end.isBefore(start);
        boolean operating = overnight
                ? !now.isBefore(start) || now.isBefore(end)
                : !now.isBefore(start) && now.isBefore(end);
        if (!operating) {
            return -1;
        }

        long elapsed = now.toSecondOfDay() - start.toSecondOfDay();
        return elapsed < 0 ? elapsed + DAY_SECONDS : elapsed;
    }

    private List<MotionStep> buildCycle(List<LineStation> route, OperationConfig config) {
        if (route == null || route.size() < 2) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tuyến cần có ít nhất hai ga để mô phỏng");
        }

        int stationCount = route.size();
        long[] travelTimes = new long[stationCount];
        long[] dwellTimes = new long[stationCount];
        long totalTravelSeconds = 0;
        long intermediateDwellSeconds = 0;

        for (int i = 1; i < stationCount; i++) {
            Integer travelTime = route.get(i).getTravelTimeFromPrevSeconds();
            if (travelTime == null || travelTime <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Thiếu travel_time_from_prev_seconds hợp lệ trên tuyến");
            }
            travelTimes[i] = travelTime;
            totalTravelSeconds += travelTime;
        }
        for (int i = 1; i < stationCount - 1; i++) {
            Integer dwellTime = route.get(i).getDwellTimeSeconds();
            dwellTimes[i] = dwellTime == null ? 0 : dwellTime;
            if (dwellTimes[i] < 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "dwell_time_seconds không được âm");
            }
            intermediateDwellSeconds += dwellTimes[i];
        }

        long runningSeconds = config.getRunningTimeMinutes() * 60L;
        long movementBudget = runningSeconds - intermediateDwellSeconds;
        if (movementBudget < totalTravelSeconds || totalTravelSeconds <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "running_time_minutes không đủ cho thời gian chạy và dừng giữa các ga");
        }

        List<MotionStep> outbound = buildDirection(route, travelTimes, dwellTimes, movementBudget, totalTravelSeconds, true);
        List<MotionStep> inbound = buildDirection(route, travelTimes, dwellTimes, movementBudget, totalTravelSeconds, false);
        List<MotionStep> cycle = new ArrayList<>(outbound.size() + inbound.size() + 2);
        cycle.addAll(outbound);
        addTurnaround(cycle, route.get(stationCount - 1), config.getTurnaroundTimeMinutes(), false);
        cycle.addAll(inbound);
        addTurnaround(cycle, route.get(0), config.getTurnaroundTimeMinutes(), true);
        return cycle;
    }

    private List<MotionStep> buildDirection(List<LineStation> route, long[] travelTimes, long[] dwellTimes,
            long movementBudget, long totalTravelSeconds, boolean outbound) {
        List<MotionStep> steps = new ArrayList<>();
        int stationCount = route.size();
        long cumulativeTravel = 0;
        int edges = stationCount - 1;
        for (int edge = 0; edge < edges; edge++) {
            int fromIndex = outbound ? edge : stationCount - 1 - edge;
            int toIndex = outbound ? edge + 1 : stationCount - 2 - edge;
            int travelIndex = Math.max(fromIndex, toIndex);
            cumulativeTravel += travelTimes[travelIndex];
            long movementDuration = cumulativeTravel * movementBudget / totalTravelSeconds
                    - (cumulativeTravel - travelTimes[travelIndex]) * movementBudget / totalTravelSeconds;
            steps.add(new MotionStep(route.get(fromIndex), route.get(toIndex), true, outbound, movementDuration));
            if (edge < edges - 1 && dwellTimes[toIndex] > 0) {
                steps.add(new MotionStep(route.get(toIndex), route.get(toIndex), false, outbound, dwellTimes[toIndex]));
            }
        }
        return steps;
    }

    private void addTurnaround(List<MotionStep> cycle, LineStation station, Integer turnaroundMinutes,
            boolean outbound) {
        long seconds = turnaroundMinutes == null ? 0 : turnaroundMinutes * 60L;
        if (seconds > 0) {
            cycle.add(new MotionStep(station, station, false, outbound, seconds));
        }
    }

    private TrainPositionResponse simulatePosition(Train train, MetroLine line, List<LineStation> route,
            List<MotionStep> cycle, long phaseSeconds) {
        long remaining = phaseSeconds;
        int stepIndex = 0;
        while (stepIndex < cycle.size() - 1 && remaining >= cycle.get(stepIndex).durationSeconds()) {
            remaining -= cycle.get(stepIndex).durationSeconds();
            stepIndex++;
        }

        MotionStep step = cycle.get(stepIndex);
        MotionStep nextMove = step.movement() ? step : nextMovement(cycle, stepIndex);
        LineStation headsign = nextMove.outbound()
                ? route.get(route.size() - 1)
                : route.get(0);

        double latitude;
        double longitude;
        long etaSeconds;
        if (step.movement()) {
            double fraction = (double) remaining / step.durationSeconds();
            latitude = interpolate(step.from().getStation().getLatitude().doubleValue(),
                    step.to().getStation().getLatitude().doubleValue(), fraction);
            longitude = interpolate(step.from().getStation().getLongitude().doubleValue(),
                    step.to().getStation().getLongitude().doubleValue(), fraction);
            etaSeconds = step.durationSeconds() - remaining;
        } else {
            latitude = step.from().getStation().getLatitude().doubleValue();
            longitude = step.from().getStation().getLongitude().doubleValue();
            etaSeconds = step.durationSeconds() - remaining + nextMove.durationSeconds();
        }

        return new TrainPositionResponse(
                train.getCode(),
                line.getCode(),
                latitude,
                longitude,
                nextMove.to().getStation().getCode(),
                etaSeconds,
                "RUNNING",
                headsign.getStation().getCode());
    }

    private MotionStep nextMovement(List<MotionStep> cycle, int stepIndex) {
        for (int offset = 1; offset <= cycle.size(); offset++) {
            MotionStep candidate = cycle.get((stepIndex + offset) % cycle.size());
            if (candidate.movement()) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Không tìm thấy ga kế tiếp trong lịch mô phỏng");
    }

    private static boolean sameStation(LineStation a, LineStation b) {
        return a.getStation().getCode().equalsIgnoreCase(b.getStation().getCode());
    }

    private double interpolate(double from, double to, double fraction) {
        return from + ((to - from) * fraction);
    }

    private record MotionStep(LineStation from, LineStation to, boolean movement, boolean outbound,
            long durationSeconds) {
    }

    private record Simulation(List<LineStation> route, List<MotionStep> cycle, long cycleSeconds, List<Train> trains,
            long operatingElapsedSeconds, long headwaySeconds, long remainingOperatingSeconds) {
        long phaseOf(int trainIndex) {
            return Math.floorMod(operatingElapsedSeconds + (trainIndex * headwaySeconds), cycleSeconds);
        }
    }

    public record TrainPositionResponse(
            String trainId,
            String lineId,
            double lat,
            double lng,
            String nextStationId,
            long etaSec,
            String status,
            String headsignStationId) {
    }

    public record StationArrival(
            String trainId,
            String direction,
            String headsignStationId,
            String headsignStationName,
            long arrivalInSec,
            long departureInSec,
            String expectedDepartureAt) {
    }

    public record StationArrivalsResponse(
            String lineId,
            String stationId,
            String stationName,
            String generatedAt,
            boolean inService,
            List<StationArrival> arrivals) {
    }
}
