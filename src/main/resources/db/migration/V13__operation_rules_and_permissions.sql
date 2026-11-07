-- ============================================================
-- V13: Quyền mới cho tàu / vận hành / vé / gửi xe và các ràng buộc nghiệp vụ cho operation_config
-- Idempotent ở mức có thể (ON CONFLICT DO NOTHING, IF NOT EXISTS).
-- ============================================================

-- ===== 1. Quyền =====
INSERT INTO permission (code, description) VALUES
    ('TRAIN_MANAGE',      'Quản lý đoàn tàu'),
    ('OPERATION_MANAGE',  'Soạn và gửi duyệt cấu hình vận hành'),
    ('OPERATION_APPROVE', 'Duyệt, từ chối, kích hoạt cấu hình vận hành'),
    ('FARE_MANAGE',       'Quản lý loại vé và bảng giá vé'),
    ('PARKING_OPERATE',   'Vận hành bãi xe: cho xe vào, cho xe ra'),
    ('PARKING_MANAGE',    'Quản lý bãi xe, sức chứa và giá gửi xe'),
    ('PARKING_BUY',       'Mua vé tháng gửi xe')
ON CONFLICT (code) DO NOTHING;

-- ADMIN luôn có toàn bộ quyền
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

-- MANAGER: người duyệt (maker-checker) cấu hình vận hành
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r
JOIN permission p ON p.code IN ('OPERATION_APPROVE')
WHERE r.code = 'MANAGER'
ON CONFLICT DO NOTHING;

-- STAFF: nhân viên ga soát vé và trông xe
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r
JOIN permission p ON p.code IN ('PARKING_OPERATE')
WHERE r.code = 'STAFF'
ON CONFLICT DO NOTHING;

-- PASSENGER: mua vé tháng gửi xe
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r
JOIN permission p ON p.code IN ('PARKING_BUY')
WHERE r.code = 'PASSENGER'
ON CONFLICT DO NOTHING;

-- ===== 2. operation_config: version duy nhất theo tuyến =====
-- Code cũ tạo bản nháp mới với version = version_cũ + 1 nên có thể đã sinh version trùng.
-- Đánh lại số liên tục theo (version, id) để giữ thứ tự rồi mới thêm UNIQUE.
WITH renumbered AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY line_id ORDER BY version, id) AS new_version
    FROM operation_config
)
UPDATE operation_config oc
SET version = r.new_version
FROM renumbered r
WHERE oc.id = r.id
  AND oc.version <> r.new_version;

CREATE UNIQUE INDEX IF NOT EXISTS ux_operation_config_line_version
    ON operation_config (line_id, version);

-- ===== 3. operation_config: CHECK cho giá trị vận hành =====
-- NOT VALID: chỉ áp dụng cho bản ghi mới/được sửa, không làm hỏng migration nếu dữ liệu cũ lệch.
ALTER TABLE operation_config
    ADD CONSTRAINT ck_operation_config_positive_values
    CHECK (number_of_trains > 0 AND headway_minutes > 0 AND running_time_minutes > 0
           AND turnaround_time_minutes >= 0) NOT VALID;

ALTER TABLE operation_config
    ADD CONSTRAINT ck_operation_config_effective_range
    CHECK (effective_to IS NULL OR effective_to >= effective_from) NOT VALID;

ALTER TABLE operation_config
    ADD CONSTRAINT ck_operation_config_time_window
    CHECK (start_time <> end_time) NOT VALID;

-- ===== 4. staff_station: mỗi nhân viên chỉ có 1 phân công ACTIVE tại 1 ga =====
CREATE UNIQUE INDEX IF NOT EXISTS ux_staff_station_active
    ON staff_station (user_id, station_id)
    WHERE status = 'ACTIVE';

-- ===== 5. train: tra cứu theo trạng thái =====
CREATE INDEX IF NOT EXISTS idx_train_line_status ON train (line_id, status);
