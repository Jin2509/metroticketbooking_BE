package com.metroticket_api.metro.announcement;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.announcement.AnnouncementDtos.AnnouncementResponse;
import com.metroticket_api.metro.announcement.AnnouncementDtos.CreateAnnouncementRequest;
import com.metroticket_api.metro.announcement.AnnouncementDtos.UpdateAnnouncementRequest;
import com.metroticket_api.metro.common.AnnouncementScope;
import com.metroticket_api.metro.common.AnnouncementStatus;
import com.metroticket_api.metro.line.MetroLineRepository;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.workflow.WorkflowEngineService;
import com.metroticket_api.workflow.WorkflowEntityType;

/**
 * Thông báo vận hành, quy trình maker-checker giống cấu hình vận hành.
 *
 * <ul>
 *   <li>Người soạn (ANNOUNCEMENT_MANAGE) và người duyệt (ANNOUNCEMENT_APPROVE) lấy từ JWT, người duyệt phải khác người soạn.</li>
 *   <li>Người không có quyền quản lý chỉ thấy thông báo đã duyệt, đang trong thời gian hiệu lực, và với scope ROLE
 *       thì chỉ khi có đúng vai trò đó. Bản nháp/bị từ chối không lộ ra ngoài.</li>
 *   <li>Bản REJECTED sửa lại sẽ quay về DRAFT để gửi duyệt lại; bản APPROVED không sửa được.</li>
 * </ul>
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository announcements;
    private final MetroLineRepository lines;
    private final StationRepository stations;
    private final WorkflowEngineService workflowEngine;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.metroticket_api.notification.campaign.CampaignService campaigns;

    public AnnouncementService(AnnouncementRepository announcements, MetroLineRepository lines,
            StationRepository stations, WorkflowEngineService workflowEngine, Clock clock) {
        this.announcements = announcements;
        this.lines = lines;
        this.stations = stations;
        this.workflowEngine = workflowEngine;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> list(AnnouncementStatus status) {
        if (!canManage()) {
            return visible(announcements.findAllByStatusOrderByStartsAtDesc(AnnouncementStatus.APPROVED));
        }
        List<Announcement> result = status == null
                ? announcements.findAll(Sort.by(Sort.Direction.DESC, "startsAt"))
                : announcements.findAllByStatusOrderByStartsAtDesc(status);
        return result.stream().map(AnnouncementResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listByLine(Long lineId) {
        List<Announcement> found = announcements.findByLineIdOrderByStartsAtDesc(lineId);
        return canManage() ? found.stream().map(AnnouncementResponse::of).toList() : visible(found);
    }

    @Transactional(readOnly = true)
    public List<AnnouncementResponse> listByStation(Long stationId) {
        List<Announcement> found = announcements.findByStationIdOrderByStartsAtDesc(stationId);
        return canManage() ? found.stream().map(AnnouncementResponse::of).toList() : visible(found);
    }

    @Transactional(readOnly = true)
    public Announcement getById(Long id) {
        return announcements.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo"));
    }

    @Transactional(readOnly = true)
    public AnnouncementResponse get(Long id) {
        Announcement a = getById(id);
        if (!canManage() && !isVisibleToCurrentUser(a, clock.instant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo");
        }
        return AnnouncementResponse.of(a);
    }

    @Transactional
    public AnnouncementResponse create(CreateAnnouncementRequest request) {
        Announcement announcement = new Announcement(request.title().trim(), request.content(), request.severity(),
                request.startsAt() == null ? clock.instant() : request.startsAt(), request.endsAt(),
                AnnouncementStatus.DRAFT, request.scope() == null ? AnnouncementScope.ALL : request.scope(),
                blankToNull(request.targetRole()), CurrentUser.optionalId().orElse(null));
        if (request.lineId() != null) {
            announcement.setLine(lines.findById(request.lineId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến")));
        }
        if (request.stationId() != null) {
            announcement.setStation(stations.findById(request.stationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy ga")));
        }
        validate(announcement);
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    @Transactional
    public AnnouncementResponse update(Long id, UpdateAnnouncementRequest request) {
        Announcement announcement = getById(id);
        if (announcement.getStatus() == AnnouncementStatus.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Không thể sửa thông báo đang chờ phê duyệt");
        }
        if (announcement.getStatus() == AnnouncementStatus.APPROVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Thông báo đã được duyệt; hãy tạo thông báo mới để thay đổi nội dung");
        }
        if (announcement.getStatus() == AnnouncementStatus.REJECTED) {
            workflowEngine.ensureTransitionAllowed(WorkflowEntityType.ANNOUNCEMENT,
                    AnnouncementStatus.REJECTED.name(), AnnouncementStatus.DRAFT.name());
            announcement.setStatus(AnnouncementStatus.DRAFT);
            announcement.setRejectionReason(null);
            announcement.setSubmittedAt(null);
        }
        if (request.title() != null && !request.title().isBlank()) announcement.setTitle(request.title().trim());
        if (request.content() != null && !request.content().isBlank()) announcement.setContent(request.content());
        if (request.severity() != null) announcement.setSeverity(request.severity());
        if (request.startsAt() != null) announcement.setStartsAt(request.startsAt());
        if (request.endsAt() != null) announcement.setEndsAt(request.endsAt());
        if (request.scope() != null) announcement.setScope(request.scope());
        if (request.targetRole() != null) announcement.setTargetRole(blankToNull(request.targetRole()));
        if (request.lineId() != null) {
            announcement.setLine(lines.findById(request.lineId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy tuyến")));
        }
        if (request.stationId() != null) {
            announcement.setStation(stations.findById(request.stationId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không tìm thấy ga")));
        }
        validate(announcement);
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    @Transactional
    public AnnouncementResponse submit(Long id) {
        Announcement announcement = getById(id);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.ANNOUNCEMENT,
                announcement.getStatus().name(), AnnouncementStatus.PENDING_APPROVAL.name());
        validate(announcement);
        announcement.setStatus(AnnouncementStatus.PENDING_APPROVAL);
        announcement.setSubmittedAt(clock.instant());
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    @Transactional
    public AnnouncementResponse approve(Long id) {
        Announcement announcement = getById(id);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.ANNOUNCEMENT,
                announcement.getStatus().name(), AnnouncementStatus.APPROVED.name());
        UUID reviewer = requireDifferentReviewer(announcement);
        announcement.setApprovedBy(reviewer);
        announcement.setApprovedAt(clock.instant());
        announcement.setStatus(AnnouncementStatus.APPROVED);
        announcement.setRejectionReason(null);
        Announcement saved = announcements.save(announcement);
        pushToPassengers(saved);
        return AnnouncementResponse.of(saved);
    }

    @Transactional
    public AnnouncementResponse reject(Long id, String reason) {
        Announcement announcement = getById(id);
        workflowEngine.ensureTransitionAllowed(WorkflowEntityType.ANNOUNCEMENT,
                announcement.getStatus().name(), AnnouncementStatus.REJECTED.name());
        announcement.setApprovedBy(requireDifferentReviewer(announcement));
        announcement.setStatus(AnnouncementStatus.REJECTED);
        announcement.setRejectionReason(reason.trim());
        return AnnouncementResponse.of(announcements.save(announcement));
    }

    /**
     * Thông báo vận hành vừa duyệt → tự tạo chiến dịch đẩy tới đúng đối tượng (gửi lúc startsAt). CRITICAL dùng push
     * ưu tiên cao. Đã hết hiệu lực thì không đẩy.
     */
    private void pushToPassengers(Announcement a) {
        if (campaigns == null || (a.getEndsAt() != null && !a.getEndsAt().isAfter(clock.instant()))) {
            return;
        }
        var target = switch (a.getScope()) {
            case ALL -> java.util.Map.entry(com.metroticket_api.notification.campaign.AudienceType.ALL, "");
            case ROLE -> java.util.Map.entry(com.metroticket_api.notification.campaign.AudienceType.ROLE,
                    a.getTargetRole().toUpperCase());
            case LINE -> java.util.Map.entry(com.metroticket_api.notification.campaign.AudienceType.LINE,
                    a.getLine().getId().toString());
            case STATION -> java.util.Map.entry(com.metroticket_api.notification.campaign.AudienceType.STATION,
                    a.getStation().getId().toString());
        };
        campaigns.scheduleFromAnnouncement(a.getId(), a.getTitle(), a.getContent(),
                a.getSeverity() == com.metroticket_api.metro.common.AnnouncementSeverity.CRITICAL, target.getKey(),
                target.getValue().isEmpty() ? null : target.getValue(), a.getStartsAt());
    }

    private void validate(Announcement a) {
        if (a.getEndsAt() != null && !a.getEndsAt().isAfter(a.getStartsAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endsAt phải sau startsAt");
        }
        switch (a.getScope()) {
            case ROLE -> {
                if (a.getTargetRole() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Với scope ROLE phải cung cấp targetRole");
                }
            }
            case LINE -> {
                if (a.getLine() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Với scope LINE phải chọn tuyến");
                }
            }
            case STATION -> {
                if (a.getStation() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Với scope STATION phải chọn ga");
                }
            }
            case ALL -> { }
        }
    }

    private static UUID requireDifferentReviewer(Announcement a) {
        UUID reviewer = CurrentUser.optionalId().orElse(null);
        if (reviewer != null && Objects.equals(reviewer, a.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Người soạn thông báo không được tự duyệt/từ chối");
        }
        return reviewer;
    }

    private List<AnnouncementResponse> visible(List<Announcement> candidates) {
        Instant now = clock.instant();
        return candidates.stream().filter(a -> isVisibleToCurrentUser(a, now)).map(AnnouncementResponse::of).toList();
    }

    private static boolean isVisibleToCurrentUser(Announcement a, Instant now) {
        if (a.getStatus() != AnnouncementStatus.APPROVED || a.getStartsAt().isAfter(now)
                || (a.getEndsAt() != null && !a.getEndsAt().isAfter(now))) {
            return false;
        }
        if (a.getScope() != AnnouncementScope.ROLE) {
            return true;
        }
        return currentRoles().contains(a.getTargetRole().toUpperCase());
    }

    private static Set<String> currentRoles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Set.of();
        }
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith(RolePermissionCache.ROLE_PREFIX))
                .map(a -> a.substring(RolePermissionCache.ROLE_PREFIX.length()))
                .collect(Collectors.toSet());
    }

    private static boolean canManage() {
        return CurrentUser.hasAuthority(PermissionCodes.ANNOUNCEMENT_MANAGE)
                || CurrentUser.hasAuthority(PermissionCodes.ANNOUNCEMENT_APPROVE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
