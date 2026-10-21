INSERT INTO role (code, name, is_system) VALUES
    ('PASSENGER', 'Hành khách',   TRUE),
    ('STAFF',     'Nhân viên ga', TRUE),
    ('ADMIN',     'Quản trị viên', TRUE);

INSERT INTO permission (code, description) VALUES
    ('TICKET_BUY',      'Mua vé'),
    ('TICKET_VIEW_OWN', 'Xem vé của mình'),
    ('TICKET_VALIDATE', 'Soát vé'),
    ('TICKET_VIEW_ALL', 'Xem mọi vé'),
    ('STATION_MANAGE',  'Quản lý ga, tuyến'),
    ('REPORT_VIEW',     'Xem báo cáo'),
    ('USER_VIEW',       'Xem danh sách tài khoản'),
    ('USER_MANAGE',     'Gán vai trò, khoá/mở tài khoản'),
    ('RBAC_MANAGE',     'Quản lý vai trò và quyền');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('TICKET_BUY', 'TICKET_VIEW_OWN')
WHERE r.code = 'PASSENGER';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('TICKET_VIEW_OWN', 'TICKET_VALIDATE', 'TICKET_VIEW_ALL')
WHERE r.code = 'STAFF';

-- ADMIN có toàn bộ quyền
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN';
