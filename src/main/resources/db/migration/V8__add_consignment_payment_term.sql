ALTER TABLE consignments
    ADD COLUMN payment_term_days INTEGER CHECK (payment_term_days > 0);
