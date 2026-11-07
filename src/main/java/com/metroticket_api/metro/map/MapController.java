package com.metroticket_api.metro.map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.map.MapDtos.NetworkResponse;
import com.metroticket_api.metro.map.RoutePlanner.Journey;
import com.metroticket_api.metro.map.RoutePlanner.Optimize;
import com.metroticket_api.metro.train.TrainPositionService;
import com.metroticket_api.metro.train.TrainPositionService.StationArrivalsResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** API công khai (không cần đăng nhập) cho bản đồ hệ thống trên web/mobile. */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "System Map", description = "Bản đồ mạng lưới, tìm đường, giờ tàu đến ga")
public class MapController {

    private final MapService mapService;
    private final TrainPositionService positions;

    public MapController(MapService mapService, TrainPositionService positions) {
        this.mapService = mapService;
        this.positions = positions;
    }

    @Operation(summary = "Toàn bộ mạng lưới", description = "Tuyến (màu, trạng thái, ga theo thứ tự + luỹ kế km/giây), danh sách ga, điểm chuyển tuyến.")
    @GetMapping("/map/network")
    public NetworkResponse network() {
        return mapService.network();
    }

    @Operation(summary = "Tìm đường giữa 2 ga", description = "optimize=FASTEST (mặc định) hoặc SHORTEST. Trả về các chặng, điểm chuyển tuyến, tổng km và thời gian.")
    @GetMapping("/map/route")
    public Journey route(@RequestParam Long fromStationId, @RequestParam Long toStationId,
            @RequestParam(defaultValue = "FASTEST") Optimize optimize) {
        return mapService.route(fromStationId, toStationId, optimize);
    }

    @Operation(summary = "Tàu sắp rời ga", description = "Theo mô phỏng cấu hình vận hành ACTIVE, tối đa `limit` chuyến mỗi hướng.")
    @GetMapping("/lines/{lineCode}/stations/{stationCode}/arrivals")
    public StationArrivalsResponse arrivals(@PathVariable String lineCode, @PathVariable String stationCode,
            @RequestParam(defaultValue = "3") int limit) {
        return positions.getArrivals(lineCode, stationCode, limit);
    }
}
