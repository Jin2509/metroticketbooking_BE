package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Client gọi API OpenStreetMap/Overpass để tìm các tiện ích quanh ga.
 */
@Component
public class OsmFacilityClient implements FacilityProvider {

    private static final Logger log = LoggerFactory.getLogger(OsmFacilityClient.class);
    private static final int MAX_RETRIES = 1;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String interpreterPath;

    public OsmFacilityClient(@Qualifier("osmRestTemplate") RestTemplate restTemplate,
            @Value("${app.external.osm.base-url:https://overpass-api.de}") String baseUrl,
            @Value("${app.external.osm.interpreter-path:/api/interpreter}") String interpreterPath) {
        this.restTemplate = restTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.interpreterPath = normalizePath(interpreterPath);
    }

    @Override
    public List<OsmFeature> getNearbyFacilities(BigDecimal lat, BigDecimal lng, int radiusMeters) {
        if (lat == null || lng == null) {
            return List.of();
        }

        String query = """
                [out:json];
                (
                  nwr["amenity"~"^(restaurant|cafe|food_court|fast_food|bar|pub|canteen|ice_cream|pharmacy|hospital|clinic|dentist|veterinary|doctors|school|college|university|kindergarten|library|bank|atm|bureau_de_change|bus_station|train_station|subway_station|subway_entrance|taxi|parking|fuel|bicycle_rental|car_rental|charging_station)$"](around:%d,%s,%s);
                  nwr["shop"~"^(supermarket|convenience|bakery|mall|department_store|clothes|greengrocer|marketplace)$"](around:%d,%s,%s);
                  nwr["leisure"~"^(park|garden|playground|pitch|stadium|sports_centre)$"](around:%d,%s,%s);
                  nwr["tourism"~"^(museum|hotel|attraction|gallery|viewpoint)$"](around:%d,%s,%s);
                );
                out center;
                """.formatted(radiusMeters, lat, lng, radiusMeters, lat, lng, radiusMeters, lat, lng,
                radiusMeters, lat, lng);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("User-Agent", "MetroTicketBooking/0.0.1");
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("data", query);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(form, headers);

        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                Object payload = restTemplate.postForObject(buildUrl(baseUrl, interpreterPath), request, Object.class);
                if (!(payload instanceof Map<?, ?> response)
                        || !(response.get("elements") instanceof List<?> elements)) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "OpenStreetMap trả về dữ liệu tiện ích không hợp lệ");
                }

                List<OsmFeature> features = new ArrayList<>();
                for (Object value : elements) {
                    if (value instanceof Map<?, ?> element && element.get("tags") instanceof Map<?, ?>) {
                        OsmFeature feature = OsmFeature.fromMap(element);
                        if (isValidFeature(feature)) {
                            features.add(feature);
                        }
                    }
                }
                return features;
            } catch (RestClientException ex) {
                log.warn("OSM facility provider call failed (attempt {}/{}): {}",
                        attempt, MAX_RETRIES + 1, ex.getMessage(), ex);
                if (attempt > MAX_RETRIES) {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Không thể lấy tiện ích từ OpenStreetMap", ex);
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Không thể lấy tiện ích từ OpenStreetMap");
    }

    private boolean isValidFeature(OsmFeature feature) {
        return feature != null
                && feature.latitude() != null
                && feature.longitude() != null
                && feature.name() != null
                && !feature.name().isBlank();
    }

    private static String buildUrl(String baseUrl, String path) {
        return (baseUrl == null || baseUrl.isBlank() ? "" : baseUrl) + path;
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
