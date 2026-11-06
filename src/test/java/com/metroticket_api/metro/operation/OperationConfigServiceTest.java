package com.metroticket_api.metro.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfigDtos.CreateOperationConfigRequest;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.train.Train;
import com.metroticket_api.metro.train.TrainRepository;
import com.metroticket_api.workflow.WorkflowEngineService;

class OperationConfigServiceTest {

    @Test
    void acceptsConfigurationMatchingFullRoundTripCycle() {
        TestContext context = new TestContext();

        OperationConfigDtos.OperationConfigResponse response = context.service.create(1L,
                context.request(14));

        assertEquals(5, response.numberOfTrains());
        assertEquals(14, response.headwayMinutes());
        verify(context.configs).save(org.mockito.ArgumentMatchers.any(OperationConfig.class));
    }

    @Test
    void rejectsHeadwayThatDoesNotMatchFullRoundTripCycle() {
        TestContext context = new TestContext();

        assertThrows(ResponseStatusException.class, () -> context.service.create(1L, context.request(13)));

        verify(context.configs, never()).save(org.mockito.ArgumentMatchers.any(OperationConfig.class));
    }

    private static class TestContext {
        private final OperationConfigRepository configs = mock(OperationConfigRepository.class);
        private final MetroLineRepository lines = mock(MetroLineRepository.class);
        private final TrainRepository trains = mock(TrainRepository.class);
        private final LineStationRepository lineStations = mock(LineStationRepository.class);
        private final OperationConfigService service;

        private TestContext() {
            MetroLine line = mock(MetroLine.class);
            when(line.getId()).thenReturn(1L);
            when(lines.findById(1L)).thenReturn(Optional.of(line));
            when(configs.findTopByLineIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
            when(configs.save(org.mockito.ArgumentMatchers.any(OperationConfig.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            List<LineStation> route = route();
            when(lineStations.findByLineIdOrderByStationOrderAsc(1L)).thenReturn(route);
            List<Train> readyTrains = readyTrains();
            when(trains.findByLineId(1L)).thenReturn(readyTrains);
            service = new OperationConfigService(configs, lines, trains, lineStations,
                    mock(WorkflowEngineService.class));
        }

        private CreateOperationConfigRequest request(int headwayMinutes) {
            return new CreateOperationConfigRequest(1L, 5, headwayMinutes, 30, 5,
                    LocalTime.of(5, 0), LocalTime.of(22, 0), Instant.parse("2024-12-21T17:00:00Z"),
                    null, null);
        }

        private List<LineStation> route() {
            int[] travelTimes = {0, 42, 64, 114, 57, 75, 61, 103, 94, 90, 112, 147, 101, 130};
            List<LineStation> route = new ArrayList<>();
            for (int travelTime : travelTimes) {
                LineStation station = mock(LineStation.class);
                when(station.getTravelTimeFromPrevSeconds()).thenReturn(travelTime);
                when(station.getDwellTimeSeconds()).thenReturn(30);
                route.add(station);
            }
            return route;
        }

        private List<Train> readyTrains() {
            List<Train> readyTrains = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                Train train = mock(Train.class);
                when(train.getStatus()).thenReturn(TrainStatus.READY);
                readyTrains.add(train);
            }
            return readyTrains;
        }
    }
}
