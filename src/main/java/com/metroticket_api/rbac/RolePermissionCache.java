package com.metroticket_api.rbac;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Cache bất biến: mã vai trò → danh sách authority (ROLE_xxx + các mã quyền).
 *
 * <p>Mỗi request chỉ tra Map trong bộ nhớ, không truy vấn DB. Map được thay thế
 * nguyên khối (copy-on-write) qua biến volatile nên đọc không cần khoá.
 * Nạp lại khi admin đổi quyền, và định kỳ 5 phút để đồng bộ khi chạy nhiều instance.
 */
@Component
public class RolePermissionCache {

    private static final Logger log = LoggerFactory.getLogger(RolePermissionCache.class);
    public static final String ROLE_PREFIX = "ROLE_";

    private final RoleRepository roles;
    private volatile Map<String, List<GrantedAuthority>> authoritiesByRole = Map.of();

    public RolePermissionCache(RoleRepository roles) {
        this.roles = roles;
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${app.rbac.cache-refresh:5m}", initialDelayString = "${app.rbac.cache-refresh:5m}")
    public void reload() {
        Map<String, Set<GrantedAuthority>> building = new HashMap<>();
        for (RolePermissionRow row : roles.findAllRolePermissionRows()) {
            Set<GrantedAuthority> set = building.computeIfAbsent(row.roleCode(), code -> {
                Set<GrantedAuthority> s = new LinkedHashSet<>();
                s.add(new SimpleGrantedAuthority(ROLE_PREFIX + code));
                return s;
            });
            if (row.permissionCode() != null) {
                set.add(new SimpleGrantedAuthority(row.permissionCode()));
            }
        }
        Map<String, List<GrantedAuthority>> snapshot = new HashMap<>(building.size() * 2);
        building.forEach((role, set) -> snapshot.put(role, List.copyOf(set)));
        authoritiesByRole = Map.copyOf(snapshot);
        log.debug("Đã nạp quyền cho {} vai trò", snapshot.size());
    }

    /** Vai trò không tồn tại → danh sách rỗng (không có quyền gì). */
    public Collection<GrantedAuthority> authoritiesOf(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        Map<String, List<GrantedAuthority>> current = authoritiesByRole;
        if (roleCodes.size() == 1) {
            // trường hợp phổ biến nhất: trả thẳng list bất biến, không cấp phát thêm
            return current.getOrDefault(roleCodes.iterator().next(), List.of());
        }
        Set<GrantedAuthority> merged = new LinkedHashSet<>();
        for (String code : roleCodes) {
            merged.addAll(current.getOrDefault(code, List.of()));
        }
        return new ArrayList<>(merged);
    }
}
