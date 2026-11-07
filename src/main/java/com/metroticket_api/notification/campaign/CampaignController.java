package com.metroticket_api.notification.campaign;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

import com.metroticket_api.notification.campaign.CampaignDtos.CampaignRequest;
import com.metroticket_api.notification.campaign.CampaignDtos.CampaignResponse;
import com.metroticket_api.notification.campaign.CampaignDtos.PreviewResponse;
import com.metroticket_api.notification.campaign.CampaignDtos.SendRequest;
import com.metroticket_api.notification.campaign.CampaignDtos.StatsResponse;
import com.metroticket_api.notification.campaign.CampaignDtos.UpdateCampaignRequest;
import com.metroticket_api.notification.campaign.CampaignDtos.VersionRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Chiến dịch thông báo. Soạn/gửi: NOTIFICATION_MANAGE (ADMIN). Xem + thống kê: thêm REPORT_VIEW (MANAGER). */
@RestController
@RequestMapping("/api/v1/admin/notification-campaigns")
@Tag(name = "Notification campaigns", description = "Admin soạn, hẹn giờ, đẩy thông báo tới nhóm người dùng")
public class CampaignController {

    private final CampaignService service;

    public CampaignController(CampaignService service) {
        this.service = service;
    }

    @PreAuthorize("hasAnyAuthority('NOTIFICATION_MANAGE', 'REPORT_VIEW')")
    @GetMapping
    public Page<CampaignResponse> list(@RequestParam(required = false) CampaignStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable);
    }

    @PreAuthorize("hasAnyAuthority('NOTIFICATION_MANAGE', 'REPORT_VIEW')")
    @GetMapping("/{id}")
    public CampaignResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Thống kê: số người nhận, đã đọc, push/email đã gửi/lỗi")
    @PreAuthorize("hasAnyAuthority('NOTIFICATION_MANAGE', 'REPORT_VIEW')")
    @GetMapping("/{id}/stats")
    public StatsResponse stats(@PathVariable UUID id) {
        return service.stats(id);
    }

    @Operation(summary = "Xem trước số người nhận")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @GetMapping("/{id}/preview")
    public PreviewResponse preview(@PathVariable UUID id) {
        return service.preview(id);
    }

    @Operation(summary = "Tạo chiến dịch (bản nháp)")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignResponse create(@Valid @RequestBody CampaignRequest request) {
        return service.create(request);
    }

    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @PutMapping("/{id}")
    public CampaignResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCampaignRequest request) {
        return service.update(id, request.version(), request.campaign());
    }

    @Operation(summary = "Gửi ngay hoặc hẹn giờ", description = "scheduledAt null = gửi ngay (worker xử lý trong ~10 giây).")
    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @PostMapping("/{id}/send")
    public CampaignResponse send(@PathVariable UUID id, @Valid @RequestBody SendRequest request) {
        return service.send(id, request.version(), request.scheduledAt());
    }

    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @PostMapping("/{id}/cancel")
    public CampaignResponse cancel(@PathVariable UUID id, @Valid @RequestBody VersionRequest request) {
        return service.cancel(id, request.version());
    }

    @PreAuthorize("hasAuthority('NOTIFICATION_MANAGE')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
