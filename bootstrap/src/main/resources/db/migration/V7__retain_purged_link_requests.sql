ALTER TABLE link_creation_requests
    ADD COLUMN purged_at DATETIME(6) NULL,
    ADD COLUMN request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT ck_link_creation_requests_purge_evidence
        CHECK ((purged_at IS NULL AND request_hash IS NULL)
            OR (purged_at IS NOT NULL AND request_hash IS NOT NULL));

ALTER TABLE smart_links
    ADD COLUMN retired_at DATETIME(6) GENERATED ALWAYS AS (COALESCE(revoked_at, expires_at)) VIRTUAL,
    ADD INDEX ix_smart_links_retention (retired_at, id);
