package com.metroticket_api.rbac;

/** Các vai trò hệ thống được seed sẵn bằng Flyway. Admin có thể tạo thêm vai trò khác. */
public final class RoleCodes {

    public static final String PASSENGER = "PASSENGER";
    public static final String STAFF = "STAFF";
    public static final String ADMIN = "ADMIN";

    /** Vai trò mặc định khi người dùng tự đăng ký. */
    public static final String DEFAULT = PASSENGER;

    private RoleCodes() {
    }
}
