-- ============================================================
-- V14: Vé Metro – loại vé, bảng giá theo quãng đường, vé, lịch sử soát vé, giao dịch thanh toán
-- ============================================================

-- ===== Loại vé =====
-- SINGLE_JOURNEY: vé lượt, giá tính theo quãng đường (fare_band) nên price_vnd = NULL.
-- Vé ngày / nhiều ngày / tháng: giá cố định, hiệu lực validity_hours kể từ lần quẹt vào đầu tiên,
-- phải kích hoạt trong activation_window_days kể từ lúc mua.
CREATE TABLE ticket_product (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                   VARCHAR(30)  NOT NULL UNIQUE,
    name                   VARCHAR(100) NOT NULL,
    description            TEXT,
    ticket_type            VARCHAR(30)  NOT NULL
        CHECK (ticket_type IN ('SINGLE_JOURNEY', 'DAY_PASS', 'MULTI_DAY_PASS', 'MONTHLY_PASS')),
    price_vnd              INT CHECK (price_vnd IS NULL OR price_vnd >= 0),
    validity_hours         INT CHECK (validity_hours IS NULL OR validity_hours > 0),
    activation_window_days INT          NOT NULL DEFAULT 30 CHECK (activation_window_days > 0),
    status                 VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ticket_product_pricing CHECK (
        (ticket_type = 'SINGLE_JOURNEY' AND price_vnd IS NULL AND validity_hours IS NULL)
        OR (ticket_type <> 'SINGLE_JOURNEY' AND price_vnd IS NOT NULL AND validity_hours IS NOT NULL))
);

-- ===== Bảng giá vé lượt theo quãng đường (km) =====
-- Khoảng [min_distance_km, max_distance_km), max NULL = không giới hạn trên.
-- EXCLUDE bảo đảm ở mức DB các khoảng không chồng lấn nhau.
CREATE TABLE fare_band (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    min_distance_km DECIMAL(8,3) NOT NULL CHECK (min_distance_km >= 0),
    max_distance_km DECIMAL(8,3),
    price_vnd       INT          NOT NULL CHECK (price_vnd > 0),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_fare_band_range CHECK (max_distance_km IS NULL OR max_distance_km > min_distance_km),
    CONSTRAINT ex_fare_band_no_overlap EXCLUDE USING gist (
        numrange(min_distance_km, max_distance_km, '[)') WITH &&)
);

-- ===== Vé =====
CREATE TABLE ticket (
    id                     UUID         PRIMARY KEY,
    ticket_code            VARCHAR(20)  NOT NULL UNIQUE,
    qr_token               VARCHAR(64)  NOT NULL UNIQUE,
    user_id                UUID         NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    product_id             BIGINT       NOT NULL REFERENCES ticket_product (id) ON DELETE RESTRICT,
    ticket_type            VARCHAR(30)  NOT NULL
        CHECK (ticket_type IN ('SINGLE_JOURNEY', 'DAY_PASS', 'MULTI_DAY_PASS', 'MONTHLY_PASS')),
    origin_station_id      BIGINT REFERENCES station (id) ON DELETE RESTRICT,
    destination_station_id BIGINT REFERENCES station (id) ON DELETE RESTRICT,
    distance_km            DECIMAL(8,3),
    price_vnd              INT          NOT NULL CHECK (price_vnd >= 0),
    status                 VARCHAR(20)  NOT NULL
        CHECK (status IN ('ACTIVE', 'IN_USE', 'USED', 'EXPIRED', 'REFUNDED')),
    purchased_at           TIMESTAMPTZ  NOT NULL,
    valid_from             TIMESTAMPTZ  NOT NULL,
    valid_until            TIMESTAMPTZ  NOT NULL,
    activated_at           TIMESTAMPTZ,
    last_entry_station_id  BIGINT REFERENCES station (id) ON DELETE RESTRICT,
    last_entry_at          TIMESTAMPTZ,
    trips_count            INT          NOT NULL DEFAULT 0 CHECK (trips_count >= 0),
    refunded_at            TIMESTAMPTZ,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ticket_single_journey_stations CHECK (
        ticket_type <> 'SINGLE_JOURNEY'
        OR (origin_station_id IS NOT NULL AND destination_station_id IS NOT NULL
            AND origin_station_id <> destination_station_id)),
    CONSTRAINT ck_ticket_validity CHECK (valid_until > valid_from),
    CONSTRAINT ck_ticket_in_use_has_entry CHECK (status <> 'IN_USE' OR last_entry_station_id IS NOT NULL)
);

