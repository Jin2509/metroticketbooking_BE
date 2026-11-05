package com.metroticket_api.external.bus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client gọi API bus thật của EBMS. Khi chưa có provider thật, trả về dữ liệu mẫu để app vẫn chạy.
 */
@Component
public class BusApiClient implements BusProvider {

    private static final Logger log = LoggerFactory.getLogger(BusApiClient.class);
    private static final int MAX_RETRIES = 2;

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String routesPath;
    private final String nearbyStopsPath;

    public BusApiClient(RestTemplate restTemplate,
            @Value("${app.external.bus.base-url:}") String baseUrl,
            @Value("${app.external.bus.routes-path:/businfo/getallroute}") String routesPath,
            @Value("${app.external.bus.nearby-stops-path:/businfo/getstopsinbounds}") String nearbyStopsPath) {
        this.restTemplate = restTemplate;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.routesPath = normalizePath(routesPath);
        this.nearbyStopsPath = normalizePath(nearbyStopsPath);
    }

    @Override
    public List<BusRouteDto> getRoutes() {
        if (baseUrl.isBlank()) {
            return List.of(
                    new BusRouteDto("B01", "Metro Connector", "Tuyến kết nối trung tâm Metro"),
                    new BusRouteDto("B02", "City Loop", "Tuyến vòng vòng trung tâm"));
        }

        return executeWithRetry("getRoutes", () -> {
            Object payload = restTemplate.getForObject(buildUrl(baseUrl, routesPath), Object.class);
            return parseRoutes(payload);
        }, () -> List.of(
                new BusRouteDto("B01", "Metro Connector", "Tuyến kết nối trung tâm Metro"),
                new BusRouteDto("B02", "City Loop", "Tuyến vòng vòng trung tâm")));
    }

    @Override
    public List<BusStopDto> getNearbyStops(BigDecimal lat, BigDecimal lng, double radiusKm, int limit) {
        if (baseUrl.isBlank()) {
            List<BusStopDto> fallback = new ArrayList<>();
            fallback.add(new BusStopDto("BUS_001", "Trạm Đại học Bách Khoa", new BigDecimal("10.7722"), new BigDecimal("106.6609"), 320.0, "B01", "Metro Connector", 6));
            fallback.add(new BusStopDto("BUS_002", "Trạm Hòa Bình", new BigDecimal("10.7740"), new BigDecimal("106.6630"), 520.0, "B02", "City Loop", 10));
            return fallback;
        }

        return executeWithRetry("getNearbyStops", () -> {
            double delta = Math.max(0.01, radiusKm / 111.0);
            String url = buildBoundsUrl(baseUrl, nearbyStopsPath, lng.doubleValue(), lat.doubleValue(), delta);
            Object payload = restTemplate.getForObject(url, Object.class);
            return parseStops(payload, lat, lng, limit);
        }, () -> List.of(
                new BusStopDto("BUS_001", "Trạm Đại học Bách Khoa", new BigDecimal("10.7722"), new BigDecimal("106.6609"), 320.0, "B01", "Metro Connector", 6),
                new BusStopDto("BUS_002", "Trạm Hòa Bình", new BigDecimal("10.7740"), new BigDecimal("106.6630"), 520.0, "B02", "City Loop", 10)));
    }

