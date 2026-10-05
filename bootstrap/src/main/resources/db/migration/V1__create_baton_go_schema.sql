CREATE TABLE smart_links
(
    id            BINARY(16)    NOT NULL,
    code_hash     CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    target_system VARCHAR(32)   NOT NULL,
    target_path   VARCHAR(1024) NOT NULL,
    purpose       VARCHAR(32)   NOT NULL,
    not_before    DATETIME(6)   NULL,
    expires_at    DATETIME(6)   NULL,
    revoked_at    DATETIME(6)   NULL,
    created_at    DATETIME(6)   NOT NULL,
    retired_at    DATETIME(6) GENERATED ALWAYS AS (COALESCE(revoked_at, expires_at)) VIRTUAL,
    CONSTRAINT pk_smart_links PRIMARY KEY (id),
    CONSTRAINT uk_smart_links_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_smart_links_expiry_after_creation
        CHECK (expires_at IS NULL OR expires_at > created_at),
    CONSTRAINT ck_smart_links_expiry_after_activation
        CHECK (expires_at IS NULL OR not_before IS NULL OR expires_at > not_before),
    INDEX ix_smart_links_retention (retired_at, id)
);

CREATE TABLE link_creation_requests
(
    idempotency_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    link_id              BINARY(16)   NOT NULL,
    public_origin        VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL,
    key_id               VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    purged_at            DATETIME(6)  NULL,
    request_hash         CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT pk_link_creation_requests PRIMARY KEY (idempotency_key_hash),
    CONSTRAINT uk_link_creation_requests_link_id UNIQUE (link_id),
    -- 정리 전 예약은 재시도용 공개 출처를, 정리한 예약은 재시도 판정용 요청 해시만 남긴다.
    CONSTRAINT ck_link_creation_requests_purge_state
        CHECK ((purged_at IS NULL AND request_hash IS NULL AND public_origin IS NOT NULL)
            OR (purged_at IS NOT NULL AND request_hash IS NOT NULL AND public_origin IS NULL)),
    INDEX ix_link_creation_requests_key (key_id)
);

CREATE TABLE link_code_keys
(
    key_id             VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    derivation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_fingerprint    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT pk_link_code_keys PRIMARY KEY (key_id)
);
