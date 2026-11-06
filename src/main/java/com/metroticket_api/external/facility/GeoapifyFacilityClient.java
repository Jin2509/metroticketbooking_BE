package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * Queries nearby places from Geoapify Places API.
 */
@Component
public class GeoapifyFacilityClient implements FacilityProvider {

    private static final Logger log = LoggerFactory.getLogger(GeoapifyFacilityClient.class);
    private static final String ALL_CATEGORIES =
            "catering,commercial,education,entertainment,healthcare,leisure,parking,public_transport,tourism";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String placesPath;
    private final String apiKey;

    public GeoapifyFacilityClient(@Qualifier("facilityRestTemplate") RestTemplate restTemplate,
            @Value("${app.external.geoapify.base-url:https://api.geoapify.com}") String baseUrl,
            @Value("${app.external.geoapify.places-path:/v2/places}") String placesPath,
            @Value("${app.external.geoapify.api-key:}") String apiKey) {
        this.restTemplate = restTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.placesPath = normalizePath(placesPath);
        this.apiKey = apiKey;
    }

    @Override
    public List<FacilityFeature> getNearbyFacilities(
            BigDecimal lat, BigDecimal lng, int radiusMeters, FacilityCategory category, int limit) {
        if (lat == null || lng == null) {
            return List.of();
        }
        if (limit <= 0 || limit > 50) {
            throw new IllegalArgumentException("Geoapify result limit must be between 1 and 50");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Geoapify API key is not configured");
        }

        URI uri = UriComponentsBuilder.fromUriString(baseUrl + placesPath)
                .queryParam("categories", categoriesFor(category))
                .queryParam("filter", "circle:" + lng + "," + lat + "," + radiusMeters)
                .queryParam("limit", limit)
                .queryParam("apiKey", apiKey)
                .build()
                .encode()
                .toUri();

        try {
            Object payload = restTemplate.getForObject(uri, Object.class);
            if (!(payload instanceof Map<?, ?> response)
                    || !(response.get("features") instanceof List<?> features)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "Geoapify returned an invalid facilities response");
            }

            List<FacilityFeature> result = new ArrayList<>();
            for (Object value : features) {
                if (value instanceof Map<?, ?> feature) {
                    FacilityFeature place = FacilityFeature.fromGeoJson(feature);
                    if (isValid(place)) {
                        result.add(place);
                    }
                }
                if (result.size() == limit) {
                    break;
                }
            }
            return List.copyOf(result);
        } catch (RestClientException exception) {
            log.warn("Geoapify facility provider call failed: {}", exception.getMessage(), exception);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Unable to retrieve nearby facilities from Geoapify", exception);
        }
    }

    private boolean isValid(FacilityFeature feature) {
        return feature.name() != null
                && !feature.name().isBlank()
                && feature.latitude() != null
                && feature.longitude() != null;
    }

    private String categoriesFor(FacilityCategory category) {
        if (category == null) {
            return ALL_CATEGORIES;
        }
        return switch (category) {
            case FOOD -> "catering";
            case HEALTH -> "healthcare";
            case EDUCATION -> "education";
            case SHOP -> "commercial";
            case PARK -> "leisure";
            case BANK -> "office.financial";
            case TRANSPORT -> "public_transport,parking";
            case ENTERTAINMENT -> "entertainment,tourism,accommodation";
            case OTHER -> "activity,building,heritage,man_made,natural,office,production,religion";
        };
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }
}
