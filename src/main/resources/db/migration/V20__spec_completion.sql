-- Hoàn thiện theo đặc tả báo cáo Đồ án 1 (v2): RBAC đủ 5 vai trò, tham số hệ thống, CPoint và hạng,
-- duyệt bảng giá, đặt chỗ gửi xe, đối soát, sự cố, chatbot, nhật ký thao tác.

-- ===== 1. RBAC =====
INSERT INTO role (code, name, is_system) VALUES ('ACCOUNTANT', 'Kế toán', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO permission (code, description) VALUES
    ('SYSTEM_PARAM_MANAGE', 'Cấu hình tham số hệ thống và hạng thành viên'),
    ('FARE_APPROVE',        'Duyệt hoặc từ chối bảng giá vé'),
    ('INCIDENT_REPORT',     'Báo cáo sự cố tại ga được phân công'),
    ('INCIDENT_MANAGE',     'Tiếp nhận và xử lý sự cố tại ga'),
    ('REPORT_EXPORT',       'Xuất báo cáo PDF, Excel'),
    ('REPORT_TRAFFIC_VIEW', 'Xem thống kê lưu lượng hành khách'),
    ('CHATBOT_USE',         'Hỏi đáp với chatbot'),
    ('CHATBOT_MANAGE',      'Quản lý kho tri thức chatbot'),
    ('AUDIT_VIEW',          'Tra cứu nhật ký thao tác')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p
    ON p.code IN ('SYSTEM_PARAM_MANAGE', 'INCIDENT_MANAGE', 'REPORT_EXPORT', 'REPORT_TRAFFIC_VIEW', 'CHATBOT_MANAGE', 'AUDIT_VIEW')
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('FARE_APPROVE', 'INCIDENT_MANAGE')
WHERE r.code = 'MANAGER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('PAYMENT_MANAGE', 'REPORT_VIEW', 'REPORT_EXPORT')
WHERE r.code = 'ACCOUNTANT'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'INCIDENT_REPORT'
WHERE r.code = 'STAFF'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'CHATBOT_USE'
WHERE r.code = 'PASSENGER'
ON CONFLICT DO NOTHING;

-- UC21: chỉ Quản lý xem được ảnh thẻ sinh viên
DELETE FROM role_permission
WHERE role_id = (SELECT id FROM role WHERE code = 'ADMIN')
  AND permission_id = (SELECT id FROM permission WHERE code = 'STUDENT_VERIFY');

-- ===== 2. Tham số hệ thống (UC09). Mỗi lần sửa thêm một dòng, dòng cũ giữ làm lịch sử =====
CREATE TABLE system_param (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    param_key      VARCHAR(100) NOT NULL,
    param_group    VARCHAR(30)  NOT NULL CHECK (param_group IN ('green_journey', 'payment', 'parking')),
    param_value    VARCHAR(255) NOT NULL,
    value_type     VARCHAR(20)  NOT NULL DEFAULT 'int' CHECK (value_type IN ('int', 'decimal', 'string', 'boolean')),
    description    VARCHAR(255),
    effective_from TIMESTAMPTZ  NOT NULL,
    effective_to   TIMESTAMPTZ,
    updated_by     UUID REFERENCES app_user (id) ON DELETE SET NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE UNIQUE INDEX ux_system_param_open ON system_param (param_key) WHERE effective_to IS NULL;
CREATE INDEX idx_system_param_lookup ON system_param (param_key, effective_from DESC);

INSERT INTO system_param (param_key, param_group, param_value, description, effective_from) VALUES
    ('CPOINT_PER_CHECKIN',          'green_journey', '50',    'Số điểm CPoint cho mỗi lượt check-in hợp lệ',         '2020-01-01T00:00:00Z'),
    ('CPOINT_MAX_CHECKINS_PER_DAY', 'green_journey', '4',     'Số lượt check-in được tích điểm tối đa mỗi ngày',     '2020-01-01T00:00:00Z'),
    ('CPOINT_MAX_DISCOUNT_VND',     'green_journey', '20000', 'Mức giảm tối đa khi dùng điểm cho một đơn (đồng)',    '2020-01-01T00:00:00Z'),
    ('PAYMENT_QR_TTL_MINUTES',      'payment',       '10',    'Thời hạn mã QR thanh toán (phút)',                    '2020-01-01T00:00:00Z'),
    ('PARKING_OVERTIME_FEE_PER_HOUR', 'parking',     '5000',  'Phí gửi xe quá giờ cho mỗi giờ hoặc phần giờ (đồng)', '2020-01-01T00:00:00Z');

-- ===== 3. Hạng thành viên và CPoint (UC12, UC15) =====
CREATE TABLE membership_tier (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code       VARCHAR(30) NOT NULL UNIQUE,
    name_tier  VARCHAR(50) NOT NULL UNIQUE,
    min_points INT         NOT NULL DEFAULT 0 CHECK (min_points >= 0),
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO membership_tier (code, name_tier, min_points) VALUES
    ('BRONZE', 'Đồng', 0), ('SILVER', 'Bạc', 5000), ('GOLD', 'Vàng', 20000), ('PLATINUM', 'Bạch kim', 50000);

CREATE TABLE loyalty_account (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         UUID        NOT NULL UNIQUE REFERENCES app_user (id) ON DELETE CASCADE,
    tier_id         BIGINT REFERENCES membership_tier (id) ON DELETE SET NULL,
    points_balance  INT         NOT NULL DEFAULT 0 CHECK (points_balance >= 0),
    lifetime_points INT         NOT NULL DEFAULT 0 CHECK (lifetime_points >= 0),
    tier_updated_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE loyalty_transaction (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    validation_id    BIGINT REFERENCES ticket_validation (id) ON DELETE SET NULL,
    reference_type   VARCHAR(30),
    reference_id     VARCHAR(64),
    transaction_type VARCHAR(20)  NOT NULL CHECK (transaction_type IN ('EARN', 'REDEEM', 'RESTORE')),
    points           INT          NOT NULL CHECK (points <> 0),
    balance_after    INT          NOT NULL CHECK (balance_after >= 0),
    description      VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_loyalty_earn_validation ON loyalty_transaction (validation_id) WHERE transaction_type = 'EARN';
CREATE UNIQUE INDEX ux_loyalty_restore_reference ON loyalty_transaction (reference_type, reference_id) WHERE transaction_type = 'RESTORE';
CREATE INDEX idx_loyalty_transaction_user ON loyalty_transaction (user_id, created_at DESC);

ALTER TABLE ticket ADD COLUMN points_used INT NOT NULL DEFAULT 0 CHECK (points_used >= 0);
-- price_vnd là số tiền còn phải trả sau khi trừ điểm
ALTER TABLE ticket DROP CONSTRAINT ck_ticket_total_price;
ALTER TABLE ticket ADD CONSTRAINT ck_ticket_total_price CHECK (price_vnd + points_used = unit_price_vnd * periods);

-- ===== 4. Bảng giá chờ duyệt (UC08) =====
CREATE TABLE periodic_ticket_price (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id       BIGINT      NOT NULL REFERENCES ticket_product (id) ON DELETE CASCADE,
    price_vnd        INT         NOT NULL CHECK (price_vnd > 0),
    effective_from   TIMESTAMPTZ NOT NULL,
    status           VARCHAR(30) NOT NULL CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'APPLIED', 'REJECTED')),
    created_by       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    submitted_at     TIMESTAMPTZ NOT NULL,
    approved_by      UUID REFERENCES app_user (id) ON DELETE SET NULL,
    approved_at      TIMESTAMPTZ,
    rejection_reason TEXT,
    applied_at       TIMESTAMPTZ
);
-- Mỗi loại vé chỉ có một bảng giá đang chờ áp dụng, nên thời gian áp dụng không chồng lấn
CREATE UNIQUE INDEX ux_ticket_price_open ON periodic_ticket_price (product_id) WHERE status IN ('PENDING_APPROVAL', 'APPROVED');
CREATE INDEX idx_ticket_price_status ON periodic_ticket_price (status, effective_from);

-- ===== 5. Đặt chỗ gửi xe (UC14, UC17) =====
CREATE TABLE parking_booking (
    id               UUID PRIMARY KEY,
    booking_code     VARCHAR(20) NOT NULL UNIQUE,
    user_id          UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    lot_id           BIGINT      NOT NULL REFERENCES parking_lot (id) ON DELETE RESTRICT,
    vehicle_type     VARCHAR(20) NOT NULL,
    plate_number     VARCHAR(20),
    price_vnd        INT         NOT NULL CHECK (price_vnd >= 0),
    overtime_fee_vnd INT         NOT NULL DEFAULT 0 CHECK (overtime_fee_vnd >= 0),
    qr_token         VARCHAR(64) NOT NULL UNIQUE,
    start_at         TIMESTAMPTZ NOT NULL,
    end_at           TIMESTAMPTZ NOT NULL,
    checkin_at       TIMESTAMPTZ,
    checkout_at      TIMESTAMPTZ,
    checkin_by       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    checkout_by      UUID REFERENCES app_user (id) ON DELETE SET NULL,
    status           VARCHAR(30) NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CHECKED_IN', 'COMPLETED', 'CANCELLED')),
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    CHECK (end_at > start_at)
);
CREATE INDEX idx_parking_booking_hold ON parking_booking (lot_id, vehicle_type, status);
CREATE INDEX idx_parking_booking_user ON parking_booking (user_id, created_at DESC);
CREATE INDEX idx_parking_booking_plate ON parking_booking (plate_number, status);

ALTER TABLE payment_order DROP CONSTRAINT IF EXISTS payment_order_purpose_check;
ALTER TABLE payment_order ADD CONSTRAINT payment_order_purpose_check
    CHECK (purpose IN ('TICKET', 'PARKING_MONTHLY_PASS', 'PARKING_BOOKING'));
ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_purpose_check;
ALTER TABLE payment_transaction ADD CONSTRAINT payment_transaction_purpose_check
    CHECK (purpose IN ('TICKET', 'PARKING_SESSION', 'PARKING_MONTHLY_PASS', 'PARKING_BOOKING'));

-- ===== 6. Đối soát (UC18) =====
ALTER TABLE sepay_transaction
    ADD COLUMN reconciled     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reconciled_at  TIMESTAMPTZ,
    ADD COLUMN reconciled_by  UUID REFERENCES app_user (id) ON DELETE SET NULL,
    ADD COLUMN reconcile_note TEXT;
CREATE INDEX idx_sepay_transaction_received ON sepay_transaction (received_at);
CREATE INDEX idx_payment_transaction_created ON payment_transaction (created_at);

-- ===== 7. Sự cố tại ga (UC19) =====
CREATE TABLE incident (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id    BIGINT      NOT NULL REFERENCES station (id) ON DELETE RESTRICT,
    reported_by   UUID REFERENCES app_user (id) ON DELETE SET NULL,
    incident_type VARCHAR(30) NOT NULL CHECK (incident_type IN ('EQUIPMENT', 'SECURITY', 'MEDICAL', 'PASSENGER', 'OTHER')),
    severity      VARCHAR(20) NOT NULL DEFAULT 'MEDIUM' CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    description   TEXT        NOT NULL,
    image_key     VARCHAR(500),
    status        VARCHAR(20) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'IN_PROGRESS', 'RESOLVED')),
    handled_by    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    resolution    TEXT,
    accepted_at   TIMESTAMPTZ,
    resolved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_incident_status ON incident (status, created_at DESC);
CREATE INDEX idx_incident_reporter ON incident (reported_by, created_at DESC);

-- ===== 8. Chatbot (UC06, UC22) =====
CREATE TABLE chatbot_session (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_chatbot_session_user ON chatbot_session (user_id, last_message_at DESC);

CREATE TABLE chatbot_message (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id       BIGINT      NOT NULL REFERENCES chatbot_session (id) ON DELETE CASCADE,
    sender           VARCHAR(10) NOT NULL CHECK (sender IN ('USER', 'BOT')),
    content          TEXT        NOT NULL,
    action_called    VARCHAR(255),
    response_time_ms INT CHECK (response_time_ms IS NULL OR response_time_ms >= 0),
    feedback         VARCHAR(10) CHECK (feedback IS NULL OR feedback IN ('LIKE', 'DISLIKE')),
    created_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_chatbot_message_session ON chatbot_message (session_id, id);
CREATE INDEX idx_chatbot_message_rate ON chatbot_message (sender, created_at);

CREATE TABLE chatbot_faq (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category      VARCHAR(30) NOT NULL,
    question      TEXT        NOT NULL,
    answer        TEXT        NOT NULL,
    keywords      TEXT,
    search_vector TSVECTOR GENERATED ALWAYS AS
        (to_tsvector('simple', question || ' ' || answer || ' ' || coalesce(keywords, ''))) STORED,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    created_by    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX ux_chatbot_faq_question ON chatbot_faq (lower(question)) WHERE status = 'ACTIVE';
CREATE INDEX idx_chatbot_faq_search ON chatbot_faq USING GIN (search_vector);

-- ===== 9. Nhật ký thao tác =====
CREATE TABLE audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    UUID REFERENCES app_user (id) ON DELETE SET NULL,
    action     VARCHAR(50) NOT NULL,
    table_name VARCHAR(100),
    record_id  VARCHAR(64),
    content    TEXT,
    old_data   TEXT,
    new_data   TEXT,
    ip_address VARCHAR(45),
    event_time TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_audit_log_time ON audit_log (event_time DESC);
CREATE INDEX idx_audit_log_action ON audit_log (action, event_time DESC);
