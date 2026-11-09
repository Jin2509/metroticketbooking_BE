package com.metroticket_api.payment;

/** Kết quả xử lý một giao dịch SePay báo về, dùng cho màn đối soát. */
public enum SepayResult {
    /** Khớp đơn, đã giao vé/vé tháng. */
    MATCHED,
    /** Không tìm thấy mã đơn trong nội dung chuyển khoản. */
    UNMATCHED,
    /** Chuyển sai số tiền (thiếu hoặc thừa), không tạo vé, đơn vẫn chờ tới hạn. */
    AMOUNT_MISMATCH,
    /** Tiền đã vào nhưng đơn không còn nhận thanh toán (hết hạn, đã huỷ, trả 2 lần). Kế toán xử lý khi đối soát. */
    NEEDS_REFUND,
    /** Giao dịch tiền ra hoặc khác tài khoản thụ hưởng. */
    IGNORED
}
