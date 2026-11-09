package com.metroticket_api.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.payment.PaymentController.PaymentView;
import com.metroticket_api.security.CurrentUser;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Transactions", description = "UC18: tra cứu và đối soát giao dịch")
public class TransactionController {

    private static final java.time.ZoneId VIETNAM = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

    public record TransactionPage(Page<PaymentView> transactions, long totalAmountVnd) {
    }

    public record ReconcileRequest(@NotNull Instant from, @NotNull Instant to) {
    }

    public record ReconcileNoteRequest(@NotBlank @Size(max = 2000) String note) {
    }

    public record ReconcileNoteView(Long sepayId, boolean reconciled, Instant reconciledAt, String note) {
    }

    private final PaymentTransactionRepository payments;
    private final ReconciliationService reconciliation;
    private final AuditLogService audit;
    private final EntityManager em;

    public TransactionController(PaymentTransactionRepository payments, ReconciliationService reconciliation,
            AuditLogService audit, EntityManager em) {
        this.payments = payments;
        this.reconciliation = reconciliation;
        this.audit = audit;
        this.em = em;
    }

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me/transactions")
    @Transactional(readOnly = true)
    public TransactionPage mine(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) PaymentPurpose purpose, @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return search(CurrentUser.id(), from, to, purpose, status, null, pageable);
    }

    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @GetMapping("/admin/transactions")
    @Transactional
    public TransactionPage all(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) PaymentPurpose purpose, @RequestParam(required = false) PaymentStatus status,
            @RequestParam(required = false) String providerRef, @PageableDefault(size = 50) Pageable pageable) {
        audit.log("TRANSACTION_SEARCH", "payment_transaction", null, "Tra cứu giao dịch toàn hệ thống");
        return search(null, from, to, purpose, status, providerRef, pageable);
    }

    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @PostMapping("/admin/reconciliations")
    public ReconciliationService.Result reconcile(@Valid @RequestBody ReconcileRequest request) {
        return reconciliation.reconcile(request.from(), request.to());
    }

    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @PutMapping("/admin/sepay-transactions/{id}/note")
    public ReconcileNoteView note(@PathVariable Long id, @Valid @RequestBody ReconcileNoteRequest request) {
        SepayTransaction tx = reconciliation.note(id, request.note());
        return new ReconcileNoteView(tx.getId(), tx.isReconciled(), tx.getReconciledAt(), tx.getReconcileNote());
    }

    private TransactionPage search(UUID userId, LocalDate from, LocalDate to, PaymentPurpose purpose, PaymentStatus status,
            String providerRef, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Khoảng thời gian không hợp lệ");
        }
        Specification<PaymentTransaction> spec = (root, query, cb) -> cb.and(predicates(root, cb, userId, from, to, purpose, status, providerRef));
        Pageable newestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<PaymentView> page = payments.findAll(spec, newestFirst).map(PaymentView::of);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> sum = cb.createQuery(Long.class);
        Root<PaymentTransaction> root = sum.from(PaymentTransaction.class);
        sum.select(cb.coalesce(cb.sumAsLong(root.get("amountVnd")), 0L))
                .where(predicates(root, cb, userId, from, to, purpose, status, providerRef));
        return new TransactionPage(page, em.createQuery(sum).getSingleResult());
    }

    private static Predicate[] predicates(Root<PaymentTransaction> root, CriteriaBuilder cb, UUID userId, LocalDate from,
            LocalDate to, PaymentPurpose purpose, PaymentStatus status, String providerRef) {
        List<Predicate> list = new ArrayList<>();
        if (userId != null) list.add(cb.equal(root.get("userId"), userId));
        if (from != null) list.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(VIETNAM).toInstant()));
        if (to != null) list.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(VIETNAM).toInstant()));
        if (purpose != null) list.add(cb.equal(root.get("purpose"), purpose));
        if (status != null) list.add(cb.equal(root.get("status"), status));
        if (providerRef != null && !providerRef.isBlank()) list.add(cb.like(root.get("providerRef"), "%" + providerRef.trim() + "%"));
        return list.toArray(Predicate[]::new);
    }
}
