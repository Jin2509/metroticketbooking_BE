package com.metroticket_api.external.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GeoapifyGeocodingClientTest {

    @Test
    void searchesWithinVietnamAndParsesGeoJsonCoordinates() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenReturn(Map.of("features", List.of(Map.of(
                        "properties", Map.of(
                                "name", "Landmark 81",
                                "formatted", "Landmark 81, Bình Thạnh, TP. Hồ Chí Minh",
                                "rank", Map.of("confidence", 0.94)),
                        "geometry", Map.of("coordinates", List.of(106.7219, 10.7950))))));
        GeoapifyGeocodingClient client = new GeoapifyGeocodingClient(
                restTemplate, "https://api.geoapify.com/", "test-key", "106.35,10.35,107.05,11.15",
                new SimpleMeterRegistry());

        assertThat(client.search("Landmark 81"))
                .containsExactly(new GeoapifyGeocodingClient.PlaceCandidate(
                        "Landmark 81", "Landmark 81, Bình Thạnh, TP. Hồ Chí Minh",
                        new BigDecimal("10.795"), new BigDecimal("106.7219"), 0.94));
        org.mockito.ArgumentCaptor<URI> request = org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(request.capture(), eq(Object.class));
        assertThat(request.getValue().toString())
                .contains("/v1/geocode/search?")
                .contains("filter=rect:106.35,10.35,107.05,11.15")
                .contains("bias=proximity:106.7000,10.7750")
                .contains("limit=5")
                .contains("apiKey=test-key");
    }

    @Test
    void returnsNoResultsForBlankQueriesWithoutCallingProvider() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        GeoapifyGeocodingClient client = new GeoapifyGeocodingClient(
                restTemplate, "https://api.geoapify.com", "test-key", "106.35,10.35,107.05,11.15",
                new SimpleMeterRegistry());

        assertThat(client.search("  ")).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    void filtersProviderResultsOutsideConfiguredBounds() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenReturn(Map.of("features", List.of(
                        Map.of(
                                "properties", Map.of("name", "Landmark 81", "formatted", "Landmark 81"),
                                "geometry", Map.of("coordinates", List.of(106.7219, 10.7950))),
                        Map.of(
                                "properties", Map.of("name", "Hanoi", "formatted", "Hanoi, Vietnam"),
                                "geometry", Map.of("coordinates", List.of(105.8342, 21.0278))))));
        GeoapifyGeocodingClient client = new GeoapifyGeocodingClient(
                restTemplate, "https://api.geoapify.com", "test-key", "106.35,10.35,107.05,11.15",
                new SimpleMeterRegistry());

        assertThat(client.search("a place"))
                .extracting(GeoapifyGeocodingClient.PlaceCandidate::name)
                .containsExactly("Landmark 81");
    }

    @Test
    void failsClearlyWhenGeocodingApiKeyIsMissing() {
        GeoapifyGeocodingClient client = new GeoapifyGeocodingClient(
                mock(RestTemplate.class), "https://api.geoapify.com", "", "106.35,10.35,107.05,11.15",
                new SimpleMeterRegistry());

        assertThatThrownBy(() -> client.search("Landmark 81"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
