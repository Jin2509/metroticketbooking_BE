package com.metroticket_api.metro.announcement;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.announcement.AnnouncementDtos.AnnouncementResponse;
import com.metroticket_api.metro.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.metroticket_api.metro.announcement.AnnouncementDtos.UpdateAnnouncementRequest;
import com.metroticket_api.metro.common.AnnouncementScope;
import com.metroticket_api.metro.common.AnnouncementStatus;
import com.metroticket_api.metro.line.MetroLine;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.workflow.WorkflowEngineService;
import com.metroticket_api.workflow.WorkflowEntityType;

/**
 * Service xử lý thông báo Metro.
 *
 * <p>Class này lưu trữ thông báo liên quan đến tuyến, ga hoặc sự cố vận hành và thực hiện các bước duyệt,
 * phát hành và công bố tới người dùng.</p>
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcements;
    private final MetroLineRepository lines;
    private final StationRepository stations;
    private final WorkflowEngineService workflowEngine;

    public AnnouncementService(AnnouncementRepository announcements, MetroLineRepository lines,
            StationRepository stations, WorkflowEngineService workflowEngine) {
        this.announcements = announcements;
        this.lines = lines;
        this.stations = stations;
        this.workflowEngine = workflowEngine;
    }

    /**
     * Lấy tất cả thông báo Metro theo thời gian bắt đầu giảm dần để màn hình quản lý hoặc portal tin tức có thể hiển thị đầy đủ.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> list(AnnouncementStatus status) {
       List<Announcement> result = status == null
               ? announcements.findAll(Sort.by(Sort.Direction.DESC, "startsAt"))
               : announcements.findAllByStatusOrderByStartsAtDesc(status);
       return result.stream().map(AnnouncementResponse::of).toList();
    }

    /**
     * Lấy danh sách thông báo liên quan đến một tuyến, sắp xếp theo thời gian bắt đầu giảm dần để hiển thị thông báo mới nhất trước.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listByLine(Long lineId) {
       return announcements.findByLineIdOrderByStartsAtDesc(lineId)
               .stream().map(AnnouncementResponse::of).toList();
    }

    /**
     * Hiển thị thông báo theo ga để người dùng hoặc nhân viên biết các cảnh báo hoạt động tại điểm đón/xuống gần đó.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listByStation(Long stationId) {
        return announcements.findByStationIdOrderByStartsAtDesc(stationId)
                .stream().map(AnnouncementResponse::of).toList();
    }

    /**
     * Tìm thông báo theo ID để phục vụ xem chi tiết hoặc thao tác duyệt.
     */
    @Transactional(readOnly = true)
    public Announcement getById(Long id) {
        return announcements.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo"));
    }

    /**
     * Tạo thông báo mới. Quy tắc chính là tiêu đề, nội dung và thời gian hiệu lực phải hợp lệ trước khi lưu vào hệ thống.
     */
    @Transactional
    public AnnouncementResponse create(CreateAnnouncementRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tiêu đề không được để trống");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nội dung không được để trống");
        }
        MetroLine line = request.lineId() == null ? null : lines.findById(request.lineId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến"));
        Station station = request.stationId() == null ? null : stations.findById(request.stationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy ga"));
        Announcement announcement = new Announcement(request.title(), request.content(), request.severity(),
                request.startsAt(), request.endsAt(), AnnouncementStatus.DRAFT,
                request.scope() == null ? AnnouncementScope.ALL : request.scope(),
                request.targetRole(), request.createdBy());
        announcement.setLine(line);
        announcement.setStation(station);
        if (announcement.getScope() == AnnouncementScope.ROLE && (announcement.getTargetRole() == null || announcement.getTargetRole().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Với scope ROLE phải cung cấp targetRole");
        }
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    /**
     * Cập nhật thông báo đang ở nháp hoặc đang chờ duyệt để sửa nội dung, tuyến, ga hoặc thời gian hiển thị.
     * Nếu announcement đã được duyệt, hệ thống không sửa trực tiếp; phải tạo bản mới ở trạng thái DRAFT.
     */
    @Transactional
    public AnnouncementResponse update(Long id, UpdateAnnouncementRequest request) {
        Announcement announcement = getById(id);

        if (announcement.getStatus() == AnnouncementStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không thể sửa announcement đang chờ phê duyệt");
        }
        if (announcement.getStatus() == AnnouncementStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Announcement đã được duyệt; tạo bản mới ở trạng thái DRAFT để chỉnh sửa");
        }

        if (request.title() != null) announcement.setTitle(request.title());
        if (request.content() != null) announcement.setContent(request.content());
        if (request.severity() != null) announcement.setSeverity(request.severity());
        if (request.startsAt() != null) announcement.setStartsAt(request.startsAt());
        if (request.endsAt() != null) announcement.setEndsAt(request.endsAt());
        if (request.scope() != null) announcement.setScope(request.scope());
        if (request.targetRole() != null) announcement.setTargetRole(request.targetRole());
        if (request.createdBy() != null) announcement.setCreatedBy(request.createdBy());
        if (announcement.getScope() == AnnouncementScope.ROLE && (announcement.getTargetRole() == null || announcement.getTargetRole().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Với scope ROLE phải cung cấp targetRole");
        }
        if (request.lineId() != null) {
            announcement.setLine(lines.findById(request.lineId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến")));
        }
        if (request.stationId() != null) {
            announcement.setStation(stations.findById(request.stationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy ga")));
        }
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    /**
     * Workflow phê duyệt thông báo tương tự operation config: tạo nháp, gửi duyệt, duyệt hoặc từ chối.
     * Việc tách riêng từng method giúp UI dễ gọi đúng hành động mà không cần xử lý trạng thái ở client.
     */
    @Transactional
    public AnnouncementResponse submit(Long id) {
        Announcement announcement = getById(id);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.ANNOUNCEMENT,
                announcement.getStatus().name(),
                AnnouncementStatus.PENDING_APPROVAL.name());
        announcement.setStatus(AnnouncementStatus.PENDING_APPROVAL);
        announcement.setSubmittedAt(Instant.now());
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    /**
     * Phê duyệt thông báo và ghi nhận người duyệt cùng lúc phê duyệt.
     */
    @Transactional
    public AnnouncementResponse approve(Long id, java.util.UUID approvedBy) {
        Announcement announcement = getById(id);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.ANNOUNCEMENT,
                announcement.getStatus().name(),
                AnnouncementStatus.APPROVED.name());
        announcement.setApprovedBy(approvedBy);
        announcement.setApprovedAt(Instant.now());
        announcement.setStatus(AnnouncementStatus.APPROVED);
        announcement.setRejectionReason(null);
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    /**
     * Từ chối thông báo và giữ nguyên nhân từ chối để người tạo biết cần sửa lại.
     */
    @Transactional
    public AnnouncementResponse reject(Long id, String reason) {
        Announcement announcement = getById(id);
        workflowEngine.ensureTransitionAllowed(
                WorkflowEntityType.ANNOUNCEMENT,
                announcement.getStatus().name(),
                AnnouncementStatus.REJECTED.name());
        announcement.setStatus(AnnouncementStatus.REJECTED);
        announcement.setRejectionReason(reason);
        return AnnouncementResponse.of(announcements.save(announcement));
    }

}

