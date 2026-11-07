-- ============================================================
-- V17: Bỏ vé lượt (theo quyết định nghiệp vụ: khó kiểm soát), chỉ bán vé theo thời gian:
--      1 ngày, 3 ngày, 1 tuần, 1 tháng (vé tháng được mua nhiều tháng một lần).
-- Vé lượt mới chỉ có trên nhánh phát triển (V14), chưa phát hành: dữ liệu thử của nó bị xoá ở đây.
-- ============================================================

-- ===== 1. Dọn dữ liệu thử của vé lượt =====
DELETE FROM payment_transaction pt
USING ticket t
WHERE t.ticket_type = 'SINGLE_JOURNEY'
  AND pt.purpose IN ('TICKET', 'TICKET_FARE_ADJUSTMENT')
  AND pt.reference_id = t.id::text;

DELETE FROM payment_order po
USING ticket t
WHERE t.ticket_type = 'SINGLE_JOURNEY'
  AND po.purpose = 'TICKET'
  AND po.reference_id = t.id::text;

UPDATE parking_session ps SET metro_ticket_id = NULL
FROM ticket t
WHERE ps.metro_ticket_id = t.id AND t.ticket_type = 'SINGLE_JOURNEY';

DELETE FROM ticket WHERE ticket_type = 'SINGLE_JOURNEY';          -- ticket_validation xoá theo CASCADE
DELETE FROM ticket_product WHERE ticket_type = 'SINGLE_JOURNEY';

DROP TABLE IF EXISTS fare_band;

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_purpose_check;
ALTER TABLE payment_transaction ADD CONSTRAINT payment_transaction_purpose_check
    CHECK (purpose IN ('TICKET', 'PARKING_SESSION', 'PARKING_MONTHLY_PASS'));

ALTER TABLE ticket_validation DROP COLUMN IF EXISTS fare_difference_vnd;

-- ===== 2. Loại vé: định nghĩa chung theo đơn vị thời gian =====
-- duration_unit + duration_value: DAY×1, DAY×3, WEEK×1, MONTH×1 ...
-- max_periods: số kỳ tối đa mua một lần (vé tháng: tối đa 12 tháng); vé ngày/tuần chỉ 1 kỳ.
ALTER TABLE ticket_product DROP CONSTRAINT IF EXISTS ck_ticket_product_pricing;
ALTER TABLE ticket_product DROP CONSTRAINT IF EXISTS ticket_product_ticket_type_check;
ALTER TABLE ticket_product DROP CONSTRAINT IF EXISTS ticket_product_validity_hours_check;
ALTER TABLE ticket_product DROP CONSTRAINT IF EXISTS ticket_product_price_vnd_check;

ALTER TABLE ticket_product
    ADD COLUMN duration_unit  VARCHAR(10),
    ADD COLUMN duration_value INT,
    ADD COLUMN max_periods    INT     NOT NULL DEFAULT 1,
    ADD COLUMN created_by     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN updated_by     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN version        BIGINT  NOT NULL DEFAULT 0;

UPDATE ticket_product SET duration_unit = 'DAY',   duration_value = 1, max_periods = 1  WHERE ticket_type = 'DAY_PASS';
UPDATE ticket_product SET duration_unit = 'DAY',   duration_value = 3, max_periods = 1  WHERE ticket_type = 'MULTI_DAY_PASS';
UPDATE ticket_product SET duration_unit = 'MONTH', duration_value = 1, max_periods = 12 WHERE ticket_type = 'MONTHLY_PASS';
-- loại vé do admin tự tạo trước đây (nếu có): suy ra theo số giờ
UPDATE ticket_product
SET duration_unit = CASE WHEN validity_hours % 168 = 0 THEN 'WEEK' ELSE 'DAY' END,
    duration_value = CASE WHEN validity_hours % 168 = 0 THEN validity_hours / 168
                          ELSE GREATEST(1, validity_hours / 24) END
WHERE duration_unit IS NULL;

ALTER TABLE ticket_product
    ALTER COLUMN duration_unit SET NOT NULL,
    ALTER COLUMN duration_value SET NOT NULL,
    ALTER COLUMN price_vnd SET NOT NULL,
    DROP COLUMN ticket_type,
    DROP COLUMN validity_hours;

