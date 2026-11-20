package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import com.metroticket_api.external.bus.BusService;
import com.metroticket_api.external.facility.FacilityService;
import com.metroticket_api.external.facility.GeoapifyGeocodingClient;
import com.metroticket_api.loyalty.LoyaltyService;
import com.metroticket_api.metro.announcement.AnnouncementService;
import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.operation.OperationConfigService;
import com.metroticket_api.metro.station.LineStation;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.metro.train.TrainPositionService;
import com.metroticket_api.ticket.FareService;
import com.metroticket_api.ticket.TicketProductService;
import com.metroticket_api.ticket.TicketRepository;

import tools.jackson.databind.ObjectMapper;

class ChatbotOperationalToolTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void resolvesLineNumberAliasAndDoesNotInventScheduleWhenNoActiveConfigExists() throws Exception {
        MetroLine line = mock(MetroLine.class);
        when(line.getId()).thenReturn(7L);
        when(line.getCode()).thenReturn("BTS-01");
        when(line.getNameLine()).thenReturn("Bến Thành - Suối Tiên");
        when(line.getDescription()).thenReturn("Tuyến Metro số 1, khoảng 19.7 km");
        when(line.getStatus()).thenReturn(MetroLineStatus.ACTIVE);

        MetroLineRepository metroLines = mock(MetroLineRepository.class);
        when(metroLines.findByCode("TUYẾN METRO SỐ 1")).thenReturn(Optional.empty());
        when(metroLines.findByNameLineContainingIgnoreCase("Tuyến Metro số 1", PageRequest.of(0, 3)))
                .thenReturn(new PageImpl<>(List.of()));
        when(metroLines.findAll(PageRequest.of(0, 100))).thenReturn(new PageImpl<>(List.of(line)));
        OperationConfigService schedules = mock(OperationConfigService.class);
        when(schedules.currentActiveForLine(7L)).thenReturn(Optional.empty());

        ChatbotTools tools = tools(metroLines, schedules, mock(AnnouncementService.class),
                mock(StationRepository.class), mock(LineStationRepository.class));
        Map<?, ?> result = result(tools.execute("get_operation_schedule",
                json.readTree("{\"line_name\":\"Tuyến Metro số 1\"}"), null));

        assertThat(result.get("status")).isEqualTo("NO_CURRENT_SCHEDULE");
        assertThat(result.get("line")).isEqualTo("Bến Thành - Suối Tiên");
        verify(schedules).currentActiveForLine(7L);
    }

    @Test
    void stationAnnouncementLookupIncludesItsLineScope() throws Exception {
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(9L);
        LineStation lineStation = mock(LineStation.class);
        MetroLine line = mock(MetroLine.class);
        when(line.getId()).thenReturn(7L);
        when(lineStation.getLine()).thenReturn(line);

        StationRepository stations = mock(StationRepository.class);
        when(stations.searchByNameAndStatus("Bến Thành", StationStatus.ACTIVE, PageRequest.of(0, 3)))
                .thenReturn(new PageImpl<>(List.of(station)));
        LineStationRepository lineStations = mock(LineStationRepository.class);
        when(lineStations.findByStationId(9L)).thenReturn(List.of(lineStation));
        AnnouncementService announcements = mock(AnnouncementService.class);
        when(announcements.listPublishedForChatbot(Set.of(7L), 9L)).thenReturn(List.of());

        ChatbotTools tools = tools(mock(MetroLineRepository.class), mock(OperationConfigService.class),
                announcements, stations, lineStations);
        Map<?, ?> result = result(tools.execute("get_service_announcements",
                json.readTree("{\"station_name\":\"Bến Thành\"}"), null));

        assertThat(result.get("status")).isEqualTo("NO_CURRENT_ANNOUNCEMENTS");
        verify(announcements).listPublishedForChatbot(Set.of(7L), 9L);
    }

    private ChatbotTools tools(MetroLineRepository metroLines, OperationConfigService schedules,
            AnnouncementService announcements, StationRepository stations, LineStationRepository lineStations) {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        return new ChatbotTools(
                mock(TicketProductService.class),
                mock(TicketRepository.class),
                mock(LoyaltyService.class),
                stations,
                lineStations,
                mock(MapService.class),
                mock(TrainPositionService.class),
                mock(FacilityService.class),
                mock(BusService.class),
                mock(FareService.class),
                mock(GeoapifyGeocodingClient.class),
                metroLines,
                schedules,
                announcements,
                transactions);
    }

    private static Map<?, ?> result(Map<String, Object> toolResponse) {
        return (Map<?, ?>) toolResponse.get("result");
    }
}
