package com.metroticket_api.metro.station;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.common.StationStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.station.StationDtos.CreateStationRequest;
import com.metroticket_api.metro.station.StationDtos.StationResponse;
import com.metroticket_api.metro.station.StationDtos.UpdateStationRequest;

/**
 * Controller cho các endpoint quản lý nhà ga Metro.
 *
 * <p>Đây là nơi người dùng, frontend hoặc mobile app lấy danh sách ga, tìm ga gần vị trí, xem tuyến đi qua ga
 * và các kết nối chuyển tuyến tại ga.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class StationController {

    private final StationService service;

    public StationController(StationService service) {
        this.service = service;
    }

    /**
     * Lấy danh sách ga theo dữ liệu tìm kiếm, lọc theo tuyến, trạng thái và phân trang.
     * Endpoint này phù hợp cho màn hình tra cứu ga và map filter.
     */
    @GetMapping("/stations")
    public Page<StationResponse> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long lineId,
            @RequestParam(required = false) StationStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(q, lineId, status, pageable);
    }

    /**
     * Tìm các ga nằm trong bán kính quanh một vị trí người dùng, dùng cho chức năng “ga gần nhất” trên ứng dụng.
     */
    @GetMapping("/stations/nearest")
    public List<Station> nearest(@RequestParam BigDecimal lat,
            @RequestParam BigDecimal lng,
            @RequestParam(defaultValue = "5") double radiusKm) {
        return service.nearest(lat, lng, radiusKm);
    }

    /**
     * Trả về thông tin chi tiết của một nhà ga, bao gồm tọa độ, địa chỉ và trạng thái hoạt động.
     */
    @GetMapping("/stations/{stationId}")
    public StationResponse get(@PathVariable Long stationId) {
        return StationResponse.of(service.getById(stationId));
    }

    /**
     * Tạo nhà ga mới. Thông tin quan trọng gồm mã ga, tên, tọa độ, địa chỉ và trạng thái.
     */
    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    public StationResponse create(@Valid @RequestBody CreateStationRequest request) {
        return service.create(request);
    }

    /**
     * Cập nhật thông tin nhà ga hiện có, ví dụ thay đổi tọa độ, tên ga hoặc trạng thái bảo trì.
     */
    @PutMapping("/stations/{stationId}")
    public StationResponse update(@PathVariable Long stationId, @Valid @RequestBody UpdateStationRequest request) {
        return service.update(stationId, request);
    }

    /**
     * Xóa ga khỏi hệ thống, thường dùng khi ga ngừng hoạt động lâu dài hoặc cần dọn dữ liệu.
     */
    @DeleteMapping("/stations/{stationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long stationId) {
        service.delete(stationId);
    }

    /**
     * Trả về các điểm kết nối chuyển tuyến tại ga, phục vụ tra cứu đường đi trên bản đồ.
     */
    @GetMapping("/stations/{stationId}/connections")
    public List<LineTransfer> connections(@PathVariable Long stationId) {
        return service.connections(stationId);
    }

    /**
     * Trả về danh sách tuyến đi qua ga, giúp xác định ga thuộc tuyến nào và tuyến nào kết nối với nhau.
     */
    @GetMapping("/stations/{stationId}/lines")
    public List<MetroLine> lines(@PathVariable Long stationId) {
        return service.linesByStation(stationId);
    }
}
