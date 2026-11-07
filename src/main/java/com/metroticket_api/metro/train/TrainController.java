package com.metroticket_api.metro.train;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.train.TrainDtos.CreateTrainRequest;
import com.metroticket_api.metro.train.TrainDtos.TrainResponse;
import com.metroticket_api.metro.train.TrainDtos.UpdateTrainRequest;

/**
 * Quản lý đoàn tàu. Đọc: mọi người dùng đã đăng nhập. Ghi: quyền TRAIN_MANAGE.
 */
@RestController
@RequestMapping("/api/v1")
public class TrainController {

    private final TrainService service;

    public TrainController(TrainService service) {
        this.service = service;
    }

    /** Danh sách tàu có phân trang, lọc theo tuyến, trạng thái, mã/tên (q). */
    @GetMapping("/trains")
    public Page<TrainResponse> list(
            @RequestParam(required = false) Long lineId,
            @RequestParam(required = false) TrainStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(lineId, status, q, pageable);
    }

    @GetMapping("/trains/all")
    public List<TrainResponse> listAll() {
        return service.listAll();
    }

    @GetMapping("/trains/{trainId}")
    public TrainResponse get(@PathVariable Long trainId) {
        return service.get(trainId);
    }

    @GetMapping("/lines/{lineId}/trains")
    public List<TrainResponse> listByLine(@PathVariable Long lineId) {
        return service.listByLine(lineId);
    }

    @PreAuthorize("hasAuthority('TRAIN_MANAGE')")
    @PostMapping("/trains")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainResponse create(@Valid @RequestBody CreateTrainRequest request) {
        return service.create(request);
    }

    @PreAuthorize("hasAuthority('TRAIN_MANAGE')")
    @PutMapping("/trains/{trainId}")
    public TrainResponse update(@PathVariable Long trainId, @Valid @RequestBody UpdateTrainRequest request) {
        return service.update(trainId, request);
    }

    /** Body là chuỗi JSON enum, ví dụ {@code "MAINTENANCE"}. */
    @PreAuthorize("hasAuthority('TRAIN_MANAGE')")
    @PatchMapping("/trains/{trainId}/status")
    public TrainResponse updateStatus(@PathVariable Long trainId, @RequestBody TrainStatus status) {
        return service.updateStatus(trainId, status);
    }

    @PreAuthorize("hasAuthority('TRAIN_MANAGE')")
    @DeleteMapping("/trains/{trainId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long trainId) {
        service.delete(trainId);
    }
}
