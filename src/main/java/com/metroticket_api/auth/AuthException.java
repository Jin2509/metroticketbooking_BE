package com.metroticket_api.auth;

import org.springframework.http.HttpStatus;

import com.metroticket_api.verification.VerificationResult;

public class AuthException extends RuntimeException {

    private final HttpStatus status;

    public AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    /** Đổi kết quả kiểm tra mã OTP (khác SUCCESS) thành lỗi trả cho client. */
    public static AuthException of(VerificationResult result) {
        return switch (result) {
            case EXPIRED -> new AuthException(HttpStatus.BAD_REQUEST, "Mã đã hết hạn, vui lòng yêu cầu mã mới");
            case TOO_MANY_ATTEMPTS -> new AuthException(HttpStatus.TOO_MANY_REQUESTS, "Nhập sai quá nhiều lần, vui lòng yêu cầu mã mới");
            case INVALID_CODE, NOT_FOUND -> new AuthException(HttpStatus.BAD_REQUEST, "Mã xác thực không đúng");
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS không phải lỗi");
        };
    }

    public HttpStatus getStatus() {
        return status;
    }
}
