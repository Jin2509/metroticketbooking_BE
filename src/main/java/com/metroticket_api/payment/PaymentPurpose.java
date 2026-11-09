package com.metroticket_api.payment;

public enum PaymentPurpose {
    TICKET,
    /** Thu bù khi vé lượt ra quá ga đã mua. */
    TICKET_FARE_ADJUSTMENT,
    PARKING_SESSION,
    PARKING_MONTHLY_PASS,
    PARKING_BOOKING
}
