-- Thông tin xác thực của tài khoản. NULL nghĩa là chưa xảy ra hoặc xảy ra trước migration này,
-- không suy đoán lại dữ liệu cũ.
ALTER TABLE app_user
    ADD COLUMN email_verified_at   TIMESTAMPTZ,
    ADD COLUMN last_login_at       TIMESTAMPTZ,
    ADD COLUMN password_changed_at TIMESTAMPTZ;

-- Lịch sử bảo mật của từng tài khoản. Chỉ ghi cho tài khoản có thật, tránh bot làm đầy bảng bằng email ngẫu nhiên.
-- actor_id: admin thực hiện thao tác; NULL khi chính người dùng thực hiện.
CREATE TABLE auth_event (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    actor_id   UUID         REFERENCES app_user (id) ON DELETE SET NULL,
    type       VARCHAR(40)  NOT NULL CHECK (type IN (
        'LOGIN_SUCCESS', 'LOGIN_FAILED', 'LOGOUT', 'TOKEN_REUSE_DETECTED', 'EMAIL_VERIFIED',
        'PASSWORD_CHANGED', 'PASSWORD_RESET', 'EMAIL_CHANGED',
        'ACCOUNT_CREATED_BY_ADMIN', 'PASSWORD_RESET_BY_ADMIN', 'ROLES_CHANGED', 'ACCOUNT_LOCKED', 'ACCOUNT_ACTIVATED')),
    ip_address VARCHAR(45),
    user_agent VARCHAR(255),
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_auth_event_user ON auth_event (user_id, created_at DESC);
CREATE INDEX idx_auth_event_created_at ON auth_event (created_at);
