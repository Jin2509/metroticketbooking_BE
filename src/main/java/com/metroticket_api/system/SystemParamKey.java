package com.metroticket_api.system;

/**
 * Danh mục tham số hệ thống (UC09). Giá trị mặc định chỉ dùng khi bảng system_param thiếu dòng.
 * Ngoài các khoá cố định dưới đây còn có khoá động FARE_PER_KM_&lt;mã loại vé&gt; cho từng loại vé lượt.
 */
public enum SystemParamKey {
    CPOINT_PER_CHECKIN(50, 0, 1_000),
    CPOINT_MAX_CHECKINS_PER_DAY(4, 1, 20),
    CPOINT_MAX_DISCOUNT_VND(20_000, 0, 1_000_000),
    PAYMENT_QR_TTL_MINUTES(10, 5, 15),
    PARKING_OVERTIME_FEE_PER_HOUR(5_000, 0, 1_000_000),
    /** Giá vé lượt được làm tròn LÊN tới bội số của giá trị này. */
    FARE_ROUNDING_VND(1_000, 1, 100_000),
    /** Giá vé lượt thấp nhất, áp dụng cho quãng đường ngắn. */
    FARE_MIN_VND(7_000, 0, 1_000_000);

    /** Tiền tố khoá đơn giá/km của từng loại vé lượt: FARE_PER_KM_&lt;mã loại vé&gt;. */
    public static final String FARE_PER_KM_PREFIX = "FARE_PER_KM_";
    public static final int FARE_PER_KM_DEFAULT = 1_000;
    public static final int FARE_PER_KM_MIN = 1;
    public static final int FARE_PER_KM_MAX = 1_000_000;

    public static String farePerKmKey(String productCode) {
        return FARE_PER_KM_PREFIX + productCode;
    }

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
