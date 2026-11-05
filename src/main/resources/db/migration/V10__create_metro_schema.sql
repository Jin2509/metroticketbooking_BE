-- ============================================================
-- Metro domains
-- ============================================================

CREATE TABLE metro_line (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name_line VARCHAR(100) NOT NULL,
    color_hex VARCHAR(7),
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'UNDER_CONSTRUCTION')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE station (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name_station VARCHAR(100) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    address VARCHAR(255),
    district VARCHAR(100),
    image_url VARCHAR(500),
    is_interchange BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'MAINTENANCE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE line_station (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id BIGINT NOT NULL REFERENCES metro_line(id) ON DELETE CASCADE,
    station_id BIGINT NOT NULL REFERENCES station(id) ON DELETE RESTRICT,
    station_order INT NOT NULL CHECK (station_order > 0),
    distance_from_prev_km DECIMAL(8,3),
    travel_time_from_prev_seconds INT,
    dwell_time_seconds INT NOT NULL DEFAULT 30,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (line_id, station_id),
    UNIQUE (line_id, station_order)
);

CREATE TABLE line_transfer (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    station_id BIGINT NOT NULL REFERENCES station(id) ON DELETE CASCADE,
    from_line_id BIGINT NOT NULL REFERENCES metro_line(id) ON DELETE CASCADE,
    to_line_id BIGINT NOT NULL REFERENCES metro_line(id) ON DELETE CASCADE,
    transfer_time_minutes INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE staff_station (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    station_id BIGINT NOT NULL REFERENCES station(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE train (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code VARCHAR(30) NOT NULL UNIQUE,
    name_train VARCHAR(100),
    line_id BIGINT REFERENCES metro_line(id) ON DELETE SET NULL,
    capacity INT CHECK (capacity IS NULL OR capacity > 0),
    manufacture_year INT CHECK (manufacture_year IS NULL OR manufacture_year > 1900),
    status VARCHAR(30) NOT NULL DEFAULT 'READY'
        CHECK (status IN ('READY', 'IN_OPERATION', 'MAINTENANCE', 'OUT_OF_SERVICE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE operation_config (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id BIGINT NOT NULL REFERENCES metro_line(id) ON DELETE CASCADE,
    version INT NOT NULL,
    number_of_trains INT NOT NULL,
    headway_minutes INT NOT NULL,
    running_time_minutes INT NOT NULL,
    turnaround_time_minutes INT NOT NULL DEFAULT 0,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    submitted_at TIMESTAMPTZ,
    approved_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE announcement (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    line_id BIGINT REFERENCES metro_line(id) ON DELETE SET NULL,
    station_id BIGINT REFERENCES station(id) ON DELETE SET NULL,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO'
        CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    starts_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED')),
    scope VARCHAR(20) NOT NULL DEFAULT 'ALL'
        CHECK (scope IN ('ALL', 'LINE', 'STATION', 'ROLE')),
    target_role VARCHAR(50),
    created_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    submitted_at TIMESTAMPTZ,
    approved_by UUID REFERENCES app_user(id) ON DELETE SET NULL,
    approved_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_line_station_line_order ON line_station (line_id, station_order);
CREATE INDEX idx_line_station_station ON line_station (station_id);
CREATE INDEX idx_line_transfer_station ON line_transfer (station_id);
CREATE INDEX idx_line_transfer_line_pair ON line_transfer (from_line_id, to_line_id);
CREATE INDEX idx_staff_station_user ON staff_station (user_id);
CREATE INDEX idx_staff_station_station ON staff_station (station_id);
CREATE INDEX idx_train_line ON train (line_id);
CREATE INDEX idx_operation_config_line ON operation_config (line_id);
CREATE INDEX idx_operation_config_effective ON operation_config (line_id, effective_from DESC);
CREATE INDEX idx_announcement_line ON announcement (line_id);
CREATE INDEX idx_announcement_station ON announcement (station_id);
CREATE INDEX idx_announcement_status ON announcement (status);
CREATE INDEX idx_announcement_scope ON announcement (scope);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_metro_line_updated_at
BEFORE UPDATE ON metro_line
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_station_updated_at
BEFORE UPDATE ON station
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_line_transfer_updated_at
BEFORE UPDATE ON line_transfer
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_train_updated_at
BEFORE UPDATE ON train
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_operation_config_updated_at
BEFORE UPDATE ON operation_config
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_announcement_updated_at
BEFORE UPDATE ON announcement
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
