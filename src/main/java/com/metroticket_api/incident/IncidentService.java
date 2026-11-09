package com.metroticket_api.incident;

import java.io.IOException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.content.ObjectStorage;
import com.metroticket_api.metro.staff.StaffStationService;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.security.CurrentUser;

/** Báo cáo và xử lý sự cố tại ga (UC19): Mới → Đang xử lý → Đã xử lý. */
@Service
public class IncidentService {

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final IncidentRepository incidents;
    private final StationRepository stations;
    private final StaffStationService staffStations;
    private final ObjectProvider<ObjectStorage> storage;
    private final AuditLogService audit;
    private final Clock clock;

    public IncidentService(IncidentRepository incidents, StationRepository stations, StaffStationService staffStations,
            ObjectProvider<ObjectStorage> storage, AuditLogService audit, Clock clock) {
        this.incidents = incidents;
        this.stations = stations;
        this.staffStations = staffStations;
        this.storage = storage;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Incident report(Long stationId, Incident.Type type, Incident.Severity severity, String description, MultipartFile image) {
        Station station = stations.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga"));
        staffStations.requireOnDutyAt(stationId, null);
        String imageKey = image == null || image.isEmpty() ? null : store(image);
        return incidents.save(new Incident(station, CurrentUser.id(), type, severity, description.trim(), imageKey, clock.instant()));
    }

    @Transactional(readOnly = true)
    public Page<Incident> list(Incident.Status status, Pageable pageable) {
        return status == null ? incidents.findNewestFirst(pageable) : incidents.findByStatusNewestFirst(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Incident> mine(Pageable pageable) {
        return incidents.findMine(CurrentUser.id(), pageable);
    }

    @Transactional
    public Incident accept(Long id) {
        Incident incident = require(id);
        if (incident.getStatus() != Incident.Status.NEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sự cố đã được tiếp nhận");
        }
        incident.accept(CurrentUser.id(), clock.instant());
        audit.log("INCIDENT_ACCEPT", "incident", id, "Tiếp nhận sự cố");
        return incident;
    }

    @Transactional
    public Incident resolve(Long id, String resolution) {
        Incident incident = require(id);
        if (incident.getStatus() != Incident.Status.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Chỉ hoàn tất được sự cố đang xử lý");
        }
        incident.resolve(resolution.trim(), clock.instant());
        audit.log("INCIDENT_RESOLVE", "incident", id, resolution.trim());
        return incident;
    }

    /** Ảnh chỉ người báo cáo và người có quyền xử lý sự cố xem được. */
    @Transactional(readOnly = true)
    public ObjectStorage.StoredObject image(Long id) {
        Incident incident = incidents.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sự cố"));
        UUID me = CurrentUser.id();
        if (!me.equals(incident.getReportedBy()) && !CurrentUser.hasAuthority(PermissionCodes.INCIDENT_MANAGE)) {
            throw new AccessDeniedException("Không có quyền xem ảnh sự cố");
        }
        if (incident.getImageKey() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sự cố không có ảnh");
        }
        return requiredStorage().get(incident.getImageKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy ảnh"));
    }

    private String store(MultipartFile image) {
        if (!IMAGE_TYPES.contains(String.valueOf(image.getContentType())) || image.getSize() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ảnh phải là JPEG, PNG hoặc WebP và không quá 5 MB");
        }
        String key = "incidents/" + UUID.randomUUID();
        try {
            requiredStorage().put(key, image.getBytes(), image.getContentType());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không đọc được ảnh đính kèm");
        }
        return key;
    }

    private ObjectStorage requiredStorage() {
        ObjectStorage s = storage.getIfAvailable();
        if (s == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình lưu trữ ảnh");
        }
        return s;
    }

    private Incident require(Long id) {
        return incidents.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy sự cố"));
    }
}
