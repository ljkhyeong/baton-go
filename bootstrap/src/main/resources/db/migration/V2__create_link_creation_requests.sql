CREATE TABLE link_creation_requests
(
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    link_id              BINARY(16)   NOT NULL,
    created_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_link_creation_requests PRIMARY KEY (idempotency_key_hash),
    CONSTRAINT uk_link_creation_requests_link_id UNIQUE (link_id)
);
