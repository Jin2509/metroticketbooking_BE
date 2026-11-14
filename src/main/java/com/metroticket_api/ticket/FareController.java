package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.system.SystemParamController.ParamView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fares", description = "Giá vé lượt theo km giữa hai ga")
public class FareController {

    /** effectiveFrom bỏ trống = áp dụng ngay. */
    public record UpdateFarePerKmRequest(@NotNull Integer farePerKmVnd, Instant effectiveFrom) {
    }

    private final FareService fares;

    public FareController(FareService fares) {
        this.fares = fares;
    }

    @Operation(summary = "Đơn giá/km của các loại vé lượt đang bán", description = "Kèm bước làm tròn và giá tối thiểu.")
    @GetMapping("/public/fares")
    public List<FareService.FareConfig> fares() {
        return fares.list();
    }

    @Operation(summary = "Báo giá vé lượt", description = "productCode bỏ trống = loại vé lượt mặc định (SINGLE).")
    @GetMapping("/public/fares/quote")
    public FareService.Quote quote(@RequestParam Long fromStationId, @RequestParam Long toStationId,
            @RequestParam(required = false) String productCode) {
        if (fromStationId.equals(toStationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga đi và ga đến phải khác nhau");
        }
        return fares.quote(productCode, fromStationId, toStationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lộ trình phù hợp"));
    }

    @Operation(summary = "Đổi đơn giá/km của một loại vé lượt",
            description = "Lưu thành phiên bản mới của tham số FARE_PER_KM_<mã>; vé đã bán giữ giá cũ.")
    @PreAuthorize("hasAuthority('FARE_MANAGE') or hasAuthority('SYSTEM_PARAM_MANAGE')")
    @PutMapping("/fares/{productCode}/per-km")
    public ParamView updatePerKm(@PathVariable String productCode, @Valid @RequestBody UpdateFarePerKmRequest request) {
        return ParamView.of(fares.updateFarePerKm(productCode, request.farePerKmVnd(), request.effectiveFrom()));
    }
}
