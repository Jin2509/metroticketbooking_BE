package com.metroticket_api.external.bus;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationService;

/**
 * Service xử lý dữ liệu bus gần ga Metro, dùng dữ liệu ngoài hoặc fallback sample khi chưa cấu hình hệ thống bus.
 */
@Service
public class BusService {

    private final BusProvider busProvider;
    private final StationService stationService;

    public BusService(BusProvider busProvider, StationService stationService) {
        this.busProvider = busProvider;
        this.stationService = stationService;
    }

    @Cacheable(value = "bus-routes")
    public List<BusRouteDto> getRoutes() {
        return busProvider.getRoutes();
    }

    @Cacheable(value = "bus-nearby",
            key = "'station:' + #stationId + ':' + #radiusKm + ':' + #limit")
    public List<BusStopDto> getNearbyStops(Long stationId, double radiusKm, int limit) {
        Station station = stationService.getById(stationId);
        List<BusStopDto> stops = busProvider.getNearbyStops(station.getLatitude(), station.getLongitude(), radiusKm, limit);
        return stops.stream()
                .sorted(Comparator.comparing(BusStopDto::distanceMeters, Comparator.nullsLast(Double::compareTo)))
                .limit(limit)
                .toList();
    }

    @Cacheable(value = "bus-nearby",
            key = "'coord:' + #lat.stripTrailingZeros() + ':' + #lng.stripTrailingZeros() + ':' + #radiusKm + ':' + #limit")
    public List<BusStopDto> getNearbyStops(BigDecimal lat, BigDecimal lng, double radiusKm, int limit) {
        List<BusStopDto> stops = busProvider.getNearbyStops(lat, lng, radiusKm, limit);
        return stops.stream()
                .sorted(Comparator.comparing(BusStopDto::distanceMeters, Comparator.nullsLast(Double::compareTo)))
                .limit(limit)
                .toList();
    }
}
