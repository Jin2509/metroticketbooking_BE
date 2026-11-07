package com.metroticket_api.external.bus;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
            @RequestParam @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") BigDecimal lng,
            @RequestParam(defaultValue = "1.0") @DecimalMin("0.1") @DecimalMax("5") double radiusKm,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        // Làm tròn ~100 m: khoá cache hữu hạn, client không thể spam toạ độ để gọi API ngoài liên tục
        return service.getNearbyStops(lat.setScale(3, RoundingMode.HALF_UP), lng.setScale(3, RoundingMode.HALF_UP),
                radiusKm, limit);
    }

    @GetMapping("/stations/{stationId}/bus")
    public List<BusStopDto> getNearestBusForStation(
            @PathVariable Long stationId,
            @RequestParam(defaultValue = "1.0") @DecimalMin("0.1") @DecimalMax("5") double radiusKm,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return service.getNearbyStops(stationId, radiusKm, limit);
    }
}
