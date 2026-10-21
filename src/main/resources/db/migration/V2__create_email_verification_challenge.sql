CREATE TABLE email_verification_challenge (
    id              UUID         PRIMARY KEY,
    email           VARCHAR(320) NOT NULL,
    purpose         VARCHAR(32)  NOT NULL CHECK (purpose IN ('REGISTER', 'RESET_PASSWORD')),
    code_hash       VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_evc_lookup ON email_verification_challenge (email, purpose, created_at DESC);
CREATE INDEX idx_evc_expires ON email_verification_challenge (expires_at);
