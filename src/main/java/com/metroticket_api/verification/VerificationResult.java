package com.metroticket_api.verification;

public enum VerificationResult {
    SUCCESS,
    INVALID_CODE,
    EXPIRED,
    TOO_MANY_ATTEMPTS,
    NOT_FOUND
}
