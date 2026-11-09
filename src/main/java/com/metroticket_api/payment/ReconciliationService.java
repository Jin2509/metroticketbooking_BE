package com.metroticket_api.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.metroticket_api.audit.AuditLogService;
import com.metroticket_api.security.CurrentUser;

/**
 * Đối soát theo kỳ (UC18 A3): so khớp dữ liệu cổng thanh toán (bản ghi SePay gốc nhận qua webhook) với giao dịch hệ
 * thống theo mã giao dịch và số tiền. Chỉ đánh dấu đã đối soát trên bản ghi SePay, không sửa giao dịch gốc.
 */
@Service
public class ReconciliationService {

    static final Duration MAX_PERIOD = Duration.ofDays(366);

    public record Mismatch(Long sepayId, UUID paymentId, long gatewayAmountVnd, Integer systemAmountVnd, SepayResult result,
            String reason) {
    }

    public record Result(Instant from, Instant to, int gatewayCount, int matchedCount, int mismatchedCount,
            long gatewayTotalVnd, long systemTotalVnd, List<Mismatch> mismatches) {
    }

    private final SepayTransactionRepository sepay;
    private final PaymentTransactionRepository payments;
    private final AuditLogService audit;
    private final Clock clock;

    public ReconciliationService(SepayTransactionRepository sepay, PaymentTransactionRepository payments,
            AuditLogService audit, Clock clock) {
        this.sepay = sepay;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Result reconcile(Instant from, Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kỳ đối soát không hợp lệ (tối đa 1 năm)");
        }
        List<SepayTransaction> gateway = sepay.findIncomingInPeriod(from, to);
        List<PaymentTransaction> system = payments.findByMethodAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                PaymentMethod.SEPAY_QR, from, to);
        Result result = compare(from, to, gateway, system);

        Instant now = clock.instant();
        UUID actor = CurrentUser.id();
        gateway.forEach(tx -> tx.markReconciled(actor, now));
        audit.log("RECONCILIATION_RUN", "sepay_transaction", null, "Kỳ " + from + " đến " + to + ": " + result.matchedCount()
                + " khớp, " + result.mismatchedCount() + " lệch");
        return result;
    }

    static Result compare(Instant from, Instant to, List<SepayTransaction> gateway, List<PaymentTransaction> system) {
        Map<Long, PaymentTransaction> bySepayId = new HashMap<>();
        for (PaymentTransaction p : system) {
            Long id = sepayIdOf(p.getProviderRef());
            if (id != null) {
                bySepayId.put(id, p);
            }
        }
        List<Mismatch> mismatches = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int matched = 0;
        long gatewayTotal = 0;
        for (SepayTransaction tx : gateway) {
            seen.add(tx.getId());
            gatewayTotal += tx.getTransferAmount();
            PaymentTransaction payment = bySepayId.get(tx.getId());
            if (tx.getResult() != SepayResult.MATCHED) {
                mismatches.add(new Mismatch(tx.getId(), null, tx.getTransferAmount(), null, tx.getResult(), tx.getNote()));
            } else if (payment == null) {
                mismatches.add(new Mismatch(tx.getId(), null, tx.getTransferAmount(), null, tx.getResult(),
                        "Cổng báo thành công nhưng hệ thống không có giao dịch"));
            } else if (payment.getAmountVnd() != tx.getTransferAmount()) {
                mismatches.add(new Mismatch(tx.getId(), payment.getId(), tx.getTransferAmount(), payment.getAmountVnd(),
                        tx.getResult(), "Số tiền lệch"));
            } else {
                matched++;
            }
        }
        long systemTotal = 0;
        for (PaymentTransaction p : system) {
            systemTotal += p.getAmountVnd();
            Long id = sepayIdOf(p.getProviderRef());
            if (id == null || !seen.contains(id)) {
                mismatches.add(new Mismatch(id, p.getId(), 0, p.getAmountVnd(), null, "Hệ thống có giao dịch nhưng không có dữ liệu cổng trong kỳ"));
            }
        }
        return new Result(from, to, gateway.size(), matched, mismatches.size(), gatewayTotal, systemTotal, mismatches);
    }

    /** providerRef có dạng SEPAY-{id} hoặc SEPAY-{id}/{mã ngân hàng}. */
    static Long sepayIdOf(String providerRef) {
        if (providerRef == null || !providerRef.startsWith("SEPAY-")) {
            return null;
        }
        String rest = providerRef.substring("SEPAY-".length());
        int slash = rest.indexOf('/');
        try {
            return Long.valueOf(slash < 0 ? rest : rest.substring(0, slash));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Transactional
    public SepayTransaction note(Long sepayId, String note) {
        SepayTransaction tx = sepay.findById(sepayId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch"));
        tx.addReconcileNote(note.trim());
        audit.log("RECONCILIATION_NOTE", "sepay_transaction", sepayId, note.trim());
        return tx;
    }
}
