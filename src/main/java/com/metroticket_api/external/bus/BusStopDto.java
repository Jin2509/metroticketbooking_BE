package com.metroticket_api.external.bus;

import java.math.BigDecimal;

public record BusStopDto(
        String stopId,
        String name,
        BigDecimal latitude,
        BigDecimal longitude,
        Double distanceMeters,
        String routeCode,
        String routeName,
        Integer arrivalMinutes) {
}
