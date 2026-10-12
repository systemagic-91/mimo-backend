CREATE TABLE users (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'STAFF')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    replaced_by_id UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL,
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_refresh_tokens_user_expiry ON refresh_tokens(user_id, expires_at);

CREATE TABLE sellers (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    phone VARCHAR(40) NOT NULL DEFAULT '',
    commission_rate INTEGER NOT NULL CHECK (commission_rate BETWEEN 0 AND 100),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ATIVA', 'INATIVA')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_sellers_status_name ON sellers(status, lower(name));

CREATE TABLE pieces (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL CHECK (length(trim(code)) > 0),
    type VARCHAR(80) NOT NULL CHECK (length(trim(type)) > 0),
    value_cents BIGINT NOT NULL CHECK (value_cents > 0),
    status VARCHAR(30) NOT NULL CHECK (status IN ('FABRICA', 'BANHO_OURO', 'ETIQUETADA', 'CONSIGNACAO', 'VENDIDA', 'DEVOLVIDA')),
    current_location VARCHAR(120) NOT NULL CHECK (length(trim(current_location)) > 0),
    photo_url VARCHAR(2048),
    note VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uk_pieces_code UNIQUE (code)
);
CREATE INDEX ix_pieces_status_updated ON pieces(status, updated_at);

CREATE TABLE piece_events (
    id UUID PRIMARY KEY,
    piece_id UUID NOT NULL REFERENCES pieces(id) ON DELETE RESTRICT,
    stage VARCHAR(30) NOT NULL CHECK (stage IN ('FABRICA', 'BANHO_OURO', 'ETIQUETADA', 'CONSIGNACAO', 'VENDIDA', 'DEVOLVIDA')),
    occurred_at TIMESTAMPTZ NOT NULL,
    note VARCHAR(500),
    actor_user_id UUID REFERENCES users(id) ON DELETE RESTRICT
);
CREATE INDEX ix_piece_events_piece_time ON piece_events(piece_id, occurred_at, id);

CREATE TABLE consignments (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL CHECK (length(trim(code)) > 0),
    seller_id UUID NOT NULL REFERENCES sellers(id) ON DELETE RESTRICT,
    delivered_at TIMESTAMPTZ NOT NULL,
    due_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ABERTA', 'QUITADA')),
    note VARCHAR(500),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_consignments_due_after_delivery CHECK (due_at >= delivered_at),
    CONSTRAINT uk_consignments_code UNIQUE (code)
);
CREATE INDEX ix_consignments_seller_delivered ON consignments(seller_id, delivered_at DESC);
CREATE INDEX ix_consignments_status_due ON consignments(status, due_at);

CREATE TABLE consignment_items (
    id UUID PRIMARY KEY,
    consignment_id UUID NOT NULL REFERENCES consignments(id) ON DELETE RESTRICT,
    piece_id UUID NOT NULL REFERENCES pieces(id) ON DELETE RESTRICT,
    code_snapshot VARCHAR(32) NOT NULL,
    type_snapshot VARCHAR(80) NOT NULL,
    value_cents_snapshot BIGINT NOT NULL CHECK (value_cents_snapshot > 0),
    commission_rate_snapshot INTEGER NOT NULL CHECK (commission_rate_snapshot BETWEEN 0 AND 100),
    status VARCHAR(30) NOT NULL CHECK (status IN ('COM_VENDEDORA', 'VENDIDA', 'DEVOLVIDA')),
    sold_at TIMESTAMPTZ,
    returned_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_consignment_items_terminal_timestamp CHECK (
        (status = 'COM_VENDEDORA' AND sold_at IS NULL AND returned_at IS NULL)
        OR (status = 'VENDIDA' AND sold_at IS NOT NULL AND returned_at IS NULL)
        OR (status = 'DEVOLVIDA' AND sold_at IS NULL AND returned_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX uk_consignment_items_active_piece ON consignment_items(piece_id)
    WHERE status = 'COM_VENDEDORA';
CREATE INDEX ix_items_piece_status ON consignment_items(piece_id, status);
CREATE INDEX ix_items_status_sold_at ON consignment_items(status, sold_at);
CREATE INDEX ix_items_consignment_status ON consignment_items(consignment_id, status);

CREATE TABLE settings (
    id UUID PRIMARY KEY,
    singleton_key BOOLEAN NOT NULL DEFAULT TRUE UNIQUE CHECK (singleton_key),
    commission_min INTEGER NOT NULL CHECK (commission_min BETWEEN 0 AND 100),
    commission_max INTEGER NOT NULL CHECK (commission_max BETWEEN commission_min AND 100),
    commission_step INTEGER NOT NULL CHECK (commission_step > 0),
    default_commission_rate INTEGER NOT NULL CHECK (default_commission_rate BETWEEN commission_min AND commission_max),
    payment_terms JSONB NOT NULL CHECK (jsonb_typeof(payment_terms) = 'array'),
    default_consignment_days INTEGER NOT NULL CHECK (default_consignment_days >= 1),
    timezone VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by UUID REFERENCES users(id) ON DELETE SET NULL
);

CREATE TABLE code_sequences (
    id UUID PRIMARY KEY,
    scope VARCHAR(40) NOT NULL,
    sequence_year SMALLINT NOT NULL DEFAULT 0 CHECK (sequence_year BETWEEN 0 AND 9999),
    next_value BIGINT NOT NULL CHECK (next_value > 0),
    CONSTRAINT uk_code_sequences_scope_year UNIQUE (scope, sequence_year)
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    before_state JSONB,
    after_state JSONB,
    ip_address VARCHAR(45),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_entity_time ON audit_logs(entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_actor_time ON audit_logs(actor_user_id, occurred_at DESC);

CREATE TABLE idempotency_keys (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    idempotency_key VARCHAR(255) NOT NULL,
    fingerprint VARCHAR(128) NOT NULL,
    response_status SMALLINT,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_idempotency_user_key UNIQUE(user_id, idempotency_key),
    CONSTRAINT ck_idempotency_response_status CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT ck_idempotency_expiry CHECK (expires_at > created_at)
);
CREATE INDEX ix_idempotency_expiry ON idempotency_keys(expires_at);

CREATE FUNCTION reject_piece_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'piece_events are immutable' USING ERRCODE = '55000';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_piece_events_immutable
    BEFORE UPDATE OR DELETE ON piece_events
    FOR EACH ROW EXECUTE FUNCTION reject_piece_event_mutation();