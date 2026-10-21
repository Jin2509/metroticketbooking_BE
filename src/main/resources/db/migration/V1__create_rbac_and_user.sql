-- ===== RBAC: role, permission, role_permission =====
CREATE TABLE role (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code      VARCHAR(50)  NOT NULL UNIQUE,
    name      VARCHAR(100) NOT NULL,
    is_system BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE permission (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL UNIQUE,
    description VARCHAR(255) NOT NULL
);

CREATE TABLE role_permission (
    role_id       BIGINT NOT NULL REFERENCES role (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permission (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- ===== Người dùng =====
CREATE TABLE app_user (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    status        VARCHAR(32)  NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'LOCKED')),
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE user_role (
    user_id UUID   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES role (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

-- PK (user_id, role_id) đã phục vụ tra theo user; index này phục vụ tra theo role
CREATE INDEX idx_user_role_role ON user_role (role_id);
CREATE INDEX idx_app_user_created_at ON app_user (created_at);
