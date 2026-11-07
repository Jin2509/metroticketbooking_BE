package com.metroticket_api.parking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Công thức phí gửi xe theo lượt (giờ Việt Nam):
 *
 * <pre>
 * phí = giá lượt ngày + (số lần qua 0h) × giá qua đêm
 * </pre>
 *
 * Gửi và lấy trong cùng ngày → 1 lượt ngày. Qua 1 đêm → lượt ngày + 1 lượt đêm, v.v.
 */
public final class ParkingFeeCalculator {

    public static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private ParkingFeeCalculator() {
    }

    public static long nightsCrossed(Instant checkIn, Instant checkOut) {
        if (!checkOut.isAfter(checkIn)) {
            return 0;
        }
        LocalDate in = LocalDate.ofInstant(checkIn, VIETNAM);
        LocalDate out = LocalDate.ofInstant(checkOut, VIETNAM);
        return Math.max(0, ChronoUnit.DAYS.between(in, out));
    }

    public static int fee(int dayPrice, int overnightPrice, Instant checkIn, Instant checkOut) {
        long nights = nightsCrossed(checkIn, checkOut);
        return Math.toIntExact(dayPrice + nights * overnightPrice);
    }

    /** Ưu đãi Park &amp; Ride: giảm theo %, làm tròn xuống tới đồng. */
    public static int discount(int fee, int percent) {
        int p = Math.clamp(percent, 0, 100);
        return (int) ((long) fee * p / 100);
    }

    /** Bãi có đang trong giờ mở cửa (hỗ trợ khung qua đêm, ví dụ 05:00–01:00). */
    public static boolean isOpen(LocalTime open, LocalTime close, Instant now) {
        LocalTime t = LocalTime.ofInstant(now, VIETNAM);
        if (close.isAfter(open)) {
            return !t.isBefore(open) && t.isBefore(close);
        }
        return !t.isBefore(open) || t.isBefore(close);
    }
}
