package com.metroticket_api.payment;

/** Kết quả xử lý một giao dịch SePay báo về, dùng cho màn đối soát. */
public enum SepayResult {
    /** Khớp đơn, đã giao vé/vé tháng. */
    MATCHED,
    /** Không tìm thấy mã đơn trong nội dung chuyển khoản. */
    UNMATCHED,
    /** Chuyển thiếu tiền, đơn vẫn chờ. Cần liên hệ khách. */
    AMOUNT_MISMATCH,
    /** Tiền đã vào nhưng không giao được hàng (đơn đã huỷ, trả 2 lần, vé hết hạn...). Kế toán hoàn tiền. */
    NEEDS_REFUND,
    /** Giao dịch tiền ra hoặc khác tài khoản thụ hưởng. */
    IGNORED
}
