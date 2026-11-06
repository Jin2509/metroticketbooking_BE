package com.metroticket_api.external.bus;

import java.math.BigDecimal;
import java.util.List;

public interface BusProvider {
    /**
     * Lấy danh sách tuyến bus theo nguồn dữ liệu bên ngoài.
     */
    List<BusRouteDto> getRoutes();

    /**
     * Tìm trạm dừng quanh một tọa độ và giới hạn theo bán kính km.
     * Nguồn dữ liệu có thể là API thành phố, vendor GTFS hoặc endpoint bus riêng.
     */
    List<BusStopDto> getNearbyStops(BigDecimal lat, BigDecimal lng, double radiusKm, int limit);
}
