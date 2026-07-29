CREATE TABLE smart_links
(
    id            BINARY(16)   NOT NULL,
    code_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_system VARCHAR(32)  NOT NULL,
    target_path   VARCHAR(1024) NOT NULL,
    purpose       VARCHAR(32)  NOT NULL,
    not_before    TIMESTAMP(6) NULL,
    expires_at    TIMESTAMP(6) NULL,
    revoked_at    TIMESTAMP(6) NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_smart_links PRIMARY KEY (id),
    CONSTRAINT uk_smart_links_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_smart_links_expiry_after_creation
        CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_smart_links_expiry_after_activation
        CHECK (expires_at IS NULL OR not_before IS NULL OR expires_at > not_before)
);

CREATE INDEX ix_smart_links_expiry
    ON smart_links (expires_at);
