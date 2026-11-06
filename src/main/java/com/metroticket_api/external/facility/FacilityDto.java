package com.metroticket_api.external.facility;

import java.math.BigDecimal;

/**
 * DTO chuẩn của tiện ích quanh ga, dùng cho controller và frontend.
 */
public record FacilityDto(
        Long id,
        String name,
        FacilityCategory category,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceMeters,
        String address,
        String source) {
}
