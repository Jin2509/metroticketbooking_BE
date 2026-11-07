package com.metroticket_api.metro.staff;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.metro.common.TransferStatus;
import com.metroticket_api.metro.station.Station;
import com.metroticket_api.metro.station.StationRepository;
import com.metroticket_api.rbac.PermissionCodes;
import com.metroticket_api.rbac.Role;
import com.metroticket_api.rbac.RolePermissionCache;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.user.User;
import com.metroticket_api.user.UserRepository;
import com.metroticket_api.user.UserStatus;

/**
 * Phân công nhân viên tại ga. Nhân viên chỉ soát vé / trông xe được ở ga mình được phân công;
 * người có quyền quản lý tương ứng (STATION_MANAGE / PARKING_MANAGE) thao tác được ở mọi ga.
 */
@Service
public class StaffStationService {

    /** Quyền tác nghiệp tại ga: user phải có ít nhất một quyền này mới được phân công. */
    private static final Set<String> STATION_DUTY_PERMISSIONS = Set.of(PermissionCodes.TICKET_VALIDATE,
            PermissionCodes.PARKING_OPERATE);

    public record StaffAssignmentResponse(Long id, UUID userId, String email, String fullName, Long stationId,
            String stationCode, String stationName, Instant assignedAt) {
    }

    private final StaffStationRepository assignments;
    private final StationRepository stations;
    private final UserRepository users;
    private final RolePermissionCache rolePermissions;
    private final Clock clock;

    public StaffStationService(StaffStationRepository assignments, StationRepository stations, UserRepository users,
            RolePermissionCache rolePermissions, Clock clock) {
        this.assignments = assignments;
        this.stations = stations;
        this.users = users;
        this.rolePermissions = rolePermissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<StaffAssignmentResponse> listByStation(Long stationId) {
        requireStation(stationId);
        List<StaffStation> rows = assignments.findByStation(stationId, TransferStatus.ACTIVE);
        Map<UUID, User> byId = users.findAllById(rows.stream().map(StaffStation::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream().map(row -> toResponse(row, byId.get(row.getUserId()))).toList();
    }

    @Transactional(readOnly = true)
    public List<StaffAssignmentResponse> myStations() {
        UUID me = CurrentUser.id();
        User user = users.findById(me).orElse(null);
        return assignments.findByUser(me, TransferStatus.ACTIVE).stream().map(row -> toResponse(row, user)).toList();
    }

    @Transactional
    public StaffAssignmentResponse assign(Long stationId, UUID userId) {
        Station station = requireStation(stationId);
        User user = users.findWithRolesById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy người dùng"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Tài khoản chưa kích hoạt hoặc đang bị khoá");
        }
        List<String> roleCodes = user.getRoles().stream().map(Role::getCode).toList();
        boolean hasDuty = rolePermissions.authoritiesOf(roleCodes).stream()
                .anyMatch(a -> STATION_DUTY_PERMISSIONS.contains(a.getAuthority()));
        if (!hasDuty) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Người dùng không có quyền soát vé hoặc trông xe nên không thể phân công tại ga");
        }
        if (assignments.existsByUserIdAndStationIdAndStatus(userId, stationId, TransferStatus.ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nhân viên đã được phân công tại ga này");
        }
        StaffStation saved = assignments.save(new StaffStation(userId, station, clock.instant()));
        return toResponse(saved, user);
    }

    @Transactional
    public void unassign(Long stationId, UUID userId) {
        StaffStation row = assignments.findByUserIdAndStationIdAndStatus(userId, stationId, TransferStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nhân viên không được phân công tại ga này"));
        row.deactivate();
    }

    /**
     * Chặn thao tác tại ga nếu người dùng hiện tại không được phân công ở đó
     * và cũng không có quyền quản lý {@code managerPermission}.
     */
    @Transactional(readOnly = true)
    public void requireOnDutyAt(Long stationId, String managerPermission) {
        if (managerPermission != null && CurrentUser.hasAuthority(managerPermission)) {
            return;
        }
        UUID me = CurrentUser.id();
        if (!assignments.existsByUserIdAndStationIdAndStatus(me, stationId, TransferStatus.ACTIVE)) {
            throw new AccessDeniedException("Bạn không được phân công làm việc tại ga này");
        }
    }

    private Station requireStation(Long stationId) {
        return stations.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy nhà ga"));
    }

    private static StaffAssignmentResponse toResponse(StaffStation row, User user) {
        return new StaffAssignmentResponse(row.getId(), row.getUserId(), user == null ? null : user.getEmail(),
                user == null ? null : user.getFullName(), row.getStation().getId(), row.getStation().getCode(),
                row.getStation().getNameStation(), row.getAssignedAt());
    }
}
