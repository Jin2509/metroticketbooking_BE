package com.metroticket_api.ticket;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Đơn vị thời lượng của vé. Hạn dùng tính từ lần quẹt vào đầu tiên:
 * <ul>
 *   <li>DAY / WEEK: đủ 24 giờ × số ngày (vé 1 ngày quẹt lúc 08:00 dùng tới 08:00 hôm sau).</li>
 *   <li>MONTH: theo lịch tháng giờ Việt Nam (quẹt 31/01 → hết hạn 28/02 hoặc 29/02, cùng giờ).</li>
 * </ul>
 */
public enum DurationUnit {
    DAY(1, 6),
    WEEK(1, 3),
    MONTH(1, 12);

    static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Vé tháng mua nhiều kỳ: tối đa 12 tháng một lần. */
    public static final int MAX_PERIODS = 12;

    private final int minValue;
    private final int maxValue;

    DurationUnit(int minValue, int maxValue) {
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    public boolean acceptsValue(int value) {
        return value >= minValue && value <= maxValue;
    }

    public int maxValue() {
        return maxValue;
    }

    /** Chỉ vé tháng được mua nhiều kỳ một lần. */
    public boolean allowsMultiplePeriods() {
        return this == MONTH;
    }

    public Instant expiry(Instant activatedAt, int totalUnits) {
        return switch (this) {
            case DAY -> activatedAt.plus(Duration.ofDays(totalUnits));
            case WEEK -> activatedAt.plus(Duration.ofDays(7L * totalUnits));
            case MONTH -> activatedAt.atZone(VIETNAM).plusMonths(totalUnits).toInstant();
        };
    }
}
