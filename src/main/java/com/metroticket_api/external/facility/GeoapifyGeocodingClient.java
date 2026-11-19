package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Chuyển tên địa danh thành tọa độ bằng Geoapify Geocoding API.
 * Kết quả luôn được giới hạn trong Việt Nam và ưu tiên khu vực TP. Hồ Chí Minh.
 * Geoapify là nguồn tham khảo; ChatbotTools mới tìm ga gần tọa độ trong dữ liệu nội bộ.
 */
@Component
public class GeoapifyGeocodingClient {

    private static final Logger log = LoggerFactory.getLogger(GeoapifyGeocodingClient.class);
    private static final int MAX_RESULTS = 5;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;

    public GeoapifyGeocodingClient(@Qualifier("facilityRestTemplate") RestTemplate restTemplate,
            @Value("${app.external.geoapify.base-url:https://api.geoapify.com}") String baseUrl,
            @Value("${app.external.geoapify.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public List<PlaceCandidate> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Geoapify API key is not configured");
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl + "/v1/geocode/search")
                .queryParam("text", query.trim())
                .queryParam("filter", "countrycode:vn")
                .queryParam("bias", "proximity:106.7000,10.7750")
                .queryParam("lang", "vi")
                .queryParam("limit", MAX_RESULTS)
                .queryParam("apiKey", apiKey)
                .build()
                .encode()
                .toUri();
        try {
            Object payload = restTemplate.getForObject(uri, Object.class);
            if (!(payload instanceof Map<?, ?> response) || !(response.get("features") instanceof List<?> features)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Geoapify returned an invalid geocoding response");
            }
            List<PlaceCandidate> result = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Object value : features) {
                if (value instanceof Map<?, ?> feature) {
                    PlaceCandidate candidate = candidate(feature);
                    if (candidate != null && seen.add(identity(candidate))) {
                        result.add(candidate);
                    }
                }
            }
            return List.copyOf(result);
        } catch (RestClientException exception) {
            log.warn("Geoapify geocoding call failed: {}", exception.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to geocode the requested place", exception);
        }
    }

    private static PlaceCandidate candidate(Map<?, ?> feature) {
        if (!(feature.get("properties") instanceof Map<?, ?> properties)
                || !(feature.get("geometry") instanceof Map<?, ?> geometry)
                || !(geometry.get("coordinates") instanceof List<?> coordinates)
                || coordinates.size() < 2) {
            return null;
        }
        BigDecimal longitude = decimal(coordinates.get(0));
        BigDecimal latitude = decimal(coordinates.get(1));
        String name = string(properties.get("name"));
        String formatted = string(properties.get("formatted"));
        if (latitude == null || longitude == null || (name == null && formatted == null)) {
            return null;
        }
        if (name == null) {
            name = formatted;
        }
        double confidence = 0;
        if (properties.get("rank") instanceof Map<?, ?> rank) {
            Number value = number(rank.get("confidence"));
            if (value != null) {
                confidence = value.doubleValue();
            }
        }
        return new PlaceCandidate(name, formatted == null ? name : formatted, latitude, longitude, confidence);
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String identity(PlaceCandidate candidate) {
        return candidate.formatted() + "|" + candidate.latitude().stripTrailingZeros().toPlainString()
                + "|" + candidate.longitude().stripTrailingZeros().toPlainString();
    }

    private static BigDecimal decimal(Object value) {
        Number number = number(value);
        return number == null ? null : BigDecimal.valueOf(number.doubleValue());
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : null;
    }

    public record PlaceCandidate(String name, String formatted, BigDecimal latitude, BigDecimal longitude, double confidence) {
    }
}
