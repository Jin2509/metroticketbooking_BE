package com.metroticket_api.student;

public enum StudentVerificationStatus {
    /** Đã nộp, chờ AI đọc. */
    PENDING,
    /** Worker đang gọi AI. */
    PROCESSING,
    /** AI không đủ chắc chắn / lệch thông tin / AI lỗi → chờ ADMIN hoặc MANAGER duyệt tay. */
    NEEDS_REVIEW,
    APPROVED,
    REJECTED,
    /** Sinh viên tự huỷ khi chưa có kết quả. */
    CANCELLED,
    /** Đã duyệt nhưng bị thu hồi (phát hiện gian lận, thôi học...). */
    REVOKED;

    public boolean open() {
        return this == PENDING || this == PROCESSING || this == NEEDS_REVIEW;
    }
}
