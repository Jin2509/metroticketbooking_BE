package com.metroticket_api.external.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class OsmFeatureTest {

    @Test
    void readsCoordinatesFromOverpassCenterForWays() {
        OsmFeature feature = OsmFeature.fromMap(Map.of(
                "tags", Map.of("name", "City Park", "leisure", "park"),
                "center", Map.of("lat", 10.77, "lon", 106.69)));

        assertThat(feature.name()).isEqualTo("City Park");
        assertThat(feature.latitude()).isEqualByComparingTo("10.77");
        assertThat(feature.longitude()).isEqualByComparingTo("106.69");
    }

    @Test
    void searchesWaysAndRelationsAndReportsProviderFailures() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(Map.of("elements", List.of()));

        OsmFacilityClient client = new OsmFacilityClient(restTemplate, "https://overpass.example", "/api/interpreter");
        assertThat(client.getNearbyFacilities(new java.math.BigDecimal("10.77"),
                new java.math.BigDecimal("106.69"), 500)).isEmpty();

        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(anyString(), request.capture(), eq(Object.class));
        assertThat(request.getValue().getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        assertThat(request.getValue().getHeaders().getFirst("User-Agent"))
                .isEqualTo("MetroTicketBooking/0.0.1");
        @SuppressWarnings("unchecked")
        MultiValueMap<String, String> form = (MultiValueMap<String, String>) request.getValue().getBody();
        String query = form.getFirst("data");
        assertThat(query)
                .contains("nwr[\"amenity\"", "nwr[\"shop\"", "nwr[\"leisure\"", "nwr[\"tourism\"");

        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Object.class)))
                .thenThrow(new ResourceAccessException("provider unavailable"));
        assertThatThrownBy(() -> client.getNearbyFacilities(
                new java.math.BigDecimal("10.77"), new java.math.BigDecimal("106.69"), 500))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void clientReturnsNamedFacilitiesWithWayCenterCoordinates() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Object.class)))
                .thenReturn(Map.of("elements", List.of(Map.of(
                        "type", "way",
                        "tags", Map.of("name", "Nearby Park", "leisure", "park"),
                        "center", Map.of("lat", 10.77, "lon", 106.69)))));

        OsmFacilityClient client = new OsmFacilityClient(restTemplate, "https://overpass.example", "/api/interpreter");

        assertThat(client.getNearbyFacilities(
                new java.math.BigDecimal("10.77"), new java.math.BigDecimal("106.69"), 500))
                .containsExactly(new OsmFeature(
                        "Nearby Park",
                        new java.math.BigDecimal("10.77"),
                        new java.math.BigDecimal("106.69"),
                        null,
                        null,
                        "park",
                        null));
    }
}
