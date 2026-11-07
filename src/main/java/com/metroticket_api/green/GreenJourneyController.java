package com.metroticket_api.green;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.green.GreenJourneyDtos.LeaderboardResponse;
import com.metroticket_api.green.GreenJourneyDtos.PublicStatsResponse;
import com.metroticket_api.green.GreenJourneyDtos.SettingsResponse;
import com.metroticket_api.green.GreenJourneyDtos.SummaryResponse;
import com.metroticket_api.green.GreenJourneyDtos.TripResponse;
import com.metroticket_api.green.GreenJourneyDtos.UpdateSettingsRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Green journey", description = "Hành trình xanh: CO2 tiết kiệm, điểm xanh, cấp độ, bảng xếp hạng")
public class GreenJourneyController {

    private final GreenJourneyService service;

    public GreenJourneyController(GreenJourneyService service) {
        this.service = service;
    }

    @Operation(summary = "Tổng quan Hành trình xanh của tôi")
    @GetMapping("/me/green-journey")
    public SummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/me/green-journey/trips")
    public Page<TripResponse> trips(@PageableDefault(size = 20) Pageable pageable) {
        return service.myTrips(pageable);
    }

    @Operation(summary = "Bảng xếp hạng tháng", description = "Cần đăng nhập; chỉ hiện tên viết tắt.")
    @GetMapping("/green-journey/leaderboard")
    public LeaderboardResponse leaderboard(@RequestParam(required = false) String month,
            @RequestParam(defaultValue = "10") int limit) {
        return service.leaderboard(month, limit);
    }

    @Operation(summary = "Thống kê toàn hệ thống (công khai)")
    @GetMapping("/public/green-journey/stats")
    public PublicStatsResponse publicStats() {
        return service.publicStats();
    }

    @PreAuthorize("hasAnyAuthority('GREEN_JOURNEY_MANAGE', 'REPORT_VIEW')")
    @GetMapping("/admin/green-journey/settings")
    public SettingsResponse settings() {
        return service.settings();
    }

    @PreAuthorize("hasAuthority('GREEN_JOURNEY_MANAGE')")
    @PutMapping("/admin/green-journey/settings")
    public SettingsResponse updateSettings(@Valid @RequestBody UpdateSettingsRequest request) {
        return service.updateSettings(request);
    }
}
