package com.metroticket_api.auth;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.metroticket_api.auth.AuthDtos.EmailRequest;
import com.metroticket_api.auth.AuthDtos.LoginRequest;
import com.metroticket_api.auth.AuthDtos.LogoutRequest;
import com.metroticket_api.auth.AuthDtos.RefreshRequest;
import com.metroticket_api.auth.AuthDtos.RegisterRequest;
import com.metroticket_api.auth.AuthDtos.ResetPasswordRequest;
import com.metroticket_api.auth.AuthDtos.TokenResponse;
import com.metroticket_api.auth.AuthDtos.VerifyEmailRequest;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req.email(), req.password(), req.fullName());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
        authService.verifyEmail(req.email(), req.code());
    }

    @PostMapping("/resend-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendCode(@Valid @RequestBody EmailRequest req) {
        authService.resendRegisterCode(req.email());
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.email(), req.password());
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    /** Header Authorization là tuỳ chọn: nếu gửi kèm thì access token cũng bị thu hồi ngay. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody LogoutRequest req,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String accessToken = authorization != null && authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? authorization.substring(BEARER_PREFIX.length()).trim()
                : null;
        authService.logout(req.refreshToken(), accessToken);
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody EmailRequest req) {
        authService.forgotPassword(req.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req.email(), req.code(), req.newPassword());
    }
}
