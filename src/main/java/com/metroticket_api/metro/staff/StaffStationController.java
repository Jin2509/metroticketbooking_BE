package com.metroticket_api.metro.staff;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.metro.staff.StaffStationService.StaffAssignmentResponse;

@RestController
@RequestMapping("/api/v1")
public class StaffStationController {

    public record AssignStaffRequest(@NotNull UUID userId) {
    }

    private final StaffStationService service;

    public StaffStationController(StaffStationService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    @GetMapping("/stations/{stationId}/staff")
    public List<StaffAssignmentResponse> list(@PathVariable Long stationId) {
        return service.listByStation(stationId);
    }

    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    @PostMapping("/stations/{stationId}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffAssignmentResponse assign(@PathVariable Long stationId, @Valid @RequestBody AssignStaffRequest request) {
        return service.assign(stationId, request.userId());
    }

    @PreAuthorize("hasAuthority('STATION_MANAGE')")
    @DeleteMapping("/stations/{stationId}/staff/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable Long stationId, @PathVariable UUID userId) {
        service.unassign(stationId, userId);
    }

    /** Nhân viên xem mình được phân công ở những ga nào. */
    @GetMapping("/me/stations")
    public List<StaffAssignmentResponse> myStations() {
        return service.myStations();
    }
}
