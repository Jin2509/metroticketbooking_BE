package com.metroticket_api.external.bus;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API bus lấy dữ liệu từ hệ thống giao thông bên ngoài.
 */
@RestController
@RequestMapping("/api/v1")
public class BusController {

    private final BusService service;

    public BusController(BusService service) {
        this.service = service;
    }

    @GetMapping("/bus/routes")
    public List<BusRouteDto> getRoutes() {
        return service.getRoutes();
    }

    @GetMapping("/bus/stops/nearby")
    public List<BusStopDto> getNearbyStops(
            @RequestParam BigDecimal lat,
            @RequestParam BigDecimal lng,
            @RequestParam(defaultValue = "1.0") double radiusKm,
            @RequestParam(defaultValue = "10") int limit) {
        return service.getNearbyStops(lat, lng, radiusKm, limit);
    }

    @GetMapping("/stations/{stationId}/bus")
    public List<BusStopDto> getNearestBusForStation(
            @PathVariable Long stationId,
            @RequestParam(defaultValue = "1.0") double radiusKm,
            @RequestParam(defaultValue = "10") int limit) {
        return service.getNearbyStops(stationId, radiusKm, limit);
    }
}
