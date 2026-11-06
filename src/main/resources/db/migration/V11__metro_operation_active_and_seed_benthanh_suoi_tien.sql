-- Seed Metro Line 1 and a simulation-ready operation config.
-- Coordinates are station points; segment distances and times are simulation estimates.

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
    'Tuyến Metro số 1 Bến Thành - Suối Tiên, khoảng 19.7 km với 14 nhà ga.',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (code) DO UPDATE
SET name_line = EXCLUDED.name_line,
    color_hex = EXCLUDED.color_hex,
    description = EXCLUDED.description,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO station (
    code, name_station, latitude, longitude, address, district, image_url,
    is_interchange, status, created_at, updated_at
)
VALUES
    ('ST001', 'Bến Thành', 10.7709609, 106.6977577, NULL, 'Quận 1', NULL, TRUE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST002', 'Nhà hát Thành phố', 10.7752950, 106.7018388, NULL, 'Quận 1', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST003', 'Ba Son', 10.7818182, 106.7081839, NULL, 'Quận 1', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST004', 'Văn Thánh', 10.7960663, 106.7154934, NULL, 'Bình Thạnh', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST005', 'Tân Cảng', 10.7985913, 106.7232429, NULL, 'Bình Thạnh', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST006', 'Thảo Điền', 10.8004968, 106.7336453, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST007', 'An Phú', 10.8021406, 106.7421864, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST008', 'Rạch Chiếc', 10.8085410, 106.7552594, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST009', 'Phước Long', 10.8214148, 106.7581686, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST010', 'Bình Thái', 10.8326469, 106.7638968, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST011', 'Thủ Đức', 10.8460297, 106.7723865, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST012', 'Khu Công nghệ cao', 10.8589881, 106.7888345, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST013', 'Đại học Quốc gia', 10.8663172, 106.8012214, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ST014', 'Bến xe Suối Tiên', 10.8794994, 106.8140395, NULL, 'Thành phố Thủ Đức', NULL, FALSE, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (code) DO UPDATE
SET name_station = EXCLUDED.name_station,
    latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    address = EXCLUDED.address,
    district = EXCLUDED.district,
    is_interchange = EXCLUDED.is_interchange,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM line_station
WHERE line_id = (SELECT id FROM metro_line WHERE code = 'BTS-01');

INSERT INTO line_station (
    line_id, station_id, station_order, distance_from_prev_km,
    travel_time_from_prev_seconds, dwell_time_seconds, created_at
)
SELECT ml.id, s.id, route.station_order, route.distance_km,
       route.travel_seconds, 30, CURRENT_TIMESTAMP
FROM metro_line ml
CROSS JOIN (VALUES
    (1,  'ST001', 0.000,   0),
    (2,  'ST002', 0.700,  42),
    (3,  'ST003', 1.061,  64),
    (4,  'ST004', 1.878, 114),
    (5,  'ST005', 0.944,  57),
    (6,  'ST006', 1.231,  75),
    (7,  'ST007', 1.008,  61),
    (8,  'ST008', 1.698, 103),
    (9,  'ST009', 1.560,  94),
    (10, 'ST010', 1.485,  90),
    (11, 'ST011', 1.857, 112),
    (12, 'ST012', 2.441, 147),
    (13, 'ST013', 1.676, 101),
    (14, 'ST014', 2.155, 130)
) AS route(station_order, station_code, distance_km, travel_seconds)
JOIN station s ON s.code = route.station_code
WHERE ml.code = 'BTS-01';

INSERT INTO train (code, name_train, line_id, capacity, manufacture_year, status, created_at, updated_at)
SELECT seed.code, seed.name_train, ml.id, 400, 2022, seed.status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM metro_line ml
CROSS JOIN (VALUES
    ('TR-001', 'Metro 01', 'READY'),
    ('TR-002', 'Metro 02', 'IN_OPERATION'),
    ('TR-003', 'Metro 03', 'READY'),
    ('TR-004', 'Metro 04', 'READY'),
    ('TR-005', 'Metro 05', 'IN_OPERATION'),
    ('TR-006', 'Metro 06', 'MAINTENANCE')
) AS seed(code, name_train, status)
WHERE ml.code = 'BTS-01'
ON CONFLICT (code) DO UPDATE
SET name_train = EXCLUDED.name_train,
    line_id = EXCLUDED.line_id,
    capacity = EXCLUDED.capacity,
    manufacture_year = EXCLUDED.manufacture_year,
    status = EXCLUDED.status,
    updated_at = CURRENT_TIMESTAMP;

-- Keep this migration safe to re-run manually while preserving unrelated active configs.
UPDATE operation_config oc
SET number_of_trains = 5,
    headway_minutes = 14,
    running_time_minutes = 30,
    turnaround_time_minutes = 5,
    start_time = TIME '05:00:00',
    end_time = TIME '22:00:00',
    effective_from = TIMESTAMPTZ '2024-12-21 17:00:00+00',
    effective_to = NULL,
    created_by = COALESCE(
        (SELECT au.id FROM app_user au
         WHERE LOWER(au.email) = LOWER(NULLIF('${admin-email}', ''))
           AND au.status = 'ACTIVE'
         ORDER BY au.created_at
         LIMIT 1),
        oc.created_by),
    approved_by = COALESCE(
        (SELECT au.id FROM app_user au
         WHERE LOWER(au.email) = LOWER(NULLIF('${admin-email}', ''))
           AND au.status = 'ACTIVE'
         ORDER BY au.created_at
         LIMIT 1),
        oc.approved_by),
    submitted_at = COALESCE(oc.submitted_at, CURRENT_TIMESTAMP),
    approved_at = COALESCE(oc.approved_at, CURRENT_TIMESTAMP),
    updated_at = CURRENT_TIMESTAMP
WHERE oc.line_id = (SELECT id FROM metro_line WHERE code = 'BTS-01')
  AND oc.status = 'ACTIVE'
  AND oc.number_of_trains = 5
  AND oc.headway_minutes = 6
  AND oc.running_time_minutes = 45
  AND oc.turnaround_time_minutes = 10
  AND oc.start_time = TIME '05:30:00'
  AND oc.end_time = TIME '23:30:00';

INSERT INTO operation_config (
    line_id, version, number_of_trains, headway_minutes, running_time_minutes,
    turnaround_time_minutes, start_time, end_time, effective_from, effective_to,
    status, created_by, submitted_at, approved_by, approved_at, rejection_reason,
    created_at, updated_at
)
SELECT
    ml.id,
    COALESCE((SELECT MAX(existing.version) + 1
              FROM operation_config existing
              WHERE existing.line_id = ml.id), 1),
    5,
    14,
    30,
    5,
    TIME '05:00:00',
    TIME '22:00:00',
    TIMESTAMPTZ '2024-12-21 17:00:00+00',
    NULL,
    'ACTIVE',
    au.id,
    CASE WHEN au.id IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
    au.id,
    CASE WHEN au.id IS NULL THEN NULL ELSE CURRENT_TIMESTAMP END,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM metro_line ml
LEFT JOIN app_user au
    ON LOWER(au.email) = LOWER(NULLIF('${admin-email}', ''))
   AND au.status = 'ACTIVE'
WHERE ml.code = 'BTS-01'
  AND NOT EXISTS (
      SELECT 1
      FROM operation_config current
      WHERE current.line_id = ml.id
        AND current.status = 'ACTIVE'
  );
