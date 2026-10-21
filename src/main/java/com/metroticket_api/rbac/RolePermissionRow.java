package com.metroticket_api.rbac;

/** Một cặp (vai trò, quyền); permissionCode null khi vai trò chưa có quyền nào. */
public record RolePermissionRow(String roleCode, String permissionCode) {
}
