-- ============================================================
-- V18: Xác thực sinh viên bằng AI (Gemini) + duyệt tay, ưu đãi vé sinh viên, Hành trình xanh
-- ============================================================

-- ===== 1. Quyền =====
INSERT INTO permission (code, description) VALUES
    ('STUDENT_VERIFY',        'Duyệt tay hồ sơ xác thực sinh viên, xem ảnh thẻ, thu hồi'),
    ('STUDENT_POLICY_MANAGE', 'Cấu hình điều kiện xác thực sinh viên và mức ưu đãi'),
    ('GREEN_JOURNEY_MANAGE',  'Cấu hình hệ số Hành trình xanh')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('STUDENT_VERIFY')
WHERE r.code = 'MANAGER'
ON CONFLICT DO NOTHING;

-- ===== 2. Điều kiện xác thực sinh viên (1 dòng duy nhất, admin chỉnh qua API) =====
CREATE TABLE student_verification_policy (
    id                          SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled                     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- false: AI chỉ đọc thông tin, mọi hồ sơ đều chờ duyệt tay
    auto_approve                BOOLEAN      NOT NULL DEFAULT TRUE,
    min_confidence              NUMERIC(3,2) NOT NULL DEFAULT 0.85 CHECK (min_confidence BETWEEN 0 AND 1),
    -- danh sách trường được chấp nhận, mỗi dòng một tên; rỗng = mọi trường
    accepted_schools            TEXT         NOT NULL DEFAULT '',
    require_name_match          BOOLEAN      NOT NULL DEFAULT TRUE,
    require_account_name_match  BOOLEAN      NOT NULL DEFAULT TRUE,
    require_student_code_match  BOOLEAN      NOT NULL DEFAULT TRUE,
    require_not_expired         BOOLEAN      NOT NULL DEFAULT TRUE,
    require_valid_until         BOOLEAN      NOT NULL DEFAULT FALSE,
    default_validity_months     INT          NOT NULL DEFAULT 12 CHECK (default_validity_months BETWEEN 1 AND 60),
    max_validity_months         INT          NOT NULL DEFAULT 12 CHECK (max_validity_months BETWEEN 1 AND 60),
    discount_percent            INT          NOT NULL DEFAULT 50 CHECK (discount_percent BETWEEN 0 AND 100),
    -- mã loại vé được giảm giá sinh viên, phân cách bằng dấu phẩy
    eligible_product_codes      TEXT         NOT NULL DEFAULT 'MONTH_1',
    max_submissions_per_30_days INT          NOT NULL DEFAULT 3 CHECK (max_submissions_per_30_days BETWEEN 1 AND 20),
    image_retention_days        INT          NOT NULL DEFAULT 30 CHECK (image_retention_days BETWEEN 1 AND 365),
    updated_by                  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_student_policy_validity CHECK (default_validity_months <= max_validity_months)
);
INSERT INTO student_verification_policy (id) VALUES (1) ON CONFLICT DO NOTHING;

-- ===== 3. Hồ sơ xác thực sinh viên =====
-- PENDING (chờ AI) → PROCESSING → APPROVED / REJECTED / NEEDS_REVIEW (chờ duyệt tay) ; CANCELLED ; REVOKED
CREATE TABLE student_verification (
    id                    UUID         PRIMARY KEY,
    user_id               UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    status                VARCHAR(20)  NOT NULL CHECK (status IN
        ('PENDING', 'PROCESSING', 'NEEDS_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED', 'REVOKED')),
    declared_full_name    VARCHAR(120) NOT NULL,
    declared_student_code VARCHAR(30)  NOT NULL,
    declared_school       VARCHAR(200) NOT NULL,
    image_mime_type       VARCHAR(20)  NOT NULL,
    -- kết quả AI
    ai_attempts           INT          NOT NULL DEFAULT 0,
    ai_next_attempt_at    TIMESTAMPTZ,
    ai_locked_at          TIMESTAMPTZ,
    ai_model              VARCHAR(60),
    ai_is_student_card    BOOLEAN,
    ai_full_name          VARCHAR(200),
    ai_student_code       VARCHAR(60),
    ai_school             VARCHAR(300),
    ai_valid_until        DATE,
    ai_confidence         NUMERIC(3,2),
    ai_tampering_suspected BOOLEAN,
    ai_notes              TEXT,
    ai_error              VARCHAR(500),
    ai_completed_at       TIMESTAMPTZ,
    -- quyết định
    decision_source       VARCHAR(10) CHECK (decision_source IS NULL OR decision_source IN ('AI', 'MANUAL')),
    decision_reasons      TEXT,
    reviewed_by           UUID REFERENCES app_user (id) ON DELETE SET NULL,
    review_note           VARCHAR(1000),
    approved_until        DATE,
    submitted_at          TIMESTAMPTZ  NOT NULL,
    decided_at            TIMESTAMPTZ,
    image_purged_at       TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_student_verification_approved CHECK (status <> 'APPROVED' OR approved_until IS NOT NULL)
);

