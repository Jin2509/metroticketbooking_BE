-- Refresh token: chỉ lưu SHA-256 của token, không lưu token gốc.
-- family_id gom các token sinh ra từ cùng một lần đăng nhập (xoay vòng) → phát hiện dùng lại token cũ.
CREATE TABLE refresh_token (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    family_id   UUID        NOT NULL,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_refresh_token_user ON refresh_token (user_id);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);
CREATE INDEX idx_refresh_token_expires ON refresh_token (expires_at);
