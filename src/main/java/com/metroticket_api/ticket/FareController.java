package com.metroticket_api.ticket;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Fares", description = "Giá vé lượt giữa hai ga")
public class FareController {

    public record BandRequest(@NotNull @PositiveOrZero BigDecimal minDistanceKm, @Positive BigDecimal maxDistanceKm,
            @NotNull @Positive Integer priceVnd) {
    }

    public record ReplaceBandsRequest(@NotEmpty List<@Valid BandRequest> bands) {
    }

    public record BandView(BigDecimal minDistanceKm, BigDecimal maxDistanceKm, int priceVnd) {
        static BandView of(FareBand b) {
            return new BandView(b.getMinDistanceKm(), b.getMaxDistanceKm(), b.getPriceVnd());
        }
    }

    private final FareService fares;

    public FareController(FareService fares) {
        this.fares = fares;
    }

    @GetMapping("/public/fares")
    public List<BandView> bands() {
        return fares.list().stream().map(BandView::of).toList();
    }

    @GetMapping("/public/fares/quote")
    public FareService.Quote quote(@RequestParam Long fromStationId, @RequestParam Long toStationId) {
        if (fromStationId.equals(toStationId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ga đi và ga đến phải khác nhau");
        }
        return fares.quote(fromStationId, toStationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy lộ trình phù hợp"));
    }

    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @PutMapping("/fare-bands")
    public List<BandView> replace(@Valid @RequestBody ReplaceBandsRequest request) {
        return fares.replace(request.bands()).stream().map(BandView::of).toList();
    }
}