-- Mỗi người chỉ có 1 hồ sơ đang xử lý
CREATE UNIQUE INDEX ux_student_verification_open
    ON student_verification (user_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'NEEDS_REVIEW');
CREATE INDEX idx_student_verification_queue ON student_verification (status, ai_next_attempt_at);
CREATE INDEX idx_student_verification_user ON student_verification (user_id, submitted_at DESC);

-- Ảnh thẻ tách bảng riêng (không nạp khi liệt kê), xoá sau image_retention_days kể từ khi có quyết định
CREATE TABLE student_verification_image (
    verification_id UUID  PRIMARY KEY REFERENCES student_verification (id) ON DELETE CASCADE,
    content         BYTEA NOT NULL
);

-- ===== 4. Ưu đãi trên vé =====
ALTER TABLE ticket
    ADD COLUMN list_unit_price_vnd INT,
    ADD COLUMN discount_code       VARCHAR(20) CHECK (discount_code IS NULL OR discount_code IN ('STUDENT')),
    ADD COLUMN discount_percent    INT NOT NULL DEFAULT 0 CHECK (discount_percent BETWEEN 0 AND 100);
UPDATE ticket SET list_unit_price_vnd = unit_price_vnd WHERE list_unit_price_vnd IS NULL;
ALTER TABLE ticket ALTER COLUMN list_unit_price_vnd SET NOT NULL;
ALTER TABLE ticket ADD CONSTRAINT ck_ticket_discount CHECK (
    (discount_code IS NULL AND discount_percent = 0 AND unit_price_vnd = list_unit_price_vnd)
    OR (discount_code IS NOT NULL AND unit_price_vnd <= list_unit_price_vnd));

-- ===== 5. Hành trình xanh =====
CREATE TABLE green_journey_settings (
    id                         SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled                    BOOLEAN     NOT NULL DEFAULT TRUE,
    -- phát thải trung bình / người / km (gram CO2): phương tiện so sánh (xe máy) và Metro
    baseline_grams_per_km      INT         NOT NULL DEFAULT 90 CHECK (baseline_grams_per_km BETWEEN 1 AND 1000),
    metro_grams_per_km         INT         NOT NULL DEFAULT 30 CHECK (metro_grams_per_km BETWEEN 0 AND 1000),
    points_per_km              INT         NOT NULL DEFAULT 10 CHECK (points_per_km BETWEEN 0 AND 1000),
    min_trip_minutes           INT         NOT NULL DEFAULT 2 CHECK (min_trip_minutes BETWEEN 0 AND 60),
    max_trip_minutes           INT         NOT NULL DEFAULT 240 CHECK (max_trip_minutes BETWEEN 10 AND 1440),
    max_counted_trips_per_day  INT         NOT NULL DEFAULT 6 CHECK (max_counted_trips_per_day BETWEEN 1 AND 50),
    -- 1 cây xanh hấp thụ khoảng 21 kg CO2/năm (dùng để quy đổi hiển thị)
    tree_grams_per_year        INT         NOT NULL DEFAULT 21000 CHECK (tree_grams_per_year > 0),
    updated_by                 UUID REFERENCES app_user (id) ON DELETE SET NULL,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version                    BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_green_factors CHECK (metro_grams_per_km < baseline_grams_per_km),
    CONSTRAINT ck_green_trip_window CHECK (min_trip_minutes < max_trip_minutes)
);
INSERT INTO green_journey_settings (id) VALUES (1) ON CONFLICT DO NOTHING;

CREATE TABLE green_trip (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            UUID          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    ticket_id          UUID          NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    entry_station_id   BIGINT        NOT NULL REFERENCES station (id) ON DELETE RESTRICT,
    exit_station_id    BIGINT        NOT NULL REFERENCES station (id) ON DELETE RESTRICT,
    entry_at           TIMESTAMPTZ   NOT NULL,
    exit_at            TIMESTAMPTZ   NOT NULL,
    distance_km        NUMERIC(8,3)  NOT NULL CHECK (distance_km >= 0),
    co2_saved_grams    INT           NOT NULL CHECK (co2_saved_grams >= 0),
    points             INT           NOT NULL CHECK (points >= 0),
    counted            BOOLEAN       NOT NULL,
    not_counted_reason VARCHAR(200),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_green_trip_ticket_entry UNIQUE (ticket_id, entry_at),
    CONSTRAINT ck_green_trip_time CHECK (exit_at >= entry_at),
    CONSTRAINT ck_green_trip_not_counted CHECK (counted OR (points = 0 AND co2_saved_grams = 0))
);
CREATE INDEX idx_green_trip_user ON green_trip (user_id, exit_at DESC);
CREATE INDEX idx_green_trip_exit ON green_trip (exit_at) WHERE counted;
