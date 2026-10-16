CREATE FUNCTION ensure_consignment_seller_is_active() RETURNS trigger AS $$
DECLARE
    seller_status VARCHAR(20);
BEGIN
    SELECT status INTO seller_status
    FROM sellers
    WHERE id = NEW.seller_id
    FOR SHARE;

    IF seller_status = 'INATIVA' THEN
        RAISE EXCEPTION 'inactive sellers cannot receive new consignments'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_consignments_seller_active';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_consignment_requires_active_seller
    BEFORE INSERT OR UPDATE OF seller_id ON consignments
    FOR EACH ROW EXECUTE FUNCTION ensure_consignment_seller_is_active();