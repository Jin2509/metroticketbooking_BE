package com.metroticket_api.ticket;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.notification.NotificationDispatcher;
import com.metroticket_api.notification.NotificationType;
import com.metroticket_api.security.CurrentUser;
import com.metroticket_api.ticket.TicketPrice.Status;
import com.metroticket_api.ticket.TicketProductAudit.Action;

/**
 * Lập và duyệt bảng giá vé (UC08). Mỗi loại vé chỉ có một bảng giá đang chờ áp dụng. Người duyệt phải khác người lập.
 * Bảng giá đã duyệt được áp dụng khi tới thời gian có hiệu lực; vé đã bán giữ nguyên giá cũ.
 */
@Service
public class TicketPriceService {

    private static final List<Status> OPEN = List.of(Status.PENDING_APPROVAL, Status.APPROVED);

    private final TicketPriceRepository prices;
    private final TicketProductRepository products;
    private final TicketProductAuditRepository productAudits;
    private final AuditLogService audit;
    private final NotificationDispatcher notifier;
    private final Clock clock;

    public TicketPriceService(TicketPriceRepository prices, TicketProductRepository products,
            TicketProductAuditRepository productAudits, AuditLogService audit, NotificationDispatcher notifier, Clock clock) {
        this.prices = prices;
        this.products = products;
        this.productAudits = productAudits;
        this.audit = audit;
        this.notifier = notifier;
        this.clock = clock;
    }

    @Transactional
    public TicketPrice submit(Long productId, int priceVnd, Instant effectiveFrom) {
        TicketProduct product = products.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy loại vé"));
        if (product.isSingleJourney()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Giá vé lượt được quản lý theo bảng giá quãng đường");
        }
        Instant now = clock.instant();
        if (effectiveFrom.isBefore(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Thời gian áp dụng không được ở quá khứ");
        }
        if (prices.existsByProductIdAndStatusIn(productId, OPEN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Loại vé đang có bảng giá chờ áp dụng");
        }
        TicketPrice price = prices.saveAndFlush(new TicketPrice(product, priceVnd, effectiveFrom, CurrentUser.id(), now));
        audit.log("TICKET_PRICE_SUBMIT", "periodic_ticket_price", price.getId(),
                product.getCode() + ": " + product.getPriceVnd() + " → " + priceVnd + " từ " + effectiveFrom);
        return price;
    }

    @Transactional(readOnly = true)
    public Page<TicketPrice> list(Status status, Pageable pageable) {
        return status == null ? prices.findAllNewestFirst(pageable) : prices.findByStatusNewestFirst(status, pageable);
    }

    @Transactional
    public TicketPrice approve(Long id) {
        TicketPrice price = requirePending(id);
        Instant now = clock.instant();
        price.approve(CurrentUser.id(), now);
        audit.log("TICKET_PRICE_APPROVE", "periodic_ticket_price", id, price.getProduct().getCode());
        if (!price.getEffectiveFrom().isAfter(now)) {
            apply(price, now);
        }
        return price;
    }

    @Transactional
    public TicketPrice reject(Long id, String reason) {
        TicketPrice price = requirePending(id);
        price.reject(CurrentUser.id(), reason.trim(), clock.instant());
        audit.log("TICKET_PRICE_REJECT", "periodic_ticket_price", id, reason.trim());
        if (price.getCreatedBy() != null) {
            notifier.dispatch(NotificationDispatcher.Request.of(price.getCreatedBy(), NotificationType.SYSTEM,
                    "Bảng giá bị từ chối", "Bảng giá " + price.getProduct().getName() + " bị từ chối: " + reason.trim()));
        }
        return price;
    }

    @Scheduled(fixedDelayString = "${app.ticket.price-job-interval:1m}", initialDelayString = "${app.ticket.price-job-interval:1m}")
    @Transactional
    public void applyDuePrices() {
        Instant now = clock.instant();
        prices.findDueForUpdate(Status.APPROVED, now).forEach(price -> apply(price, now));
    }

    private void apply(TicketPrice price, Instant now) {
        TicketProduct product = price.getProduct();
        UUID approver = price.getApprovedBy();
        String change = "giá: " + product.getPriceVnd() + " → " + price.getPriceVnd() + " (bảng giá #" + price.getId() + ")";
        product.setPriceVnd(price.getPriceVnd());
        product.setUpdatedBy(approver);
        price.markApplied(now);
        productAudits.save(new TicketProductAudit(product.getId(), Action.UPDATED, approver, change, now));
    }

    private TicketPrice requirePending(Long id) {
        TicketPrice price = prices.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bảng giá"));
        if (price.getStatus() != Status.PENDING_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bảng giá không ở trạng thái chờ duyệt");
        }
        if (CurrentUser.id().equals(price.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Người duyệt phải khác người lập bảng giá");
        }
        return price;
    }
}
