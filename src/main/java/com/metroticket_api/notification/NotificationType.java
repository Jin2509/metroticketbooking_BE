package com.metroticket_api.notification;

public enum NotificationType {
    /** Thông báo chung của hệ thống (chào mừng, đổi vai trò...). */
    SYSTEM,
    /** Liên quan an toàn tài khoản (đổi mật khẩu, đổi email...). */
    SECURITY,
    /** Admin gửi. */
    ANNOUNCEMENT
}
