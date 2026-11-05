package com.metroticket_api.metro.station;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.station.LineTransferDtos.CreateLineTransferRequest;
import com.metroticket_api.metro.station.LineTransferDtos.LineTransferResponse;
import com.metroticket_api.metro.station.LineTransferDtos.UpdateLineTransferRequest;

/**
 * Controller cho các thông tin chuyển tuyến giữa 2 tuyến Metro tại ga chung.
 *
 * <p>API này hỗ trợ truy vấn, thêm, cập nhật và xóa mối nối chuyển tuyến, phục vụ định tuyến và bản đồ
 * di chuyển của hành khách.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class LineTransferController {

    private final StationService service;

    public LineTransferController(StationService service) {
        this.service = service;
    }

    @GetMapping("/line-transfers")
    public List<LineTransferResponse> list() {
        return service.allLineTransfers().stream().map(LineTransferResponse::of).toList();
    }

    @GetMapping("/line-transfers/{id}")
    public LineTransferResponse get(@PathVariable Long id) {
        return LineTransferResponse.of(service.getLineTransfer(id));
    }

    @PostMapping("/line-transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public LineTransferResponse create(@Valid @RequestBody CreateLineTransferRequest request) {
        return LineTransferResponse.of(service.createLineTransfer(
                request.stationId(),
                request.fromLineId(),
                request.toLineId(),
                request.transferTimeMinutes(),
                request.status()));
    }

    @PutMapping("/line-transfers/{id}")
    public LineTransferResponse update(@PathVariable Long id,
            @Valid @RequestBody UpdateLineTransferRequest request) {
        return LineTransferResponse.of(service.updateLineTransfer(
                id,
                request.stationId(),
                request.fromLineId(),
                request.toLineId(),
                request.transferTimeMinutes(),
                request.status()));
    }

    @DeleteMapping("/line-transfers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.deleteLineTransfer(id);
    }
}
