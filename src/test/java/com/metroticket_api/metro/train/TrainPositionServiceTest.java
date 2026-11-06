package com.metroticket_api.metro.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfig;
import com.metroticket_api.metro.operation.OperationConfigRepository;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.Station;

class TrainPositionServiceTest {

    @Test
    void returnsConfiguredNumberOfSimulatedTrainPositions() {
        Long lineId = 1L;
        Instant now = Instant.parse("2026-11-06T05:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        OperationConfigRepository configs = mock(OperationConfigRepository.class);
        MetroLineRepository lines = mock(MetroLineRepository.class);
        TrainRepository trains = mock(TrainRepository.class);
        LineStationRepository lineStations = mock(LineStationRepository.class);

        MetroLine line = mock(MetroLine.class);
        when(line.getCode()).thenReturn("BTS-01");
        when(lines.findById(lineId)).thenReturn(Optional.of(line));

        OperationConfig config = mock(OperationConfig.class);
        when(config.getEffectiveFrom()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(config.getEffectiveTo()).thenReturn(null);
        when(config.getStartTime()).thenReturn(LocalTime.of(5, 30));
        when(config.getEndTime()).thenReturn(LocalTime.of(23, 30));
        when(config.getNumberOfTrains()).thenReturn(2);
        when(config.getHeadwayMinutes()).thenReturn(1);
        when(config.getRunningTimeMinutes()).thenReturn(3);
        when(config.getTurnaroundTimeMinutes()).thenReturn(1);
        when(configs.findByLineIdAndStatus(lineId, OperationConfigStatus.ACTIVE)).thenReturn(List.of(config));

        List<LineStation> route = List.of(
                lineStation("ST001", 10.0, 106.0, 1, 0, 0),
                lineStation("ST002", 10.1, 106.1, 2, 60, 0),
                lineStation("ST003", 10.2, 106.2, 3, 60, 0));
        when(lineStations.findByLineIdOrderByStationOrderAsc(lineId)).thenReturn(route);

        Train firstTrain = train("TR-001");
        Train secondTrain = train("TR-002");
        when(trains.findByLineId(lineId)).thenReturn(List.of(secondTrain, firstTrain));

        TrainPositionService service = new TrainPositionService(configs, lines, trains, lineStations, clock);
        List<TrainPositionService.TrainPositionResponse> positions = service.getPositions(lineId);

        assertEquals(2, positions.size());
        assertEquals(List.of("TR-001", "TR-002"), positions.stream()
                .map(TrainPositionService.TrainPositionResponse::trainId)
                .toList());
        assertTrue(positions.stream().allMatch(position -> position.status().equals("RUNNING")));
        assertTrue(positions.stream().allMatch(position -> position.etaSec() > 0));
        assertTrue(positions.stream().allMatch(position -> position.lineId().equals("BTS-01")));
    }

    private Train train(String code) {
        Train train = mock(Train.class);
        when(train.getCode()).thenReturn(code);
        when(train.getStatus()).thenReturn(TrainStatus.READY);
        return train;
    }

    private LineStation lineStation(String code, double latitude, double longitude, int order,
            int travelTimeSeconds, int dwellTimeSeconds) {
        Station station = mock(Station.class);
        when(station.getCode()).thenReturn(code);
        when(station.getLatitude()).thenReturn(BigDecimal.valueOf(latitude));
        when(station.getLongitude()).thenReturn(BigDecimal.valueOf(longitude));

        LineStation lineStation = mock(LineStation.class);
        when(lineStation.getStation()).thenReturn(station);
        when(lineStation.getStationOrder()).thenReturn(order);
        when(lineStation.getTravelTimeFromPrevSeconds()).thenReturn(travelTimeSeconds);
        when(lineStation.getDwellTimeSeconds()).thenReturn(dwellTimeSeconds);
        return lineStation;
    }
}
