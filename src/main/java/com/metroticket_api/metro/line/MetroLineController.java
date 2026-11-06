package com.metroticket_api.metro.line;

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

import com.metroticket_api.metro.common.MetroLineStatus;
import com.metroticket_api.metro.line.MetroLineDtos.CreateLineRequest;
import com.metroticket_api.metro.line.MetroLineDtos.LineResponse;
import com.metroticket_api.metro.line.MetroLineDtos.UpdateLineRequest;
import com.metroticket_api.metro.station.LineTransferDtos.LineTransferResponse;
import com.metroticket_api.metro.station.StationDtos;
import com.metroticket_api.metro.station.StationDtos.AddLineStationRequest;
import com.metroticket_api.metro.station.StationDtos.LineStationResponse;
import com.metroticket_api.metro.station.StationDtos.StationOrderItem;
import com.metroticket_api.metro.station.StationService;

/**
 * Controller cho các API quản lý tuyến Metro.
 *
 * <p>Đây là lớp API entry-point cho frontend/mobile để xem, lọc và cập nhật thông tin tuyến.
 * Các endpoint được thiết kế theo chuẩn REST và tách riêng khỏi logic nghiệp vụ để dễ kiểm thử.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class MetroLineController {

    private final MetroLineService service;
    private final StationService stationService;

    public MetroLineController(MetroLineService service, StationService stationService) {
        this.service = service;
        this.stationService = stationService;
    }

    /**
     * Trả về danh sách tuyến cho màn hình quản lý hoặc danh mục tuyến trên frontend.
     * Hỗ trợ lọc theo mã tuyến, tên tuyến và trạng thái hoạt động để giảm tải dữ liệu trả về.
     */
    @GetMapping("/lines")
    public Page<LineResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) MetroLineStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(code, name, status, pageable);
    }

    /**
     * Lấy toàn bộ tuyến mà không phân trang, phù hợp cho select/dropdown ở frontend.
     */
    @GetMapping("/lines/all")
    public List<LineResponse> listAll() {
        return service.listAll();
    }

    /**
     * Lấy thông tin chi tiết một tuyến theo mã định danh.
     * Dùng cho màn hình cập nhật tuyến hoặc hiển thị thông tin cơ bản của từng tuyến.
     */
    @GetMapping("/lines/{lineId}")
    public LineResponse get(@PathVariable Long lineId) {
        return LineResponse.of(service.getById(lineId));
    }

    /**
     * Tạo tuyến mới. Hành động này lưu bản ghi tuyến trên database và trả về dữ liệu vừa tạo.
     */
    @PostMapping("/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public LineResponse create(@Valid @RequestBody CreateLineRequest request) {
        return service.create(request);
    }

    /**
     * Cập nhật thông tin tuyến hiện có, như tên, mã, màu sắc, mô tả hoặc trạng thái.
     */
    @PutMapping("/lines/{lineId}")
    public LineResponse update(@PathVariable Long lineId, @Valid @RequestBody UpdateLineRequest request) {
        return service.update(lineId, request);
    }

    /**
     * Xóa tuyến. Khi tuyến bị xóa, các dữ liệu liên kết như ga thuộc tuyến và thông tin chuyển tuyến cũng cần được xử lý theo cascade.
     */
    @DeleteMapping("/lines/{lineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long lineId) {
        service.delete(lineId);
    }

    /**
     * Trả về danh sách ga theo thứ tự của tuyến, phục vụ hiển thị trên bản đồ hoặc tuyến đường.
     */
    @GetMapping("/lines/{lineId}/stations")
    public List<LineStationResponse> stations(@PathVariable Long lineId) {
        return service.stations(lineId);
    }

    /**
     * Lấy danh sách ga công khai theo mã tuyến, phục vụ dựng bản đồ tuyến trên client.
     */
    @GetMapping("/public/lines/{lineCode}/stations")
    public List<LineStationResponse> publicStations(@PathVariable String lineCode) {
        return service.stationsByCode(lineCode);
    }

    /**
     * Thêm ga vào tuyến. Endpoint này tạo quan hệ tuyến-ga và gán station_order ở cuối danh sách hiện tại.
     */
    @PostMapping("/lines/{lineId}/stations")
    @ResponseStatus(HttpStatus.CREATED)
    public LineStationResponse addStationToLine(@PathVariable Long lineId,
           @Valid @RequestBody AddLineStationRequest request) {
       return stationService.addStationToLine(
               lineId,
               request.stationId(),
               request.stationOrder(),
               request.distanceFromPrevKm(),
               request.travelTimeFromPrevSeconds(),
               request.dwellTimeSeconds());
    }

    /**
     * Cập nhật thông số đoạn đường giữa ga hiện tại và ga trước đó trên tuyến.
     */
    @PutMapping("/lines/{lineId}/stations/{stationId}/segment")
    public LineStationResponse updateStationSegment(@PathVariable Long lineId,
            @PathVariable Long stationId,
            @Valid @RequestBody StationDtos.UpdateLineStationSegmentRequest request) {
        return stationService.updateStationSegment(lineId, stationId,
                request.distanceFromPrevKm(),
                request.travelTimeFromPrevSeconds(),
                request.dwellTimeSeconds());
    }

    /**
     * Xóa ga khỏi tuyến. Bản ghi station không bị xóa, chỉ bỏ quan hệ với tuyến.
     */
    @DeleteMapping("/lines/{lineId}/stations/{stationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStationFromLine(@PathVariable Long lineId, @PathVariable Long stationId) {
       stationService.removeStationFromLine(lineId, stationId);
    }

    /**
     * Cập nhật toàn bộ thứ tự ga trên tuyến. Danh sách này phải chứa đầy đủ tất cả ga của tuyến theo thứ tự mới.
     */
    @PutMapping("/lines/{lineId}/stations/order")
    public List<LineStationResponse> updateStationOrder(@PathVariable Long lineId,
           @RequestBody List<StationOrderItem> stationOrders) {
       return stationService.updateStationOrder(lineId, stationOrders);
    }

    /**
     * Trả về thông tin các điểm chuyển tuyến từ tuyến hiện tại sang tuyến khác.
     */
    @GetMapping("/lines/{lineId}/transfers")
    public List<LineTransferResponse> transfers(@PathVariable Long lineId) {
       return service.transfers(lineId);
    }
}
