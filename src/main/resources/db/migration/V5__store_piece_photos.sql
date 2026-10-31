CREATE TABLE piece_photos (
    id UUID PRIMARY KEY,
    content BYTEA NOT NULL,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    width INTEGER NOT NULL CHECK (width BETWEEN 1 AND 2000),
    height INTEGER NOT NULL CHECK (height BETWEEN 1 AND 2000),
    size_bytes INTEGER NOT NULL CHECK (size_bytes BETWEEN 1 AND 1048576),
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_piece_photos_created_at ON piece_photos(created_at);

ALTER TABLE pieces DROP COLUMN photo_url;
ALTER TABLE pieces ADD COLUMN photo_id UUID REFERENCES piece_photos(id) ON DELETE RESTRICT;
CREATE INDEX ix_pieces_photo_id ON pieces(photo_id);
