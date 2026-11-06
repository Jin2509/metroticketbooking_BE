-- V11: enable ACTIVE/INACTIVE workflow and seed Ben Thanh - Suoi Tien metro line

ALTER TABLE operation_config
    DROP CONSTRAINT IF EXISTS operation_config_status_check;

ALTER TABLE operation_config
    ADD CONSTRAINT operation_config_status_check
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'ACTIVE', 'INACTIVE'));

CREATE UNIQUE INDEX IF NOT EXISTS idx_operation_config_active_line
    ON operation_config (line_id)
    WHERE status = 'ACTIVE';

INSERT INTO metro_line (code, name_line, color_hex, description, status, created_at, updated_at)
VALUES (
    'BTS-01',
    'Bến Thành - Suối Tiên',
    '#00AEEF',
    'Tuyến Metro số 1 kết nối trung tâm TP.HCM với khu vực phía Nam, phục vụ di chuyển nội đô và liên kết giao thông công cộng.',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (code) DO NOTHING;

INSERT INTO station (code, name_station, latitude, longitude, address, district, image_url, is_interchange, status, created_at, updated_at)
VALUES
    ('ST001', 'Bến Thành', 10.7731000, 106.6983000, 'Quận 1, TP.HCM', 'Quận 1', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST002', 'Nhà hát Thành phố', 10.7720000, 106.6971000, 'Quận 1, TP.HCM', 'Quận 1', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST003', 'Ba Son', 10.7798000, 106.7067000, 'Quận 1, TP.HCM', 'Quận 1', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST004', 'Vĩnh Viễn', 10.7855000, 106.7149000, 'Quận 1, TP.HCM', 'Quận 1', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST005', 'Hòa Hảo', 10.7930000, 106.7205000, 'Quận 3, TP.HCM', 'Quận 3', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST006', 'Tân Cảng', 10.7615000, 106.6938000, 'Quận 5, TP.HCM', 'Quận 5', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST007', 'Bến xe Miền Đông', 10.8527000, 106.7862000, 'Quận 9, TP.HCM', 'Quận 9', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST008', 'Thủ Đức', 10.8565000, 106.7709000, 'Quận 9, TP.HCM', 'Quận 9', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST009', 'Long Bình', 10.8610000, 106.7584000, 'Quận 9, TP.HCM', 'Quận 9', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST010', 'Khu Công nghệ cao', 10.8673000, 106.7482000, 'Quận 9, TP.HCM', 'Quận 9', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST011', 'Suối Tiên', 10.8727000, 106.7394000, 'Quận 9, TP.HCM', 'Quận 9', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 1, 0.000, 0, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST001'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 2, 1.200, 180, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST002'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 3, 1.350, 210, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST003'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 4, 1.100, 170, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST004'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 5, 1.420, 220, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST005'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 6, 2.600, 390, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST006'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 7, 3.100, 430, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST007'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 8, 1.300, 190, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST008'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 9, 1.050, 160, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST009'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 10, 0.980, 150, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST010'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO line_station (line_id, station_id, station_order, distance_from_prev_km, travel_time_from_prev_seconds, dwell_time_seconds, created_at)
SELECT ml.id, s.id, 11, 1.150, 180, 30, CURRENT_TIMESTAMP
FROM metro_line ml
JOIN station s ON s.code = 'ST011'
WHERE ml.code = 'BTS-01'
ON CONFLICT (line_id, station_id) DO NOTHING;

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT 'TR-001', 'Metro 01', ml.id, 400, 2022, 'READY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml WHERE ml.code='BTS-01'
ON CONFLICT (code) DO NOTHING;

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT 'TR-002', 'Metro 02', ml.id, 400, 2022, 'IN_OPERATION', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml WHERE ml.code='BTS-01'
ON CONFLICT (code) DO NOTHING;

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT 'TR-003', 'Metro 03', ml.id, 400, 2022, 'READY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml WHERE ml.code='BTS-01'
ON CONFLICT (code) DO NOTHING;

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT 'TR-004', 'Metro 04', ml.id, 400, 2022, 'READY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml WHERE ml.code='BTS-01'
ON CONFLICT (code) DO NOTHING;

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT 'TR-005', 'Metro 05', ml.id, 400, 2022, 'IN_OPERATION', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml WHERE ml.code='BTS-01'
ON CONFLICT (code) DO NOTHING;

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT 'TR-006', 'Metro 06', ml.id, 400, 2022, 'MAINTENANCE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml WHERE ml.code='BTS-01'
ON CONFLICT (code) DO NOTHING;

INSERT INTO operation_config (
    line_id,
    version,
    number_of_trains,
    headway_minutes,
    running_time_minutes,
    turnaround_time_minutes,
    start_time,
    end_time,
    effective_from,
    effective_to,
    status,
    created_by,
    submitted_at,
    approved_by,
    approved_at,
    rejection_reason,
    created_at,
    updated_at
)
SELECT
    ml.id,
    1,
    5,
    6,
    45,
    10,
    '05:30:00',
    '23:30:00',
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    NULL,
    'ACTIVE',
    au.id,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    au.id,
    TIMESTAMPTZ '2026-01-01 00:00:00+00',
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM metro_line ml
JOIN app_user au ON au.email = 'admin@metro.local'
WHERE ml.code = 'BTS-01'
  AND au.status = 'ACTIVE'
  AND NOT EXISTS (
  SELECT 1 FROM operation_config oc WHERE oc.line_id = ml.id AND oc.status = 'ACTIVE'
  );
