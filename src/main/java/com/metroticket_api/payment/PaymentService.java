package com.metroticket_api.payment;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ghi nhận thanh toán. Chưa tích hợp cổng thanh toán thật: giao dịch online được coi là thành công ngay
 * (provider_ref dạng MOCK-...). Khi tích hợp VNPay/MoMo chỉ cần thay phần charge/refund ở đây.
 * Chạy trong transaction của nghiệp vụ gọi tới để vé/lượt gửi xe và giao dịch cùng thành công hoặc cùng huỷ.
 */
@Service
public class PaymentService {

    private final PaymentTransactionRepository payments;
    private final Clock clock;
    private final org.springframework.beans.factory.ObjectProvider<com.metroticket_api.notification.NotificationDispatcher> notifier;

    public PaymentService(PaymentTransactionRepository payments, Clock clock,
            org.springframework.beans.factory.ObjectProvider<com.metroticket_api.notification.NotificationDispatcher> notifier) {
        this.payments = payments;
        this.clock = clock;
        this.notifier = notifier;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentTransaction charge(UUID userId, PaymentPurpose purpose, String referenceId, int amountVnd,
            PaymentMethod method) {
        if (method == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phải chọn phương thức thanh toán");
        }
        if (method == PaymentMethod.SEPAY_QR) {
            // Tiền chuyển khoản chỉ được ghi nhận khi SePay báo về (PaymentOrderService), không ghi "đã thu" trước
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "SEPAY_QR không dùng để thu tại chỗ; chọn CASH hoặc CARD");
        }
        if (amountVnd < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Số tiền thanh toán không hợp lệ");
        }
        String providerRef = method == PaymentMethod.CASH ? null
                : "MOCK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        return payments.save(new PaymentTransaction(userId, purpose, referenceId, amountVnd, method, providerRef,
                clock.instant()));
    }

    /** Ghi nhận tiền chuyển khoản đã về qua SePay (gọi từ webhook). */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentTransaction recordSepay(UUID userId, PaymentPurpose purpose, String referenceId, int amountVnd,
            long sepayId, String bankReference) {
        String ref = "SEPAY-" + sepayId + (bankReference == null || bankReference.isBlank() ? "" : "/" + bankReference);
        return payments.save(new PaymentTransaction(userId, purpose, referenceId, amountVnd, PaymentMethod.SEPAY_QR,
                ref.length() > 100 ? ref.substring(0, 100) : ref, clock.instant()));
    }

    /**
     * Hoàn các giao dịch thành công của một tham chiếu; trả về tổng tiền. Giao dịch giả lập/thẻ được coi là hoàn ngay;
     * chuyển khoản (SePay) không hoàn tự động được nên chuyển REFUND_PENDING để kế toán chuyển trả rồi xác nhận.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RefundSummary refundAll(PaymentPurpose purpose, String referenceId) {
        List<PaymentTransaction> succeeded = payments.findByPurposeAndReferenceIdAndStatus(purpose, referenceId,
                PaymentStatus.SUCCEEDED);
        int total = 0;
        boolean pending = false;
        for (PaymentTransaction payment : succeeded) {
            if (payment.getMethod() == PaymentMethod.SEPAY_QR || payment.getMethod() == PaymentMethod.CASH) {
                payment.markRefundPending();
                pending = true;
            } else {
                payment.markRefunded(clock.instant());
            }
            total += payment.getAmountVnd();
        }
        return new RefundSummary(total, pending);
    }

    public record RefundSummary(int amountVnd, boolean awaitingManualTransfer) {
    }

    /** Kế toán xác nhận đã chuyển trả tiền cho khách. */
    @Transactional
    public PaymentTransaction confirmRefunded(UUID paymentId) {
        PaymentTransaction payment = payments.findById(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy giao dịch"));
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Giao dịch không ở trạng thái chờ hoàn tiền");
        }
        payment.markRefunded(clock.instant());
        if (payment.getUserId() != null) {
            notifier.ifAvailable(n -> n.dispatch(com.metroticket_api.notification.NotificationDispatcher.Request
                    .of(payment.getUserId(), com.metroticket_api.notification.NotificationType.PAYMENT,
                            "Đã hoàn tiền", "Đã chuyển trả " + payment.getAmountVnd() + " đ về tài khoản của bạn.")
                    .dedupe("PAYMENT_REFUNDED:" + payment.getId()).withEmail()));
        }
        return payment;
    }
}
