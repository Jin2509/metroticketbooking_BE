package com.metroticket_api.external.facility;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Dữ liệu thô trả về từ OpenStreetMap Overpass. Chúng ta map lại sang category chuẩn ở service layer.
 */
public record OsmFeature(
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        String amenity,
        String shop,
        String leisure,
        String tourism) {

    public static OsmFeature fromMap(Map<?, ?> node) {
        Map<?, ?> tags = node.get("tags") instanceof Map<?, ?> values ? values : Map.of();
        String name = asString(tags.containsKey("name") ? tags.get("name") : "Unnamed");
        String amenity = asString(tags.get("amenity"));
        String shop = asString(tags.get("shop"));
        String leisure = asString(tags.get("leisure"));
        String tourism = asString(tags.get("tourism"));

        Map<?, ?> center = node.get("center") instanceof Map<?, ?> values ? values : Map.of();
        BigDecimal latitude = asBigDecimal(node.get("lat"));
        BigDecimal longitude = asBigDecimal(node.get("lon"));
        if (latitude == null) {
            latitude = asBigDecimal(center.get("lat"));
        }
        if (longitude == null) {
            longitude = asBigDecimal(center.get("lon"));
        }

        return new OsmFeature(
                name,
                latitude,
                longitude,
                amenity,
                shop,
                leisure,
                tourism);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().trim();
    }

    private static BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
