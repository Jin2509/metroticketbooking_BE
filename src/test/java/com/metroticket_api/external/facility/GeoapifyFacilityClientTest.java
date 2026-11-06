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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

class GeoapifyFacilityClientTest {

    @ParameterizedTest
    @CsvSource({
        "FOOD,catering",
        "HEALTH,healthcare",
        "EDUCATION,education",
        "SHOP,commercial",
        "PARK,leisure",
        "BANK,office.financial",
        "TRANSPORT,public_transport",
        "ENTERTAINMENT,entertainment",
        "OTHER,activity"
    })
    void queriesGeoapifyForSelectedCategory(FacilityCategory category, String expectedCategory) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenReturn(Map.of("features", List.of()));
        GeoapifyFacilityClient client = new GeoapifyFacilityClient(
                restTemplate, "https://api.geoapify.com", "/v2/places", "test-api-key");

        client.getNearbyFacilities(new BigDecimal("10.77"), new BigDecimal("106.69"), 500, category, 10);

        org.mockito.ArgumentCaptor<URI> request = org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(request.capture(), eq(Object.class));
        assertThat(request.getValue().toString()).contains("categories=" + expectedCategory);
    }

    @Test
    void requestsNearbyPlacesWithApiKeyAndLimitsResultsToTen() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenReturn(Map.of("features", List.of(Map.of(
                        "type", "Feature",
                        "properties", Map.of(
                                "name", "Nearby Cafe",
                                "lat", 10.77,
                                "lon", 106.69,
                                "categories", List.of("catering.cafe"),
                                "formatted", "Nearby Cafe, HCMC")))));
        GeoapifyFacilityClient client = new GeoapifyFacilityClient(
                restTemplate, "https://api.geoapify.com", "/v2/places", "test-api-key");

        assertThat(client.getNearbyFacilities(
                new BigDecimal("10.77"), new BigDecimal("106.69"), 500, FacilityCategory.FOOD, 10))
                .containsExactly(new FacilityFeature(
                        "Nearby Cafe",
                        new BigDecimal("10.77"),
                        new BigDecimal("106.69"),
                        List.of("catering.cafe"),
                        "Nearby Cafe, HCMC"));

        org.mockito.ArgumentCaptor<URI> request = org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(request.capture(), eq(Object.class));
        assertThat(request.getValue().toString())
                .contains("/v2/places?")
                .contains("categories=catering")
                .contains("filter=circle:106.69,10.77,500")
                .contains("limit=10")
                .contains("apiKey=test-api-key");
    }

    @Test
    void usesTheRequestedLimitForCategoryQueries() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenReturn(Map.of("features", List.of()));
        GeoapifyFacilityClient client = new GeoapifyFacilityClient(
                restTemplate, "https://api.geoapify.com", "/v2/places", "test-api-key");

        client.getNearbyFacilities(new BigDecimal("10.77"), new BigDecimal("106.69"), 500,
                FacilityCategory.FOOD, 2);

        org.mockito.ArgumentCaptor<URI> request = org.mockito.ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(request.capture(), eq(Object.class));
        assertThat(request.getValue().toString())
                .contains("categories=catering")
                .contains("limit=2");
    }

    @Test
    void failsClearlyWhenApiKeyIsNotConfigured() {
        GeoapifyFacilityClient client = new GeoapifyFacilityClient(
                mock(RestTemplate.class), "https://api.geoapify.com", "/v2/places", "");

        assertThatThrownBy(() -> client.getNearbyFacilities(
                new BigDecimal("10.77"), new BigDecimal("106.69"), 500, null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void reportsProviderConnectionFailuresAsBadGateway() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.getForObject(any(URI.class), eq(Object.class)))
                .thenThrow(new ResourceAccessException("provider unavailable"));
        GeoapifyFacilityClient client = new GeoapifyFacilityClient(
                restTemplate, "https://api.geoapify.com", "/v2/places", "test-api-key");

        assertThatThrownBy(() -> client.getNearbyFacilities(
                new BigDecimal("10.77"), new BigDecimal("106.69"), 500, null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }
}
