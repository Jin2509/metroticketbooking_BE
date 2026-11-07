-- ============================================================
-- V19: Chiến dịch thông báo của admin, push (FCM), realtime, tuỳ chọn nhận, thông báo tự động
-- ============================================================

-- ===== 1. Mở rộng thông báo =====
ALTER TABLE notification DROP CONSTRAINT IF EXISTS notification_type_check;
ALTER TABLE notification ADD CONSTRAINT notification_type_check CHECK (type IN
    ('SYSTEM', 'SECURITY', 'ANNOUNCEMENT', 'TICKET', 'PAYMENT', 'PARKING', 'STUDENT', 'GREEN_JOURNEY', 'PROMOTION'));

ALTER TABLE notification
    ADD COLUMN priority    VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),
    -- đường dẫn màn hình trong app (deep link), chỉ cho phép đường dẫn nội bộ bắt đầu bằng "/"
    ADD COLUMN link        VARCHAR(300) CHECK (link IS NULL OR link ~ '^/[A-Za-z0-9/_\-?=&.%]*$'),
    ADD COLUMN campaign_id UUID,
    -- khoá chống gửi trùng cho thông báo tự động (vd. TICKET_EXPIRING:<id>)
    ADD COLUMN dedupe_key  VARCHAR(150);

CREATE UNIQUE INDEX ux_notification_dedupe ON notification (user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
CREATE INDEX idx_notification_campaign ON notification (campaign_id) WHERE campaign_id IS NOT NULL;

-- ===== 2. Tuỳ chọn nhận thông báo của người dùng =====
-- Không có dòng = dùng mặc định (nhận hết). Thông báo SECURITY luôn được gửi.
CREATE TABLE notification_preference (
    user_id           UUID        PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    push_enabled      BOOLEAN     NOT NULL DEFAULT TRUE,
    email_enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    promotion_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    reminder_enabled  BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ===== 3. Thiết bị nhận push =====
CREATE TABLE user_device (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token        VARCHAR(512) NOT NULL UNIQUE,
    platform     VARCHAR(10)  NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'WEB')),
    app_version  VARCHAR(40),
    created_at   TIMESTAMPTZ  NOT NULL,
    last_seen_at TIMESTAMPTZ  NOT NULL,
    -- FCM báo token hết hiệu lực / người dùng đăng xuất
    disabled_at  TIMESTAMPTZ
);
CREATE INDEX idx_user_device_user ON user_device (user_id) WHERE disabled_at IS NULL;

-- ===== 4. Chiến dịch thông báo của admin =====
-- DRAFT → SCHEDULED (gửi ngay = hẹn lúc hiện tại) → SENDING → SENT ; DRAFT/SCHEDULED → CANCELLED ; lỗi → FAILED
CREATE TABLE notification_campaign (
    id               UUID          PRIMARY KEY,
    title            VARCHAR(200)  NOT NULL,
    body             VARCHAR(2000) NOT NULL,
    type             VARCHAR(32)   NOT NULL CHECK (type IN ('ANNOUNCEMENT', 'PROMOTION', 'SYSTEM')),
    priority         VARCHAR(10)   NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH')),
    link             VARCHAR(300) CHECK (link IS NULL OR link ~ '^/[A-Za-z0-9/_\-?=&.%]*$'),
    audience_type    VARCHAR(30)   NOT NULL CHECK (audience_type IN
        ('ALL', 'ROLE', 'LINE', 'STATION', 'STUDENTS', 'ACTIVE_PASS_HOLDERS', 'USERS')),
    audience_value   VARCHAR(50),
    channel_push     BOOLEAN       NOT NULL DEFAULT TRUE,
    channel_email    BOOLEAN       NOT NULL DEFAULT FALSE,
    status           VARCHAR(20)   NOT NULL CHECK (status IN ('DRAFT', 'SCHEDULED', 'SENDING', 'SENT', 'CANCELLED', 'FAILED')),
    scheduled_at     TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    recipient_count  INT           NOT NULL DEFAULT 0,
    push_queued      INT           NOT NULL DEFAULT 0,
    email_queued     INT           NOT NULL DEFAULT 0,
    -- nguồn: ADMIN (soạn tay) hoặc ANNOUNCEMENT (tự sinh khi thông báo vận hành được duyệt)
    source           VARCHAR(20)   NOT NULL DEFAULT 'ADMIN' CHECK (source IN ('ADMIN', 'ANNOUNCEMENT')),
    announcement_id  BIGINT REFERENCES announcement (id) ON DELETE SET NULL,
    error            VARCHAR(500),
    created_by       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    updated_by       UUID REFERENCES app_user (id) ON DELETE SET NULL,
    sent_by          UUID REFERENCES app_user (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_campaign_audience_value CHECK (
        (audience_type IN ('ROLE', 'LINE', 'STATION') AND audience_value IS NOT NULL)
        OR (audience_type NOT IN ('ROLE', 'LINE', 'STATION'))),
    CONSTRAINT ck_campaign_scheduled CHECK (status NOT IN ('SCHEDULED', 'SENDING', 'SENT') OR scheduled_at IS NOT NULL)
);
CREATE INDEX idx_campaign_due ON notification_campaign (scheduled_at) WHERE status = 'SCHEDULED';
CREATE INDEX idx_campaign_created ON notification_campaign (created_at DESC);
CREATE UNIQUE INDEX ux_campaign_announcement ON notification_campaign (announcement_id) WHERE announcement_id IS NOT NULL;

ALTER TABLE notification ADD CONSTRAINT fk_notification_campaign
    FOREIGN KEY (campaign_id) REFERENCES notification_campaign (id) ON DELETE SET NULL;

-- Danh sách người nhận khi audience_type = USERS
CREATE TABLE notification_campaign_user (
    campaign_id UUID NOT NULL REFERENCES notification_campaign (id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    PRIMARY KEY (campaign_id, user_id)
);

-- ===== 5. Hàng đợi gửi push / email (outbox) =====
CREATE TABLE notification_delivery (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_id UUID         NOT NULL REFERENCES notification (id) ON DELETE CASCADE,
    user_id         UUID         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    channel         VARCHAR(10)  NOT NULL CHECK (channel IN ('PUSH', 'EMAIL')),
    device_id       BIGINT REFERENCES user_device (id) ON DELETE CASCADE,
    status          VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_at       TIMESTAMPTZ,
    last_error      VARCHAR(500),
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_delivery_push_device CHECK (channel <> 'PUSH' OR device_id IS NOT NULL)
);
CREATE INDEX idx_delivery_due ON notification_delivery (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_delivery_notification ON notification_delivery (notification_id);

CREATE TRIGGER trg_notification_campaign_updated_at
BEFORE UPDATE ON notification_campaign
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
