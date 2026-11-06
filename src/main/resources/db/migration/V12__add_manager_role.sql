-- Vai trò Manager: duyệt các yêu cầu thay đổi do Admin gửi và xem báo cáo vận hành.
-- Viết idempotent (ON CONFLICT DO NOTHING) để chạy lại an toàn nếu vai trò đã được tạo tay trên DB.
INSERT INTO role (code, name, is_system) VALUES
    ('MANAGER', 'Quản lý', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r
JOIN permission p ON p.code IN ('TICKET_VIEW_ALL', 'STATION_MANAGE', 'REPORT_VIEW', 'USER_VIEW')
WHERE r.code = 'MANAGER'
ON CONFLICT DO NOTHING;
