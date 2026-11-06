CREATE TABLE notification (
    id         UUID          PRIMARY KEY,
    user_id    UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type       VARCHAR(32)   NOT NULL CHECK (type IN ('SYSTEM', 'SECURITY', 'ANNOUNCEMENT')),
    title      VARCHAR(200)  NOT NULL,
    body       VARCHAR(2000) NOT NULL,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_notification_user ON notification (user_id, created_at DESC);
-- Đếm số chưa đọc được gọi thường xuyên (client polling), chỉ index phần chưa đọc cho gọn
CREATE INDEX idx_notification_unread ON notification (user_id) WHERE read_at IS NULL;
CREATE INDEX idx_notification_created_at ON notification (created_at);

INSERT INTO permission (code, description) VALUES ('NOTIFICATION_MANAGE', 'Gửi thông báo cho người dùng');

-- ADMIN luôn có toàn bộ quyền
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'NOTIFICATION_MANAGE'
WHERE r.code = 'ADMIN';
