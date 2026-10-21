package com.metroticket_api.security;

import java.util.Collection;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.metroticket_api.rbac.RolePermissionCache;

/**
 * Đổi claim "roles" trong JWT thành authority:
 * roles=["STAFF"] → ROLE_STAFF, TICKET_VIEW_OWN, TICKET_VALIDATE, TICKET_VIEW_ALL
 */
@Component
public class RoleAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final RolePermissionCache cache;

    public RoleAuthoritiesConverter(RolePermissionCache cache) {
        this.cache = cache;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return cache.authoritiesOf(jwt.getClaimAsStringList(TokenService.ROLES_CLAIM));
    }
}
