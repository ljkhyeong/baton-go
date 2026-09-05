CREATE TABLE link_code_keys
(
    key_id             VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    derivation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    key_fingerprint    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT pk_link_code_keys PRIMARY KEY (key_id)
);

INSERT INTO link_code_keys (key_id, derivation_version, key_fingerprint)
SELECT 'legacy', derivation_version, key_fingerprint
FROM link_code_key_guard
WHERE derivation_version IS NOT NULL AND key_fingerprint IS NOT NULL;

ALTER TABLE link_creation_requests
    ADD COLUMN key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'legacy';

CREATE INDEX ix_link_creation_requests_key ON link_creation_requests (key_id);
