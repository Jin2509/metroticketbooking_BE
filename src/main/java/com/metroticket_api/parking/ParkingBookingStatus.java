package com.metroticket_api.parking;

/** PAID là "Chưa gửi", CHECKED_IN là "Đang gửi", COMPLETED là "Đã lấy xe". */
public enum ParkingBookingStatus {
    PENDING_PAYMENT,
    PAID,
    CHECKED_IN,
    COMPLETED,
    CANCELLED
}
