package com.personal.batongo.adapter.out.persistence.link;

import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

interface SpringDataLinkCreationRequestRepository
        extends CrudRepository<LinkCreationRequestEntity, String> {

    @Modifying
    @Query(value = """
            INSERT IGNORE INTO link_creation_requests (
                idempotency_key_hash,
                link_id,
                public_origin,
                key_id,
                created_at
            ) VALUES (
                :idempotencyKeyHash,
                UUID_TO_BIN(:linkId),
                :publicOrigin,
                :keyId,
                :createdAt
            )
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("linkId") String linkId,
            @Param("publicOrigin") String publicOrigin,
            @Param("keyId") String keyId,
            @Param("createdAt") Instant createdAt
    );
}
