SET @baton_go_previous_time_zone = @@SESSION.time_zone;
SET SESSION time_zone = '+00:00';

ALTER TABLE smart_links
    MODIFY COLUMN not_before DATETIME(6) NULL,
    MODIFY COLUMN expires_at DATETIME(6) NULL,
    MODIFY COLUMN revoked_at DATETIME(6) NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL;

ALTER TABLE link_creation_requests
    MODIFY COLUMN created_at DATETIME(6) NOT NULL;

SET SESSION time_zone = @baton_go_previous_time_zone;