ALTER TABLE ticket_product
    ADD CONSTRAINT ck_ticket_product_code CHECK (code ~ '^[A-Z0-9_]{2,30}$'),
    ADD CONSTRAINT ck_ticket_product_unit CHECK (duration_unit IN ('DAY', 'WEEK', 'MONTH')),
    ADD CONSTRAINT ck_ticket_product_duration CHECK (
        (duration_unit = 'DAY' AND duration_value BETWEEN 1 AND 6)
        OR (duration_unit = 'WEEK' AND duration_value BETWEEN 1 AND 3)
        OR (duration_unit = 'MONTH' AND duration_value BETWEEN 1 AND 12)),
    ADD CONSTRAINT ck_ticket_product_price CHECK (price_vnd > 0 AND price_vnd <= 100000000),
    ADD CONSTRAINT ck_ticket_product_periods CHECK (
        max_periods BETWEEN 1 AND 12 AND (duration_unit = 'MONTH' OR max_periods = 1)),
    ADD CONSTRAINT ck_ticket_product_activation CHECK (activation_window_days BETWEEN 1 AND 90);

-- Không bán song song 2 loại vé ACTIVE cùng thời lượng (khách không biết chọn loại nào)
CREATE UNIQUE INDEX ux_ticket_product_active_duration
    ON ticket_product (duration_unit, duration_value)
    WHERE status = 'ACTIVE';

-- ===== 3. Vé: lưu snapshot thời lượng và số kỳ đã mua =====
ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ck_ticket_single_journey_stations;
ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ticket_ticket_type_check;

ALTER TABLE ticket
    ADD COLUMN duration_unit  VARCHAR(10),
    ADD COLUMN duration_value INT,
    ADD COLUMN periods        INT NOT NULL DEFAULT 1,
    ADD COLUMN unit_price_vnd INT;

UPDATE ticket t
SET duration_unit = p.duration_unit,
    duration_value = p.duration_value,
    unit_price_vnd = t.price_vnd
FROM ticket_product p
WHERE p.id = t.product_id;

ALTER TABLE ticket
    ALTER COLUMN duration_unit SET NOT NULL,
    ALTER COLUMN duration_value SET NOT NULL,
    ALTER COLUMN unit_price_vnd SET NOT NULL,
    DROP COLUMN ticket_type,
    DROP COLUMN origin_station_id,
    DROP COLUMN destination_station_id,
    DROP COLUMN distance_km;

ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ticket_status_check;
ALTER TABLE ticket ADD CONSTRAINT ticket_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'IN_USE', 'EXPIRED', 'REFUNDED', 'CANCELLED'));

ALTER TABLE ticket
    ADD CONSTRAINT ck_ticket_unit CHECK (duration_unit IN ('DAY', 'WEEK', 'MONTH')),
    ADD CONSTRAINT ck_ticket_periods CHECK (periods BETWEEN 1 AND 12),
    ADD CONSTRAINT ck_ticket_total_price CHECK (price_vnd = unit_price_vnd * periods);

-- ===== 4. Nhật ký thay đổi loại vé (ai đổi giá/trạng thái, lúc nào) =====
CREATE TABLE ticket_product_audit (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id  BIGINT       NOT NULL,
    action      VARCHAR(20)  NOT NULL CHECK (action IN ('CREATED', 'UPDATED', 'ACTIVATED', 'DEACTIVATED', 'DELETED')),
    changed_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    changes     TEXT         NOT NULL,
    changed_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_ticket_product_audit_product ON ticket_product_audit (product_id, changed_at DESC);

-- ===== 5. Seed vé 1 tuần, sửa mô tả các loại vé =====
INSERT INTO ticket_product (code, name, description, price_vnd, activation_window_days, status,
                            duration_unit, duration_value, max_periods)
VALUES ('WEEK_1', 'Vé 1 tuần', 'Đi không giới hạn trong 7 ngày kể từ lần quẹt vào đầu tiên.',
        160000, 30, 'ACTIVE', 'WEEK', 1, 1)
ON CONFLICT (code) DO NOTHING;

UPDATE ticket_product SET code = 'MONTH_1',
       description = 'Đi không giới hạn trong 1 tháng kể từ lần quẹt vào đầu tiên. Mua được tối đa 12 tháng một lần.'
WHERE code = 'MONTHLY';

UPDATE permission SET description = 'Quản lý loại vé (thêm, sửa giá, ngừng bán, xoá)' WHERE code = 'FARE_MANAGE';
