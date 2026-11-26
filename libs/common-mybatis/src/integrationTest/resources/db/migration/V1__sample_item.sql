-- Sample table with the columns of BaseEntity (design.md 6.1).
CREATE TABLE sample_item (
    id         BIGSERIAL      PRIMARY KEY,
    name       VARCHAR(64)    NOT NULL,
    quantity   NUMERIC(38, 18) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL,
    created_by VARCHAR(64)    NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    updated_by VARCHAR(64)    NOT NULL,
    version    BIGINT         NOT NULL DEFAULT 0
);
