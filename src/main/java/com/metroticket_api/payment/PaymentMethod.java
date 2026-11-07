package com.metroticket_api.payment;

public enum PaymentMethod {
    /** Tiền mặt tại quầy/cổng (nhân viên thu). */
    CASH,
    /** Quẹt thẻ tại máy POS ở quầy/cổng. Mua online: chỉ khi bật app.payment.mock-enabled (môi trường dev). */
    CARD,
    E_WALLET,
    BANK_TRANSFER,
    /** Chuyển khoản qua mã VietQR, SePay báo về bằng webhook. Phương thức thật duy nhất cho mua online. */
    SEPAY_QR;

    public boolean isOnline() {
        return this != CASH;
    }

    /** Phương thức chỉ được giả lập "thành công ngay" (không có cổng thanh toán thật phía sau). */
    public boolean isMockOnline() {
        return this == CARD || this == E_WALLET || this == BANK_TRANSFER;
    }
}
