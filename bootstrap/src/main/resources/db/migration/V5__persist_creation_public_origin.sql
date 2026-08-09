ALTER TABLE link_creation_requests
    ADD COLUMN public_origin VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL
        AFTER link_id;
