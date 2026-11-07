package com.metroticket_api.ticket;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.ticket.TicketProductDtos.AuditResponse;
import com.metroticket_api.ticket.TicketProductDtos.CreateProductRequest;
import com.metroticket_api.ticket.TicketProductDtos.ProductResponse;
import com.metroticket_api.ticket.TicketProductDtos.PublicProductResponse;
import com.metroticket_api.ticket.TicketProductDtos.UpdateProductRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Loại vé. Công khai: danh sách đang bán. Xem toàn bộ + lịch sử: FARE_MANAGE hoặc REPORT_VIEW (MANAGER).
 * Thêm/sửa/ngừng bán/xoá: FARE_MANAGE (mặc định chỉ ADMIN).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ticket products", description = "Loại vé theo thời gian: 1 ngày, 3 ngày, 1 tuần, 1 tháng (mua nhiều tháng)")
public class TicketProductController {

    private final TicketProductService service;

    public TicketProductController(TicketProductService service) {
        this.service = service;
    }

    @Operation(summary = "Loại vé đang bán (công khai)")
    @GetMapping("/public/ticket-products")
    public List<PublicProductResponse> active() {
        return service.listActive();
    }

    @PreAuthorize("hasAnyAuthority('FARE_MANAGE', 'REPORT_VIEW')")
    @GetMapping("/ticket-products")
    public List<ProductResponse> all() {
        return service.listAll();
    }

    @PreAuthorize("hasAnyAuthority('FARE_MANAGE', 'REPORT_VIEW')")
    @GetMapping("/ticket-products/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @Operation(summary = "Lịch sử thay đổi loại vé")
    @PreAuthorize("hasAnyAuthority('FARE_MANAGE', 'REPORT_VIEW')")
    @GetMapping("/ticket-products/{id}/history")
    public List<AuditResponse> history(@PathVariable Long id) {
        return service.history(id);
    }

    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @PostMapping("/ticket-products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody CreateProductRequest request) {
        return service.create(request);
    }

    @Operation(summary = "Sửa loại vé", description = "Bắt buộc gửi version hiện tại. Đổi giá chỉ áp dụng cho lượt mua sau.")
    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @PutMapping("/ticket-products/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody UpdateProductRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @PostMapping("/ticket-products/{id}/activate")
    public ProductResponse activate(@PathVariable Long id) {
        return service.activate(id);
    }

    @Operation(summary = "Ngừng bán", description = "Vé đã bán vẫn dùng được.")
    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @PostMapping("/ticket-products/{id}/deactivate")
    public ProductResponse deactivate(@PathVariable Long id) {
        return service.deactivate(id);
    }

    @Operation(summary = "Xoá loại vé", description = "Chỉ khi chưa bán vé nào, ngược lại trả 409.")
    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @DeleteMapping("/ticket-products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
