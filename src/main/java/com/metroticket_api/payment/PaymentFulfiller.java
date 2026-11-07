package com.metroticket_api.payment;

import java.time.Instant;

/**
 * Giao hàng sau khi thanh toán (vé, vé tháng gửi xe). Mỗi {@link PaymentPurpose} có một bean.
 * Chạy trong transaction của webhook / job hết hạn.
 */
public interface PaymentFulfiller {

    PaymentPurpose purpose();

    /**
     * Kích hoạt hàng hoá của đơn. {@code late = true}: đơn đã hết hạn giữ chỗ nhưng tiền vẫn về, cố gắng kích hoạt lại
     * nếu còn dùng được. Trả về null nếu thành công, ngược lại là lý do không giao được (→ cần hoàn tiền).
     */
    String fulfil(PaymentOrder order, boolean late, Instant now);

    /** Đơn hết hạn / bị huỷ: huỷ hàng hoá đang giữ chỗ. */
    void release(PaymentOrder order);
}
