package com.metroticket_api.rbac;

/**
 * Mã quyền dùng trong @PreAuthorize, ví dụ: @PreAuthorize("hasAuthority('USER_MANAGE')").
 * Mỗi hằng số ở đây phải có một dòng tương ứng trong bảng permission
 * (RbacSeedConsistencyTest kiểm tra điều này).
 */
public final class PermissionCodes {

    public static final String TICKET_BUY = "TICKET_BUY";
    public static final String TICKET_VIEW_OWN = "TICKET_VIEW_OWN";
    public static final String TICKET_VALIDATE = "TICKET_VALIDATE";
    public static final String TICKET_VIEW_ALL = "TICKET_VIEW_ALL";
    public static final String STATION_MANAGE = "STATION_MANAGE";
    public static final String REPORT_VIEW = "REPORT_VIEW";
    public static final String USER_VIEW = "USER_VIEW";
    public static final String USER_MANAGE = "USER_MANAGE";
    public static final String RBAC_MANAGE = "RBAC_MANAGE";

    private PermissionCodes() {
    }
}