CREATE INDEX idx_ticket_user_purchased ON ticket (user_id, purchased_at DESC);
CREATE INDEX idx_ticket_status_valid_until ON ticket (status, valid_until);

-- ===== Lịch sử soát vé (cả lượt bị từ chối, phục vụ đối soát) =====
CREATE TABLE ticket_validation (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id            UUID REFERENCES ticket (id) ON DELETE CASCADE,
    station_id           BIGINT       NOT NULL REFERENCES station (id) ON DELETE RESTRICT,
    direction            VARCHAR(10)  NOT NULL CHECK (direction IN ('ENTRY', 'EXIT')),
    result               VARCHAR(10)  NOT NULL CHECK (result IN ('ACCEPTED', 'REJECTED')),
    reason               VARCHAR(255),
    fare_difference_vnd  INT CHECK (fare_difference_vnd IS NULL OR fare_difference_vnd >= 0),
    validated_by         UUID REFERENCES app_user (id) ON DELETE SET NULL,
    validated_at         TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_ticket_validation_ticket ON ticket_validation (ticket_id, validated_at DESC);
CREATE INDEX idx_ticket_validation_station ON ticket_validation (station_id, validated_at DESC);

-- ===== Giao dịch thanh toán (dùng chung cho vé và gửi xe; cổng thanh toán đang giả lập) =====
CREATE TABLE payment_transaction (
    id            UUID         PRIMARY KEY,
    user_id       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    purpose       VARCHAR(30)  NOT NULL
        CHECK (purpose IN ('TICKET', 'TICKET_FARE_ADJUSTMENT', 'PARKING_SESSION', 'PARKING_MONTHLY_PASS')),
    reference_id  VARCHAR(64)  NOT NULL,
    amount_vnd    INT          NOT NULL CHECK (amount_vnd >= 0),
    method        VARCHAR(20)  NOT NULL CHECK (method IN ('CASH', 'CARD', 'E_WALLET', 'BANK_TRANSFER')),
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('SUCCEEDED', 'REFUNDED')),
    provider_ref  VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL,
    refunded_at   TIMESTAMPTZ
);

CREATE INDEX idx_payment_reference ON payment_transaction (purpose, reference_id);
CREATE INDEX idx_payment_user ON payment_transaction (user_id, created_at DESC);

CREATE TRIGGER trg_ticket_product_updated_at
BEFORE UPDATE ON ticket_product
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_fare_band_updated_at
BEFORE UPDATE ON fare_band
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_ticket_updated_at
BEFORE UPDATE ON ticket
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ===== Seed: loại vé & bảng giá (giá mô phỏng theo tuyến số 1, chỉnh lại qua API FARE_MANAGE) =====
INSERT INTO ticket_product (code, name, description, ticket_type, price_vnd, validity_hours, activation_window_days, status)
VALUES
    ('SINGLE',  'Vé lượt',   'Đi một lượt giữa 2 ga đã chọn, dùng trong ngày mua, giá theo quãng đường.',
        'SINGLE_JOURNEY', NULL, NULL, 1, 'ACTIVE'),
    ('DAY_1',   'Vé 1 ngày', 'Đi không giới hạn trong 24 giờ kể từ lần quẹt vào đầu tiên.',
        'DAY_PASS', 40000, 24, 30, 'ACTIVE'),
    ('DAY_3',   'Vé 3 ngày', 'Đi không giới hạn trong 72 giờ kể từ lần quẹt vào đầu tiên.',
        'MULTI_DAY_PASS', 90000, 72, 30, 'ACTIVE'),
    ('MONTHLY', 'Vé tháng',  'Đi không giới hạn trong 30 ngày kể từ lần quẹt vào đầu tiên.',
        'MONTHLY_PASS', 300000, 720, 30, 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

INSERT INTO fare_band (min_distance_km, max_distance_km, price_vnd)
SELECT v.min_km, v.max_km, v.price
FROM (VALUES
    (0.000,  3.000,  7000),
    (3.000,  6.000,  9000),
    (6.000,  9.000, 12000),
    (9.000, 12.000, 14000),
    (12.000, 15.000, 16000),
    (15.000, 18.000, 18000),
    (18.000, NULL::DECIMAL(8,3), 20000)
) AS v(min_km, max_km, price)
WHERE NOT EXISTS (SELECT 1 FROM fare_band);
