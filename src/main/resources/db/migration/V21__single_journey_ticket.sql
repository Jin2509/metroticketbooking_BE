-- Vé lượt: giá theo km của lộ trình ngắn nhất giữa hai ga, tra bảng fare_band.
-- Vé gắn ga đi và ga đến; vào đúng ga đi, ra quá ga đã mua thì thu bù chênh lệch.

CREATE TABLE fare_band (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    min_distance_km DECIMAL(8,3) NOT NULL CHECK (min_distance_km >= 0),
    max_distance_km DECIMAL(8,3),
    price_vnd       INT          NOT NULL CHECK (price_vnd > 0),
    CONSTRAINT ck_fare_band_range CHECK (max_distance_km IS NULL OR max_distance_km > min_distance_km),
    CONSTRAINT ex_fare_band_no_overlap EXCLUDE USING gist (numrange(min_distance_km, max_distance_km, '[)') WITH &&)
);

INSERT INTO fare_band (min_distance_km, max_distance_km, price_vnd) VALUES
    (0, 4, 7000), (4, 8, 10000), (8, 12, 13000), (12, 16, 16000), (16, NULL, 20000);

-- Loại vé lượt dùng lại ràng buộc thời lượng (DAY x 1); giá thực tế lấy từ fare_band nên price_vnd chỉ là giá thấp nhất
ALTER TABLE ticket_product ADD COLUMN single_journey BOOLEAN NOT NULL DEFAULT FALSE;

DROP INDEX ux_ticket_product_active_duration;
CREATE UNIQUE INDEX ux_ticket_product_active_duration
    ON ticket_product (duration_unit, duration_value)
    WHERE status = 'ACTIVE' AND NOT single_journey;

INSERT INTO ticket_product (code, name, description, duration_unit, duration_value, price_vnd, max_periods,
                            activation_window_days, status, single_journey)
VALUES ('SINGLE', 'Vé lượt', 'Đi một lượt giữa hai ga, dùng trong ngày mua. Giá theo quãng đường.',
        'DAY', 1, 7000, 1, 1, 'ACTIVE', TRUE);

ALTER TABLE ticket
    ADD COLUMN origin_station_id      BIGINT REFERENCES station (id) ON DELETE RESTRICT,
    ADD COLUMN destination_station_id BIGINT REFERENCES station (id) ON DELETE RESTRICT,
    ADD COLUMN distance_km            DECIMAL(8,3),
    ADD CONSTRAINT ck_ticket_single_journey_stations CHECK (
        (origin_station_id IS NULL AND destination_station_id IS NULL AND distance_km IS NULL)
        OR (origin_station_id IS NOT NULL AND destination_station_id IS NOT NULL AND distance_km IS NOT NULL
            AND origin_station_id <> destination_station_id));

ALTER TABLE ticket DROP CONSTRAINT ticket_status_check;
ALTER TABLE ticket ADD CONSTRAINT ticket_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'IN_USE', 'USED', 'EXPIRED', 'REFUNDED', 'CANCELLED'));

ALTER TABLE ticket_validation
    ADD COLUMN fare_difference_vnd INT CHECK (fare_difference_vnd IS NULL OR fare_difference_vnd >= 0);

ALTER TABLE payment_transaction DROP CONSTRAINT payment_transaction_purpose_check;
ALTER TABLE payment_transaction ADD CONSTRAINT payment_transaction_purpose_check
    CHECK (purpose IN ('TICKET', 'TICKET_FARE_ADJUSTMENT', 'PARKING_SESSION', 'PARKING_MONTHLY_PASS', 'PARKING_BOOKING'));
