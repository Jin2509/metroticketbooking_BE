-- ============================================================
-- V16: Thanh toán chuyển khoản qua QR SePay + quyền cho các lỗ hổng phân quyền đã sửa
-- ============================================================

-- ===== 1. Quyền mới =====
INSERT INTO permission (code, description) VALUES
    ('ANNOUNCEMENT_MANAGE',  'Soạn và gửi duyệt thông báo vận hành'),
    ('ANNOUNCEMENT_APPROVE', 'Duyệt, từ chối thông báo vận hành'),
    ('CONTENT_MANAGE',       'Quản lý banner, nội dung hiển thị'),
    ('PAYMENT_MANAGE',       'Đối soát thanh toán, xác nhận hoàn tiền')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r CROSS JOIN permission p
WHERE r.code = 'ADMIN'
ON CONFLICT DO NOTHING;

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('ANNOUNCEMENT_APPROVE')
WHERE r.code = 'MANAGER'
ON CONFLICT DO NOTHING;

-- Nhân viên ga soạn thông báo sự cố tại ga, quản lý duyệt
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('ANNOUNCEMENT_MANAGE')
WHERE r.code = 'STAFF'
ON CONFLICT DO NOTHING;

-- ===== 2. Trạng thái chờ thanh toán / huỷ =====
ALTER TABLE ticket DROP CONSTRAINT IF EXISTS ticket_status_check;
ALTER TABLE ticket ADD CONSTRAINT ticket_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'IN_USE', 'USED', 'EXPIRED', 'REFUNDED', 'CANCELLED'));

ALTER TABLE parking_monthly_pass DROP CONSTRAINT IF EXISTS parking_monthly_pass_status_check;
ALTER TABLE parking_monthly_pass ADD CONSTRAINT parking_monthly_pass_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CANCELLED'));

ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_method_check;
ALTER TABLE payment_transaction ADD CONSTRAINT payment_transaction_method_check
    CHECK (method IN ('CASH', 'CARD', 'E_WALLET', 'BANK_TRANSFER', 'SEPAY_QR'));

-- REFUND_PENDING: chuyển khoản không hoàn tự động được, kế toán chuyển trả rồi xác nhận REFUNDED
ALTER TABLE payment_transaction DROP CONSTRAINT IF EXISTS payment_transaction_status_check;
ALTER TABLE payment_transaction ADD CONSTRAINT payment_transaction_status_check
    CHECK (status IN ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED'));

ALTER TABLE parking_session DROP CONSTRAINT IF EXISTS parking_session_payment_method_check;
ALTER TABLE parking_session ADD CONSTRAINT parking_session_payment_method_check
    CHECK (payment_method IS NULL OR payment_method IN ('CASH', 'CARD', 'E_WALLET', 'BANK_TRANSFER', 'SEPAY_QR'));

-- ===== 3. Đơn thanh toán (mỗi đơn = 1 mã QR, nội dung chuyển khoản chứa order_code) =====
CREATE TABLE payment_order (
    id                    UUID         PRIMARY KEY,
    order_code            VARCHAR(20)  NOT NULL UNIQUE,
    user_id               UUID         NOT NULL REFERENCES app_user (id) ON DELETE RESTRICT,
    purpose               VARCHAR(30)  NOT NULL CHECK (purpose IN ('TICKET', 'PARKING_MONTHLY_PASS')),
    reference_id          VARCHAR(64)  NOT NULL,
    amount_vnd            INT          NOT NULL CHECK (amount_vnd > 0),
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED')),
    expires_at            TIMESTAMPTZ  NOT NULL,
    paid_at               TIMESTAMPTZ,
    paid_amount_vnd       INT CHECK (paid_amount_vnd IS NULL OR paid_amount_vnd > 0),
    sepay_transaction_id  BIGINT,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_payment_order_reference UNIQUE (purpose, reference_id),
    CONSTRAINT ck_payment_order_paid CHECK (status <> 'PAID' OR (paid_at IS NOT NULL AND paid_amount_vnd IS NOT NULL))
);

CREATE INDEX idx_payment_order_pending ON payment_order (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_payment_order_user ON payment_order (user_id, created_at DESC);

-- ===== 4. Nhật ký webhook SePay (đối soát; id của SePay là khoá chống xử lý trùng) =====
CREATE TABLE sepay_transaction (
    id                BIGINT       PRIMARY KEY,
    gateway           VARCHAR(100),
    transaction_date  VARCHAR(30),
    account_number    VARCHAR(50),
    sub_account       VARCHAR(50),
    code              VARCHAR(100),
    content           TEXT,
    transfer_type     VARCHAR(10)  NOT NULL,
    transfer_amount   BIGINT       NOT NULL,
    reference_code    VARCHAR(100),
    description       TEXT,
    order_id          UUID REFERENCES payment_order (id) ON DELETE SET NULL,
    result            VARCHAR(30)  NOT NULL
        CHECK (result IN ('MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH', 'NEEDS_REFUND', 'IGNORED')),
    note              VARCHAR(255),
    received_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_sepay_transaction_result ON sepay_transaction (result, received_at DESC);
CREATE INDEX idx_sepay_transaction_order ON sepay_transaction (order_id);

CREATE TRIGGER trg_payment_order_updated_at
BEFORE UPDATE ON payment_order
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