    @SuppressWarnings("unchecked")
    private List<BusRouteDto> parseRoutes(Object payload) {
        List<Map<String, Object>> rows = extractList(payload);
        List<BusRouteDto> routes = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String code = firstNonBlank(row,
                    "RouteNo", "routeNo", "routeNo", "route_id", "routeId", "code", "busCode",
                    "routeShortName", "shortName", "route_short_name");
            String name = firstNonBlank(row,
                    "RouteName", "routeName", "route_name", "name", "title", "routeTitle",
                    "routeDisplayName", "routeFullName");
            String description = firstNonBlank(row,
                    "description", "routeDescription", "note", "info", "remark");

            if (code == null && name == null) {
                continue;
            }
            routes.add(new BusRouteDto(
                    code == null ? "UNKNOWN" : code,
                    name == null ? (code == null ? "UNKNOWN" : code) : name,
                    description == null ? "Tuyến xe buýt từ EBMS" : description));
        }
        return routes;
    }

    @SuppressWarnings("unchecked")
    private List<BusStopDto> parseStops(Object payload, BigDecimal lat, BigDecimal lng, int limit) {
        List<Map<String, Object>> rows = extractList(payload);
        List<BusStopDto> stops = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String stopId = firstNonBlank(row,
                    "StopId", "stopId", "stop_id", "id", "stopCode", "code", "stationId");
            String name = firstNonBlank(row,
                    "Name", "stopName", "stop_name", "name", "stationName", "nameStation");
            BigDecimal stopLat = asBigDecimal(firstNonBlank(row,
                    "Lat", "lat", "latitude", "Latitude", "stopLat", "stopLatitude"));
            BigDecimal stopLng = asBigDecimal(firstNonBlank(row,
                    "Lng", "lng", "longitude", "Longitude", "stopLng", "stopLongitude"));
            String routeCode = firstNonBlank(row,
                    "Routes", "RouteNo", "routeCode", "routeNo", "route_id", "routeId", "code");
            String routeName = firstNonBlank(row,
                    "RouteName", "routeName", "route_name", "name", "routeTitle");

            if (stopId == null && name == null) {
                continue;
            }
            if (stopLat == null || stopLng == null) {
                continue;
            }

            String normalizedRoutes = normalizeRouteCodes(routeCode);
            double distance = calculateDistanceMeters(lat, lng, stopLat, stopLng);
            stops.add(new BusStopDto(
                    stopId == null ? "BUS_STOP_" + (stops.size() + 1) : stopId,
                    name == null ? "Bus stop" : name,
                    stopLat,
                    stopLng,
                    distance,
                    normalizedRoutes,
                    routeName == null ? "" : routeName,
                    null));
        }

        stops.sort((a, b) -> Double.compare(a.distanceMeters() == null ? Double.MAX_VALUE : a.distanceMeters(),
                b.distanceMeters() == null ? Double.MAX_VALUE : b.distanceMeters()));
        return stops.stream().limit(limit > 0 ? limit : 10).toList();
    }

    private List<Map<String, Object>> extractList(Object payload) {
        if (payload instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add(new LinkedHashMap<>());
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        result.get(result.size() - 1).put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
            }
            return result;
        }
        if (payload instanceof Map<?, ?> map) {
            Object entries = map.get("data");
            if (entries instanceof List<?> list) {
                return extractList(list);
            }
            Object value = map.values().stream()
                    .filter(v -> v instanceof List<?>)
                    .findFirst()
                    .orElse(null);
            if (value instanceof List<?> list) {
                return extractList(list);
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return List.of(normalized);
        }
        return List.of();
    }

    private <T> T executeWithRetry(String action, Supplier<T> supplier, Supplier<T> fallback) {
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            try {
                return supplier.get();
            } catch (Exception ex) {
                log.warn("Bus provider call failed for {} (attempt {}/{}): {}",
                        action, attempt, MAX_RETRIES + 1, ex.getMessage());
                if (attempt > MAX_RETRIES) {
                    return fallback.get();
                }
            }
        }
        return fallback.get();
    }

    private static String buildUrl(String baseUrl, String path) {
        return (baseUrl == null || baseUrl.isBlank() ? "" : baseUrl) + path;
    }

    private static String buildBoundsUrl(String baseUrl, String path, double lng, double lat, double delta) {
        double lng1 = lng - delta;
        double lat1 = lat - delta;
        double lng2 = lng + delta;
        double lat2 = lat + delta;
        return buildUrl(baseUrl, path)
                + "/" + formatCoordinate(lng1)
                + "/" + formatCoordinate(lat1)
                + "/" + formatCoordinate(lng2)
                + "/" + formatCoordinate(lat2);
    }

    private static String formatCoordinate(double value) {
        return String.format(Locale.US, "%.6f", value);
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

    private static String normalizeRouteCodes(String routeCode) {
        if (routeCode == null || routeCode.isBlank()) {
            return "";
        }
        return Arrays.stream(routeCode.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.joining(","));
    }

    private static String firstNonBlank(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static BigDecimal asBigDecimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static double calculateDistanceMeters(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        double earthRadius = 6371000.0;
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLon = Math.toRadians(lon2.doubleValue() - lon1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1.doubleValue())) * Math.cos(Math.toRadians(lat2.doubleValue()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }
}
