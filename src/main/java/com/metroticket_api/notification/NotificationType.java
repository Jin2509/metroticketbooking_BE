package com.metroticket_api.notification;

public enum NotificationType {
    /** Thông báo chung của hệ thống (chào mừng, đổi vai trò...). */
    SYSTEM,
    /** Liên quan an toàn tài khoản (đổi mật khẩu, đổi email...). Luôn gửi, không tắt được. */
    SECURITY,
    /** Admin gửi / thông báo vận hành. */
    ANNOUNCEMENT,
    /** Vé: sắp hết hạn, sắp hết hạn kích hoạt... */
    TICKET,
    /** Thanh toán, hoàn tiền. */
    PAYMENT,
    /** Gửi xe, vé tháng gửi xe. */
    PARKING,
    /** Xác thực sinh viên. */
    STUDENT,
    /** Hành trình xanh: lên cấp... */
    GREEN_JOURNEY,
    /** Khuyến mãi: người dùng có thể tắt. */
    PROMOTION;

    /** Nhắc nhở tự động (người dùng có thể tắt). */
    public boolean isReminder() {
        return this == TICKET || this == PARKING || this == GREEN_JOURNEY;
    }
}
