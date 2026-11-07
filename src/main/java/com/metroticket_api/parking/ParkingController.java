package com.metroticket_api.parking;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import com.metroticket_api.parking.ParkingDtos.BuyMonthlyPassRequest;
import com.metroticket_api.parking.ParkingDtos.CheckInRequest;
import com.metroticket_api.parking.ParkingDtos.CheckOutRequest;
import com.metroticket_api.parking.ParkingDtos.CreateLotRequest;
import com.metroticket_api.parking.ParkingDtos.LotResponse;
import com.metroticket_api.parking.ParkingDtos.MonthlyPassResponse;
import com.metroticket_api.parking.ParkingDtos.SessionResponse;
import com.metroticket_api.parking.ParkingDtos.UpdateLotRequest;
import com.metroticket_api.parking.ParkingDtos.UpsertRateRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Parking", description = "Gửi xe tại ga: bãi xe, chỗ trống, xe vào/ra, vé tháng")
public class ParkingController {

    private final ParkingService service;

    public ParkingController(ParkingService service) {
        this.service = service;
    }

    @Operation(summary = "Bãi xe và chỗ trống (công khai)", description = "Lọc theo stationId. Mỗi loại xe có sức chứa, đang gửi, còn trống, giá.")
    @GetMapping("/public/parking-lots")
    public List<LotResponse> lots(@RequestParam(required = false) Long stationId) {
        return service.listLots(stationId);
    }

    @GetMapping("/public/parking-lots/{lotId}")
    public LotResponse lot(@PathVariable Long lotId) {
        return service.getLot(lotId);
    }

    @PreAuthorize("hasAuthority('PARKING_MANAGE')")
    @PostMapping("/parking-lots")
    @ResponseStatus(HttpStatus.CREATED)
    public LotResponse createLot(@Valid @RequestBody CreateLotRequest request) {
        return service.createLot(request);
    }

    @PreAuthorize("hasAuthority('PARKING_MANAGE')")
    @PutMapping("/parking-lots/{lotId}")
    public LotResponse updateLot(@PathVariable Long lotId, @Valid @RequestBody UpdateLotRequest request) {
        return service.updateLot(lotId, request);
    }

    @Operation(summary = "Thêm/sửa sức chứa và giá theo loại xe")
    @PreAuthorize("hasAuthority('PARKING_MANAGE')")
    @PutMapping("/parking-lots/{lotId}/rates/{vehicleType}")
    public LotResponse upsertRate(@PathVariable Long lotId, @PathVariable String vehicleType,
            @Valid @RequestBody UpsertRateRequest request) {
        return service.upsertRate(lotId, ParkingService.parseVehicleType(vehicleType), request);
    }

    @PreAuthorize("hasAnyAuthority('PARKING_OPERATE', 'PARKING_MANAGE')")
    @GetMapping("/parking-lots/{lotId}/sessions")
    public Page<SessionResponse> lotSessions(@PathVariable Long lotId,
            @RequestParam(required = false) ParkingSessionStatus status,
            @PageableDefault(size = 50) Pageable pageable) {
        return service.lotSessions(lotId, status, pageable);
    }

    @Operation(summary = "Nhận xe vào bãi", description = "Nhân viên phải được phân công tại ga của bãi (hoặc có PARKING_MANAGE).")
    @PreAuthorize("hasAnyAuthority('PARKING_OPERATE', 'PARKING_MANAGE')")
    @PostMapping("/parking/check-in")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse checkIn(@Valid @RequestBody CheckInRequest request) {
        return service.checkIn(request);
    }

    @Operation(summary = "Xem lượt gửi + phí tạm tính")
    @PreAuthorize("hasAnyAuthority('PARKING_OPERATE', 'PARKING_MANAGE')")
    @GetMapping("/parking/sessions/{sessionCode}")
    public SessionResponse session(@PathVariable String sessionCode) {
        return service.getSession(sessionCode);
    }

    @Operation(summary = "Trả xe", description = "Đối chiếu biển số, tính phí, áp ưu đãi Park & Ride nếu có vé Metro hợp lệ.")
    @PreAuthorize("hasAnyAuthority('PARKING_OPERATE', 'PARKING_MANAGE')")
    @PostMapping("/parking/check-out")
    public SessionResponse checkOut(@Valid @RequestBody CheckOutRequest request) {
        return service.checkOut(request);
    }

    @PreAuthorize("hasAuthority('PARKING_BUY')")
    @PostMapping("/parking/monthly-passes")
    @ResponseStatus(HttpStatus.CREATED)
    public MonthlyPassResponse buyMonthlyPass(@Valid @RequestBody BuyMonthlyPassRequest request) {
        return service.buyMonthlyPass(request);
    }

    @GetMapping("/me/parking-passes")
    public List<MonthlyPassResponse> myPasses() {
        return service.myPasses();
    }

    @GetMapping("/me/parking-sessions")
    public Page<SessionResponse> mySessions(@PageableDefault(size = 20) Pageable pageable) {
        return service.mySessions(pageable);
    }
}
