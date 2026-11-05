package com.metroticket_api.external.facility;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API tiện ích xung quanh ga, lấy từ dữ liệu ngoài như OSM.
 */
@RestController
@RequestMapping("/api/v1")
public class FacilityController {

    private final FacilityService service;

    public FacilityController(FacilityService service) {
        this.service = service;
    }

    @GetMapping("/stations/{stationId}/facilities")
    public List<FacilityDto> getFacilities(
            @PathVariable Long stationId,
            @RequestParam(required = false) FacilityCategory category,
            @RequestParam(defaultValue = "500") int radiusMeters) {
        return service.getNearbyFacilities(stationId, category, radiusMeters);
    }
}
