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
    public static final String NOTIFICATION_MANAGE = "NOTIFICATION_MANAGE";
    public static final String TRAIN_MANAGE = "TRAIN_MANAGE";
    public static final String OPERATION_MANAGE = "OPERATION_MANAGE";
    public static final String OPERATION_APPROVE = "OPERATION_APPROVE";
    public static final String FARE_MANAGE = "FARE_MANAGE";
    public static final String PARKING_OPERATE = "PARKING_OPERATE";
    public static final String PARKING_MANAGE = "PARKING_MANAGE";
    public static final String PARKING_BUY = "PARKING_BUY";
    public static final String ANNOUNCEMENT_MANAGE = "ANNOUNCEMENT_MANAGE";
    public static final String ANNOUNCEMENT_APPROVE = "ANNOUNCEMENT_APPROVE";
    public static final String CONTENT_MANAGE = "CONTENT_MANAGE";
    public static final String PAYMENT_MANAGE = "PAYMENT_MANAGE";
    public static final String STUDENT_VERIFY = "STUDENT_VERIFY";
    public static final String STUDENT_POLICY_MANAGE = "STUDENT_POLICY_MANAGE";
    public static final String GREEN_JOURNEY_MANAGE = "GREEN_JOURNEY_MANAGE";
    public static final String SYSTEM_PARAM_MANAGE = "SYSTEM_PARAM_MANAGE";
    public static final String FARE_APPROVE = "FARE_APPROVE";
    public static final String INCIDENT_REPORT = "INCIDENT_REPORT";
    public static final String INCIDENT_MANAGE = "INCIDENT_MANAGE";
    public static final String REPORT_EXPORT = "REPORT_EXPORT";
    public static final String REPORT_TRAFFIC_VIEW = "REPORT_TRAFFIC_VIEW";
    public static final String CHATBOT_USE = "CHATBOT_USE";
    public static final String CHATBOT_MANAGE = "CHATBOT_MANAGE";
    public static final String AUDIT_VIEW = "AUDIT_VIEW";

    private PermissionCodes() {
    }
}
