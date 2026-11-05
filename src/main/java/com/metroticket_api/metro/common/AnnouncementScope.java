package com.metroticket_api.metro.common;

/**
 * Quy mô phát sóng của thông báo Metro.
 *
 * <p>Announcement là nguồn tin tức và nội dung đã được duyệt, còn việc gửi tới từng user sẽ được xử lý
 * ở layer notification. Với nhóm tin tức chung, mặc định scope = ALL.</p>
 */
public enum AnnouncementScope {
    ALL,
    LINE,
    STATION,
    ROLE
}
