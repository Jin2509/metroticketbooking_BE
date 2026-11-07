-- ============================================================
-- V15: Gửi xe tại ga (Park & Ride) – bãi xe, sức chứa/giá theo loại xe, vé tháng, lượt gửi
-- ============================================================

CREATE TABLE parking_lot (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id  BIGINT       NOT NULL REFERENCES station (id) ON DELETE RESTRICT,
    code        VARCHAR(30)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    address     VARCHAR(255),
    open_time   TIME         NOT NULL,
    close_time  TIME         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_parking_lot_hours CHECK (open_time <> close_time)
);

CREATE INDEX idx_parking_lot_station ON parking_lot (station_id);

-- Sức chứa và bảng giá theo loại xe của từng bãi.
-- Phí lượt = day_price_vnd + (số lần qua 0h) × overnight_price_vnd.
CREATE TABLE parking_rate (
    id                             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lot_id                         BIGINT      NOT NULL REFERENCES parking_lot (id) ON DELETE CASCADE,
    vehicle_type                   VARCHAR(20) NOT NULL CHECK (vehicle_type IN ('BICYCLE', 'MOTORBIKE', 'CAR')),
    capacity                       INT         NOT NULL CHECK (capacity >= 0),
    day_price_vnd                  INT         NOT NULL CHECK (day_price_vnd >= 0),
    overnight_price_vnd            INT         NOT NULL CHECK (overnight_price_vnd >= 0),
    monthly_price_vnd              INT         NOT NULL CHECK (monthly_price_vnd >= 0),
    park_and_ride_discount_percent INT         NOT NULL DEFAULT 0
        CHECK (park_and_ride_discount_percent BETWEEN 0 AND 100),
    created_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_parking_rate_lot_vehicle UNIQUE (lot_id, vehicle_type)
);

CREATE TABLE parking_monthly_pass (
    id            UUID         PRIMARY KEY,
    pass_code     VARCHAR(20)  NOT NULL UNIQUE,
    user_id       UUID         NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    lot_id        BIGINT       NOT NULL REFERENCES parking_lot (id) ON DELETE RESTRICT,
    vehicle_type  VARCHAR(20)  NOT NULL CHECK (vehicle_type IN ('BICYCLE', 'MOTORBIKE', 'CAR')),
    plate_number  VARCHAR(20)  NOT NULL,
    price_vnd     INT          NOT NULL CHECK (price_vnd >= 0),
    valid_from    TIMESTAMPTZ  NOT NULL,
    valid_until   TIMESTAMPTZ  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_parking_pass_validity CHECK (valid_until > valid_from)
);

CREATE INDEX idx_parking_pass_user ON parking_monthly_pass (user_id, valid_until DESC);
CREATE INDEX idx_parking_pass_lot_plate ON parking_monthly_pass (lot_id, plate_number);

CREATE TABLE parking_session (
    id               UUID         PRIMARY KEY,
    session_code     VARCHAR(20)  NOT NULL UNIQUE,
    lot_id           BIGINT       NOT NULL REFERENCES parking_lot (id) ON DELETE RESTRICT,
    vehicle_type     VARCHAR(20)  NOT NULL CHECK (vehicle_type IN ('BICYCLE', 'MOTORBIKE', 'CAR')),
    plate_number     VARCHAR(20),
    monthly_pass_id  UUID REFERENCES parking_monthly_pass (id) ON DELETE SET NULL,
    metro_ticket_id  UUID REFERENCES ticket (id) ON DELETE SET NULL,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('PARKED', 'COMPLETED')),
    check_in_at      TIMESTAMPTZ  NOT NULL,
    check_out_at     TIMESTAMPTZ,
    fee_vnd          INT CHECK (fee_vnd IS NULL OR fee_vnd >= 0),
    discount_vnd     INT CHECK (discount_vnd IS NULL OR discount_vnd >= 0),
    payment_method   VARCHAR(20) CHECK (payment_method IS NULL OR payment_method IN ('CASH', 'CARD', 'E_WALLET', 'BANK_TRANSFER')),
    checked_in_by    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    checked_out_by   UUID REFERENCES app_user (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_parking_session_checkout CHECK (
        (status = 'PARKED' AND check_out_at IS NULL)
        OR (status = 'COMPLETED' AND check_out_at IS NOT NULL AND check_out_at >= check_in_at AND fee_vnd IS NOT NULL)),
    CONSTRAINT ck_parking_session_plate CHECK (vehicle_type = 'BICYCLE' OR plate_number IS NOT NULL)
);

-- Một xe (biển số) không thể đang gửi ở 2 nơi cùng lúc
CREATE UNIQUE INDEX ux_parking_session_plate_parked
    ON parking_session (plate_number)
    WHERE status = 'PARKED' AND plate_number IS NOT NULL;
-- Đếm số xe đang gửi để kiểm tra sức chứa
CREATE INDEX idx_parking_session_lot_parked
    ON parking_session (lot_id, vehicle_type)
    WHERE status = 'PARKED';
CREATE INDEX idx_parking_session_pass ON parking_session (monthly_pass_id);

CREATE TRIGGER trg_parking_lot_updated_at
BEFORE UPDATE ON parking_lot
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_parking_rate_updated_at
BEFORE UPDATE ON parking_rate
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_parking_monthly_pass_updated_at
BEFORE UPDATE ON parking_monthly_pass
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_parking_session_updated_at
BEFORE UPDATE ON parking_session
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ===== Seed: bãi xe tại một số ga tuyến số 1 (số liệu mô phỏng) =====
INSERT INTO parking_lot (station_id, code, name, address, open_time, close_time, status)
SELECT s.id, v.code, v.name, NULL, TIME '05:00', TIME '23:00', 'ACTIVE'
FROM (VALUES
    ('PK-ST001', 'ST001', 'Bãi xe ga Bến Thành'),
    ('PK-ST008', 'ST008', 'Bãi xe ga Rạch Chiếc'),
    ('PK-ST011', 'ST011', 'Bãi xe ga Thủ Đức'),
    ('PK-ST014', 'ST014', 'Bãi xe ga Bến xe Suối Tiên')
) AS v(code, station_code, name)
JOIN station s ON s.code = v.station_code
ON CONFLICT (code) DO NOTHING;

INSERT INTO parking_rate (lot_id, vehicle_type, capacity, day_price_vnd, overnight_price_vnd,
                          monthly_price_vnd, park_and_ride_discount_percent)
SELECT pl.id, v.vehicle_type, v.capacity, v.day_price, v.overnight_price, v.monthly_price, v.discount
FROM parking_lot pl
CROSS JOIN (VALUES
    ('BICYCLE',   100,  2000,  3000,   50000, 100),
    ('MOTORBIKE', 300,  5000, 10000,  120000,  50),
    ('CAR',        20, 25000, 50000, 1200000,   0)
) AS v(vehicle_type, capacity, day_price, overnight_price, monthly_price, discount)
WHERE pl.code IN ('PK-ST001', 'PK-ST008', 'PK-ST011', 'PK-ST014')
ON CONFLICT (lot_id, vehicle_type) DO NOTHING;
