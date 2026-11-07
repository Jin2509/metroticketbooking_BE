package com.metroticket_api.metro.operation;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.common.OperationConfigStatus;
import com.metroticket_api.metro.operation.OperationConfigDtos.CreateOperationConfigRequest;
import com.metroticket_api.metro.operation.OperationConfigDtos.OperationConfigResponse;
import com.metroticket_api.metro.operation.OperationConfigDtos.RejectOperationConfigRequest;
import com.metroticket_api.metro.operation.OperationConfigDtos.UpdateOperationConfigRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Cấu hình vận hành theo phiên bản. Soạn/gửi duyệt: OPERATION_MANAGE. Duyệt/từ chối/kích hoạt: OPERATION_APPROVE.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Operation Config", description = "Cấu hình vận hành theo phiên bản: DRAFT -> PENDING_APPROVAL -> APPROVED -> ACTIVE/INACTIVE.")
public class OperationConfigController {

    private final OperationConfigService service;

    public OperationConfigController(OperationConfigService service) {
        this.service = service;
    }

    @Operation(summary = "Liệt kê operation config", description = "Lọc tuỳ chọn theo status, ví dụ status=PENDING_APPROVAL cho hàng chờ duyệt.")
    @GetMapping("/operation-configs")
    public List<OperationConfigResponse> listAll(@RequestParam(required = false) OperationConfigStatus status) {
        return service.listAll(status);
    }

    @Operation(summary = "Lịch sử operation config của tuyến", description = "Sắp theo version giảm dần.")
    @GetMapping("/lines/{lineId}/operation-configs")
    public List<OperationConfigResponse> listByLine(@PathVariable Long lineId) {
        return service.listByLine(lineId);
    }

    @GetMapping("/operation-configs/{id}")
    public OperationConfigResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Tạo operation config (DRAFT)", description = "Version = max + 1 của tuyến. Người tạo lấy từ JWT.")
    @PreAuthorize("hasAuthority('OPERATION_MANAGE')")
    @PostMapping("/lines/{lineId}/operation-configs")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationConfigResponse create(@PathVariable Long lineId,
            @Valid @RequestBody CreateOperationConfigRequest request) {
        return service.create(lineId, request);
    }

    @Operation(summary = "Sửa DRAFT/REJECTED, hoặc tạo DRAFT mới từ bản đã duyệt")
    @PreAuthorize("hasAuthority('OPERATION_MANAGE')")
    @PutMapping("/operation-configs/{id}")
    public OperationConfigResponse update(@PathVariable Long id, @Valid @RequestBody UpdateOperationConfigRequest request) {
        return service.update(id, request);
    }

    @Operation(summary = "Gửi duyệt", description = "DRAFT -> PENDING_APPROVAL.")
    @PreAuthorize("hasAuthority('OPERATION_MANAGE')")
    @PostMapping("/operation-configs/{id}/submit")
    public OperationConfigResponse submit(@PathVariable Long id) {
        return service.submit(id);
    }

    @Operation(summary = "Phê duyệt", description = "PENDING_APPROVAL -> APPROVED. Người duyệt lấy từ JWT, phải khác người soạn.")
    @PreAuthorize("hasAuthority('OPERATION_APPROVE')")
    @PostMapping("/operation-configs/{id}/approve")
    public OperationConfigResponse approve(@PathVariable Long id) {
        return service.approve(id);
    }

    @Operation(summary = "Từ chối", description = "PENDING_APPROVAL -> REJECTED, bắt buộc có lý do.")
    @PreAuthorize("hasAuthority('OPERATION_APPROVE')")
    @PostMapping("/operation-configs/{id}/reject")
    public OperationConfigResponse reject(@PathVariable Long id, @Valid @RequestBody RejectOperationConfigRequest request) {
        return service.reject(id, request.reason());
    }

    @Operation(summary = "Kích hoạt", description = "APPROVED/INACTIVE -> ACTIVE; bản ACTIVE cũ của tuyến tự chuyển INACTIVE.")
    @PreAuthorize("hasAuthority('OPERATION_APPROVE')")
    @PostMapping("/operation-configs/{id}/activate")
    public OperationConfigResponse activate(@PathVariable Long id) {
        return service.activate(id);
    }

    @Operation(summary = "Huỷ kích hoạt", description = "ACTIVE/APPROVED -> INACTIVE.")
    @PreAuthorize("hasAuthority('OPERATION_APPROVE')")
    @PostMapping("/operation-configs/{id}/deactivate")
    public OperationConfigResponse deactivate(@PathVariable Long id) {
        return service.deactivate(id);
    }
}
