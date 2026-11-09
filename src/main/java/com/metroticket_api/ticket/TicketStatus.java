package com.metroticket_api.ticket;

/**
 * PENDING_PAYMENT → ACTIVE khi tiền về (hoặc CANCELLED khi hết hạn giữ chỗ).
 * ACTIVE: đã thanh toán, đang ở ngoài khu vực trả phí (chưa dùng, hoặc giữa các chuyến).
 * IN_USE: đã quẹt vào, chưa quẹt ra. EXPIRED: hết hạn. REFUNDED: đã hoàn tiền.
 */
public enum TicketStatus {
    /** Đã đặt, chờ tiền chuyển khoản về (SePay). Chưa có QR dùng được. */
    PENDING_PAYMENT,
    ACTIVE,
    IN_USE,
    /** Vé lượt đã đi xong. */
    USED,
    EXPIRED,
    REFUNDED,
    /** Đơn thanh toán hết hạn hoặc khách huỷ trước khi trả tiền. */
    CANCELLED;

    /** Chỉ vé đã thanh toán và còn dùng được mới được trả mã QR cho app. */
    public boolean hasUsableQr() {
        return this == ACTIVE || this == IN_USE;
    }
}
