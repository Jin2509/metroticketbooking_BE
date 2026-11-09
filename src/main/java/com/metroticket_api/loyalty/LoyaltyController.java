package com.metroticket_api.loyalty;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.security.CurrentUser;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Loyalty", description = "UC15: điểm CPoint và hạng thành viên")
public class LoyaltyController {

    public record TransactionView(Long id, LoyaltyTransaction.Type type, int points, int balanceAfter, String description,
            Instant createdAt) {
        static TransactionView of(LoyaltyTransaction t) {
            return new TransactionView(t.getId(), t.getType(), t.getPoints(), t.getBalanceAfter(), t.getDescription(),
                    t.getCreatedAt());
        }
    }

    public record UpdateTierRequest(@NotBlank @Size(max = 50) String name, @NotNull @PositiveOrZero Integer minPoints) {
    }

    private final LoyaltyService loyalty;

    public LoyaltyController(LoyaltyService loyalty) {
        this.loyalty = loyalty;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me/loyalty")
    public LoyaltyService.Summary summary() {
        return loyalty.summary(CurrentUser.id());
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me/loyalty/transactions")
    public Page<TransactionView> history(@PageableDefault(size = 20) Pageable pageable) {
        return loyalty.history(CurrentUser.id(), pageable).map(TransactionView::of);
    }

    @PreAuthorize("hasAuthority('SYSTEM_PARAM_MANAGE')")
    @GetMapping("/admin/membership-tiers")
    public List<LoyaltyService.TierView> tiers() {
        return loyalty.listTiers();
    }

    @PreAuthorize("hasAuthority('SYSTEM_PARAM_MANAGE')")
    @PutMapping("/admin/membership-tiers/{code}")
    public LoyaltyService.TierView updateTier(@PathVariable String code, @Valid @RequestBody UpdateTierRequest request) {
        return loyalty.updateTier(code, request.name(), request.minPoints());
    }
}
