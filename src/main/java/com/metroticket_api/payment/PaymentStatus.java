package com.metroticket_api.payment;

public enum PaymentStatus {
    SUCCEEDED,
    /** Đã chấp nhận hoàn tiền, chờ kế toán chuyển khoản trả lại (chuyển khoản không hoàn tự động được). */
    REFUND_PENDING,
    REFUNDED
}
