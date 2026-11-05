package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client gọi API OpenStreetMap/Overpass để tìm các tiện ích quanh ga.
 */
@Component
public class OsmFacilityClient implements FacilityProvider {

    private static final Logger log = LoggerFactory.getLogger(OsmFacilityClient.class);
    private static final int MAX_RETRIES = 2;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String interpreterPath;

    public OsmFacilityClient(RestTemplate restTemplate,
            @Value("${app.external.osm.base-url:https://overpass-api.de}") String baseUrl,
            @Value("${app.external.osm.interpreter-path:/api/interpreter}") String interpreterPath) {
        this.restTemplate = restTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.interpreterPath = normalizePath(interpreterPath);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<OsmFeature> getNearbyFacilities(BigDecimal lat, BigDecimal lng, int radiusMeters) {
        if (lat == null || lng == null) {
            return List.of();
        }

        String query = """
                [out:json];
                (
                  node["amenity"~"^(restaurant|cafe|pharmacy|hospital|clinic|school|college|university|bank|atm)$"](around:%d,%s,%s);
                  node["shop"~"^(supermarket|convenience|bakery|mall)$"](around:%d,%s,%s);
                  node["leisure"~"^(park|garden)$"](around:%d,%s,%s);
                );
                out center;
                """.formatted(radiusMeters, lat, lng, radiusMeters, lat, lng, radiusMeters, lat, lng);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<String> request = new HttpEntity<>(query, headers);

        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                Map<String, Object> response = restTemplate.postForObject(buildUrl(baseUrl, interpreterPath), request, Map.class);
                if (response == null || response.get("elements") == null) {
                    return List.of();
                }

                List<Map<String, Object>> elements = (List<Map<String, Object>>) response.get("elements");
                List<OsmFeature> features = new ArrayList<>();
                for (Map<String, Object> element : elements) {
                    if (element.containsKey("tags") && element.get("tags") instanceof Map<?, ?>) {
                        OsmFeature feature = OsmFeature.fromMap(element);
                        if (isValidFeature(feature)) {
                            features.add(feature);
                        }
                    }
                }
                return features;
            } catch (Exception ex) {
                log.warn("OSM facility provider call failed (attempt {}/{}): {}",
                        attempt, MAX_RETRIES + 1, ex.getMessage());
                if (attempt > MAX_RETRIES) {
                    return List.of();
                }
            }
        }
        return List.of();
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
