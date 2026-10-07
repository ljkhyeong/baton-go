-- 생성 예약 때 요청 해시를 저장해 정리 단계가 링크 대상 원문을 다시 읽지 않게 한다.
-- 정리 전 예약은 request_hash가 비어 있으므로 그런 행이 있는 DB에는 적용할 수 없다.
ALTER TABLE link_creation_requests
    DROP CHECK ck_link_creation_requests_purge_state,
    DROP INDEX ix_link_creation_requests_key,
    MODIFY request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD CONSTRAINT ck_link_creation_requests_origin_until_purge
        CHECK ((purged_at IS NULL AND public_origin IS NOT NULL)
            OR (purged_at IS NOT NULL AND public_origin IS NULL));
