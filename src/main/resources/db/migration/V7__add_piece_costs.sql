ALTER TABLE pieces
    ADD COLUMN factory_cost_cents BIGINT CHECK (factory_cost_cents >= 0),
    ADD COLUMN bath_cost_cents BIGINT CHECK (bath_cost_cents >= 0);
