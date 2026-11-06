package com.metroticket_api.metro.announcement;

import java.util.List;
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

import com.metroticket_api.metro.announcement.AnnouncementDtos.AnnouncementResponse;
import com.metroticket_api.metro.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.metroticket_api.metro.announcement.AnnouncementDtos.UpdateAnnouncementRequest;
import com.metroticket_api.metro.common.AnnouncementStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Controller quản lý thông báo nội bộ cho Metro.
 *
 * <p>Những endpoint này hỗ trợ tạo, xem, gửi duyệt và quản lý trạng thái approval cho các thông báo liên quan đến tuyến, ga hoặc tình trạng vận hành.</p>
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Announcement", description = "Quản lý thông báo Metro theo workflow DRAFT -> PENDING_APPROVAL -> APPROVED/REJECTED.")
public class AnnouncementController {

    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    /**
     * Lấy toàn bộ tin tức Metro để hiển thị trên dashboard nội bộ hoặc portal thông tin chung.
     */
    @Operation(summary = "Danh sách announcement", description = "Lấy danh sách thông báo, có thể lọc theo trạng thái workflow.")
    @GetMapping("/announcements")
    public List<AnnouncementResponse> list(@RequestParam(required = false) AnnouncementStatus status) {
       return service.list(status);
    }

    /**
     * Lấy danh sách thông báo liên quan đến một tuyến để hiển thị trên màn hình tuyến hoặc thông tin vận hành.
     */
    @Operation(summary = "Danh sách announcement theo tuyến", description = "Lấy các thông báo thuộc một tuyến Metro.")
    @GetMapping("/lines/{lineId}/announcements")
    public List<AnnouncementResponse> listByLine(@PathVariable Long lineId) {
       return service.listByLine(lineId);
    }

    /**
     * Lấy danh sách thông báo tại một ga cụ thể, phục vụ hiển thị nhắc nhở khi hành khách ở stnion.
     */
    @Operation(summary = "Danh sách announcement theo ga", description = "Lấy các thông báo thuộc một nhà ga cụ thể.")
    @GetMapping("/stations/{stationId}/announcements")
    public List<AnnouncementResponse> listByStation(@PathVariable Long stationId) {
        return service.listByStation(stationId);
    }

    /**
     * Xem chi tiết một thông báo theo ID để kiểm tra nội dung, thời gian hiệu lực và trạng thái duyệt.
     */
    @Operation(summary = "Xem chi tiết announcement", description = "Lấy thông tin chi tiết một announcement theo ID.")
    @GetMapping("/announcements/{id}")
    public AnnouncementResponse get(@PathVariable Long id) {
        return AnnouncementResponse.of(service.getById(id));
    }

    /**
     * Tạo một thông báo mới ở trạng thái nháp, có thể kèm tuyến hoặc ga liên quan.
     */
    @Operation(summary = "Tạo announcement mới", description = "Tạo announcement ở trạng thái DRAFT, chưa được phê duyệt.")
    @PostMapping("/announcements")
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementResponse create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return service.create(request);
    }

    /**
     * Chỉnh sửa nội dung thông báo trước khi gửi duyệt hoặc phát hành.
     */
    @Operation(summary = "Cập nhật announcement", description = "Chỉ cho phép sửa bản ở trạng thái DRAFT. Nếu đã APPROVED, phải tạo bản mới để chỉnh sửa.")
    @PutMapping("/announcements/{id}")
    public AnnouncementResponse update(@PathVariable Long id, @Valid @RequestBody UpdateAnnouncementRequest request) {
        return service.update(id, request);
    }

    /**
     * Gửi thông báo lên quy trình duyệt để admin hoặc quản lý kiểm tra.
     */
    @Operation(summary = "Gửi duyệt announcement", description = "Chuyển trạng thái DRAFT -> PENDING_APPROVAL.")
    @PostMapping("/announcements/{id}/submit")
    public AnnouncementResponse submit(@PathVariable Long id) {
        return service.submit(id);
    }

    /**
     * Phê duyệt thông báo để chuyển sang trạng thái đã duyệt và sẵn sàng phát hành.
     */
    @Operation(summary = "Phê duyệt announcement", description = "Chuyển trạng thái PENDING_APPROVAL -> APPROVED.")
    @PostMapping("/announcements/{id}/approve")
    public AnnouncementResponse approve(@PathVariable Long id, @RequestParam UUID approvedBy) {
        return service.approve(id, approvedBy);
    }

    /**
     * Từ chối thông báo và lưu lý do, giúp người tạo biết điểm cần chỉnh sửa.
     */
    @Operation(summary = "Từ chối announcement", description = "Chuyển trạng thái PENDING_APPROVAL -> REJECTED và lưu lý do từ chối.")
    @PostMapping("/announcements/{id}/reject")
    public AnnouncementResponse reject(@PathVariable Long id, @RequestParam String reason) {
        return service.reject(id, reason);
    }

}
