package com.metroticket_api.metro.operation;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.operation.OperationConfigDtos.CreateOperationConfigRequest;
import com.metroticket_api.metro.operation.OperationConfigDtos.OperationConfigResponse;
import com.metroticket_api.metro.operation.OperationConfigDtos.UpdateOperationConfigRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Controller quản lý cấu hình vận hành của tuyến Metro.
 *
 * <p>Đây là vùng API gắn với việc mô phỏng lịch chạy, thiết lập headway, số lượng tàu và quy trình phê duyệt cấu hình.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operation Config", description = "Quản lý versioned operation config của tuyến Metro: DRAFT -> PENDING_APPROVAL -> APPROVED -> ACTIVE/INACTIVE.")
public class OperationConfigController {

    private final OperationConfigService service;

    public OperationConfigController(OperationConfigService service) {
        this.service = service;
    }

    /**
     * Lấy toàn bộ cấu hình vận hành trên hệ thống.
     */
    @Operation(summary = "Liệt kê tất cả operation config", description = "Trả về toàn bộ cấu hình vận hành trong hệ thống để dashboard hoặc filter nhanh.")
    @GetMapping("/operation-configs")
    public java.util.List<OperationConfigResponse> listAll() {
       return service.listAll();
    }

    /**
     * Lấy lịch sử cấu hình vận hành của một tuyến để admin hoặc người vận hành xem lại các phiên bản cũ.
     */
    @Operation(summary = "Liệt kê lịch sử operation config theo tuyến", description = "Trả về danh sách phiên bản cấu hình của tuyến theo thời điểm hiệu lực giảm dần.")
    @GetMapping("/lines/{lineId}/operation-configs")
    public java.util.List<OperationConfigResponse> listByLine(@PathVariable Long lineId) {
       return service.listByLine(lineId);
    }

    /**
     * Xem chi tiết cấu hình vận hành theo ID.
     */
    @Operation(summary = "Xem chi tiết operation config", description = "Lấy thông tin chi tiết một phiên bản cấu hình theo ID.")
    @GetMapping("/operation-configs/{id}")
    public OperationConfigResponse get(@PathVariable Long id) {
        return OperationConfigResponse.of(service.getById(id));
    }

    /**
     * Tạo mới một cấu hình vận hành cho tuyến, thường bắt đầu ở trạng thái DRAFT.
     */
    @Operation(summary = "Tạo operation config mới", description = "Tạo bản cấu hình mới với version tự sinh và trạng thái DRAFT. Version tăng theo maxVersion + 1 cho tuyến.")
    @PostMapping("/lines/{lineId}/operation-configs")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationConfigResponse create(@PathVariable Long lineId,
            @Valid @RequestBody CreateOperationConfigRequest request) {
        return service.create(lineId, request);
    }

    /**
     * Cập nhật cấu hình hiện có, thích hợp khi điều chỉnh số tàu, khoảng cách, thời gian chạy hoặc thời hiệu lực.
     */
    @Operation(summary = "Cập nhật draft hoặc tạo draft mới từ approved", description = "Nếu bản đang APPROVED, service sẽ tạo bản mới DRAFT với version tăng lên. Không cho phép truyền version/status thủ công.")
    @PutMapping("/operation-configs/{id}")
    public OperationConfigResponse update(@PathVariable Long id, @Valid @RequestBody UpdateOperationConfigRequest request) {
        return service.update(id, request);
    }

    /**
     * Gửi cấu hình lên để chờ phê duyệt, chuyển trạng thái từ DRAFT sang PENDING_APPROVAL.
     */
    @Operation(summary = "Gửi duyệt operation config", description = "Chuyển trạng thái DRAFT -> PENDING_APPROVAL.")
    @PostMapping("/operation-configs/{id}/submit")
    public OperationConfigResponse submit(@PathVariable Long id) {
        return service.submit(id);
    }

    /**
     * Phê duyệt cấu hình vận hành bởi người có quyền và đánh dấu thời điểm phê duyệt.
     */
    @Operation(summary = "Phê duyệt operation config", description = "Chuyển trạng thái PENDING_APPROVAL -> APPROVED, đánh dấu phiên bản hiện hành cho tuyến.")
    @PostMapping("/operation-configs/{id}/approve")
    public OperationConfigResponse approve(@PathVariable Long id, @RequestParam UUID approvedBy) {
        return service.approve(id, approvedBy);
    }

    /**
     * Từ chối cấu hình và lưu nguyên nhân từ chối để gửi lại cho người tạo chỉnh sửa.
     */
    @Operation(summary = "Từ chối operation config", description = "Chuyển trạng thái PENDING_APPROVAL -> REJECTED và lưu lý do từ chối.")
    @PostMapping("/operation-configs/{id}/reject")
    public OperationConfigResponse reject(@PathVariable Long id, @RequestParam String reason) {
        return service.reject(id, reason);
    }

    @Operation(summary = "Kích hoạt operation config", description = "Đặt cấu hình được phê duyệt thành ACTIVE. Chỉ cho phép 1 cấu hình ACTIVE cho mỗi tuyến.")
    @PostMapping("/operation-configs/{id}/activate")
    public OperationConfigResponse activate(@PathVariable Long id) {
        return service.activate(id);
    }

    @Operation(summary = "Hủy kích hoạt operation config", description = "Chuyển trạng thái ACTIVE -> INACTIVE hoặc APPROVED -> INACTIVE.")
    @PostMapping("/operation-configs/{id}/deactivate")
    public OperationConfigResponse deactivate(@PathVariable Long id) {
        return service.deactivate(id);
    }

}
