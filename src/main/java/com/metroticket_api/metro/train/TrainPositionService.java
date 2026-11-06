package com.metroticket_api.metro.train;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfig;
import com.metroticket_api.metro.operation.OperationConfigRepository;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;

@Service
public class TrainPositionService {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

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
        Instant now = clock.instant();
        OperationConfig config = configs.findByLineIdAndStatus(lineId, OperationConfigStatus.ACTIVE).stream()
                .filter(candidate -> !candidate.getEffectiveFrom().isAfter(now))
                .filter(candidate -> candidate.getEffectiveTo() == null || candidate.getEffectiveTo().isAfter(now))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Tuyến chưa có cấu hình vận hành ACTIVE đang hiệu lực"));

        LocalTime localNow = LocalTime.ofInstant(now, VIETNAM);
        long operatingElapsedSeconds = getOperatingElapsedSeconds(config, localNow);
        if (operatingElapsedSeconds < 0) {
            return List.of();
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
                .filter(train -> train.getStatus() == TrainStatus.READY || train.getStatus() == TrainStatus.IN_OPERATION)
                .sorted(Comparator.comparing(Train::getCode))
                .limit(config.getNumberOfTrains())
                .toList();
        if (availableTrains.size() < config.getNumberOfTrains()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Số tàu sẵn sàng trên tuyến ít hơn number_of_trains trong cấu hình ACTIVE");
        }

        long headwaySeconds = config.getHeadwayMinutes() * 60L;
        List<TrainPositionResponse> positions = new ArrayList<>(config.getNumberOfTrains());
        for (int i = 0; i < availableTrains.size(); i++) {
            long phaseSeconds = Math.floorMod(operatingElapsedSeconds + (i * headwaySeconds), cycleSeconds);
            positions.add(simulatePosition(availableTrains.get(i), line, route, cycle, phaseSeconds));
        }
        return positions;
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
        return elapsed < 0 ? elapsed + 24L * 60 * 60 : elapsed;
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
        LineStation headsign = step.outbound()
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

    private double interpolate(double from, double to, double fraction) {
        return from + ((to - from) * fraction);
    }

    private record MotionStep(LineStation from, LineStation to, boolean movement, boolean outbound,
            long durationSeconds) {
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
}
