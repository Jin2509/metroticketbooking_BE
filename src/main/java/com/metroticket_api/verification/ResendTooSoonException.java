package com.metroticket_api.verification;

import java.time.Duration;

public class ResendTooSoonException extends RuntimeException {

    private final Duration retryAfter;

    public ResendTooSoonException(Duration retryAfter) {
        super("Vui lòng chờ " + retryAfter.toSeconds() + " giây trước khi yêu cầu mã mới");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
