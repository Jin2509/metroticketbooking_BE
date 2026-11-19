package com.metroticket_api.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.metroticket_api.external.bus.BusService;
import com.metroticket_api.external.facility.FacilityService;
import com.metroticket_api.external.facility.GeoapifyGeocodingClient;
import com.metroticket_api.external.facility.GeoapifyGeocodingClient.PlaceCandidate;
import com.metroticket_api.loyalty.LoyaltyService;
import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.map.MapService;
import com.metroticket_api.metro.station.LineStationRepository;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.metro.train.TrainPositionService;
import com.metroticket_api.ticket.FareService;
import com.metroticket_api.ticket.TicketProductService;
import com.metroticket_api.ticket.TicketRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ChatbotLocationToolTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void asksForClarificationWhenGeocodingResultsAreAmbiguous() throws Exception {
        GeoapifyGeocodingClient geocoding = mock(GeoapifyGeocodingClient.class);
        StationRepository stations = mock(StationRepository.class);
        when(geocoding.search("Central Park")).thenReturn(List.of(
                place("Central Park", "Central Park, D1", 10.0, 106.0, 0.91),
                place("Central Park", "Central Park, D7", 10.01, 106.01, 0.88)));
        when(stations.findByStatus(StationStatus.ACTIVE)).thenReturn(List.of(
                station("ST01", "Ga gần nhất", 10.0, 106.0)));

        Map<?, ?> result = result(tool(geocoding, stations).execute(
                "find_place_and_nearest_stations", args("Central Park"), null));

        assertThat(result.get("status")).isEqualTo("CLARIFICATION_REQUIRED");
        assertThat((List<?>) result.get("choices")).hasSize(2);
    }

    @Test
    void asksForConfirmationWhenTheOnlyGeocodingResultHasLowConfidence() throws Exception {
        GeoapifyGeocodingClient geocoding = mock(GeoapifyGeocodingClient.class);
        StationRepository stations = mock(StationRepository.class);
        when(geocoding.search("Park")).thenReturn(List.of(
                place("Park", "Park, TP. Hồ Chí Minh", 10.0, 106.0, 0.62)));

        Map<?, ?> result = result(tool(geocoding, stations).execute(
                "find_place_and_nearest_stations", args("Park"), null));

        assertThat(result.get("status")).isEqualTo("CLARIFICATION_REQUIRED");
        assertThat((List<?>) result.get("choices")).hasSize(1);
    }

    @Test
    void returnsNearestStationForAnUnambiguousPlace() throws Exception {
        GeoapifyGeocodingClient geocoding = mock(GeoapifyGeocodingClient.class);
        StationRepository stations = mock(StationRepository.class);
        when(geocoding.search("Landmark 81")).thenReturn(List.of(
                place("Landmark 81", "Landmark 81, Bình Thạnh", 10.0, 106.0, 0.95)));
        when(stations.findByStatus(StationStatus.ACTIVE)).thenReturn(List.of(
                station("ST02", "Ga xa hơn", 10.005, 106.0),
                station("ST01", "Ga gần nhất", 10.001, 106.0)));

        Map<?, ?> result = result(tool(geocoding, stations).execute(
                "find_place_and_nearest_stations", args("Landmark 81"), null));

        assertThat(result.get("status")).isEqualTo("RESOLVED");
        Map<?, ?> nearest = (Map<?, ?>) ((List<?>) result.get("nearestStations")).get(0);
        assertThat(nearest.get("name")).isEqualTo("Ga gần nhất");
    }

    private static ChatbotTools tool(GeoapifyGeocodingClient geocoding, StationRepository stations) {
        return new ChatbotTools(
                mock(TicketProductService.class),
                mock(TicketRepository.class),
                mock(LoyaltyService.class),
                stations,
                mock(LineStationRepository.class),
                mock(MapService.class),
                mock(TrainPositionService.class),
                mock(FacilityService.class),
                mock(BusService.class),
                mock(FareService.class),
                geocoding,
                mock(PlatformTransactionManager.class));
    }

    private JsonNode args(String place) throws Exception {
        return json.readTree("{\"place_name\":\"" + place + "\"}");
    }

    private static Map<?, ?> result(Map<String, Object> toolResponse) {
        return (Map<?, ?>) toolResponse.get("result");
    }

    private static PlaceCandidate place(String name, String address, double lat, double lon, double confidence) {
        return new PlaceCandidate(name, address, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), confidence);
    }

    private static Station station(String code, String name, double lat, double lon) {
        return new Station(code, name, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon), null, null, null, false,
                StationStatus.ACTIVE);
    }
}
