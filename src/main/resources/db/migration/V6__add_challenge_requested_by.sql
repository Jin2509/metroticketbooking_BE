-- Mã đổi email gắn với người yêu cầu: user khác không dùng được, cũng không làm mất mã của nhau.
-- NULL với REGISTER/RESET_PASSWORD vì các luồng này chưa đăng nhập.
ALTER TABLE email_verification_challenge
    ADD COLUMN requested_by UUID REFERENCES app_user (id) ON DELETE CASCADE;

CREATE INDEX idx_evc_requested_by ON email_verification_challenge (requested_by, purpose, created_at DESC);
