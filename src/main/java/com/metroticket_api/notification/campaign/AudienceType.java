package com.metroticket_api.notification.campaign;

/** Đối tượng nhận chiến dịch. Chỉ tài khoản ACTIVE. */
public enum AudienceType {
    /** Mọi tài khoản. */
    ALL,
    /** Theo mã vai trò (audienceValue = PASSENGER, STAFF...). */
    ROLE,
    /** Người đã đi qua ga thuộc tuyến trong 30 ngày (audienceValue = lineId). */
    LINE,
    /** Người đã đi qua ga trong 30 ngày (audienceValue = stationId). */
    STATION,
    /** Sinh viên đang được xác thực. */
    STUDENTS,
    /** Người đang có vé còn hạn. */
    ACTIVE_PASS_HOLDERS,
    /** Danh sách người dùng cụ thể (userIds). */
    USERS
}
