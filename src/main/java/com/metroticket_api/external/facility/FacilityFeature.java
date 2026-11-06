package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Normalized place properties returned by Geoapify.
 */
public record FacilityFeature(
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        List<String> categories,
        String address) {

    public static FacilityFeature fromGeoJson(Map<?, ?> feature) {
        Map<?, ?> properties = feature.get("properties") instanceof Map<?, ?> values ? values : Map.of();
        List<String> categories = new ArrayList<>();
        if (properties.get("categories") instanceof List<?> values) {
            for (Object value : values) {
                if (value != null) {
                    categories.add(value.toString());
                }
            }
        }

        return new FacilityFeature(
                asString(properties.get("name")),
                asBigDecimal(properties.get("lat")),
                asBigDecimal(properties.get("lon")),
                List.copyOf(categories),
                firstNonBlank(properties.get("formatted"), properties.get("address_line1")));
    }

    private static String firstNonBlank(Object first, Object second) {
        String value = asString(first);
        return value == null || value.isBlank() ? asString(second) : value;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
