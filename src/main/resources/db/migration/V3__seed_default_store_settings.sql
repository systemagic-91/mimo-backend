ALTER TABLE sellers
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

ALTER TABLE settings
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);

INSERT INTO settings (
    id,
    singleton_key,
    commission_min,
    commission_max,
    commission_step,
    default_commission_rate,
    payment_terms,
    default_consignment_days,
    timezone
) VALUES (
    '06f47c81-4e31-47d9-9ef8-65f7815a6f06',
    TRUE,
    25,
    40,
    5,
    30,
    '[30, 60, 90]'::jsonb,
    30,
    'America/Sao_Paulo'
);