package com.metroticket_api.ticket;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Ticket prices", description = "UC08: lập và duyệt bảng giá vé")
public class TicketPriceController {

    public record SubmitPriceRequest(@NotNull @Positive @Max(100_000_000) Integer priceVnd, @NotNull Instant effectiveFrom) {
    }

    public record RejectPriceRequest(@NotBlank @Size(max = 1000) String reason) {
    }

    public record PriceView(Long id, Long productId, String productCode, int priceVnd, Instant effectiveFrom,
            TicketPrice.Status status, UUID createdBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
            String rejectionReason, Instant appliedAt) {
        static PriceView of(TicketPrice p) {
            return new PriceView(p.getId(), p.getProduct().getId(), p.getProduct().getCode(), p.getPriceVnd(),
                    p.getEffectiveFrom(), p.getStatus(), p.getCreatedBy(), p.getSubmittedAt(), p.getApprovedBy(),
                    p.getApprovedAt(), p.getRejectionReason(), p.getAppliedAt());
        }
    }

    private final TicketPriceService service;

    public TicketPriceController(TicketPriceService service) {
        this.service = service;
    }

    @PreAuthorize("hasAuthority('FARE_MANAGE')")
    @PostMapping("/ticket-products/{productId}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceView submit(@PathVariable Long productId, @Valid @RequestBody SubmitPriceRequest request) {
        return PriceView.of(service.submit(productId, request.priceVnd(), request.effectiveFrom()));
    }

    @PreAuthorize("hasAnyAuthority('FARE_MANAGE', 'FARE_APPROVE')")
    @GetMapping("/ticket-prices")
    public Page<PriceView> list(@RequestParam(required = false) TicketPrice.Status status,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(status, pageable).map(PriceView::of);
    }

    @PreAuthorize("hasAuthority('FARE_APPROVE')")
    @PostMapping("/ticket-prices/{id}/approve")
    public PriceView approve(@PathVariable Long id) {
        return PriceView.of(service.approve(id));
    }

    @PreAuthorize("hasAuthority('FARE_APPROVE')")
    @PostMapping("/ticket-prices/{id}/reject")
    public PriceView reject(@PathVariable Long id, @Valid @RequestBody RejectPriceRequest request) {
        return PriceView.of(service.reject(id, request.reason()));
    }
}
