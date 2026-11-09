package com.metroticket_api.system;

/** Danh mục tham số hệ thống (UC09). Giá trị mặc định chỉ dùng khi bảng system_param thiếu dòng. */
public enum SystemParamKey {
    CPOINT_PER_CHECKIN(50, 0, 1_000),
    CPOINT_MAX_CHECKINS_PER_DAY(4, 1, 20),
    CPOINT_MAX_DISCOUNT_VND(20_000, 0, 1_000_000),
    PAYMENT_QR_TTL_MINUTES(10, 5, 15),
    PARKING_OVERTIME_FEE_PER_HOUR(5_000, 0, 1_000_000);

    private final int defaultValue;
    private final int min;
    private final int max;

    SystemParamKey(int defaultValue, int min, int max) {
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
    }

    public int defaultValue() { return defaultValue; }
    public int min() { return min; }
    public int max() { return max; }
}
