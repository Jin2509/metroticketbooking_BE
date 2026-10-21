package com.metroticket_api.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.metroticket_api.rbac.Role;
import com.metroticket_api.user.User;

@Service
public class TokenService {

    public static final String ROLES_CLAIM = "roles";

    private final JwtEncoder encoder;
    private final JwtProps props;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, JwtProps props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    /** user.getRoles() phải đã được nạp (dùng UserRepository.findWithRolesByEmail). */
    public IssuedToken issueAccessToken(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(props.accessTokenTtl());
        List<String> roles = user.getRoles().stream().map(Role::getCode).sorted().toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("email", user.getEmail())
                // Token chỉ chứa vai trò (nhỏ gọn); quyền được tra từ RolePermissionCache mỗi request,
                // nên admin đổi quyền của vai trò thì có hiệu lực ngay, không cần cấp lại token.
                .claim(ROLES_CLAIM, roles)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
