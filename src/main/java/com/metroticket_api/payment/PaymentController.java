package com.metroticket_api.payment;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.payment.PaymentOrderService.PaymentInstruction;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Payments", description = "Đơn thanh toán QR và đối soát")
public class PaymentController {

    public record OrderView(UUID id, String orderCode, UUID userId, PaymentPurpose purpose, String referenceId,
            int amountVnd, PaymentOrderStatus status, Instant expiresAt, Instant paidAt, Integer paidAmountVnd,
            Long sepayTransactionId, Instant createdAt) {
        static OrderView of(PaymentOrder o) {
            return new OrderView(o.getId(), o.getOrderCode(), o.getUserId(), o.getPurpose(), o.getReferenceId(),
                    o.getAmountVnd(), o.getStatus(), o.getExpiresAt(), o.getPaidAt(), o.getPaidAmountVnd(),
                    o.getSepayTransactionId(), o.getCreatedAt());
        }
    }

    public record SepayTransactionView(Long id, String gateway, String transactionDate, String accountNumber,
            String code, String content, String transferType, long transferAmount, String referenceCode,
            UUID orderId, SepayResult result, String note, Instant receivedAt) {
        static SepayTransactionView of(SepayTransaction t) {
            return new SepayTransactionView(t.getId(), t.getGateway(), t.getTransactionDate(), t.getAccountNumber(),
                    t.getCode(), t.getContent(), t.getTransferType(), t.getTransferAmount(), t.getReferenceCode(),
                    t.getOrderId(), t.getResult(), t.getNote(), t.getReceivedAt());
        }
    }

    public record PaymentView(UUID id, UUID userId, PaymentPurpose purpose, String referenceId, int amountVnd,
            PaymentMethod method, PaymentStatus status, String providerRef, Instant createdAt, Instant refundedAt) {
        static PaymentView of(PaymentTransaction p) {
            return new PaymentView(p.getId(), p.getUserId(), p.getPurpose(), p.getReferenceId(), p.getAmountVnd(),
                    p.getMethod(), p.getStatus(), p.getProviderRef(), p.getCreatedAt(), p.getRefundedAt());
        }
    }

    private final PaymentOrderService orders;
    private final PaymentOrderRepository orderRepository;
    private final SepayTransactionRepository sepayTransactions;
    private final PaymentTransactionRepository paymentTransactions;
    private final PaymentService payments;

    public PaymentController(PaymentOrderService orders, PaymentOrderRepository orderRepository,
            SepayTransactionRepository sepayTransactions, PaymentTransactionRepository paymentTransactions,
            PaymentService payments) {
        this.orders = orders;
        this.orderRepository = orderRepository;
        this.sepayTransactions = sepayTransactions;
        this.paymentTransactions = paymentTransactions;
        this.payments = payments;
    }

    @Operation(summary = "Trạng thái đơn thanh toán của tôi", description = "App gọi định kỳ (2–3 giây) sau khi hiện QR cho tới khi status = PAID.")
    @GetMapping("/me/payment-orders/{orderCode}")
    public PaymentInstruction myOrder(@PathVariable String orderCode) {
        return orders.getMine(orderCode);
    }

    @Operation(summary = "Huỷ đơn chưa thanh toán", description = "Vé/vé tháng đang giữ chỗ chuyển CANCELLED.")
    @PostMapping("/me/payment-orders/{orderCode}/cancel")
    @PreAuthorize("isAuthenticated()")
    public PaymentInstruction cancel(@PathVariable String orderCode) {
        return orders.cancelMine(orderCode);
    }

    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @GetMapping("/admin/payment-orders")
    public Page<OrderView> adminOrders(@RequestParam(required = false) PaymentOrderStatus status,
            @PageableDefault(size = 50) Pageable pageable) {
        return (status == null ? orderRepository.findAllByOrderByCreatedAtDesc(pageable)
                : orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable)).map(OrderView::of);
    }

    @Operation(summary = "Giao dịch SePay báo về", description = "Lọc result=UNMATCHED / AMOUNT_MISMATCH / NEEDS_REFUND để đối soát.")
    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @GetMapping("/admin/sepay-transactions")
    public Page<SepayTransactionView> sepayTransactions(@RequestParam(required = false) SepayResult result,
            @PageableDefault(size = 50) Pageable pageable) {
        return (result == null ? sepayTransactions.findAllByOrderByReceivedAtDesc(pageable)
                : sepayTransactions.findByResultOrderByReceivedAtDesc(result, pageable)).map(SepayTransactionView::of);
    }

    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @GetMapping("/admin/payments")
    public Page<PaymentView> paymentsByStatus(@RequestParam(defaultValue = "REFUND_PENDING") PaymentStatus status,
            @PageableDefault(size = 50) Pageable pageable) {
        return paymentTransactions.findByStatusOrderByCreatedAtDesc(status, pageable).map(PaymentView::of);
    }

    @Operation(summary = "Xác nhận đã chuyển trả tiền cho khách")
    @PreAuthorize("hasAuthority('PAYMENT_MANAGE')")
    @PostMapping("/admin/payments/{paymentId}/refunded")
    public PaymentView confirmRefunded(@PathVariable UUID paymentId) {
        return PaymentView.of(payments.confirmRefunded(paymentId));
    }
}
