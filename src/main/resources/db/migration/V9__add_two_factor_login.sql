-- Xác nhận 2 lớp khi đăng nhập qua mã gửi email, người dùng tự bật trong hồ sơ.
ALTER TABLE app_user
    ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE email_verification_challenge
    DROP CONSTRAINT email_verification_challenge_purpose_check;

ALTER TABLE email_verification_challenge
    ADD CONSTRAINT email_verification_challenge_purpose_check
    CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD', 'CHANGE_EMAIL', 'LOGIN_2FA'));

-- Chứng minh đã qua bước mật khẩu: chỉ lưu hash, NULL với các mục đích khác.
ALTER TABLE email_verification_challenge
    ADD COLUMN login_ticket_hash VARCHAR(64);

CREATE INDEX idx_evc_login_ticket ON email_verification_challenge (login_ticket_hash)
    WHERE login_ticket_hash IS NOT NULL;

ALTER TABLE auth_event
    DROP CONSTRAINT auth_event_type_check;

ALTER TABLE auth_event
    ADD CONSTRAINT auth_event_type_check CHECK (type IN (
        'LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGOUT', 'TOKEN_REUSE_DETECTED', 'EMAIL_VERIFIED',
        'PASSWORD_CHANGED', 'PASSWORD_RESET', 'EMAIL_CHANGED',
        'ACCOUNT_CREATED_BY_ADMIN', 'PASSWORD_RESET_BY_ADMIN', 'ROLES_CHANGED', 'ACCOUNT_LOCKED', 'ACCOUNT_ACTIVATED',
        'TWO_FACTOR_ENABLED', 'TWO_FACTOR_DISABLED', 'TWO_FACTOR_FAILED'));
