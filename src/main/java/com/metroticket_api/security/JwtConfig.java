package com.metroticket_api.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Hệ thống tự phát hành JWT ký HS256 và tự kiểm tra (oauth2-resource-server).
 */
@Configuration
public class JwtConfig {

    private final SecretKey key;
    private final JwtProps props;

    public JwtConfig(JwtProps props) {
        this.props = props;
        this.key = new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder(AccessTokenDenylist denylist) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Kiểm tra hạn dùng (exp/nbf), issuer và token chưa bị thu hồi (đăng xuất)
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.issuer()),
                notRevoked(denylist)));
        return decoder;
    }

    private static OAuth2TokenValidator<Jwt> notRevoked(AccessTokenDenylist denylist) {
        OAuth2Error revoked = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Token đã bị thu hồi", null);
        return jwt -> denylist.isRevoked(jwt)
                ? OAuth2TokenValidatorResult.failure(revoked)
                : OAuth2TokenValidatorResult.success();
    }
}
