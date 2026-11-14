-- Vé lượt tính theo km: giá = max(giá tối thiểu, làm tròn LÊN(km × đơn giá/km của loại vé, bước làm tròn)).
-- Đơn giá/km của TỪNG loại vé lượt lưu trong system_param (khoá FARE_PER_KM_<mã loại vé>), có lịch sử hiệu lực.
-- Bước làm tròn và giá tối thiểu dùng chung cho mọi loại vé lượt. Bỏ bảng fare_band.

ALTER TABLE system_param DROP CONSTRAINT IF EXISTS system_param_param_group_check;
ALTER TABLE system_param ADD CONSTRAINT system_param_param_group_check
    CHECK (param_group IN ('green_journey', 'payment', 'parking', 'fare'));

INSERT INTO system_param (param_key, param_group, param_value, description, effective_from) VALUES
    ('FARE_ROUNDING_VND', 'fare', '1000', 'Bước làm tròn lên giá vé lượt (đồng)',            '2020-01-01T00:00:00Z'),
    ('FARE_MIN_VND',      'fare', '7000', 'Giá vé lượt tối thiểu cho mọi quãng đường (đồng)', '2020-01-01T00:00:00Z');

INSERT INTO system_param (param_key, param_group, param_value, description, effective_from)
SELECT 'FARE_PER_KM_' || p.code, 'fare', '1000', 'Đơn giá theo km của vé lượt ' || p.name || ' (đồng/km)',
       '2020-01-01T00:00:00Z'
FROM ticket_product p
WHERE p.single_journey;

-- Snapshot đơn giá/km lúc mua để tính bù chênh lệch khi ra quá ga theo đúng giá đã mua
ALTER TABLE ticket ADD COLUMN fare_per_km_vnd INT CHECK (fare_per_km_vnd IS NULL OR fare_per_km_vnd > 0);

-- Vé lượt bán trước V22 (giá theo bậc): coi như đơn giá/km = giá đã mua / km để bù vé không bị lệch
UPDATE ticket
SET fare_per_km_vnd = GREATEST(1, CEIL(list_unit_price_vnd / NULLIF(distance_km, 0))::INT)
WHERE origin_station_id IS NOT NULL AND distance_km > 0;

DROP TABLE fare_band;
