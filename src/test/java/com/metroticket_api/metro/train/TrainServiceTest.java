package com.metroticket_api.metro.train;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.operation.OperationConfig;
import com.metroticket_api.metro.operation.OperationConfigRepository;
import com.metroticket_api.metro.train.TrainDtos.UpdateTrainRequest;

class TrainServiceTest {

    private final TrainRepository trains = mock(TrainRepository.class);
    private final MetroLineRepository lines = mock(MetroLineRepository.class);
    private final OperationConfigRepository configs = mock(OperationConfigRepository.class);
    private final TrainService service = new TrainService(trains, lines, configs);
    private MetroLine line;
    private final List<Train> fleet = new ArrayList<>();

    @BeforeEach
    void setUp() {
        line = mock(MetroLine.class);
        when(line.getId()).thenReturn(1L);
        when(line.getCode()).thenReturn("BTS-01");
        when(line.getStatus()).thenReturn(MetroLineStatus.ACTIVE);
        when(lines.findById(1L)).thenReturn(Optional.of(line));
        for (int i = 1; i <= 5; i++) {
            Train t = mock(Train.class);
            when(t.getId()).thenReturn((long) i);
            when(t.getCode()).thenReturn("TR-00" + i);
            when(t.getLine()).thenReturn(line);
            when(t.getStatus()).thenReturn(TrainStatus.READY);
            when(trains.findById((long) i)).thenReturn(Optional.of(t));
            fleet.add(t);
        }
        when(trains.findByLineId(1L)).thenReturn(fleet);
        OperationConfig active = mock(OperationConfig.class);
        when(active.getNumberOfTrains()).thenReturn(5);
        when(configs.findByLineIdAndStatus(1L, OperationConfigStatus.ACTIVE)).thenReturn(List.of(active));
    }

    @Test
    void statusMachine() {
        assertThat(TrainStatus.OUT_OF_SERVICE.canTransitionTo(TrainStatus.READY)).isFalse();
        assertThat(TrainStatus.OUT_OF_SERVICE.canTransitionTo(TrainStatus.MAINTENANCE)).isTrue();
        assertThat(TrainStatus.MAINTENANCE.canTransitionTo(TrainStatus.IN_OPERATION)).isFalse();
        assertThat(TrainStatus.READY.canTransitionTo(TrainStatus.IN_OPERATION)).isTrue();
    }

    @Test
    void cannotDeleteTrainNeededByActiveConfig() {
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("cần 5");
        verify(trains, never()).delete(any(Train.class));
    }

    @Test
    void canDeleteSpareTrain() {
        Train spare = mock(Train.class);
        when(spare.getId()).thenReturn(6L);
        when(spare.getLine()).thenReturn(line);
        when(spare.getStatus()).thenReturn(TrainStatus.READY);
        fleet.add(spare);
        when(trains.findById(6L)).thenReturn(Optional.of(spare));
        service.delete(6L);
        verify(trains).delete(spare);
    }

    @Test
    void cannotDeleteRunningTrain() {
        when(fleet.get(0).getStatus()).thenReturn(TrainStatus.IN_OPERATION);
        assertThatThrownBy(() -> service.delete(1L)).hasMessageContaining("đang vận hành");
    }

    @Test
    void breakdownToMaintenanceIsAlwaysAllowed() {
        Train t = fleet.get(0);
        when(trains.save(t)).thenReturn(t);
        service.updateStatus(1L, TrainStatus.MAINTENANCE);
        verify(t).setStatus(TrainStatus.MAINTENANCE);
    }

    @Test
    void invalidTransitionIsRejected() {
        when(fleet.get(0).getStatus()).thenReturn(TrainStatus.OUT_OF_SERVICE);
        assertThatThrownBy(() -> service.updateStatus(1L, TrainStatus.READY)).hasMessageContaining("Không thể chuyển");
    }

    @Test
    void cannotOperateOnInactiveLine() {
        when(line.getStatus()).thenReturn(MetroLineStatus.UNDER_CONSTRUCTION);
        assertThatThrownBy(() -> service.updateStatus(1L, TrainStatus.IN_OPERATION)).hasMessageContaining("ACTIVE");
    }

    @Test
    void reassignBlockedWhenOldLineWouldBeShort() {
        MetroLine other = mock(MetroLine.class);
        when(other.getId()).thenReturn(2L);
        when(lines.findById(2L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.update(1L, new UpdateTrainRequest(null, null, 2L, null, null, null)))
                .hasMessageContaining("điều chỉnh cấu hình");
    }
}
