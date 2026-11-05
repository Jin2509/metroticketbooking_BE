package com.metroticket_api.metro.train;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.common.TrainStatus;
import com.metroticket_api.metro.train.TrainDtos.CreateTrainRequest;
import com.metroticket_api.metro.train.TrainDtos.TrainResponse;
import com.metroticket_api.metro.train.TrainDtos.UpdateTrainRequest;

/**
 * Controller quản lý đoàn tàu của Metro.
 *
 * <p>Những endpoint này cho phép quản trị viên hoặc nhân sự kiểm tra danh sách tàu, cập nhật trạng thái,
 * gán tuyến và theo dõi quy trình vận hành của từng đoàn tàu.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class TrainController {

    private final TrainService service;

    public TrainController(TrainService service) {
        this.service = service;
    }

    /**
     * Lấy toàn bộ đoàn tàu trong hệ thống theo phân trang, phục vụ màn hình quản lý phương tiện vận hành.
     */
    @GetMapping("/trains")
    public Page<TrainResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return service.list(pageable);
    }

    /**
     * Xem chi tiết một đoàn tàu cụ thể, bao gồm tuyến hoạt động, trạng thái và thông tin kỹ thuật.
     */
    @GetMapping("/trains/{trainId}")
    public TrainResponse get(@PathVariable Long trainId) {
        return TrainResponse.of(service.getById(trainId));
    }

    /**
     * Trả về danh sách tàu thuộc một tuyến Metro, dùng cho màn hình điều phối hoặc sơ đồ vận hành tuyến.
     */
    @GetMapping("/lines/{lineId}/trains")
    public List<TrainResponse> listByLine(@PathVariable Long lineId) {
        return service.listByLine(lineId);
    }

    /**
     * Thêm mới đoàn tàu và gán tuyến nếu có, đồng thời lưu trạng thái hiện tại của tàu.
     */
    @PostMapping("/trains")
    @ResponseStatus(HttpStatus.CREATED)
    public TrainResponse create(@Valid @RequestBody CreateTrainRequest request) {
        return service.create(request);
    }

    /**
     * Cập nhật thông tin tàu, bao gồm tuyến, sức chứa, năm sản xuất hoặc trạng thái.
     */
    @PutMapping("/trains/{trainId}")
    public TrainResponse update(@PathVariable Long trainId, @Valid @RequestBody UpdateTrainRequest request) {
        return service.update(trainId, request);
    }

    /**
     * Cập nhật trạng thái hoạt động của tàu như READY, IN_OPERATION, MAINTENANCE, OUT_OF_SERVICE.
     */
    @PatchMapping("/trains/{trainId}/status")
    public TrainResponse updateStatus(@PathVariable Long trainId, @RequestBody TrainStatus status) {
        return service.updateStatus(trainId, status);
    }

    /**
     * Xóa đoàn tàu khỏi hệ thống khi không còn hoạt động hoặc cần dọn dữ liệu cũ.
     */
    @DeleteMapping("/trains/{trainId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long trainId) {
        service.delete(trainId);
    }
}
