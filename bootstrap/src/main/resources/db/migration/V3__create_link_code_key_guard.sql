CREATE TABLE link_code_key_guard
(
    guard_id           TINYINT UNSIGNED NOT NULL,
    derivation_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    key_fingerprint    CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    CONSTRAINT pk_link_code_key_guard PRIMARY KEY (guard_id),
    CONSTRAINT ck_link_code_key_guard_singleton CHECK (guard_id = 1),
    CONSTRAINT ck_link_code_key_guard_binding_state CHECK (
        (derivation_version IS NULL AND key_fingerprint IS NULL)
            OR (derivation_version IS NOT NULL AND key_fingerprint IS NOT NULL)
    )
);

INSERT INTO link_code_key_guard (
    guard_id,
    derivation_version,
    key_fingerprint
) VALUES (
    1,
    NULL,
    NULL
);
